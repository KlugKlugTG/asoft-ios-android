#!/bin/bash
# test-safari.sh OUTDIR [home [row]]: on a booted guest in the app, unlock, open Safari, type a URL
# on iOS's own keyboard, load it. All input goes through guarded.sh.
OUT=${1:?output directory}
. "$(dirname "$0")/guarded.sh"

wait_boot ${BOOT_WAIT:-270}
if [ "${2:-}" != home ]; then       # test-safari.sh OUTDIR home: the guest already shows its home screen
    shot 0-lock
    send swipe 720 3080 720 1500 120
    nap 8
    shot 1-home
fi
send tap 720 2920
nap 12
shot 2-safari
send tap 720 2740
nap 15
shot 3-keyboard
guest_tap 270 1142              # Continue on iOS's first-use pane; no key sits there if it does not come
nap 10
shot 3-keyboard2
type_ios example.com
nap 5
shot 3-typed
if [ "${3:-}" = row ]; then
    send tap 423 593            # the suggestion row under the typed text, instead of go
else
    guest_tap 495 1058          # go
fi
nap 30
shot 4-page
nap 30
shot 5-page
crashes
adb shell "run-as com.asoft.ios sh -c 'grep -aE \"exec com.apple.WebKit\" files/run/serial.txt | sort | uniq -c'" | tr -d '\r'
adb shell "ps -A -o args | grep -c '[l]ibqemu.so -M'"
heat
save_logs
echo "$(date +%T) done"
