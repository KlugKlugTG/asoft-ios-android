#!/bin/bash
# test-ios26-wall.sh OUTDIR [quick|running] [HOLD_S=180]: the lock screen's wallpaper gallery (a long press on the lock
# screen), which drew the old reims views to Android's 65,530 mappings in ~3 min (ANDROID-S25 §8.10): iOS 26 in the dev
# app from its quick save (or the one that runs), locked and woken, a 1.5 s press in the middle of the lock screen,
# the "+" (the gallery of new wallpapers, live previews), HOLD_S seconds there with a scroll every 15 s (iOS's Auto-Lock
# is 30 s), a screenshot and the mappings every 30 s, Home, 60 s. The power button wakes the display (Home would open the
# lock screen: no passcode). The phone samples the mappings
# once a second (tools/maps-peak.sh). Ends with the app's off (no save). Input through guarded.sh.
OUT=${1:?output directory}
MODE=${2:-quick}
HOLD=${3:-180}
PKG=com.asoft.ios.dev
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/guarded.sh"
SCREEN='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]ScreenActivity'
qpid() { adb shell "ps -A -o PID,ARGS | grep '[l]ibqemu26.so -name' | awk '{print \$1}'" | tr -d '\r' | head -n 1; }
log() { echo "$(date +%T) $*" | tee -a "$OUT/wall.txt"; }
rs() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }
press() { WAIT=1 bash "$HERE/qmp26.sh" "{\"execute\":\"qom-set\",\"arguments\":{\"path\":\"/machine/peripheral/ucons\",\"property\":\"press\",\"value\":\"$1\"}}" >/dev/null; }
colours_ok() { [ "$(colours "$1")" -gt 300 ]; }
maps_now() { rs 'tail -n 1 files/run/maps-sample.txt'; }
until_picture() {
    local t0=$(date +%s) led
    while :; do
        sleep 3
        led=$(rs 'od -An -tu1 -N1 files/run/leds' | tr -d ' ')
        [ $(( ${led:-0} & 1 )) -ne 0 ] && { press home; sleep 4; }
        shot "$1-cur"
        colours_ok "$1-cur" && { cp "$OUT/$1-cur.png" "$OUT/$1.png"; return 0; }
        [ -z "$(qpid)" ] && { log "$1: QEMU gone"; return 1; }
        [ $(($(date +%s) - t0)) -gt "$2" ] && { log "$1: nothing in $2 s"; return 1; }
    done
}
log "start ($MODE): $(heat | tr '\n' ' ')"
if [ "$MODE" != running ]; then
    [ -z "$(qpid)" ] || { echo "a QEMU of the dev app runs already"; exit 1; }
    adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService" >/dev/null
    nap 2
fi
adb shell "am start -n $PKG/com.asoft.ios.ScreenActivity" >/dev/null
T0=$(date +%s)
TOP=$SCREEN
sleep 8
P=$(qpid)
[ -n "$P" ] || { log "no QEMU"; exit 1; }
if [ "$(adb shell "ps -A -o ARGS | grep -c '[m]aps-peak.sh $P'" | tr -d '\r')" = 0 ]; then
    adb push "$(cygpath -w "$HERE/tools/maps-peak.sh")" /data/local/tmp/asoft/maps-peak.sh >/dev/null
    adb shell "chmod a+r /data/local/tmp/asoft/maps-peak.sh; run-as $PKG sh -c 'nohup sh /data/local/tmp/asoft/maps-peak.sh $P 3600 >/dev/null 2>&1 &'"
fi
until_picture first 900 || exit 1
log "picture $(($(date +%s) - T0)) s after the start; maps $(maps_now)"
nap 20
# lock a lit display, wake it with the power button (Home on the lock screen opens it: no passcode)
led=$(rs 'od -An -tu1 -N1 files/run/leds' | tr -d ' ')
[ $(( ${led:-0} & 1 )) -eq 0 ] && { press power; nap 4; }
press power; nap 6
shot lock
log "lock screen; maps $(maps_now)"
send swipe 720 1500 720 1500 1500
nap 6
shot pressed
# PAGE=customize (default): the lock screen's Customize, then its Lock Screen preview: the live wallpaper's editor,
# which draws every frame on the GPU (the page of ANDROID-S25 §8.10); PAGE=plus: the "+", the gallery of new wallpapers
if [ "${PAGE:-customize}" = plus ]; then
    send tap 1211 2900
    nap 8
    shot plus
else
    send tap 720 2900
    nap 8
    shot customize
    # the two previews, Lock Screen on the left
    send tap 470 1500
    nap 8
    shot editor
fi
log "${PAGE:-customize}; maps $(maps_now)"
# an input every 15 s keeps iOS's Auto-Lock away: in the gallery a scroll down and back, which draws more previews
for t in $(seq 15 15 "$HOLD"); do
    if [ "${PAGE:-customize}" != plus ]; then
        # a 20 px drag, under every gesture's threshold: no action, iOS's idle clock reset
        send swipe 720 2000 740 2000 200
    elif [ $((t % 30)) -eq 15 ]; then send swipe 720 2300 720 1100 400; else send swipe 720 1100 720 2300 400; fi
    nap 15
    if [ $((t % 30)) -eq 0 ]; then
        shot "gallery-$t"
        log "gallery $t s: maps $(maps_now)"
    fi
    [ -z "$(qpid)" ] && { log "QEMU gone"; break; }
done
press home; nap 8
shot after
nap 52
log "60 s after the gallery: maps $(maps_now); peak copy at $(rs 'cat files/run/maps-peak.n')"
rs 'cat files/run/maps-sample.txt' > "$OUT/maps-sample.txt"
rs 'cat files/run/maps-peak.txt' > "$OUT/maps-peak.txt"
for f in qemu-stderr serial app; do rs "cat files/run/$f.txt" > "$OUT/$f.txt"; done
py -3.12 "$(cygpath -w "$HERE/maps-views.py")" "$(cygpath -w "$OUT/maps-peak.txt")" | tee -a "$OUT/wall.txt"
if [ -n "$(qpid)" ]; then
    adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService -a off" >/dev/null
    t=0; while [ -n "$(qpid)" ] && [ $t -lt 60 ]; do sleep 1; t=$((t + 1)); done
fi
log "off; end: $(heat | tr '\n' ' ')"
