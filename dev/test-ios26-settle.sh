#!/bin/bash
# test-ios26-settle.sh OUTDIR [IDLE_MIN=60]: is iOS 26 slow only while it settles after a boot (PROMPT-APP 2026-11-05
# §4a)? A boot of the user's disk in the dev app, the same input set at ~5 min and again after IDLE_MIN minutes left
# alone (the display sleeps), each three times: the unlock flick, Calculator open, the side bar's Home, volume up
# (iOS's HUD), a page swipe on the home screen. ios26.txt turns on reims' frame stamps (ASOFT_LAT) and the BQL
# report; settle-report.py turns marks.txt + the stamps into first-reaction and settle times. Beside it once a
# second the QEMU's kernel mappings (maps-sample.sh: reims' views against vm.max_map_count) and every 10 s the heat.
# Ends with a Save and close: a settled quick save for daily use.
OUT=${1:?output directory}
IDLE=${2:-60}
PKG=com.asoft.ios.dev
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/guarded.sh"
SCREEN='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]ScreenActivity'
EXT=/sdcard/Android/data/$PKG/files
qpid() { adb shell "ps -A -o PID,ARGS | grep '[l]ibqemu26.so -name' | awk '{print \$1}'" | tr -d '\r' | head -n 1; }
log() { echo "$(date +%T) $*" | tee -a "$OUT/settle.txt"; }
rs() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }
up() { adb shell cat /proc/uptime | tr -d '\r' | awk '{print $1}'; }
mark() { echo "$1 $(up)" >> "$OUT/marks.txt"; }
until_picture() {
    local t0=$(date +%s) c
    while :; do
        sleep 3
        shot "$1-cur"; c=$(colours "$1-cur")
        [ "$c" -gt 3000 ] && { cp "$OUT/$1-cur.png" "$OUT/$1.png"; return 0; }
        [ -z "$(qpid)" ] && { log "$1: QEMU gone"; return 1; }
        [ $(($(date +%s) - t0)) -gt "$2" ] && { log "$1: nothing in $2 s"; return 1; }
    done
}
press() { WAIT=1 bash "$HERE/qmp26.sh" "{\"execute\":\"qom-set\",\"arguments\":{\"path\":\"/machine/peripheral/ucons\",\"property\":\"press\",\"value\":\"$1\"}}" >/dev/null; }
el() { rs 'grep -a "el-sample t=[0-9]*s all" files/run/qemu-stderr.txt | tail -n 1' | sed 's/ | native.*//'; }
round() {          # round NAME: one pass of the input set, each input marked just before it
    TOP=$SCREEN
    # from the lock screen every time: the power button locks a lit display, Home lights it again
    local led
    led=$(rs 'od -An -tu1 -N1 files/run/leds' | tr -d ' ')
    [ $(( ${led:-0} & 1 )) -eq 0 ] && { press power; nap 4; }
    press home; nap 5
    mark "$1-unlock"; send swipe 720 3080 720 250 300; nap 12
    mark "$1-calc"; send tap 1214 384; nap 10
    mark "$1-home"; send tap ${HOME_XY:-1346 1310}; nap 8
    mark "$1-vol"; press volup; nap 6
    mark "$1-swipe"; send swipe 1200 1600 200 1600 300; nap 8
    mark "$1-swipeback"; send swipe 200 1600 1200 1600 300; nap 8
    shot "$1-end"
    log "$1: $(el)"
}

[ -z "$(qpid)" ] || { echo "a QEMU of the dev app runs already"; exit 1; }
# a cooler start (battery below BATT °C, at most 15 min): the first rounds then compare better with the later ones
batt() { adb shell "dumpsys battery | grep -m1 '^  temperature'" | tr -d '\r' | awk '{print $2}'; }
T=$(date +%s)
while [ "$(batt)" -gt $((${BATT:-37} * 10)) ] && [ $(($(date +%s) - T)) -lt 900 ]; do sleep 20; done
log "cooled $(($(date +%s) - T)) s"
printf '%s\n' "env.ASOFT_LAT=1" "env.ASOFT_BQLPROF=1" > "$OUT/ios26.in"
adb push "$(cygpath -w "$OUT/ios26.in")" "$EXT/ios26.txt" >/dev/null
log "start: $(heat | tr '\n' ' '), free $(adb shell df -h /data | tail -n 1 | awk '{print $4}')"
adb shell 'while true; do echo "$(cut -d" " -f1 /proc/uptime) $(dumpsys battery | grep -m1 "^  temperature" | tr -s " " | cut -d" " -f3) $(for z in /sys/class/thermal/thermal_zone*; do case $(cat $z/type) in cpu-*-*) cat $z/temp;; esac; done | sort -n | tail -n 1) $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq) $(cat /sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq)"; sleep 10; done' > "$OUT/heat.txt" &
HEATPID=$!
mark start
adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService --ez cold true" >/dev/null
nap 2
adb shell "am start -n $PKG/com.asoft.ios.ScreenActivity" >/dev/null
T0=$(date +%s)
TOP=$SCREEN
sleep 15
bash "$HERE/maps-sample.sh" "$OUT/maps.txt" $(( (IDLE + 30) * 60 )) 1 &
MAPSPID=$!
until_picture lock 900 || { kill $HEATPID $MAPSPID 2>/dev/null; exit 1; }
mark lock-seen
log "lock screen $(($(date +%s) - T0)) s after the start"
# ~5 min after the start: three rounds
while [ $(($(date +%s) - T0)) -lt 300 ]; do sleep 5; done
for r in a1 a2 a3; do round "$r"; done
log "rounds a done $(($(date +%s) - T0)) s after the start; maps $(tail -n 1 "$OUT/maps.txt")"
# left alone (the display sleeps by itself)
TA=$(date +%s)
while [ $(($(date +%s) - TA)) -lt $((IDLE * 60)) ]; do
    sleep 300
    log "idle $(( ($(date +%s) - TA) / 60 )) min: $(el); maps $(tail -n 1 "$OUT/maps.txt"); $(heat | tr '\n' ' ')"
    [ -z "$(qpid)" ] && { log "QEMU gone while idle"; break; }
done
for r in b1 b2 b3; do round "$r"; done
log "rounds b done $(($(date +%s) - T0)) s after the start; maps $(tail -n 1 "$OUT/maps.txt")"
mark end
for f in qemu-stderr serial app; do rs "cat files/run/$f.txt" > "$OUT/$f.txt"; done
adb shell "rm -f $EXT/ios26.txt"
adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService -a save" >/dev/null
t=$(date +%s); while [ -n "$(qpid)" ] && [ $(($(date +%s) - t)) -lt 300 ]; do sleep 1; done
log "saved and closed $(($(date +%s) - t)) s"
kill $HEATPID $MAPSPID 2>/dev/null
log "end: $(heat | tr '\n' ' ')"
