#!/bin/bash
# test-ios26.sh OUTDIR [MAXS=900]: iOS 26 from the app's start screen with one tap (Start iOS 26), on the
# dev package (com.asoft.ios.dev, gradlew assembleDebug -PasoftDev). Every 10 s: QEMU alive, screenshot size
# (colours: the boot is black and white text, the lock screen a wallpaper), frames reims read back, heat. LOCK when a
# screenshot is a picture. Then the unlock flick through the app's touch path, Home, a screenshot each.
OUT=${1:?output directory}; MAXS=${2:-900}
PKG=com.asoft.ios.dev
. "$(dirname "$0")/guarded.sh"
TOP='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.](MainActivity|ScreenActivity)'
qpid() { adb shell "ps -A -o PID,ARGS | grep '[l]ibqemu26.so -name' | awk '{print \$1}'" | tr -d '\r' | head -n 1; }
heat_line() {
    adb shell "dumpsys battery | grep -E '^  temperature' | awk '{print \$2/10}'; for z in /sys/class/thermal/thermal_zone*; do case \$(cat \$z/type) in cpu-*-*) cat \$z/temp;; esac; done | sort -n | tail -n 1 | awk '{print \$1/1000}'; cat /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq 2>/dev/null" | tr -d '\r' | tr '\n' ' '
}
log() { echo "$(date +%T) t=$(( $(date +%s) - T0 )) $*" | tee -a "$OUT/run.txt"; }
run_as() { adb shell "run-as $PKG sh -c '$1'" 2>/dev/null | tr -d '\r'; }

if [ -z "$(qpid)" ]; then
    adb shell "am start -n $PKG/com.asoft.ios.MainActivity" >/dev/null
    nap 3
    shot b0-start-screen
    T0=$(date +%s)
    tap_text "Start iOS 26" || { echo "no Start iOS 26 button"; exit 1; }
else
    echo "iOS 26 runs already (pid $(qpid))"
    T0=$(date +%s)
fi
TOP='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]ScreenActivity'
log "tapped Start iOS 26; heat (batt, hottest cpu, max freq little/big) $(heat_line)"
lock=0; n=0
while :; do
    sleep 10
    now=$(( $(date +%s) - T0 ))
    p=$(qpid)
    if [ -z "$p" ]; then log "QEMU_GONE"; break; fi
    n=$((n + 1))
    shot cur
    sz=$(stat -c %s "$OUT/cur.png" 2>/dev/null || echo 0)
    fr=$(run_as "grep -c \" shown \" files/run/qemu-stderr.txt")
    col=$(colours cur 2>/dev/null || echo 0)
    log "pid $p png $sz colours $col frames-shown-lines $fr heat $(heat_line)"
    [ $((n % 6)) = 0 ] && cp "$OUT/cur.png" "$OUT/b-$now.png"
    if [ "${col:-0}" -gt 300 ]; then
        cp "$OUT/cur.png" "$OUT/b-lock-$now.png"; log "LOCK png $sz"; lock=1; break
    fi
    [ $now -ge $MAXS ] && { log "TIMEOUT"; break; }
done
if [ $lock = 1 ]; then
    nap 20
    shot c1-before-unlock
    # the flick of IOS26-INPUT §2.6 (0.5,0.997 -> 0.5,0.08 of the guest screen), through ScreenActivity's touch
    send swipe 720 3080 720 250 300
    log "unlock flick sent"
    for i in 1 2 3 4 5 6; do nap 10; shot "c2-after-unlock-$i"; done
    log "after unlock: png $(stat -c %s "$OUT/c2-after-unlock-6.png" 2>/dev/null)"
fi
run_as "cat files/run/qemu-stderr.txt" > "$OUT/qemu-stderr.txt"
run_as "cat files/run/serial.txt" > "$OUT/serial.txt"
run_as "cat files/run/cmdline.txt" > "$OUT/cmdline.txt"
run_as "cat files/run/app.txt" > "$OUT/app.txt"
log "END crash=$(grep -c 'CRASH .* sig' "$OUT/serial.txt") panic=$(grep -c 'panic(' "$OUT/serial.txt")"
