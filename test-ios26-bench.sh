#!/bin/bash
# test-ios26-bench.sh OUTDIR: one measured cold boot of iOS 26 in the dev app, for A/B runs. Waits until the
# phone has cooled (hottest CPU zone below COOL °C, 55 by default), starts iOS 26 without a save (GuestService
# cold), waits for the lock screen, flicks it open 20 s later, taps Calculator 60 s after that, the side bar's
# Home 20 s later, then the app's off. What the run differs in goes in the app's ios26.txt beforehand.
# OUTDIR gets bench.txt, marks.txt (boot-time clock before each input), heat.txt (every 10 s: uptime,
# battery, hottest CPU zone, small/big core ceilings), screenshots and the run's logs; with ASOFT_LAT=1 in
# ios26.txt, bench-report.py turns reims' frame stamps into the unlock/Calculator/Home times.
OUT=${1:?output directory}
PKG=com.asoft.ios.dev
COOL=${COOL:-55}
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/guarded.sh"
SCREEN='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]ScreenActivity'
qpid() { adb shell "ps -A -o PID,ARGS | grep '[l]ibqemu26.so -name' | awk '{print \$1}'" | tr -d '\r' | head -n 1; }
log() { echo "$(date +%T) $*" | tee -a "$OUT/bench.txt"; }
up() { adb shell cat /proc/uptime | tr -d '\r' | awk '{print $1}'; }
mark() { echo "$1 $(up)" >> "$OUT/marks.txt"; }
hot() { adb shell "for z in /sys/class/thermal/thermal_zone*; do case \$(cat \$z/type) in cpu-*-*) cat \$z/temp;; esac; done | sort -n | tail -n 1" | tr -d '\r'; }
until_picture() {   # until_picture NAME MAXS: a screenshot with many colours (a lock or home screen)
    local t0=$(date +%s) c
    while :; do
        sleep 3
        shot "$1-cur"; c=$(colours "$1-cur")
        [ "$c" -gt 3000 ] && { cp "$OUT/$1-cur.png" "$OUT/$1.png"; return 0; }
        [ -z "$(qpid)" ] && { log "$1: QEMU gone"; return 1; }
        [ $(($(date +%s) - t0)) -gt "$2" ] && { log "$1: nothing in $2 s"; return 1; }
    done
}
stop_guest() {
    adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService -a off" >/dev/null
    local t=0
    while [ -n "$(qpid)" ] && [ $t -lt 60 ]; do sleep 1; t=$((t + 1)); done
    [ -z "$(qpid)" ] || log "QEMU still up 60 s after the app's off"
}
[ -z "$(qpid)" ] || { echo "a QEMU of the dev app runs already"; exit 1; }
adb shell "cat /sdcard/Android/data/$PKG/files/ios26.txt 2>/dev/null" | tr -d '\r' > "$OUT/ios26.txt"
log "ios26.txt: $(tr '\n' ' ' < "$OUT/ios26.txt")"
T=$(date +%s)
batt() { adb shell "dumpsys battery | grep -m1 '^  temperature'" | tr -d '\r' | awk '{print $2}'; }
# BATT=°C: also wait for the battery (it throttles the cores long after they cool)
while { [ "$(hot)" -gt $((COOL * 1000)) ] || [ "$(batt)" -gt $((${BATT:-99} * 10)) ]; } && [ $(($(date +%s) - T)) -lt 2400 ]; do sleep 20; done
log "cooled $(($(date +%s) - T)) s: hottest CPU $(hot), $(heat | tr '\n' ' ')"
adb shell 'while true; do echo "$(cut -d" " -f1 /proc/uptime) $(dumpsys battery | grep -m1 "^  temperature" | tr -s " " | cut -d" " -f3) $(for z in /sys/class/thermal/thermal_zone*; do case $(cat $z/type) in cpu-*-*) cat $z/temp;; esac; done | sort -n | tail -n 1) $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq) $(cat /sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq)"; sleep 10; done' > "$OUT/heat.txt" &
HEATPID=$!
mark start
adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService --ez cold true" >/dev/null
nap 2
adb shell "am start -n $PKG/com.asoft.ios.ScreenActivity" >/dev/null
T0=$(date +%s)
TOP=$SCREEN
probe() { py -3.12 "$(cygpath -w "$HERE/bench-shot.py")" "$(cygpath -w "$HERE/bench-home-ref.png")" "$(cygpath -w "$OUT")/$1.png"; }
until_shot() {      # until_shot NAME MAXS home|calc: screenshots until the home screen has every icon drawn, or Calculator is open
    local t0=$(date +%s) r
    while :; do
        shot "$1-cur"
        if [ -f "$OUT/$1-cur.png" ]; then
            r=$(probe "$1-cur")
            case $3 in
                home) awk -v r="$r" 'BEGIN { split(r, a, " "); exit !(a[1] < 6) }' ;;
                calc) awk -v r="$r" 'BEGIN { split(r, a, " "); exit !(a[2] > 0.4) }' ;;
            esac && { mv "$OUT/$1-cur.png" "$OUT/$1.png"; return 0; }
        fi
        [ $(($(date +%s) - t0)) -gt "$2" ] && { log "$1: not seen in $2 s (last $r)"; return 1; }
    done
}
if until_picture lock 900; then
    mark lock-seen
    TL=$(date +%s)
    log "lock screen $((TL - T0)) s after the start"
    nap 20
    mark unlock
    send swipe 720 3080 720 250 300
    TU=$(date +%s)
    until_shot home 300 home && { mark home-done; log "home screen with every icon $(($(date +%s) - TU)) s after the unlock flick, $(($(date +%s) - T0)) s after the start"; }
    nap 5
    mark calc
    send tap 1214 384
    TC=$(date +%s)
    until_shot calc 60 calc && { mark calc-open; log "Calculator open $(($(date +%s) - TC)) s after the tap"; }
    nap 3
    mark back
    send tap ${HOME_XY:-1346 1310}
    TB=$(date +%s)
    until_shot home2 60 home && { mark home2-done; log "home screen back $(($(date +%s) - TB)) s after the side bar's Home"; }
    mark end
fi
for f in qemu-stderr serial app; do
    adb shell "run-as $PKG sh -c 'cat files/run/$f.txt'" | tr -d '\r' > "$OUT/$f.txt"
done
stop_guest
kill $HEATPID 2>/dev/null
log "end: $(heat | tr '\n' ' ')"
