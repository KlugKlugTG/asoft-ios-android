#!/bin/bash
# test-resync.sh OUTDIR [away_s] [cycles]: on a booted guest in the app, save it as the side bar does, stay away
# away_s seconds, load it again and see the clock catch up (xnu 0103 resyncs from the PL031) without a panic.
# Input goes through guarded.sh.
OUT=${1:?output directory}
AWAY=${2:-90}
CYCLES=${3:-2}
. "$(dirname "$0")/guarded.sh"

applog() { adb shell "run-as com.asoft.ios cat files/run/app.txt" 2>/dev/null | tr -d '\r'; }
serial() { adb shell "run-as com.asoft.ios cat files/run/serial.txt" 2>/dev/null | tr -d '\r'; }

wait_boot "${BOOT_WAIT:-270}"
shot 0-lock
send swipe 720 3080 720 1500 120        # unlock
nap 8
shot 1-home
for c in $(seq 1 "$CYCLES"); do
    guest_tap 74 285                    # Settings: something running when it is saved
    nap 12
    shot "c$c-0-before"
    n=$(applog | grep -ac "save:")
    adb shell "am start-foreground-service -n com.asoft.ios/.GuestService -a save" >/dev/null
    until ! running; do sleep 1; done
    until [ "$(applog | grep -ac "save:")" -gt "$n" ]; do sleep 1; done
    echo "$(date +%T) cycle $c: $(applog | grep -a "save:" | tail -n 1)"
    sleep "$AWAY"
    seen=$(applog | grep -acE "resumed in|did not load|start failed")
    adb shell "am start-foreground-service -n com.asoft.ios/.GuestService" >/dev/null
    until [ "$(applog | grep -acE "resumed in|did not load|start failed")" -gt "$seen" ]; do sleep 1; done
    echo "$(date +%T) cycle $c: $(applog | grep -aE "resumed in|did not load|start failed" | tail -n 1)"
    shot "c$c-1-resumed"
    nap 12
    shot "c$c-2-after12s"
    serial | grep -aE "SWEET RTC: calendar|panic\(" | head -n 4
    send tap 1393 1380                  # side bar Home
    nap 8
    shot "c$c-3-home"
done
crashes | cut -c1-80
heat
save_logs
adb shell "run-as com.asoft.ios cat files/run/app.txt" > "$OUT/app.txt"
echo "$(date +%T) done"
