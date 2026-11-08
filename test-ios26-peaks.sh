#!/bin/bash
# test-ios26-peaks.sh OUTDIR [quick|cold] [WAIT_S=60] [ROUNDS=2]: how high do reims' views of guest RAM take the QEMU's
# kernel mappings (Android's limit 65,530) when apps are opened, and what are the views at the peak? iOS 26 in the dev
# app from its quick save (or a boot), then from WAIT_S on ROUNDS rounds of six apps (Calculator, Settings, Clock,
# Calendar, Compass, Contacts: open, 12 s, the side bar's Home, 8 s), then 60 s alone. The phone samples the mappings
# once a second and keeps a copy of /proc/PID/maps near the peak (tools/maps-peak.sh); maps-views.py splits it into
# views. Ends with the app's off (no save: a quick save the start loaded stays as it was). Input through guarded.sh.
OUT=${1:?output directory}
MODE=${2:-quick}
WAIT=${3:-60}
ROUNDS=${4:-2}
PKG=com.asoft.ios.dev
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/guarded.sh"
SCREEN='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]ScreenActivity'
qpid() { adb shell "ps -A -o PID,ARGS | grep '[l]ibqemu26.so -name' | awk '{print \$1}'" | tr -d '\r' | head -n 1; }
log() { echo "$(date +%T) $*" | tee -a "$OUT/peaks.txt"; }
rs() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }
press() { WAIT=1 bash "$HERE/qmp26.sh" "{\"execute\":\"qom-set\",\"arguments\":{\"path\":\"/machine/peripheral/ucons\",\"property\":\"press\",\"value\":\"$1\"}}" >/dev/null; }
until_picture() {
    local t0=$(date +%s) c led
    while :; do
        sleep 3
        # a load after a long time away: iOS locks and sleeps its display (the app's "Screen off" cover)
        led=$(rs 'od -An -tu1 -N1 files/run/leds' | tr -d ' ')
        [ $(( ${led:-0} & 1 )) -ne 0 ] && { press home; sleep 4; }
        shot "$1-cur"; c=$(colours "$1-cur")
        [ "$c" -gt 300 ] && { cp "$OUT/$1-cur.png" "$OUT/$1.png"; return 0; }
        [ -z "$(qpid)" ] && { log "$1: QEMU gone"; return 1; }
        [ $(($(date +%s) - t0)) -gt "$2" ] && { log "$1: nothing in $2 s"; return 1; }
    done
}
maps_now() { rs 'tail -n 1 files/run/maps-sample.txt'; }
# the phone's boot-time clock before each input, for settle-report.py (with ASOFT_LAT=1 in ios26.txt)
mark() { echo "$1 $(adb shell cat /proc/uptime | tr -d '\r' | awk '{print $1}')" >> "$OUT/marks.txt"; }
log "start ($MODE): $(heat | tr '\n' ' ')"
if [ "$MODE" = running ]; then     # the iOS 26 that runs now
    [ -n "$(qpid)" ] || { echo "no QEMU of the dev app runs"; exit 1; }
else
    [ -z "$(qpid)" ] || { echo "a QEMU of the dev app runs already"; exit 1; }
    # AutoSave must not save this test's iOS over the user's quick save: its guard off unless a caller's ios26.txt says
    EXT=/sdcard/Android/data/$PKG/files
    if ! adb shell "test -f $EXT/ios26.txt && echo y" | grep -q y; then
        printf 'autosave-maps=999999\n' > "$OUT/ios26.in"
        adb push "$(cygpath -w "$OUT/ios26.in")" "$EXT/ios26.txt" >/dev/null
        OWN_TXT=1
    fi
    if [ "$MODE" = cold ]; then
        adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService --ez cold true" >/dev/null
    else
        adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService" >/dev/null
    fi
    nap 2
fi
adb shell "am start -n $PKG/com.asoft.ios.ScreenActivity" >/dev/null
T0=$(date +%s)
TOP=$SCREEN
sleep 8
P=$(qpid)
if [ "$(adb shell "ps -A -o ARGS | grep -c '[m]aps-peak.sh $P'" | tr -d '\r')" = 0 ]; then
    adb push "$(cygpath -w "$HERE/tools/maps-peak.sh")" /data/local/tmp/asoft/maps-peak.sh >/dev/null
    adb shell "chmod a+r /data/local/tmp/asoft/maps-peak.sh; run-as $PKG sh -c 'nohup sh /data/local/tmp/asoft/maps-peak.sh $P 3600 >/dev/null 2>&1 &'"
fi
until_picture first 900 || exit 1
log "picture $(($(date +%s) - T0)) s after the start; $(rs 'grep -a "resumed in\|wanted" files/run/app.txt | tail -n 2' | tr '\n' ' ')"
while [ $(($(date +%s) - T0)) -lt "$WAIT" ]; do sleep 5; done
# from the lock screen: lock a lit display, wake it, flick it open
led=$(rs 'od -An -tu1 -N1 files/run/leds' | tr -d ' ')
[ $(( ${led:-0} & 1 )) -eq 0 ] && { press power; nap 4; }
press home; nap 5
send swipe 720 3080 720 250 300; nap 12
shot unlocked
log "unlocked; maps $(maps_now)"
APPS="calc:1214,409 settings:221,409 clock:552,770 calendar:221,770 compass:887,770 contacts:1217,770"
mark start
for r in $(seq 1 "$ROUNDS"); do
    for a in $APPS; do
        name=${a%%:*}; xy=${a#*:}
        mark "r$r-$name"; send tap ${xy%,*} ${xy#*,}; nap 12
        shot "r$r-$name"
        mark "r$r-home$name"; send tap ${HOME_XY:-1346 1310}; nap 8
        log "r$r $name: maps $(maps_now)"
    done
done
mark end
nap 60
log "60 s after the rounds: maps $(maps_now); peak copy at $(rs 'cat files/run/maps-peak.n')"
rs 'cat files/run/maps-sample.txt' > "$OUT/maps-sample.txt"
rs 'cat files/run/maps-peak.txt' > "$OUT/maps-peak.txt"
for f in qemu-stderr serial app; do rs "cat files/run/$f.txt" > "$OUT/$f.txt"; done
py -3.12 "$(cygpath -w "$HERE/maps-views.py")" "$(cygpath -w "$OUT/maps-peak.txt")" | tee -a "$OUT/peaks.txt"
adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService -a off" >/dev/null
t=0; while [ -n "$(qpid)" ] && [ $t -lt 60 ]; do sleep 1; t=$((t + 1)); done
[ -n "${OWN_TXT:-}" ] && adb shell "rm -f $EXT/ios26.txt"
log "off; end: $(heat | tr '\n' ' ')"
