#!/system/bin/sh
# Galaxy 5G on-device ServiceMode navigator v2.0 (Shizuku/rish/aShell You).
# No root, no fixed coordinates, no blind BACK presses, fail closed.
# Needs Android /system/bin/sh and Shizuku shell (uid=2000) or real root (uid=0).
# This tool sends screen input to Samsung's ServiceMode. It does not write NV directly.

set -u
VERSION=2.10.2
UIA_UNAVAILABLE=1
WORK=""
PROFILE=core
MODE="${1:-help}"

say() { printf '%s\n' "$*"; }
log() { printf '[Galaxy5G] %s\n' "$*"; }
fail() { printf '[Galaxy5G] 停止：%s\n' "$*" >&2; exit 2; }
cleanup() { if [ -n "$WORK" ] && [ -d "$WORK" ]; then rm -rf "$WORK"; fi; }
trap cleanup EXIT HUP INT TERM

usage() {
cat <<'HELP'
Galaxy NR Tools — 美版 Galaxy S23 / S24 / S25 / S26 系列 (U/U1)
需要：Shizuku 已启动，并获得 shell UID 2000。正式使用不需终端应用。

  sh galaxy5g.sh status               检查手机、Shizuku 权限、系统版本、记录
  sh galaxy5g.sh plan                 打印将使用的菜单路径（不操作手机）
  sh galaxy5g.sh inspect              读取当前 ServiceMode 页面菜单文字
  sh galaxy5g.sh probe core           探测五项设置的路径，不点击最终设置
  sh galaxy5g.sh probe full           探测全部六项（包括 mmWave）
  sh galaxy5g.sh apply core APPLY     实际提交五项基线设置，需显式确认词
  sh galaxy5g.sh apply full FULL      全部六项，包括 Sub-6/mmWave

条件：手机亮屏、解锁、三星拨号器 *#27663368378# 本身可用、SIM1。
重启不自动执行；使用后请手工重启验证。禁止跨机型和 SIM2 自动写入。
HELP
}

check_device() {
  command -v getprop >/dev/null 2>&1 || fail '找不到 Android getprop；请在手机上运行，不要在电脑 Shell 运行。'
  uid="$(id -u 2>/dev/null || printf unknown)"
  case "$uid" in 0|2000) ;; *) fail "当前 UID=$uid，没有 Shizuku shell 权限。请在 aShell You 选择 Shizuku 模式，或使用 rish -c。";; esac
  manufacturer="$(getprop ro.product.manufacturer 2>/dev/null)"
  model="$(getprop ro.product.model 2>/dev/null)"
  build="$(getprop ro.build.version.incremental 2>/dev/null)"
  android="$(getprop ro.build.version.release 2>/dev/null)"
  UIA_UNAVAILABLE=1
  log '仅使用本机 OCR 识别 ServiceMode 菜单（不调用 UIAutomator）'
  case "$(printf %s "$manufacturer" | tr '[:upper:]' '[:lower:]')/$model" in
    samsung/SM-S911U|samsung/SM-S911U1|samsung/SM-S916U|samsung/SM-S916U1|samsung/SM-S918U|samsung/SM-S918U1|samsung/SM-S711U|samsung/SM-S711U1|samsung/SM-S921U|samsung/SM-S921U1|samsung/SM-S926U|samsung/SM-S926U1|samsung/SM-S928U|samsung/SM-S928U1|samsung/SM-S721U|samsung/SM-S721U1|samsung/SM-S931U|samsung/SM-S931U1|samsung/SM-S936U|samsung/SM-S936U1|samsung/SM-S938U|samsung/SM-S938U1|samsung/SM-S937U|samsung/SM-S937U1|samsung/SM-S731U|samsung/SM-S731U1|samsung/SM-S942U|samsung/SM-S942U1|samsung/SM-S947U|samsung/SM-S947U1|samsung/SM-S948U|samsung/SM-S948U1|samsung/SM-S741U|samsung/SM-S741U1) ;;
    *) fail "未通过机型保护：厂商=$manufacturer 型号=$model。仅支持已列出的美版 Galaxy S23 及更新型号 U/U1；其他设备仅可使用应用的公开 Android 5G 诊断。";;
  esac
}

prepare() {
  base="${G5G_TMPBASE:-/data/local/tmp}"
  WORK="$base/g5g.$$.$(date +%s)"
  mkdir -p "$WORK" || fail '无法建立本机临时工作目录。'
  MENU="$WORK/menu.tsv"
  STATE_DIR="${G5G_STATE_DIR:-/sdcard/Download/Galaxy5G}"
}

# Obtain actual foreground focus (avoid tapping an unrelated app after interruption).
is_service_foreground() {
  # Some Samsung builds print an empty mCurrentFocus and a valid mFocusedApp.
  focus="$(dumpsys window 2>/dev/null | grep -iE 'mCurrentFocus|mFocusedApp|mTopFocused|mFocusedWindow' || true)"
  printf '%s\n' "$focus" | grep -iE 'RilServiceModeApp|servicemodeapp|service.mode' >/dev/null 2>&1 && return 0
  focus="$(dumpsys activity activities 2>/dev/null | grep -iE 'mResumedActivity|topResumedActivity|Resumed:' || true)"
  printf '%s\n' "$focus" | grep -iE 'RilServiceModeApp|servicemodeapp|service.mode' >/dev/null 2>&1
}

