#!/bin/bash
# test-save-cycle.sh OUTDIR [cycles]: from iOS off, with a quick-boot save: Start (load), use it 30 s,
# 💾 > Save and close while MemAvailable is sampled, repeat. Times come from the app's own log
# (Android/data/com.asoft.ios/files/log, readable on a release build). Guarded input.
OUT=${1:?output directory}
CYCLES=${2:-2}
. "$(dirname "$0")/guarded.sh"
LOGD=/sdcard/Android/data/com.asoft.ios/files/log
applog() { adb shell "cat $LOGD/app.txt" 2>/dev/null | tr -d '\r'; }
for c in $(seq 1 "$CYCLES"); do
    adb shell "am start -n com.asoft.ios/.MainActivity" >/dev/null; nap 5
    TOP='com[.]asoft[.]ios/[.]MainActivity'
    ui | grep -o 'text="\(Starts with\|Goes on\|First start\)[^"]*"' | head -n 1
    n=$(applog | grep -acE "resumed in|did not load|start:")
    tap_text "Start iOS 16" || exit 1
    until [ "$(applog | grep -acE "resumed in|did not load")" -gt 0 ] && [ "$(applog | grep -acE "resumed in|did not load|start:")" -ge $((n + 2)) ]; do sleep 1; done
    echo "$(date +%T) cycle $c: $(applog | grep -aE "resumed in|did not load" | tail -n 1)"
    TOP='com[.]asoft[.]ios/[.]ScreenActivity'
    nap 30
    shot "c$c-running"
    send tap 1393 1735; nap 3
    TOP='com[.]asoft[.]ios/[.](MainActivity|ScreenActivity)'
    tap_text "Save and close" || exit 1
    min=999999
    while qemu_up; do
        a=$(adb shell "grep MemAvailable /proc/meminfo" | tr -d '\r' | awk '{print int($2/1024)}')
        [ -n "$a" ] && [ "$a" -lt "$min" ] && min=$a
        sleep 1
    done
    echo "$(date +%T) cycle $c: $(applog | grep -a "save:" | tail -n 1); lowest MemAvailable $min MB"
    nap 3
done
adb logcat -d 2>/dev/null | tr -d '\r' | grep -a "lmkd.*com.asoft.ios" | tail -n 3 | cut -c1-200
heat
echo "$(date +%T) done"
