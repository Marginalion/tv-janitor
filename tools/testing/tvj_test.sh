#!/usr/bin/env bash
# TV Janitor test kit for macOS and Linux. Results go to tests/<name>/. Only options 2, 6 and 7 change the TV.
# Needs adb (Android platform-tools) on PATH or next to this file, and the TV Janitor APKs from the release.
set -u
cd "$(dirname "$0")"
ADB=adb; [ -x ./adb ] && ADB=./adb
command -v "$ADB" >/dev/null 2>&1 || {
  echo " adb not found. Install Android platform-tools: https://developer.android.com/tools/releases/platform-tools"
  echo " (macOS: brew install android-platform-tools; Debian/Ubuntu: sudo apt install adb)"; exit 1; }
FULL=$(ls TVJanitor-*-full.apk 2>/dev/null | tail -n 1); LITE=$(ls TVJanitor-*-lite.apk 2>/dev/null | tail -n 1)
PKG=io.github.marginalion.tvjanitor

read -r -p "Short TV name (for example xiaomi, novex, philips): " NAME
NAME=$(echo "$NAME" | xargs | tr ' ' '_'); DIR="tests/$NAME"; mkdir -p "$DIR"
TV_IP=$(cat "$DIR/ip.txt" 2>/dev/null || true)
[ -z "$TV_IP" ] && read -r -p "TV IP address: " TV_IP
echo "$TV_IP" > "$DIR/ip.txt"; DEV="$TV_IP:5555"

connect() {
  $ADB connect "$TV_IP" >/dev/null 2>&1
  for i in $(seq 1 15); do
    [ "$($ADB -s "$DEV" get-state 2>/dev/null)" = device ] && return 0
    sleep 2
    [ "$i" = 6 ] && { $ADB disconnect "$TV_IP" >/dev/null 2>&1; $ADB connect "$TV_IP" >/dev/null 2>&1; }
    [ "$i" = 11 ] && { $ADB kill-server >/dev/null 2>&1; $ADB connect "$TV_IP" >/dev/null 2>&1; }
  done
  echo " The TV does not answer at $TV_IP. Is it on, is USB/network debugging on, is the IP right?"
  echo " If the TV shows \"Allow USB debugging?\", tick \"Always allow\" and press OK."; return 1
}
sh_() { $ADB -s "$DEV" shell "$@"; }

snap() {
  connect || return; S="$DIR/$1"
  {
    echo "=== when"; date; sh_ uptime
    echo "=== device"; for p in ro.product.manufacturer ro.product.model ro.product.device ro.build.version.release ro.build.version.sdk ro.build.fingerprint ro.adb.secure; do sh_ getprop $p; done
    echo "=== home and screen"
    sh_ cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN
    sh_ "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'"
    echo "=== memory"; sh_ "dumpsys -t 40 meminfo | grep -E 'Total RAM|Used RAM|Free RAM'"
    sh_ "dumpsys -t 40 meminfo | head -n 30 | tail -n 26"
    echo "=== settings"; for k in window_animation_scale activity_manager_constants private_dns_mode; do sh_ settings get global $k; done
    echo "=== TV Janitor"; sh_ "dumpsys package $PKG | grep versionName"
    echo "=== crashes since boot"; sh_ "logcat -d -b crash | tail -n 20"
  } > "$S.txt" 2>&1
  sh_ pm list packages -d > "${S}_disabled.txt"; sh_ pm list packages -e > "${S}_enabled.txt"
  echo " Saved $S.txt"
}

install_() {
  [ -z "$1" ] && { echo " No TVJanitor-*-$2.apk next to this file."; return; }
  connect || return; $ADB -s "$DEV" install -r "$1"; sh_ am start -n $PKG/.MainActivity
  echo ' On the TV: "Allow USB debugging?" - tick "Always allow", OK.'
}

while true; do
  echo; echo " TV: $NAME ($TV_IP)"
  echo "  1  Snapshot BEFORE (do this first, on the untouched TV)"
  echo "  2  Install / update TV Janitor FULL and open it on the TV"
  echo "  7  Install / update TV Janitor LITE instead (one button)"
  echo "  3  Snapshot AFTER (2-3 minutes after a reboot)"
  echo "  4  Copy the app log, report and a screenshot of the TV"
  echo "  5  Compare: what is disabled now vs BEFORE (checks \"Restore everything\")"
  echo "  6  Reset TV Janitor data (start again from the first screen)"
  echo "  8  Pack the results into tests/$NAME.zip to attach to a GitHub issue"
  echo "  0  Exit"
  read -r -p "Choose: " C
  case "$C" in
    1) snap before ;;
    2) install_ "$FULL" full ;;
    7) install_ "$LITE" lite ;;
    3) snap after ;;
    4) connect && { sh_ cat /sdcard/Android/data/$PKG/files/log.txt > "$DIR/app_log.txt"
         sh_ cat /sdcard/Android/data/$PKG/files/report.txt > "$DIR/report.txt" 2>/dev/null
         sh_ screencap -p /data/local/tmp/tvj.png; $ADB -s "$DEV" pull /data/local/tmp/tvj.png "$DIR/screen_$RANDOM.png" >/dev/null
         sh_ rm /data/local/tmp/tvj.png; echo " Copied to $DIR"; } ;;
    5) connect && { sh_ pm list packages -d > "$DIR/disabled_now.txt"
         echo "--- only BEFORE (<) / only NOW (>):"; diff "$DIR/before_disabled.txt" "$DIR/disabled_now.txt" | tee "$DIR/compare.txt"; } ;;
    6) connect && { sh_ pm clear $PKG; sh_ am start -n $PKG/.MainActivity; echo " Data cleared."; } ;;
    8) (cd tests && zip -qr "$NAME.zip" "$NAME") && echo " Packed: tests/$NAME.zip (attach it to your issue on GitHub)" ;;
    0) exit 0 ;;
  esac
done
