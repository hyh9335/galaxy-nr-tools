#!/system/bin/sh
# Galaxy 5G on-device ServiceMode navigator v2.0 (Shizuku/rish/aShell You).
# No root, no fixed coordinates, no blind BACK presses, fail closed.
# Needs Android /system/bin/sh and Shizuku shell (uid=2000) or real root (uid=0).
# This tool sends screen input to Samsung's ServiceMode. It does not write NV directly.

set -u
VERSION=2.8.0
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
Galaxy 5G 手机本地脚本 v2.0 — 三星美版 S23 Ultra / S25 Ultra
需要：Shizuku 已启动，并通过 aShell You 或 rish 以 uid=2000(shell) 执行。

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
    samsung/SM-S918U|samsung/SM-S918U1|samsung/SM-S938U|samsung/SM-S938U1) ;;
    *) fail "未通过机型保护：厂商=$manufacturer 型号=$model。仅自动支持 S23U/S25U 美版 U/U1。";;
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

# Samsung engineering menus are drawn in views that can kill Android 17's
# uiautomator process. Only our local offline screenshot/OCR channel is used.
# Raw OCR text is normalized by a bounded page-aware matcher, not free-form
# fuzzy string similarity. On ambiguity or lost focus, stop without touching.
read_page() {
  is_service_foreground || fail '当前前台不是 ServiceMode；已中止，防止误点其他应用。'
  rm -f "$MENU"
  [ -n "${G5G_OCR_REPLY:-}" ] || fail '无离线 OCR 通道。请确认使用最新版 APK；不会盲点。'
  rm -f "$G5G_OCR_REPLY"
  say '@G5G_OCR_REQUEST@'
  n=0
  while [ "$n" -lt 100 ] && [ ! -f "$G5G_OCR_REPLY" ]; do
    sleep 0.2
    n=$((n+1))
  done
  [ -f "$G5G_OCR_REPLY" ] || fail '离线 OCR 响应超时；请检查手机电源管理和执行记录。'
  if grep '^ERROR:' "$G5G_OCR_REPLY" >/dev/null 2>&1; then
    fail "离线 OCR 失败：$(head -n 1 "$G5G_OCR_REPLY" | cut -c1-220)"
  fi
  # Strict TSV: label / x / y. Invalid or empty responses must NOT cause a tap.
  awk -F '\t' 'NF!=3 || $1=="" || $2!~/^[0-9]+$/ || $3!~/^[0-9]+$/ {bad=1} END {exit bad}' "$G5G_OCR_REPLY" || fail '离线 OCR 返回了无效菜单格式。'
  [ -s "$G5G_OCR_REPLY" ] || fail '离线 OCR 未识别出任何菜单文字，可能是防截图/受保护窗口。'
  cp "$G5G_OCR_REPLY" "$MENU" || fail '无法保存本机 OCR 菜单结果。'
  is_service_foreground || fail 'OCR 完成后 ServiceMode 失焦；未执行点击。'
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
  function matched(t) {
    if (t == want) return 1
    if (want == "UE SETTING & INFO")
      return t ~ /^UE SETTING (8&|&) INFO$/
    if (want == "NEXT PAGE")
      return t ~ /^NEXT PAGE([[:space:]]*[-|>]+)?$/
    if (want == "SUB6/MMWAVE" && nr_page)
      return t ~ /^(SUB6|SUB|SUBG|SUB5)[\/]MMWAVE$/
    if (want == "SA/NSA MODE CONTROL" && nr_page)
      return t == "SA/NSA MODE CONTR0L" || t == "SA/NSA MODE CONTRO1"
    if (want == "SET LTE BAND CONFIG" && sys_page)
      return t == "SET LTE BAND C0NFIG" || t == "SET LTE BAND CONF1G"
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
    if (t[NR] == "PRUNE STATUS") has_prune=1
    if (t[NR] == "SA/NSA MODE CONTROL" || t[NR] == "SA/NSA MODE CONTR0L" || t[NR] == "SA/NSA MODE CONTRO1") has_sa_nav=1
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

open_service_main() {
  # A new entry to main screen for EVERY item, never guess BACK-stack depth.
  if is_service_foreground; then
    # Check actual state; continuing from a verified root is safer/faster than
    # restarting the dialer. Unknown or nested pages use guarded reentry.
    read_page
    if [ "$(classify_page)" = MAIN ]; then
      log '状态机：当前已经位于工程主菜单，继续导航。'
      return 0
    fi
    am force-stop com.sec.android.RilServiceModeApp >/dev/null 2>&1 || true
    am force-stop com.sec.android.app.servicemodeapp >/dev/null 2>&1 || true
    sleep 0.3
  fi
  am start -W -a android.intent.action.DIAL >/dev/null 2>&1 || fail '无法打开系统拨号器。'
  sleep 0.8
  # Samsung Dialer secret codes trigger on last #, no call key sent.
  input keyevent KEYCODE_STAR >/dev/null 2>&1 || fail '无法模拟 * 键。'
  input keyevent KEYCODE_POUND >/dev/null 2>&1 || fail '无法模拟 # 键。'
  input text 27663368378 >/dev/null 2>&1 || fail '无法输入工程码数字。'
  input keyevent KEYCODE_POUND >/dev/null 2>&1 || fail '无法模拟最后一个 #。'
  attempt=0
  while [ "$attempt" -lt 12 ]; do
    if is_service_foreground; then
      wait_for 'UE SETTING & INFO'
      [ "$(classify_page)" = MAIN ] || fail '菜单虽包含 UE SETTING 字样但不是主菜单，已停止。'
      return 0
    fi
    attempt=$((attempt+1)); sleep 0.6
  done
  fail '没有进入 ServiceMode。检查三星原生拨号器的数字键盘是否显示、暗码是否可手动打开。'
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
