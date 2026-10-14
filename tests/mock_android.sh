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
  case "$1" in
   start)
     if [ "${3:-}" = -n ]; then
       echo direct_attempt >> "$base/events"
       if [ "$variant" = direct_ok ]; then echo MAIN > "$base/screen"; echo direct_ok >> "$base/events"; exit 0; fi
       echo 'SecurityException: Permission Denial: not exported' >&2;exit 1
     fi
     echo DIAL > "$base/screen"; : > "$base/keys"; echo dialer_open >> "$base/events";;
   broadcast)
     echo broadcast_attempt >> "$base/events"
     if [ "$variant" = broadcast_ok ]; then echo MAIN > "$base/screen"; echo broadcast_ok >> "$base/events"; exit 0; fi
     echo 'SecurityException: Permission Denial' >&2;exit 1;;
   force-stop)
     echo NONE > "$base/screen"; echo force_stop >> "$base/events";;
   *) echo "unknown am: $1" >&2; exit 41;;
  esac;;
 input)
  case "$1" in
   text) printf '%s' "$2" >> "$base/keys";;
   keyevent)
    case "$2" in
     KEYCODE_STAR) printf '*' >> "$base/keys"; echo keyevent_star >> "$base/events";;
     KEYCODE_POUND)
       if [ "$variant" = dialer_last_hash_dropped ] && [ "$(cat "$base/keys")" = '*#27663368378' ]; then
         echo dropped_last_hash >> "$base/events"
       else
         printf '#' >> "$base/keys"
         echo keyevent_pound >> "$base/events"
       fi;;
    esac
    if [ "$(cat "$base/keys")" = '*#27663368378#' ]; then
     echo MAIN > "$base/screen"; echo code_ok >> "$base/events"
    fi;;
   tap)
    if [ "$screen" = DIAL ]; then
      x=$2; y=$3
      case "$y" in
       650) case "$x" in 100) key=1;;200) key=2;;300) key=3;; *) exit 40;;esac;;
       750) case "$x" in 100) key=4;;200) key=5;;300) key=6;; *) exit 40;;esac;;
       850) case "$x" in 100) key=7;;200) key=8;;300) key=9;; *) exit 40;;esac;;
       950) case "$x" in 100) key='*';;200) key=0;;300) key='#';; *) exit 40;;esac;;
       *) exit 40;;
      esac
      printf '%s' "$key" >> "$base/keys"
      echo "dial_tap:$key" >> "$base/events"
      if [ "$(cat "$base/keys")" = '*#27663368378#' ]; then
        echo MAIN > "$base/screen"; echo code_ok >> "$base/events"
      fi
      exit 0
    fi
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
     SUB) case "$variant" in selected) labels='ALL ENABLE (*)|SUB6 ONLY ENABLE|MMWAVE ONLY ENABLE|DISABLE';; uncertain) labels='ALL ENABLE ()|SUB6 ONLY ENABLE|MMWAVE ONLY ENABLE|DISABLE';; *) labels='ALL ENABLE|SUB6 ONLY ENABLE|MMWAVE ONLY ENABLE|DISABLE';; esac;;
     SA) case "$variant" in sa_selected) labels='SA/NSA ENABLE (*)|SA DISABLE|NSA DISABLE|REMOVE SA PERMIT LIST';; sa_uncertain) labels='SA/NSA ENABLE ()|SA DISABLE|NSA DISABLE|REMOVE SA PERMIT LIST';; *) labels='SA/NSA ENABLE|SA DISABLE|NSA DISABLE|REMOVE SA PERMIT LIST';; esac;;
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
   SUB) case "$variant" in selected) labels='ALL ENABLE (*)|SUB6 ONLY ENABLE|MMWAVE ONLY ENABLE|DISABLE';; uncertain) labels='ALL ENABLE ()|SUB6 ONLY ENABLE|MMWAVE ONLY ENABLE|DISABLE';; *) labels='ALL ENABLE|SUB6 ONLY ENABLE|MMWAVE ONLY ENABLE|DISABLE';; esac;;
   SA) case "$variant" in sa_selected) labels='SA/NSA ENABLE (*)|SA DISABLE|NSA DISABLE|REMOVE SA PERMIT LIST';; sa_uncertain) labels='SA/NSA ENABLE ()|SA DISABLE|NSA DISABLE|REMOVE SA PERMIT LIST';; *) labels='SA/NSA ENABLE|SA DISABLE|NSA DISABLE|REMOVE SA PERMIT LIST';; esac;;
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
