#!/bin/bash
# test-newphone.sh OUTDIR [max_min]: a phone with the app installed and the bundle in its import folder:
# Set up > Import > Start iOS 16, then a screenshot a minute (while the app's screen is on top) until
# max_min minutes, with memory, the app's log and lmkd kills. Any phone: buttons found by their text.
# Set ANDROID_SERIAL. Guarded input.
OUT=${1:?output directory}
MAX=${2:-45}
. "$(dirname "$0")/guarded.sh"
LOGD=/sdcard/Android/data/com.asoft.ios/files/log
applog() { adb shell "cat $LOGD/app.txt" 2>/dev/null | tr -d '\r'; }
TOP='com[.]asoft[.]ios/[.](MainActivity|SetupActivity)'
adb shell "am start -n com.asoft.ios/.MainActivity" >/dev/null; nap 5
[ -n "$(find_text "Import ")" ] || { tap_text "Set up" || tap_text "More"; nap 2; tap_text "Set up iOS 16" ; nap 3; }
shot n0-setup
t0=$(date +%s)
tap_text "Import " || exit 1
until [ -n "$(find_text "Start iOS 16 (")" ]; do
    sleep 3
    [ -n "$(find_text "Not set up")" ] && { ui | grep -o 'text="Not set up[^"]*"'; exit 1; }
    [ $(($(date +%s) - t0)) -gt 1200 ] && { echo "no import in 1200 s"; exit 1; }
done
echo "$(date +%T) imported in $(($(date +%s) - t0)) s"
tap_text "Start iOS 16 (" || exit 1
t1=$(date +%s)
TOP='com[.]asoft[.]ios/[.]ScreenActivity'
for m in $(seq 1 "$MAX"); do
    sleep 60
    shot "n$(printf %02d $m)"
    a=$(adb shell "grep MemAvailable /proc/meminfo" | tr -d '\r' | awk '{print int($2/1024)}')
    q=$(adb shell "ps -A -o rss,args | grep '[l]ibqemu.so -M' | head -n 1" | tr -d '\r' | awk '{print int($1/1024)}')
    echo "$(date +%T) +${m} min: qemu rss ${q:-gone} MB, MemAvailable $a MB"
    qemu_up || { echo "QEMU gone"; break; }
done
echo "--- app log"; applog | tail -n 8
adb logcat -d 2>/dev/null | tr -d '\r' | grep -a "lmkd.*asoft\|has died.*asoft" | tail -n 3 | cut -c1-200
heat
echo "$(date +%T) done"
