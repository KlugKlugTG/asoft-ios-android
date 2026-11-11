#!/bin/bash
# test-turnoff.sh OUTDIR: with iOS running and booted: 💾 > Save and close, Start (quick boot), then
# 💾 > Turn off iOS without saving; the home screen must then say a boot. Screen only. Guarded input.
OUT=${1:?output directory}
. "$(dirname "$0")/guarded.sh"
TOP='com[.]asoft[.]ios/[.]ScreenActivity'
qemu_up || { echo "iOS is not running"; exit 1; }
send tap 1393 1735; nap 3
TOP='com[.]asoft[.]ios/[.](MainActivity|ScreenActivity)'
tap_text "Save and close" || exit 1
t0=$(date +%s); while qemu_up; do sleep 1; done
echo "$(date +%T) saved and closed in $(($(date +%s) - t0)) s"
nap 3
adb shell "am start -n com.asoft.ios/.MainActivity" >/dev/null; nap 5
TOP='com[.]asoft[.]ios/[.]MainActivity'
ui | grep -o 'text="\(Starts with\|Goes on\|First start\)[^"]*"' | head -n 1
tap_text "Start iOS 16" || exit 1
t1=$(date +%s)
TOP='com[.]asoft[.]ios/[.]ScreenActivity'
nap 4
wait_screen t1-resumed 120 && echo "$(date +%T) back from the save $(($(date +%s) - t1)) s after Start"
nap 10
send tap 1393 1735; nap 3
tap_text "Turn off iOS without saving" || exit 1
nap 2
tap_text "Turn off" || exit 1
t2=$(date +%s); nap 2; shot t2-turning-off
while qemu_up; do sleep 1; [ $(($(date +%s) - t2)) -gt 120 ] && { echo "QEMU still up after 120 s"; break; }; done
echo "$(date +%T) QEMU gone $(($(date +%s) - t2)) s after Turn off"
nap 3
adb shell "am start -n com.asoft.ios/.MainActivity" >/dev/null; nap 5
TOP='com[.]asoft[.]ios/[.]MainActivity'
shot t3-home
ui | grep -o 'text="\(Starts with\|Goes on\|First start\)[^"]*"' | head -n 1
echo "$(date +%T) done"