# Offline OCR is also used on the Samsung Dialer keypad; the two screens
# have DIFFERENT foreground guards. Do not reuse a ServiceMode-only reader.
is_dialer_foreground() {
  focus="$(dumpsys window 2>/dev/null | grep -iE 'mCurrentFocus|mFocusedApp|mTopFocused|mFocusedWindow' || true)"
  printf '%s\n' "$focus" | grep -iE 'com\.samsung\.android\.dialer|com\.android\.dialer|com\.sec\.android\.app\.dialertab' >/dev/null 2>&1 && return 0
  focus="$(dumpsys activity activities 2>/dev/null | grep -iE 'mResumedActivity|topResumedActivity|Resumed:' || true)"
  printf '%s\n' "$focus" | grep -iE 'com\.samsung\.android\.dialer|com\.android\.dialer|com\.sec\.android\.app\.dialertab' >/dev/null 2>&1
}

read_ocr_snapshot() {
  rm -f "$MENU"
  [ -n "${G5G_OCR_REPLY:-}" ] || fail '无离线 OCR 通道；无法安全识别页面。'
  rm -f "$G5G_OCR_REPLY"
  say '@G5G_OCR_REQUEST@'
  n=0
  while [ "$n" -lt 100 ] && [ ! -f "$G5G_OCR_REPLY" ]; do
    sleep 0.2
    n=$((n+1))
  done
  [ -f "$G5G_OCR_REPLY" ] || fail '离线 OCR 响应超时。'
  if grep '^ERROR:' "$G5G_OCR_REPLY" >/dev/null 2>&1; then
    fail "离线 OCR 失败：$(head -n 1 "$G5G_OCR_REPLY" | cut -c1-220)"
  fi
  awk -F '\t' 'NF!=3 || $1=="" || $2!~/^[0-9]+$/ || $3!~/^[0-9]+$/ {bad=1} END {exit bad}' "$G5G_OCR_REPLY" || fail '离线 OCR 返回了无效格式。'
  [ -s "$G5G_OCR_REPLY" ] || fail 'OCR 没有识别到任何文字。'
  cp "$G5G_OCR_REPLY" "$MENU" || fail '无法保存 OCR 结果。'
}

read_page() {
  is_service_foreground || fail '当前前台不是 ServiceMode；已中止，防止误点其他应用。'
  read_ocr_snapshot
  is_service_foreground || fail 'OCR 完成后 ServiceMode 失焦；未执行点击。'
}

read_dialer_page() {
  is_dialer_foreground || fail '当前前台不是三星拨号器；拒绝向未知应用注入按键。'
  read_ocr_snapshot
  is_dialer_foreground || fail '键盘截图后拨号器已经失焦；拒绝继续。'
}

