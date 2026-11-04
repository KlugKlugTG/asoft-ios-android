#!/bin/bash
# test-save-mem.sh OUTDIR: from iOS off: Start (boot), 💾 > Save and close while the phone's MemAvailable is
# sampled every second, then Start (quick boot). Prints the app's log. Guarded input.
OUT=${1:?output directory}
. "$(dirname "$0")/guarded.sh"
LOGD=/sdcard/Android/data/com.asoft.ios/files/log
applog() { adb shell "cat $LOGD/app.txt" 2>/dev/null | tr -d '\r'; }
mem() { adb shell "grep -E '^(MemAvailable|Cached|Dirty):' /proc/meminfo" | tr -d '\r' | awk '{printf "%s %d MB  ", $1, $2/1024}'; echo; }
adb shell "am start -n com.asoft.ios/.MainActivity" >/dev/null; nap 5
TOP='com[.]asoft[.]ios/[.]MainActivity'
tap_text "Start iOS 16" || exit 1
t0=$(date +%s)
TOP='com[.]asoft[.]ios/[.]ScreenActivity'
nap 30
wait_screen m1-boot 600 && echo "$(date +%T) lock screen $(($(date +%s) - t0)) s after Start"
nap 20
echo "before save: $(mem)"
send tap 1393 1735; nap 3
TOP='com[.]asoft[.]ios/[.](MainActivity|ScreenActivity)'
tap_text "Save and close" || exit 1
t1=$(date +%s)
min=999999
while qemu_up; do
    a=$(adb shell "grep MemAvailable /proc/meminfo" | tr -d '\r' | awk '{print int($2/1024)}')
    [ -n "$a" ] && [ "$a" -lt "$min" ] && min=$a
    sleep 1
done
echo "$(date +%T) QEMU gone $(($(date +%s) - t1)) s after Save and close; lowest MemAvailable during it: $min MB"
echo "after save: $(mem)"
applog | grep -a "save:\|lmk" | tail -n 1
nap 3
adb shell "am start -n com.asoft.ios/.MainActivity" >/dev/null; nap 5
TOP='com[.]asoft[.]ios/[.]MainActivity'
ui | grep -o 'text="\(Starts with\|Goes on\|First start\)[^"]*"' | head -n 1
tap_text "Start iOS 16" || exit 1
t2=$(date +%s)
TOP='com[.]asoft[.]ios/[.]ScreenActivity'
nap 4
wait_screen m2-after 300 && echo "$(date +%T) screen $(($(date +%s) - t2)) s after Start"
echo "--- app log"; applog | tail -n 6
adb logcat -d 2>/dev/null | tr -d '\r' | grep -a "lmkd.*com.asoft.ios" | tail -n 3 | cut -c1-200
echo "$(date +%T) done"
