#!/bin/bash
# test-quick.sh OUTDIR [cycles]: on a guest the app started (Safari already open is best), save its state
# the way the side bar's ⏏ does, start the app again and see it come back; then use it a little.
# Input goes through guarded.sh; timings come from the app's own log (files/run/app.txt).
OUT=${1:?output directory}
CYCLES=${2:-2}
. "$(dirname "$0")/guarded.sh"

applog() { adb shell "run-as com.asoft.ios cat files/run/app.txt" 2>/dev/null | tr -d '\r'; }
disk() { adb shell "run-as com.asoft.ios stat -c %s files/ios16-user.qcow2" | tr -d '\r'; }

for c in $(seq 1 "$CYCLES"); do
    shot "q$c-0-before"
    t0=$(date +%s)
    adb shell "am start-foreground-service -n com.asoft.ios/.GuestService -a save" >/dev/null
    until ! running; do sleep 1; done
    t1=$(date +%s)
    echo "$(date +%T) cycle $c: QEMU gone $((t1 - t0)) s after save; user disk $(disk) B"
    applog | grep -a "save:" | tail -n 1
    # the service, not the screen: a screen already on top only gets onNewIntent
    seen=$(applog | grep -acE "resumed in|did not load|start failed")
    adb shell "am start-foreground-service -n com.asoft.ios/.GuestService" >/dev/null
    until [ "$(applog | grep -acE "resumed in|did not load|start failed")" -gt "$seen" ]; do
        sleep 1
        [ $(($(date +%s) - t1)) -gt 900 ] && { echo "no resume in 900 s"; break; }
    done
    t2=$(date +%s)
    echo "$(date +%T) cycle $c: running again $((t2 - t1)) s after the start"
    applog | grep -aE "resumed in|did not load|start failed" | tail -n 1
    nap 3
    shot "q$c-1-resumed"
    send tap 1392 1472                  # side bar Home
    nap 6
    shot "q$c-2-home"
    guest_tap 74 285                    # Settings
    nap 15
    shot "q$c-3-settings"
    send tap 1392 1472
    nap 6
    guest_tap 270 1124                  # Safari: the page from before the save
    nap 10
done
crashes | cut -c1-80
heat
save_logs
adb shell "run-as com.asoft.ios cat files/run/app.txt" > "$OUT/app.txt"
echo "$(date +%T) done"