# find_label returns distinct menu target XY; exit=4 missing, exit=3 ambiguous.
find_label() {
  # Label mapping applies ONLY after the expected page can be recognized by
  # independent siblings. Do not accept unknown edit distances or partial words.
  # Raw marker (*) must be retained to detect already-selected radio settings.
  awk -F '\t' -v want="$1" '
  function strip(t) {
    gsub(/^[[:space:]]+|[[:space:]]+$/, "", t)
    gsub(/[[:space:]]+/, " ", t)
    return t
  }
  # Some One UI screenshots merge an adjacent numeric row indicator into a
  # separate menu label, e.g. "21 SYSTEM" instead of "[2] SYSTEM". Strip
  # 1-2 leading digits ONLY for known navigation labels on a recognized page.
  # Never strip prefixes when committing radio-setting leaf actions.
  function nav_text(s) {
    s=strip(s)
    sub(/^[0-9][0-9]?[[:space:]]+/, "", s)
    return s
  }
  function matched(t,   nav) {
    if (t == want) return 1
    nav=nav_text(t)
    if (want == "SETTING" && ue_page && nav == want) return 1
    if ((want == "SYSTEM" || want == "PROTOCOL") && setting_page && nav == want) return 1
    if ((want == "NR5G" || want == "GCF") && protocol_page && nav == want) return 1
    if (want == "PRUNE STATUS" && nr_page && nav == want) return 1
    if (want == "UE SETTING & INFO")
      return t ~ /^UE SETTING (8&|&) INFO$/
    if (want == "NEXT PAGE")
      return t ~ /^NEXT PAGE([[:space:]]*[-|>]+)?$/
    if (want == "SUB6/MMWAVE" && nr_page)
      return nav ~ /^(SUB6|SUB|SUBG|SUB5)[\/]MMWAVE$/
    if (want == "SA/NSA MODE CONTROL" && nr_page)
      return nav == want || nav == "SA/NSA MODE CONTR0L" || nav == "SA/NSA MODE CONTRO1"
    if (want == "SET LTE BAND CONFIG" && sys_page)
      return nav == want || nav == "SET LTE BAND C0NFIG" || nav == "SET LTE BAND CONF1G"
    if (want == "ALL ENABLE" && sub_page)
      return t ~ /^ALL ENABLE([[:space:]]*\(\*?\))?$/
    if (want == "SA/NSA ENABLE" && sa_page)
      return t ~ /^SA\/NSA ENABLE([[:space:]]*\(\*?\))?$/
    # Permit-list is its own action, NEVER an alias of SA/NSA ENABLE.
    if (want == "REMOVE SA PERMIT LIST" && sa_page)
      return t == "REMOVE SA PERMIN LIST" || t == "REMOVE SA PERMIT L1ST"
    return 0
  }
  {
    t[NR]=strip($1)
    nav[NR]=nav_text(t[NR])
    if (nav[NR] == "UE SETTING MENU" || nav[NR] == "UE SETTING MEINU") has_ue_header=1
    if (nav[NR] == "SETTING") has_setting=1
    if (nav[NR] == "SYSTEM") has_system=1
    if (nav[NR] == "PROTOCOL") has_protocol=1
    if (nav[NR] == "NR5G") has_nr5g=1
    if (nav[NR] == "GCF") has_gcf=1
    if (nav[NR] == "PRUNE STATUS") has_prune=1
    if (nav[NR] == "SA/NSA MODE CONTROL" || nav[NR] == "SA/NSA MODE CONTR0L" || nav[NR] == "SA/NSA MODE CONTRO1") has_sa_nav=1
    if (t[NR] == "SRS ENABLE/DISABLE" || t[NR] == "RRC CAP FEATURE PHY") has_nr_detail=1
    if (t[NR] == "SUB6 ONLY ENABLE") has_sub6=1
    if (t[NR] == "MMWAVE ONLY ENABLE") has_mmwave=1
    if (t[NR] == "DISABLE") has_disable=1
    if (t[NR] == "SA DISABLE") has_sa_disable=1
    if (t[NR] == "NSA DISABLE") has_nsa_disable=1
    if (t[NR] ~ /^REMOVE SA PERMI(T|N) (LIST|L1ST)$/) has_permit=1
    if (t[NR] ~ /^NEXT PAGE([[:space:]]*[-|>]+)?$/) has_next=1
  }
  END {
    # Distinct sibling signatures are necessary before accepting a
    # prefix-corrected navigation item; one isolated OCR string is insufficient.
    ue_page = has_ue_header && has_setting
    setting_page = has_system && has_protocol && !has_gcf
    protocol_page = has_nr5g && has_gcf
    nr_page = has_prune && has_sa_nav
    sub_page = has_sub6 && has_mmwave && has_disable
    sa_page = has_permit && (has_sa_disable || has_nsa_disable || !sub_page)
    sys_page = has_next
    if (want == "ALL ENABLE" && !sub_page) exit 4
    if ((want == "SA/NSA ENABLE" || want == "REMOVE SA PERMIT LIST") && !sa_page) exit 4
    count=0
    for (i=1;i<=NR;i++) if (matched(t[i])) {count++; x=xs[i]; y=ys[i]}
    if (count==1) {print x " " y; exit 0}
    if (count>1) exit 3
    exit 4
  }
  {
    xs[NR]=$2;ys[NR]=$3
  }' "$MENU"
}

exists_label() {
  find_label "$1" >/dev/null 2>&1
  result=$?
  [ "$result" -ne 3 ] || fail "菜单 [$1] 出现多个不同位置，拒绝继续。"
  [ "$result" -eq 0 ]
}

wait_for() {
  target="$1"; alt="${2:-}"; attempts=0
  max_attempts=5
  [ "$UIA_UNAVAILABLE" -eq 1 ] && max_attempts=2
  while [ "$attempts" -lt "$max_attempts" ]; do
    read_page
    if exists_label "$target" || { [ -n "$alt" ] && exists_label "$alt"; }; then return 0; fi
    attempts=$((attempts+1)); sleep 0.8
  done
  log "OCR 检测到的候选菜单（最多 18 行）："
  cut -f1 "$MENU" | head -n 18 | while IFS= read -r label; do log "候选：$label"; done
  fail "等待菜单 [$target] 失败${alt:+（备选：$alt）}。可能当前页面不是主菜单；已停止。"
}

