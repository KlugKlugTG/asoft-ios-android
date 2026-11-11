#!/bin/bash
# test-ios26-cycle.sh OUTDIR: iOS 26 in the app, end to end through its own screens (dev package):
#   1. Start iOS 26 (one tap, a boot: no quick save) -> lock screen -> unlock flick
#   2. touch probe: when does a touch on Calculator open it
#   3. the side bar's Home -> home screen
#   4. side bar 💾 > Save now (a named save in the running iOS) -> touch probe again
#   5. side bar Home, 💾 > Save and close, Start iOS 26 (-loadvm in a new QEMU) -> touch probe again
#   6. the app's stop (QMP stop + quit, SIGKILL last), then a boot from the disk it left: lock screen, and
#      does iOS find its Data volume intact
# The probes are test-ios26-alive.sh's. Times, heat and the app's log go to OUTDIR/cycle.txt.
OUT=${1:?output directory}
PKG=com.asoft.ios.dev
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/guarded.sh"
qpid() { adb shell "ps -A -o PID,ARGS | grep '[l]ibqemu26.so -name' | awk '{print \$1}'" | tr -d '\r' | head -n 1; }
log() { echo "$(date +%T) $*" | tee -a "$OUT/cycle.txt"; }
heat1() { heat | tr '\n' ' '; }
# the user disk's failed writes and guest I/O errors (cache=none failed a third of iOS 26's writes)
blockfail() {
    local f e
    f=$(WAIT=2 bash "$HERE/qmp26.sh" '{"execute":"query-blockstats"}' 2>/dev/null | grep -o '"failed_wr_operations": [0-9]*' | head -n 1 | grep -o '[0-9]*$')
    e=$(adb shell "run-as $PKG sh -c 'grep -c I/O.error files/run/serial.txt'" | tr -d '\r')
    echo "failed writes $f, guest I/O errors $e"
}
SCREEN='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]ScreenActivity'
HOME_SCR='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]MainActivity'
ANY='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.](MainActivity|ScreenActivity)'

until_picture() {   # until_picture NAME MAXS: a screenshot with many colours (lock and home screens 5900-8200 in a 72x156 copy; boot text <100, an app such as Calculator ~660)
    local t0=$(date +%s) c
    while :; do
        sleep 5
        shot "$1-cur"; c=$(colours "$1-cur")
        [ "$c" -gt 3000 ] && { cp "$OUT/$1-cur.png" "$OUT/$1.png"; return 0; }
        [ -z "$(qpid)" ] && { log "$1: QEMU gone"; return 1; }
        [ $(($(date +%s) - t0)) -gt "$2" ] && { log "$1: nothing in $2 s"; return 1; }
    done
}
home_and_wait() {   # the side bar's Home, until the home screen (many colours) is back
    TOP=$SCREEN
    send tap ${HOME_XY:-1346 1310}
    local t0=$(date +%s)
    until_picture "$1" 120 && log "$1: home screen $(($(date +%s) - t0)) s after Home"
}

# 1 (FROM=3: iOS runs already, on its home screen or in an app: go on from step 3)
if [ "${FROM:-1}" -le 2 ]; then
adb shell "am start --activity-clear-top -n $PKG/com.asoft.ios.MainActivity" >/dev/null
nap 3
TOP=$HOME_SCR
log "start: heat $(heat1), free $(adb shell df -h /data | tail -n 1 | awk '{print $4}')"
tap_text "Start iOS 26" || exit 1
T0=$(date +%s)
TOP=$SCREEN
until_picture c1-lock 900 || exit 1
log "c1: lock screen $(($(date +%s) - T0)) s after Start; heat $(heat1); $(blockfail)"
nap 20
send swipe 720 3080 720 250 300
T1=$(date +%s)
log "c1: unlock flick"
until_picture c1-unlocked 60; nap 5; shot c1-home
# 2
bash "$HERE/test-ios26-alive.sh" "$OUT" p1 900 | tail -n 1 | tee -a "$OUT/cycle.txt"
log "p1 done $(($(date +%s) - T1)) s after the unlock flick; heat $(heat1); $(blockfail)"
fi
# 3
home_and_wait c3-home
# 4
TOP=$SCREEN
send tap 1380 1740
nap 3
TOP=$ANY
tap_text "Save now" || exit 1
nap 2
tap_text "Save" || exit 1
T4=$(date +%s)
log "c4: Save now"
nap 30
until_picture c4-after-save 300 && log "c4: picture $(($(date +%s) - T4)) s after Save"
adb shell "run-as $PKG sh -c 'grep -aE \"savevm|save\" files/run/app.txt | tail -n 2'" | tr -d '\r' | tee -a "$OUT/cycle.txt"
TOP=$SCREEN
bash "$HERE/test-ios26-alive.sh" "$OUT" p2 600 | tail -n 1 | tee -a "$OUT/cycle.txt"
# 5
home_and_wait c5-home
TOP=$SCREEN
send tap 1380 1740
nap 3
TOP=$ANY
adb shell 'for i in $(seq 1 90); do echo "$(date +%T) $(grep MemAvailable /proc/meminfo)"; sleep 1; done' > "$OUT/mem-save.txt" &
tap_text "Save and close" || exit 1
T5=$(date +%s)
while [ -n "$(qpid)" ]; do sleep 1; done
log "c5: saved and closed $(($(date +%s) - T5)) s; lowest MemAvailable $(sort -k3 -n "$OUT/mem-save.txt" | head -n 1 | awk '{print $3}') kB"
nap 5
adb shell "am start --activity-clear-top -n $PKG/com.asoft.ios.MainActivity" >/dev/null
nap 4
TOP=$HOME_SCR
tap_text "Start iOS 26" || exit 1
T6=$(date +%s)
TOP=$SCREEN
until_picture c6-resumed 300 && log "c6: picture $(($(date +%s) - T6)) s after Start (quick boot); $(blockfail)"
bash "$HERE/qmp26.sh" hmp "info usb" | tee -a "$OUT/cycle.txt"
bash "$HERE/test-ios26-alive.sh" "$OUT" p3 600 | tail -n 1 | tee -a "$OUT/cycle.txt"
adb shell "run-as $PKG sh -c 'cat files/run/serial.txt'" | tr -d '\r' > "$OUT/serial-loaded.txt"
adb shell "run-as $PKG sh -c 'cat files/run/qemu-stderr.txt'" | tr -d '\r' > "$OUT/qemu-stderr-loaded.txt"
# 6
adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService -a off" >/dev/null
T7=$(date +%s)
while [ -n "$(qpid)" ]; do sleep 1; done
log "c7: stopped $(($(date +%s) - T7)) s after the app's off"
nap 3
adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService --ez cold true" >/dev/null
nap 2
adb shell "am start -n $PKG/com.asoft.ios.ScreenActivity" >/dev/null
T8=$(date +%s)
TOP=$SCREEN
until_picture c8-lock 900 && log "c8: lock screen $(($(date +%s) - T8)) s after a boot from that disk; $(blockfail)"
log "c8: corruption lines $(adb shell "run-as $PKG sh -c 'grep -c corruption files/run/serial.txt'" | tr -d '\r')"
adb shell "run-as $PKG sh -c 'cat files/run/app.txt'" | tr -d '\r' > "$OUT/app.txt"
adb shell "run-as $PKG sh -c 'cat files/run/qemu-stderr.txt'" | tr -d '\r' > "$OUT/qemu-stderr.txt"
adb shell "run-as $PKG sh -c 'cat files/run/serial.txt'" | tr -d '\r' > "$OUT/serial.txt"
log "end: heat $(heat1)"
