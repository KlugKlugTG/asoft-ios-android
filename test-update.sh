#!/bin/bash
# test-update.sh OUTDIR APK: with iOS running in the app, 💾 > Save and close, install APK over the app
# (an update: same key), see the home screen still knows the save, Start (quick boot), then
# 💾 > Turn off iOS without saving, and Start again (a boot). Screen only (no run-as). Guarded input.
OUT=${1:?output directory}
APK=${2:?apk}
. "$(dirname "$0")/guarded.sh"
TOP='com[.]asoft[.]ios/[.]ScreenActivity'

if qemu_up; then                    # iOS open: save it first, as a user would before updating
    send tap 1393 1735              # 💾
    nap 3
    TOP='com[.]asoft[.]ios/[.](MainActivity|ScreenActivity)'
    tap_text "Save and close" || exit 1
    t0=$(date +%s); while qemu_up; do sleep 1; done
    echo "$(date +%T) saved and closed in $(($(date +%s) - t0)) s"
fi
adb install -r "$APK" 2>&1 | tail -n 1
adb shell "dumpsys package com.asoft.ios | grep -m1 versionName" | tr -d '\r'
sleep 8
adb shell "am start -n com.asoft.ios/.MainActivity" >/dev/null
nap 5
TOP='com[.]asoft[.]ios/[.]MainActivity'
shot u1-home-after-update
ui | grep -o 'text="Goes on from[^"]*"' | head -n 1
tap_text "Start iOS 16" || exit 1
t1=$(date +%s)
TOP='com[.]asoft[.]ios/[.]ScreenActivity'
nap 4
wait_screen u2-resumed 120 && echo "$(date +%T) back from the save $(($(date +%s) - t1)) s after Start"
send tap 1393 1735                  # 💾
nap 3
shot u3-saves
tap_text "Turn off iOS without saving" || exit 1
nap 2
shot u4-confirm
tap_text "Turn off" || exit 1
t2=$(date +%s)
nap 3
shot u5-turning-off
while qemu_up; do sleep 1; [ $(($(date +%s) - t2)) -gt 120 ] && { echo "QEMU still up after 120 s"; break; }; done
echo "$(date +%T) QEMU gone $(($(date +%s) - t2)) s after Turn off"
nap 3
adb shell "am start -n com.asoft.ios/.MainActivity" >/dev/null
nap 5
TOP='com[.]asoft[.]ios/[.]MainActivity'
shot u6-home-after-off
ui | grep -o 'text="\(Starts with\|Goes on\|First start\)[^"]*"' | head -n 1
tap_text "Start iOS 16" || exit 1
t3=$(date +%s)
TOP='com[.]asoft[.]ios/[.]ScreenActivity'
nap 30
wait_screen u7-boot 600 && echo "$(date +%T) lock screen $(($(date +%s) - t3)) s after Start (a boot)"
heat
echo "$(date +%T) done"