# Read-only UI state classifier. A signature identifies an already-known page;
# unknown states MUST NOT trigger taps. Never infer a state from the mobile
# network icon or from only the word "SERVICEMODE".
classify_page() {
  if exists_label 'SET DEFAULT LTE BAND'; then say 'LTE_CONFIG'; return; fi
  if exists_label 'SET LTE BAND CONFIG'; then say 'SYSTEM_LTE'; return; fi
  if exists_label 'REMOVE NR5G PRUNE' || exists_label 'RESTORE NR5G PRUNE'; then say 'PRUNE'; return; fi
  if exists_label 'REMOVE ONLY NR WHITELIST'; then say 'GCF'; return; fi
  if exists_label 'SA/NSA ENABLE' && exists_label 'REMOVE SA PERMIT LIST'; then say 'SA_MODE'; return; fi
  if exists_label 'ALL ENABLE'; then say 'SUB6'; return; fi
  if exists_label 'PRUNE STATUS' && exists_label 'SA/NSA MODE CONTROL'; then say 'NR5G'; return; fi
  if exists_label 'NR5G' && exists_label 'GCF'; then say 'PROTOCOL'; return; fi
  if exists_label 'SYSTEM' && exists_label 'PROTOCOL'; then say 'SETTING'; return; fi
  if exists_label 'UE SETTING & INFO'; then say 'MAIN'; return; fi
  if exists_label 'SETTING'; then say 'UE'; return; fi
  if exists_label 'NEXT PAGE'; then say 'SYSTEM_PAGE'; return; fi
  say 'UNKNOWN'
}

# Only navigation clicks get a bounded recovery. The last radio-setting leaf is
# intentionally NOT handled here and never retried automatically.
tap_label() {
  label="$1"; next="$2"; alternate="${3:-}"
  read_page
  pos="$(find_label "$label")"; rc=$?
  if [ "$rc" -eq 3 ]; then fail "菜单 [$label] 出现多个位置；拒绝猜测。"; fi
  if [ "$rc" -ne 0 ]; then
    LAST_RECOVERY_TARGET="$label"
    log "状态机：找不到 [$label]，当前状态 $(classify_page)，准备重新定位。"
    return 5
  fi
  before="$(cksum "$MENU" | awk '{print $1 ":" $2}')"
  set -- $pos
  log "点击 [$label] ($1,$2)"
  input tap "$1" "$2" >/dev/null 2>&1 || fail "input tap [$label] 失败。"
  # Give Samsung's modem menu time to settle before OCR. Transient layout
  # changes must not be treated as an intermediate final state.
  sleep 0.85
  changed=0; attempt=0
  max_attempts=3
  while [ "$attempt" -lt "$max_attempts" ]; do
    read_page
    now="$(cksum "$MENU" | awk '{print $1 ":" $2}')"
    [ "$now" != "$before" ] && changed=1
    if { exists_label "$next" || { [ -n "$alternate" ] && exists_label "$alternate"; }; } &&
       { [ "$label" != 'NEXT PAGE' ] || [ "$changed" -eq 1 ]; }; then
      log "状态机：已验证转移 [$label] → [$next]"
      return 0
    fi
    attempt=$((attempt+1)); sleep 0.7
  done
  LAST_RECOVERY_TARGET="$label → $next"
  log "状态机：转移 [$label] 后未找到 [$next]；状态=$(classify_page)；候选：$(cut -f1 "$MENU" | head -n 14 | tr '\n' '|')"
  if [ "$label" = 'NEXT PAGE' ] && [ "$changed" -eq 0 ]; then
    fail 'NEXT PAGE 点击后界面未变化，拒绝继续翻页。'
  fi
  return 5
}

# Recovery hierarchy: (1) observe state, (2) verified ServiceMode Menu/Back,
# (3) reenter secret-code main menu, (4) stop. Android KEYCODE_BACK is never
# sent and the app menu is never blindly tapped without recognized controls.
try_menu_back_to_main() {
  attempts=0
  while [ "$attempts" -lt 3 ]; do
    read_page
    state="$(classify_page)"
    [ "$state" = MAIN ] && { log '状态机：已经回到主菜单'; return 0; }
    # Opening the options popup alone is not a radio-setting mutation.
    input keyevent KEYCODE_MENU >/dev/null 2>&1 || return 1
    sleep 0.35
    read_page
    # ServiceMode options popup is uniquely recognized by KEY INPUT and BACK.
    if ! exists_label 'KEY INPUT' || ! exists_label 'BACK'; then
      log '状态机：没有识别到明确的 Menu/Back 菜单，不猜测，改用重进工程码。'
      return 1
    fi
    pos="$(find_label 'BACK')"; rc=$?
    [ "$rc" -eq 0 ] || return 1
    set -- $pos
    input tap "$1" "$2" >/dev/null 2>&1 || return 1
    sleep 0.8
    attempts=$((attempts+1))
  done
  read_page
  [ "$(classify_page)" = MAIN ]
}

# Cache permission-denied direct entry methods for the current workflow,
# rather than issuing six identical denied commands during a six-step probe.
DIRECT_ACTIVITY_UNAVAILABLE=0
SECRET_BROADCAST_UNAVAILABLE=0

# Return 0 only after the ServiceMode MAIN page is OCR-confirmed. Shell command
# success is never sufficient to claim the engineering screen was opened.
service_main_ready() {
  tries=0
  while [ "$tries" -lt 8 ]; do
    if is_service_foreground; then
      sleep 0.4
      read_page
      if [ "$(classify_page)" = MAIN ]; then return 0; fi
      log 'ServiceMode 已出现，但不是可确认的主菜单；不继续盲目导航。'
      return 1
    fi
    tries=$((tries+1))
    sleep 0.35
  done
  return 1
}

