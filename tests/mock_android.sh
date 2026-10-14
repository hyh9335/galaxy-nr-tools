#!/bin/sh
# Toy fake Android commands, no Python startup overhead.
command=${0##*/}
base=${MOCK_DIR:?}
screen=$(cat "$base/screen")
variant=$(cat "$base/variant")
case "$command" in
 getprop)
  case "$1" in
   ro.product.manufacturer) echo samsung;;
   ro.product.model) cat "$base/model";;
   ro.build.version.incremental) echo build-101;;
   ro.build.version.release) echo "${MOCK_ANDROID_RELEASE:-16}";;
   gsm.network.type) echo LTE;;
  esac;;
 id) if [ "${1:-}" = -u ]; then echo 2000; else echo 'uid=2000(shell)'; fi;;
 dumpsys)
  case "$screen" in NONE|DIAL|OTHER) focus='com.samsung.android.dialer';; *) focus='com.sec.android.RilServiceModeApp';; esac
  if [ "$1" = window ]; then
   echo 'mCurrentFocus=null'; echo "mFocusedApp=$focus/.Main"
  else echo "mResumedActivity=$focus/.Main"; fi;;
 am)
  if [ "$1" = start ]; then
   echo DIAL > "$base/screen"; : > "$base/keys"; echo dialer_open >> "$base/events"
  else
   echo NONE > "$base/screen"; echo force_stop >> "$base/events"
  fi;;
 input)
  case "$1" in
   text) printf '%s' "$2" >> "$base/keys";;
   keyevent)
    case "$2" in KEYCODE_STAR) printf '*' >> "$base/keys";; KEYCODE_POUND) printf '#' >> "$base/keys";; esac
    if [ "$(cat "$base/keys")" = '*#27663368378#' ]; then
     echo MAIN > "$base/screen"; echo code_ok >> "$base/events"
    fi;;
   tap)
    idx=$(( ($3-150)/100 ))
    case "$screen" in
     MAIN) labels='UE SETTING & INFO';;
     UE) if [ "$variant" = ambiguous ]; then labels='SETTING|SETTING'; else labels='SETTING'; fi;;
     SETTING) labels='SYSTEM|PROTOCOL';;
     SYSTEM0) labels='NEXT PAGE|PAGE ZERO';;
     SYSTEM1) labels='NEXT PAGE|PAGE ONE';;
     SYSTEM2) labels='NEXT PAGE|PAGE TWO';;
     SYSTEM3) labels='NEXT PAGE|PAGE THREE';;
     SYSTEM4) labels='NEXT PAGE|SET LTE BAND CONFIG|PAGE FOUR';;
     LTE) labels='SET DEFAULT LTE BAND';;
     PROTOCOL) labels='NR5G|GCF';;
     NR5G) labels='PRUNE STATUS|SUB6/MMWAVE|SA/NSA MODE CONTROL';;
     PRUNE) if [ "$variant" = already ];then labels='RESTORE NR5G PRUNE'; else labels='REMOVE NR5G PRUNE'; fi;;
     GCF) if [ "$variant" = missing ];then labels='';else labels='REMOVE ONLY NR WHITELIST';fi;;
     SUB) labels='ALL ENABLE';;
     SA) labels='SA/NSA ENABLE|REMOVE SA PERMIT LIST';;
     AFTER) labels='COMPLETED';;
     *) exit 12;;
    esac
    oIFS="$IFS"; IFS='|'; set -- $labels; IFS="$oIFS"
    i=0; label=''
    for a do [ "$i" -eq "$idx" ] && label="$a"; i=$((i+1)); done
    [ -n "$label" ] || exit 13
    printf 'tap:%s\n' "$label" >> "$base/events"
    if [ "$label" = 'NEXT PAGE' ] && [ "$variant" = stuck ]; then exit 0; fi
    case "$screen/$label" in
     'MAIN/UE SETTING & INFO') new=UE;;
     'UE/SETTING') new=SETTING;;
     'SETTING/SYSTEM') new=SYSTEM0;;
     'SETTING/PROTOCOL') new=PROTOCOL;;
     'SYSTEM0/NEXT PAGE') new=SYSTEM1;;
     'SYSTEM1/NEXT PAGE') new=SYSTEM2;;
     'SYSTEM2/NEXT PAGE') new=SYSTEM3;;
     'SYSTEM3/NEXT PAGE') new=SYSTEM4;;
     'SYSTEM4/SET LTE BAND CONFIG') new=LTE;;
     'PROTOCOL/NR5G') new=NR5G;;
     'PROTOCOL/GCF') new=GCF;;
     'NR5G/PRUNE STATUS') new=PRUNE;;
     'NR5G/SUB6/MMWAVE') new=SUB;;
     'NR5G/SA/NSA MODE CONTROL') new=SA;;
     'LTE/SET DEFAULT LTE BAND'|'PRUNE/REMOVE NR5G PRUNE'|'GCF/REMOVE ONLY NR WHITELIST'|'SUB/ALL ENABLE'|'SA/SA/NSA ENABLE'|'SA/REMOVE SA PERMIT LIST')
       new=AFTER; printf '%s\n' "$label" >> "$base/settings";;
     *) exit 14;;
    esac
    [ "$variant" = focuslost ] && [ "$new" = UE ] && new=OTHER
    echo "$new" > "$base/screen";;
  esac;;
 uiautomator)
  [ "$variant" = dumpfail ] && exit 19
  case "$screen" in NONE|DIAL|OTHER) exit 15;; esac
  case "$screen" in
   MAIN) labels='UE SETTING & INFO';;
   UE) if [ "$variant" = ambiguous ]; then labels='SETTING|SETTING'; else labels='SETTING'; fi;;
   SETTING) labels='SYSTEM|PROTOCOL';;
   SYSTEM0) labels='NEXT PAGE|PAGE ZERO';;
   SYSTEM1) labels='NEXT PAGE|PAGE ONE';;
   SYSTEM2) labels='NEXT PAGE|PAGE TWO';;
   SYSTEM3) labels='NEXT PAGE|PAGE THREE';;
   SYSTEM4) labels='NEXT PAGE|SET LTE BAND CONFIG|PAGE FOUR';;
   LTE) labels='SET DEFAULT LTE BAND';;
   PROTOCOL) labels='NR5G|GCF';;
   NR5G) labels='PRUNE STATUS|SUB6/MMWAVE|SA/NSA MODE CONTROL';;
   PRUNE) if [ "$variant" = already ];then labels='RESTORE NR5G PRUNE';else labels='REMOVE NR5G PRUNE';fi;;
   GCF) if [ "$variant" = missing ];then labels='';else labels='REMOVE ONLY NR WHITELIST';fi;;
   SUB) labels='ALL ENABLE';;
   SA) labels='SA/NSA ENABLE|REMOVE SA PERMIT LIST';;
   AFTER) labels='COMPLETED';;
   *) exit 16;;
  esac
  dest=$2
  printf '<?xml version="1.0" encoding="UTF-8"?><hierarchy>' > "$dest"
  oIFS="$IFS"; IFS='|'; set -- $labels; IFS="$oIFS"
  i=0
  for label do
   j=$((i+1)); y=$((100+i*100)); y2=$((y+100))
   label=$(printf '%s' "$label" | sed 's/\&/\&amp;/g')
   printf '<node index="%d" text="[%d] %s" content-desc="" bounds="[20,%d][400,%d]" />' "$i" "$j" "$label" "$y" "$y2" >> "$dest"
   i=$((i+1))
  done
  printf '</hierarchy>' >> "$dest"
  echo UI hierarchy dumped;;
 sleep) :;;
 *) echo "unexpected mock command: $command" >&2;exit 50;;
esac
