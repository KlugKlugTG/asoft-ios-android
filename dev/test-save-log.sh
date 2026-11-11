#!/bin/bash
# test-save-log.sh OUTDIR: from iOS off: Start (boot), 💾 > Save and close, Start again; prints the app's
# own log (Android/data/com.asoft.ios/files/log, readable on a release build). Guarded input.
OUT=${1:?output directory}
. "$(dirname "$0")/guarded.sh"
LOGD=/sdcard/Android/data/com.asoft.ios/files/log
applog() { adb shell "cat $LOGD/app.txt" 2>/dev/null | tr -d '\r'; }
adb shell "am start -n com.asoft.ios/.MainActivity" >/dev/null; nap 5
TOP='com[.]asoft[.]ios/[.]MainActivity'
tap_text "Start iOS 16" || exit 1
t0=$(date +%s)
TOP='com[.]asoft[.]ios/[.]ScreenActivity'
nap 30
wait_screen l1-boot 600 && echo "$(date +%T) lock screen $(($(date +%s) - t0)) s after Start"
nap 20
send tap 1393 1735; nap 3
TOP='com[.]asoft[.]ios/[.](MainActivity|ScreenActivity)'
tap_text "Save and close" || exit 1
t1=$(date +%s); while qemu_up; do sleep 1; done
echo "$(date +%T) saved and closed in $(($(date +%s) - t1)) s"
nap 3
adb shell "am start -n com.asoft.ios/.MainActivity" >/dev/null; nap 5
TOP='com[.]asoft[.]ios/[.]MainActivity'
ui | grep -o 'text="\(Starts with\|Goes on\|First start\)[^"]*"' | head -n 1
tap_text "Start iOS 16" || exit 1
t2=$(date +%s)
TOP='com[.]asoft[.]ios/[.]ScreenActivity'
nap 4
wait_screen l2-after 300 && echo "$(date +%T) screen $(($(date +%s) - t2)) s after Start"
echo "--- app log"; applog | tail -n 12
echo "$(date +%T) done"