try_direct_activity() {
  [ "$DIRECT_ACTIVITY_UNAVAILABLE" -eq 0 ] || return 1
  log '启动入口 1/3：尝试直接打开 ServiceMode Activity…'
  output="$(am start -W -n com.sec.android.RilServiceModeApp/com.sec.android.RilServiceModeApp.ServiceModeApp 2>&1)"; rc=$?
  if [ "$rc" -ne 0 ] || printf '%s' "$output" | grep -qiE 'SecurityException|Permission Denial|not exported|Error type'; then
    log "直接启动被系统拒绝：$(printf '%s' "$output" | tr '\n' ' ' | cut -c1-150)"
    DIRECT_ACTIVITY_UNAVAILABLE=1
    return 1
  fi
  if service_main_ready; then log '入口 1/3：ServiceMode 主菜单验证通过。'; return 0; fi
  DIRECT_ACTIVITY_UNAVAILABLE=1
  log '入口 1/3 没有打开可验证的主菜单。'
  return 1
}

try_secret_broadcast() {
  [ "$SECRET_BROADCAST_UNAVAILABLE" -eq 0 ] || return 1
  log '启动入口 2/3：尝试三星工程码广播…'
  output="$(am broadcast -a com.samsung.android.action.SECRET_CODE -d android_secret_code://27663368378 -n com.sec.android.RilServiceModeApp/.SecKeyStringBroadcastReceiver 2>&1)"; rc=$?
  if [ "$rc" -ne 0 ] || printf '%s' "$output" | grep -qiE 'SecurityException|Permission Denial|not exported|not allowed'; then
    log "工程码广播被系统拒绝：$(printf '%s' "$output" | tr '\n' ' ' | cut -c1-150)"
    SECRET_BROADCAST_UNAVAILABLE=1
    return 1
  fi
  if service_main_ready; then log '入口 2/3：ServiceMode 主菜单验证通过。'; return 0; fi
  SECRET_BROADCAST_UNAVAILABLE=1
  log '入口 2/3 没有打开可验证的主菜单。'
  return 1
}

# OCR keypad recognition: accept an isolated key number or a dialer button
# label such as "2 ABC". Never treat the phone number (11 digits) as a key.
# Only derive * and # from a verified 1-9/0 3-column physical keypad, with
# spacing constraints; do NOT use fixed screen coordinates or KEYCODE_POUND.
locate_dial_key() {
  awk -F '\t' -v key="$1" '
  function clean(s) {gsub(/^[[:space:]]+|[[:space:]]+$/,"",s);gsub(/[[:space:]]+/," ",s);return s}
  {
    t=clean($1)
    if (t ~ /^[0-9]$/ || t ~ /^[2-9] [A-Z][A-Z][A-Z]?[A-Z]?$/) {
      d=substr(t,1,1)
      if (!(d in seen)) {seen[d]=1;xs[d]=$2;ys[d]=$3}
      else {duplicate[d]=1}
    }
  }
  END {
    for (i=1;i<=9;i++) if (!seen[i] || duplicate[i]) exit 5
    if (!seen[0] || duplicate[0]) exit 5
    # Keypad must be ordered in three rows and one final row.
    if (!(xs[1]<xs[2] && xs[2]<xs[3] && xs[4]<xs[5] && xs[5]<xs[6] && xs[7]<xs[8] && xs[8]<xs[9])) exit 5
    if (!(ys[1]<=ys[4] && ys[4]<ys[7] && ys[7]<ys[0])) exit 5
    dx=(xs[3]-xs[1])/2; dy=(ys[7]-ys[1])/2
    if (dx<35 || dy<35 || dx>800 || dy>900) exit 5
    if (xs[0]<xs[7] || xs[0]>xs[9]) exit 5
    # OCR often omits * and # glyphs; derive only after grid validation.
    if(key=="*") {x=xs[7]; y=ys[0]}
    else if(key=="#") {x=xs[9]; y=ys[0]}
    else if (key in seen) {x=xs[key]; y=ys[key]}
    else exit 5
    print x " " y
  }' "$MENU"
}

# Ignore ambient UI text; recognize the current dialer number only if the OCR
# produces the full contiguous prefix. Ambiguous OCR fails closed.
verify_dialed_prefix() {
  awk -F '\t' '
  {
    t=$1
    gsub(/[[:space:]()-]/,"",t)
    if(t=="*#27663368378" || t=="#27663368378" || t=="27663368378") found=1
  }
  END {exit !found}' "$MENU"
}

