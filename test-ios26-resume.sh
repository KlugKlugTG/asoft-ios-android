#!/bin/bash
# test-ios26-resume.sh OUTDIR [MIN=6]: iOS 26 back from a save made after it settled (test-ios26-settle.sh ends with
# one): is the guest idle at once, or does the jump in time since the save start background work again? The quick
# boot, then the input round of test-ios26-settle.sh at ~1 min and again at MIN minutes, the EL shares and, with
# ASOFT_WHO=1, which address spaces, kernel PCs and syscalls keep the vCPUs busy. Ends with a Save and close.
OUT=${1:?output directory}
MIN=${2:-6}
PKG=com.asoft.ios.dev
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/guarded.sh"
SCREEN='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]ScreenActivity'
EXT=/sdcard/Android/data/$PKG/files
qpid() { adb shell "ps -A -o PID,ARGS | grep '[l]ibqemu26.so -name' | awk '{print \$1}'" | tr -d '\r' | head -n 1; }
log() { echo "$(date +%T) $*" | tee -a "$OUT/resume.txt"; }
rs() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }
up() { adb shell cat /proc/uptime | tr -d '\r' | awk '{print $1}'; }
mark() { echo "$1 $(up)" >> "$OUT/marks.txt"; }
until_picture() {
    local t0=$(date +%s) c
    while :; do
        sleep 2
        shot "$1-cur"; c=$(colours "$1-cur")
        [ "$c" -gt 300 ] && { cp "$OUT/$1-cur.png" "$OUT/$1.png"; return 0; }
        [ -z "$(qpid)" ] && { log "$1: QEMU gone"; return 1; }
        [ $(($(date +%s) - t0)) -gt "$2" ] && { log "$1: nothing in $2 s"; return 1; }
    done
}
press() { WAIT=1 bash "$HERE/qmp26.sh" "{\"execute\":\"qom-set\",\"arguments\":{\"path\":\"/machine/peripheral/ucons\",\"property\":\"press\",\"value\":\"$1\"}}" >/dev/null; }
el() { rs 'grep -a "el-sample t=[0-9]*s all" files/run/qemu-stderr.txt | tail -n 1' | sed 's/ | native.*//'; }
round() {
    TOP=$SCREEN
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
printf '%s\n' "env.ASOFT_LAT=1" "env.ASOFT_WHO=1" > "$OUT/ios26.in"
adb push "$(cygpath -w "$OUT/ios26.in")" "$EXT/ios26.txt" >/dev/null
log "start: $(heat | tr '\n' ' ')"
mark start
adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService" >/dev/null
nap 2
adb shell "am start -n $PKG/com.asoft.ios.ScreenActivity" >/dev/null
T0=$(date +%s)
TOP=$SCREEN
until_picture resumed 300 || exit 1
mark resumed
log "picture $(($(date +%s) - T0)) s after the start; $(rs 'grep -a "resumed in\|not loading\|wanted" files/run/app.txt | tail -n 2' | tr '\n' ' ')"
while [ $(($(date +%s) - T0)) -lt 60 ]; do sleep 5; done
round r1
while [ $(($(date +%s) - T0)) -lt $((MIN * 60)) ]; do sleep 30; log "$(el)"; done
round r2
mark end
for f in qemu-stderr serial app; do rs "cat files/run/$f.txt" > "$OUT/$f.txt"; done
grep -a "el-sample t=[0-9]*s \(all\|who\|el1-pcs\|syscalls\)" "$OUT/qemu-stderr.txt" | cut -c1-400 > "$OUT/who.txt"
adb shell "rm -f $EXT/ios26.txt"
adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService -a save" >/dev/null
t=$(date +%s); while [ -n "$(qpid)" ] && [ $(($(date +%s) - t)) -lt 300 ]; do sleep 1; done
log "saved and closed $(($(date +%s) - t)) s; end: $(heat | tr '\n' ' ')"
