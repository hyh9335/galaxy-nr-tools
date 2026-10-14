#!/system/bin/sh
# Galaxy 5G on-device ServiceMode navigator v2.0 (Shizuku/rish/aShell You).
# No root, no fixed coordinates, no blind BACK presses, fail closed.
# Needs Android /system/bin/sh and Shizuku shell (uid=2000) or real root (uid=0).
# This tool sends screen input to Samsung's ServiceMode. It does not write NV directly.

set -u
VERSION=2.2.0
UIA_UNAVAILABLE=0
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
  case "$android" in 17*) UIA_UNAVAILABLE=1; log 'Android 17：已跳过反复被终止的 uiautomator，直接使用本机 OCR';; esac
  case "$(printf %s "$manufacturer" | tr '[:upper:]' '[:lower:]')/$model" in
    samsung/SM-S918U|samsung/SM-S918U1|samsung/SM-S938U|samsung/SM-S938U1) ;;
    *) fail "未通过机型保护：厂商=$manufacturer 型号=$model。仅自动支持 S23U/S25U 美版 U/U1。";;
  esac
}

prepare() {
  base="${G5G_TMPBASE:-/data/local/tmp}"
  WORK="$base/g5g.$$.$(date +%s)"
  mkdir -p "$WORK" || fail '无法建立本机临时工作目录。'
  XML="$WORK/hierarchy.xml"
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

# Parse raw UI Automator XML. Android usually puts every <node> on one line.
# Extract exact label and its actual per-device bounds; normalize numeric prefixes.
# Store only normalized label and center position, no IMEI/telephony identifiers.
parse_xml() {
  sed 's/></>\
</g' "$XML" | awk '
  function attr(s,k,  p,t,q) {
    p=index(s,k "=\""); if(!p)return "";
    t=substr(s,p+length(k)+2); q=index(t,"\"");
    if(!q)return ""; return substr(t,1,q-1)
  }
  function clean(s) {
    gsub(/&amp;/,"\\&",s);gsub(/&quot;/,"\"",s);gsub(/&apos;/,"\047",s);
    gsub(/&lt;/,"<",s);gsub(/&gt;/,">",s);
    sub(/^[[:space:]]+/,"",s); sub(/[[:space:]]+$/, "", s);
    sub(/^\[[0-9]+\][[:space:]]*/, "",s);
    sub(/^\([0-9]+\)[[:space:]]*/, "",s);
    sub(/^[0-9]+[.):][[:space:]]*/, "",s);
    gsub(/[[:space:]]+/," ",s); return toupper(s)
  }
  /<node[[:space:]]/ {
    label=attr($0,"text"); if(label=="")label=attr($0,"content-desc");
    label=clean(label); if(label=="")next;
    b=attr($0,"bounds"); gsub(/\[/,"",b);gsub(/\]/," ",b);gsub(/,/," ",b);
    n=split(b,p,/ +/); if(n<4)next;
    x1=p[1]+0;y1=p[2]+0;x2=p[3]+0;y2=p[4]+0;
    if(x2<=x1||y2<=y1)next;
    x=int((x1+x2)/2); y=int((y1+y2)/2);
    k=label "\t" x "\t" y;
    if(!seen[k]++) print k;
  }' > "$MENU" || fail 'UI XML 解析失败。'
  [ -s "$MENU" ]
}

# Android 17 may reject uiautomator dump (e.g. when the window never becomes idle).
# The UserService may provide an offline image OCR response through a private
# /data/local/tmp file. No guessing screen positions, no external service.
read_page() {
  is_service_foreground || fail '当前前台不是 ServiceMode；已中止，防止误点其他应用。'
  rm -f "$XML" "$MENU"
  if [ "$UIA_UNAVAILABLE" -eq 0 ]; then
    if uiautomator dump "$XML" > "$WORK/uiautomator.txt" 2>&1 && [ -s "$XML" ] && parse_xml; then
      return 0
    fi
    dump_error="$(head -n 5 "$WORK/uiautomator.txt" | tr '\n' ' ' | cut -c1-240)"
    log "uiautomator 不可用：${dump_error:-无法解析页面}；改用 OCR"
    UIA_UNAVAILABLE=1
  fi
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
  awk -F '\t' -v want="$1" '
  $1 == want { count++; x=$2; y=$3 }
  END { if(count==1) {print x " " y; exit 0} if(count>1) exit 3; exit 4 }' "$MENU"
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
    attempts=$((attempts+1)); sleep 0.5
  done
  log "OCR 检测到的候选菜单（最多 18 行）："
  cut -f1 "$MENU" | head -n 18 | while IFS= read -r label; do log "候选：$label"; done
  fail "等待菜单 [$target] 失败${alt:+（备选：$alt）}。可能当前页面不是主菜单；已停止。"
}

# Do not use Android BACK: on ServiceMode it may jump multiple levels.
tap_label() {
  label="$1"; next="$2"; alternate="${3:-}"
  read_page
  pos="$(find_label "$label")"; rc=$?
  [ "$rc" -eq 0 ] || {
    [ "$rc" -eq 3 ] && fail "菜单 [$label] 出现多个位置；拒绝猜测。"
    fail "当前找不到唯一菜单 [$label]。可运行 inspect 检查实际文字。"
  }
  before="$(cksum "$MENU" | awk '{print $1 ":" $2}')"
  set -- $pos
  log "点击 [$label] ($1,$2)"
  input tap "$1" "$2" >/dev/null 2>&1 || fail "input tap [$label] 失败。"
  sleep 0.5
  changed=0; attempt=0
  max_attempts=5
  [ "$UIA_UNAVAILABLE" -eq 1 ] && max_attempts=2
  while [ "$attempt" -lt "$max_attempts" ]; do
    read_page
    now="$(cksum "$MENU" | awk '{print $1 ":" $2}')"
    [ "$now" != "$before" ] && changed=1
    if { exists_label "$next" || { [ -n "$alternate" ] && exists_label "$alternate"; }; } &&
       { [ "$label" != 'NEXT PAGE' ] || [ "$changed" -eq 1 ]; }; then
      return 0
    fi
    attempt=$((attempt+1)); sleep 0.35
  done
  log "当前候选菜单：$(cut -f1 "$MENU" | head -n 12 | tr '\n' '|')"
  if [ "$label" = 'NEXT PAGE' ] && [ "$changed" -eq 0 ]; then
    fail 'NEXT PAGE 点击后界面未变化，拒绝继续翻页。'
  fi
  fail "点击 [$label] 后未出现 [$next]；可能菜单层级已变化。"
}

open_service_main() {
  # A new entry to main screen for EVERY item, never guess BACK-stack depth.
  if is_service_foreground; then
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

run_step() {
  idx="$1"; step_info "$idx"; log "[$idx] $TITLE"
  open_service_main
  oldIFS="$IFS"; IFS='|'; set -- $PARENTS; IFS="$oldIFS"
  while [ "$#" -gt 0 ]; do
    current="$1"; shift
    if [ "$#" -gt 0 ]; then next="$1"; alt=''; else next="$ACTION"; alt="$ALREADY"; fi
    tap_label "$current" "$next" "$alt"
  done
  read_page
  if [ -n "$ALREADY" ] && exists_label "$ALREADY"; then
    log "[$idx] 页面显示已解除 PRUNE，跳过该操作。"
    return 0
  fi
  exists_label "$ACTION" || fail "最终目标 [$ACTION] 不在菜单中。"
  if [ "$MODE" = 'probe' ]; then
    log "[$idx] 可访问：$ACTION（未点击）"
    return 0
  fi
  # Last leaf action is deliberately not followed by a guess at an unknown next page.
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