# When Samsung/ML Kit merges or omits keypad glyphs, the eleven digit
# keys may still accept input via the same Shell KEYCODE path used by v0.8.
# Never interpret OCR failure as an absent keypad. KEYCODE is sent ONLY after
# the foreground is verified to be Samsung's dialer, and never sends CALL.
# Last '#' is intentionally separate and delayed to allow the dialer to settle.
dialer_keyevent_fallback() {
  log '拨号键盘 OCR 未能确认全部按键：改用 v0.8 兼容的系统按键输入（仅三星拨号器）。'
  is_dialer_foreground || fail '备用拨号时三星拨号器已经失焦。'
  input keyevent KEYCODE_STAR >/dev/null 2>&1 || fail '备用输入开头 * 失败。'
  sleep 0.35
  is_dialer_foreground || fail '输入 * 后拨号器失焦。'
  input keyevent KEYCODE_POUND >/dev/null 2>&1 || fail '备用输入开头 # 失败。'
  sleep 0.4
  is_dialer_foreground || fail '输入开头 # 后拨号器失焦。'
  input text 27663368378 >/dev/null 2>&1 || fail '备用数字输入失败。'
  sleep 0.8
  is_dialer_foreground || { is_service_foreground && return 0; fail '数字输入期间拨号器失焦。'; }
  # OCR of the number field is advisory: several One UI builds hide or split
  # it into blocks. Do not repeat input based only on an OCR false negative.
  if read_dialer_page; then
    if verify_dialed_prefix; then
      log '输入前缀：拨号器 OCR 已确认完整工程码前缀。'
    else
      log '输入前缀：OCR 未能确认完整字符；按键命令已执行，继续尝试唯一末尾 #（不重复输入数字）。'
    fi
  fi
  is_dialer_foreground || fail '提交末尾 # 前拨号器已失焦。'
  input keyevent KEYCODE_POUND >/dev/null 2>&1 || fail '备用输入最后 # 失败。'
  sleep 0.7
  if service_main_ready; then
    log '入口 3/3：按键兼容路径成功进入 ServiceMode。'
    return 0
  fi
  fail '已完成一次按键兼容输入，但未进入 ServiceMode；可能最后的 # 未被拨号器接收。请先检查屏幕上的号码，不会重复输入或发起电话呼叫。'
}

try_dialer_ocr() {
  log '启动入口 3/3：打开三星拨号器，优先定位真实键盘；识别不完整则使用兼容按键路径。'
  am start -W -a android.intent.action.DIAL >/dev/null 2>&1 || fail '无法打开拨号器。'
  sleep 1.2
  is_dialer_foreground || fail '拨号器尚未显示，拒绝向未知应用注入按键。'
  read_dialer_page
  : > "$WORK/positions.tsv"
  complete_grid=1
  for key in 1 2 3 4 5 6 7 8 9 0 '*' '#'; do
    if ! locate_dial_key "$key" >> "$WORK/positions.tsv"; then
      complete_grid=0
      break
    fi
  done
  if [ "$complete_grid" -ne 1 ]; then
    log "拨号器 OCR 可见文本（最多 8 项）：$(cut -f1 "$MENU" | head -n 8 | tr '\n' '|')"
    dialer_keyevent_fallback
    return $?
  fi
  log '已通过 OCR 校验完整拨号键盘，采用真实按钮逐键输入。'
  : > "$WORK/positions-sequence.tsv"
  for k in '*' '#' 2 7 6 6 3 3 6 8 3 7 8 '#'; do
    case "$k" in '*' ) index=11;; '#' ) index=12;; 0) index=10;; *) index="$k";; esac
    sed -n "${index}p" "$WORK/positions.tsv" >> "$WORK/positions-sequence.tsv"
  done
  count=1
  while [ "$count" -le 14 ]; do
    is_dialer_foreground || { is_service_foreground && break; fail '拨号中途离开三星拨号器，已停止。'; }
    pos="$(sed -n "${count}p" "$WORK/positions-sequence.tsv")"
    [ -n "$pos" ] || fail '内部按键映射缺失，停止输入。'
    set -- $pos
    input tap "$1" "$2" >/dev/null 2>&1 || fail '拨号键盘点击失败。'
    sleep 0.23
    count=$((count+1))
    if [ "$count" -eq 14 ]; then
      sleep 0.55
      read_dialer_page
      if ! verify_dialed_prefix; then
        # The observed page may group or omit digits. For physical keypad
        # input, we have already matched the complete grid and each tap.
        # Do not repeatedly inject digits from an OCR false negative.
        log 'OCR 未能确认完整号码前缀；每个真实按键已按序点击，仅尝试一次末尾 #。'
      fi
    fi
  done
  if service_main_ready; then log '入口 3/3：工程码成功打开 ServiceMode。'; return 0; fi
  fail '已输入末尾 #，但没有进入 ServiceMode；请检查拨号器界面，不会继续追加或发起呼叫。'
}

open_service_main() {
  if is_service_foreground; then
    read_page
    if [ "$(classify_page)" = MAIN ]; then
      log '状态机：已处于 ServiceMode 主菜单。'
      return 0
    fi
    am force-stop com.sec.android.RilServiceModeApp >/dev/null 2>&1 || true
    am force-stop com.sec.android.app.servicemodeapp >/dev/null 2>&1 || true
    sleep 0.35
  fi
  try_direct_activity && return 0
  try_secret_broadcast && return 0
  try_dialer_ocr
}

