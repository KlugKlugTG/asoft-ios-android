#!/bin/bash
# test-release.sh OUTDIR: the signed release build end to end, with the bundle already in the import folder:
# first start opens Set up > Import > Start iOS 16 > lock screen > unlock > 💾 Save and close > open again >
# Start iOS 16 (quick boot). A release build is not debuggable (no run-as): everything is read from the screen
# (uiautomator, screenshots) and ps. Input goes through guarded.sh.
OUT=${1:?output directory}
. "$(dirname "$0")/guarded.sh"
TOP='com[.]asoft[.]ios/[.](MainActivity|SetupActivity|ScreenActivity)'

adb shell "am start -n com.asoft.ios/.MainActivity" >/dev/null
nap 4
shot r1-first-start
t0=$(date +%s)
tap_text "Import " || exit 1
until [ -n "$(find_text "Start iOS 16 (")" ]; do
    sleep 3
    [ $(($(date +%s) - t0)) -gt 600 ] && { echo "no import in 600 s"; exit 1; }
done
echo "$(date +%T) imported, $(($(date +%s) - t0)) s after Import"
shot r2-set-up
tap_text "Start iOS 16 (" || exit 1
t1=$(date +%s)
nap 60
TOP='com[.]asoft[.]ios/[.]ScreenActivity'
wait_screen r3-lock 600 && echo "$(date +%T) lock screen, $(($(date +%s) - t1)) s after Start"
send swipe 720 3080 720 1500 120
nap 8
shot r4-home
send tap 1393 1735                  # 💾
nap 3
shot r5-saves
TOP='com[.]asoft[.]ios/[.](MainActivity|ScreenActivity)'
tap_text "Save and close" || exit 1
t2=$(date +%s)
while qemu_up; do sleep 1; done
echo "$(date +%T) saved and closed, $(($(date +%s) - t2)) s"
nap 3
adb shell "am start -n com.asoft.ios/.MainActivity" >/dev/null
nap 4
TOP='com[.]asoft[.]ios/[.]MainActivity'
shot r6-home-screen
tap_text "Start iOS 16" || exit 1
t3=$(date +%s)
TOP='com[.]asoft[.]ios/[.]ScreenActivity'
nap 5
wait_screen r7-resumed 120 && echo "$(date +%T) back from the save, $(($(date +%s) - t3)) s after Start"
heat
echo "$(date +%T) done"