step_info() {
  case "$1" in
    1) TITLE='恢复默认 LTE 频段'; PARENTS='UE SETTING & INFO|SETTING|SYSTEM|NEXT PAGE|NEXT PAGE|NEXT PAGE|NEXT PAGE|SET LTE BAND CONFIG'; ACTION='SET DEFAULT LTE BAND'; ALREADY='';;
    2) TITLE='移除 NR5G PRUNE'; PARENTS='UE SETTING & INFO|SETTING|PROTOCOL|NR5G|PRUNE STATUS'; ACTION='REMOVE NR5G PRUNE'; ALREADY='RESTORE NR5G PRUNE';;
    3) TITLE='移除 NR 白名单'; PARENTS='UE SETTING & INFO|SETTING|PROTOCOL|GCF'; ACTION='REMOVE ONLY NR WHITELIST'; ALREADY='';;
    4) TITLE='Sub-6/mmWave ALL ENABLE'; PARENTS='UE SETTING & INFO|SETTING|PROTOCOL|NR5G|SUB6/MMWAVE'; ACTION='ALL ENABLE'; ALREADY='';;
    5) TITLE='启用 SA/NSA'; PARENTS='UE SETTING & INFO|SETTING|PROTOCOL|NR5G|SA/NSA MODE CONTROL'; ACTION='SA/NSA ENABLE'; ALREADY='';;
    6) TITLE='移除 SA permit list'; PARENTS='UE SETTING & INFO|SETTING|PROTOCOL|NR5G|SA/NSA MODE CONTROL'; ACTION='REMOVE SA PERMIT LIST'; ALREADY='';;
    *) fail '内部步骤编号错误。';;
  esac
}

navigate_parent_path() {
  oldIFS="$IFS"; IFS='|'; set -- $PARENTS; IFS="$oldIFS"
  while [ "$#" -gt 0 ]; do
    current="$1"; shift
    if [ "$#" -gt 0 ]; then next="$1"; alt=''; else next="$ACTION"; alt="$ALREADY"; fi
    tap_label "$current" "$next" "$alt" || return 5
  done
  return 0
}

run_step() {
  idx="$1"; step_info "$idx"; log "[$idx] $TITLE"
  open_service_main
  recovery_count=0
  while ! navigate_parent_path; do
    if [ "$recovery_count" -ge 1 ]; then
      log "当前菜单：$(cut -f1 "$MENU" | head -n 16 | tr '\n' '|')"
      fail "[$idx] 状态重新定位后仍未完成 [$LAST_RECOVERY_TARGET]；停止，不执行无线设置。"
    fi
    recovery_count=$((recovery_count+1))
    log "[$idx] 状态机恢复：优先 Menu/Back，再从工程码重新进入；恢复上限一次。"
    if ! try_menu_back_to_main; then
      open_service_main
    fi
  done
  read_page
  if [ -n "$ALREADY" ] && exists_label "$ALREADY"; then
    log "[$idx] 页面显示已解除 PRUNE，跳过该操作。"
    return 0
  fi
  exists_label "$ACTION" || fail "最终目标 [$ACTION] 不在菜单中。"
  if [ "$idx" -eq 4 ]; then
    # Explicitly distinguish navigable item from selection state. Samsung's
    # star indicates selected; our OCR must not discard it. Never toggle an
    # already-selected ALL ENABLE option, and never infer enabled from bare ().
    marker_state="$(awk -F '\t' '
      $1 ~ /^ALL ENABLE[[:space:]]*\(\*\)$/ { seen=1 }
      $1 ~ /^ALL ENABLE[[:space:]]*\(\)$/ { unsure=1 }
      END { if(seen) print "SELECTED"; else if(unsure) print "UNCERTAIN"; else print "UNSELECTED" }
      ' "$MENU")"
    if [ "$MODE" = 'probe' ]; then
      case "$marker_state" in
        SELECTED) log '[4] ALL ENABLE 带 (*)，已选中（页面标记，仅探测，未修改）';;
        UNCERTAIN) log '[4] ALL ENABLE 后为 ()，OCR 未辨认选择标记；仅确认路径，不判定是否已开启';;
        *) log '[4] 已定位 ALL ENABLE，未观察到选择标记；仅探测';;
      esac
      log "[$idx] 可访问：$ACTION（未点击）"
      return 0
    fi
    case "$marker_state" in
      SELECTED)
        log '[4] ALL ENABLE (*) 已选中：无需重复点击；跳过本项修改。'
        return 0;;
      UNCERTAIN)
        fail '[4] ALL ENABLE () 的选择标记不确定；为避免切换错误状态，未点击。请查看屏幕或重试探测。';;
    esac
  fi
  if [ "$idx" -eq 5 ]; then
    marker_state="$(awk -F '\t' '
      $1 ~ /^SA\/NSA ENABLE[[:space:]]*\(\*\)$/ { selected=1 }
      $1 ~ /^SA\/NSA ENABLE[[:space:]]*\(\)$/ { uncertain=1 }
      END { if(selected) print "SELECTED"; else if(uncertain) print "UNCERTAIN"; else print "UNSELECTED" }
      ' "$MENU")"
    if [ "$MODE" = 'probe' ]; then
      case "$marker_state" in
        SELECTED) log '[5] SA/NSA ENABLE (*) 已选中；仅探测，未修改';;
        UNCERTAIN) log '[5] SA/NSA ENABLE () OCR 未认出星号；不能确认启用状态';;
        *) log '[5] 已定位 SA/NSA ENABLE；未观察到选中标记';;
      esac
      log '[5] 提醒：第 6 项 REMOVE SA PERMIT LIST 是独立操作，仍需单独探测/执行。'
      log "[$idx] 可访问：$ACTION（未点击）"
      return 0
    fi
    case "$marker_state" in
      SELECTED)
        log '[5] SA/NSA ENABLE (*) 已选中：不重复点击；第 6 项 REMOVE SA PERMIT LIST 仍须执行。'
        return 0;;
      UNCERTAIN)
        fail '[5] SA/NSA ENABLE () 选择标记不确定，未点击；也不能据此跳过单独的 SA permit list 操作。';;
    esac
  fi
  if [ "$MODE" = 'probe' ]; then
    log "[$idx] 可访问：$ACTION（未点击）"
    return 0
  fi
  # Leaf writes must still resolve to one unique target and pass foreground
  # checks. The special selection marker above is restricted to the SUB6 page.
  pos="$(find_label "$ACTION")"; rc=$?
  [ "$rc" -eq 0 ] || fail "最终目标 [$ACTION] 无法唯一定位。"
  set -- $pos
  is_service_foreground || fail '最终提交前 ServiceMode 已失焦。'
  input tap "$1" "$2" >/dev/null 2>&1 || fail "无法执行 [$ACTION]。"
  log "[$idx] 已点击 [$ACTION]；未读取 NV 验证，需重启实测网络。"
}

print_plan() {
  say '计划路径（每个项目从主菜单独立重新进入，不使用返回键）：'
  for i in 1 2 3 4 5 6; do
    step_info "$i"
    say "$i. $TITLE：$(printf %s "$PARENTS" | tr '|' '/')/$ACTION"
  done
  say 'core 不做第4项；full 执行1–6项；仅支持 SIM1。'
}

show_status() {
  log "型号：$model | Android $android | 固件：$build"
  log "权限：uid=$(id -u)（2000 为 Shizuku/ADB shell，0 为 Root）"
  log "网络类型（参考）：$(getprop gsm.network.type 2>/dev/null)"
  state="$STATE_DIR/last-${model}.txt"
  if [ -r "$state" ]; then
    prev="$(sed -n '1p' "$state" 2>/dev/null)"
    if [ "$prev" = "$build" ]; then
      log '固件版本与上次脚本完整提交时相同；这不证明 NV 未丢失。'
    else
      log "检测到固件变化：上次=$prev；当前=$build。可先探测，勿盲目重复写入。"
    fi
  else
    log '未发现本工具的历史完整提交记录。'
  fi
}

record_complete() {
  mkdir -p "$STATE_DIR" 2>/dev/null || { log '无法保存 Download 目录的状态（不影响菜单操作）。'; return 0; }
  state="$STATE_DIR/last-${model}.txt"
  { printf '%s\n%s\n%s\n' "$build" "$PROFILE" "$(date '+%Y-%m-%d %H:%M:%S')"; } > "$state" 2>/dev/null || log '保存固件状态失败。'
}

case "$MODE" in help|-h|--help) usage; exit 0;;
  plan) print_plan; exit 0;;
  status|inspect|probe|apply) ;;
  *) usage; fail "未知命令：$MODE";;
esac

PROFILE="${2:-core}"
case "$PROFILE" in core|full) ;; *) fail '模式必须为 core 或 full。';; esac

if [ "$MODE" = 'apply' ]; then
  required=APPLY; [ "$PROFILE" = full ] && required=FULL
  [ "${3:-}" = "$required" ] || fail "拒绝写入：需明确输入确认词 $required。"
fi

check_device
prepare
if [ "$MODE" = status ]; then show_status; exit 0; fi
if [ "$MODE" = inspect ]; then
  read_page
  log "当前 ServiceMode 可读菜单（只读）："
  cut -f1 "$MENU"
  exit 0
fi

log "目标设备 $model / Android $android；模式 $MODE-$PROFILE。"
log '执行过程中手机必须亮屏、解锁，并保持当前工程菜单前台。'
if [ "$MODE" = apply ]; then
  log '即将修改基带工程菜单（仅 SIM1）；请确认已预先运行 probe，网络连接可能中断。'
fi
for i in 1 2 3 4 5 6; do
  if [ "$i" -eq 4 ] && [ "$PROFILE" = core ]; then continue; fi
  run_step "$i"
done
if [ "$MODE" = probe ]; then
  log '探测结束：没有点击终端设置项；但已在手机上打开并导航工程菜单。'
else
  record_complete
  log '所有指定菜单操作已提交；并不等于已经独立证明基带 NV 更改成功。'
  log '请手工重启手机，在实际 5G 覆盖和已开通 NR 的 SIM 下验证。'
fi
