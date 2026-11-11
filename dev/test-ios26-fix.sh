#!/bin/bash
# test-ios26-fix.sh OUTDIR [PHASES]: the two fixes of 2026-11-05 on iOS 26 in the dev app, on a fresh test disk
# (the user's iOS 26 disk is put aside for it and back afterwards):
#   1  cache=none (O_DIRECT) again: boot to the lock screen, unlock, home screen; the disk's failed writes and
#      the guest's I/O errors (before the QEMU fix a third of the writes failed), then Save and close
#   2  a load as if the phone had restarted since the save (ASOFT_HOST_COUNTER_TEST_BASE: the host's counter
#      seen from near 0): the counter goes on from the save (offset), iOS lives, Calculator opens; Save and close
#   3  the same load with the offset off (ASOFT_HOST_COUNTER_NO_RESUME=1), the behaviour before the fix:
#      XNU's sched_clutch panic expected
#   4  the user's disk back
# PHASES: which of them, "1234" by default. Every input goes through guarded.sh (the app's screen on top).
OUT=${1:?output directory}
PHASES=${2:-1234}
PKG=com.asoft.ios.dev
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/guarded.sh"
SCREEN='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]ScreenActivity'
EXT=/sdcard/Android/data/$PKG/files
qpid() { adb shell "ps -A -o PID,ARGS | grep '[l]ibqemu26.so -name' | awk '{print \$1}'" | tr -d '\r' | head -n 1; }
log() { echo "$(date +%T) $*" | tee -a "$OUT/fix.txt"; }
rs() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }
heat1() { heat | tr '\n' ' '; }
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
blockstats() {
    WAIT=2 bash "$HERE/qmp26.sh" '{"execute":"query-blockstats"}' 2>/dev/null | py -3.12 -c "
import json, sys
for l in sys.stdin:
    l = l.strip()
    if not l.startswith('{\"return\": ['): continue
    for d in json.loads(l)['return']:
        s = d['stats']
        if s['rd_operations'] + s['wr_operations']:
            print('reads %d (failed %d), writes %d (failed %d), flushes %d (failed %d)' % (s['rd_operations'], s['failed_rd_operations'], s['wr_operations'], s['failed_wr_operations'], s['flush_operations'], s['failed_flush_operations']))
"
}
guest_errors() { echo "guest I/O errors $(rs 'grep -ac "I/O error" files/run/serial.txt'), panics $(rs 'grep -ac "panic(" files/run/serial.txt')"; }
ios26txt() { printf '%s\n' "$@" > "$OUT/ios26.in"; adb push "$(cygpath -w "$OUT/ios26.in")" "$EXT/ios26.txt" >/dev/null; }
start_guest() {     # start_guest cold|quick
    if [ "$1" = cold ]; then
        adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService --ez cold true" >/dev/null
    else
        adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService" >/dev/null
    fi
    nap 2
    adb shell "am start -n $PKG/com.asoft.ios.ScreenActivity" >/dev/null
}
save_close() {
    adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService -a save" >/dev/null
    local t0=$(date +%s)
    while [ -n "$(qpid)" ] && [ $(($(date +%s) - t0)) -lt 300 ]; do sleep 1; done
    log "$1: save and close $(($(date +%s) - t0)) s; $(rs 'grep -a "save:" files/run/app.txt | tail -n 1')"
}
stop_guest() {
    adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService -a off" >/dev/null
    local t=0
    while [ -n "$(qpid)" ] && [ $t -lt 60 ]; do sleep 1; t=$((t + 1)); done
}
keep_logs() {
    rs 'cat files/run/qemu-stderr.txt' > "$OUT/$1-qemu-stderr.txt"
    rs 'cat files/run/serial.txt' > "$OUT/$1-serial.txt"
    rs 'cat files/run/app.txt' > "$OUT/$1-app.txt"
}
# the host's counter now, in ticks (19.2 MHz), from the boot clock it follows
host_ticks() { adb shell cat /proc/uptime | tr -d '\r' | awk '{printf "%.0f", $1 * 19200000}'; }

[ -z "$(qpid)" ] || { echo "a QEMU of the dev app runs already"; exit 1; }
log "start: heat $(heat1), free $(adb shell df -h /data | tail -n 1 | awk '{print $4}')"

case $PHASES in *1*)
    # the user's disk and its save's side files aside (".keep"), a fresh overlay comes with the start
    rs 'cd files && for f in ios26-user.qcow2 ios26-user.asoft-quick.leds ios26-user.asoft-quick.cpus ios26-user.asoft-quick.clock; do [ -e $f ] && mv $f $f.keep; done; ls -la | grep ios26'
    ios26txt "cache=none"
    T0=$(date +%s)
    start_guest cold
    TOP=$SCREEN
    until_picture f1-lock 900 || exit 1
    log "f1: lock screen $(($(date +%s) - T0)) s after the start; heat $(heat1); $(blockstats); $(guest_errors)"
    nap 20
    if [ -z "${QUICK1:-}" ]; then
        send swipe 720 3080 720 250 300
        until_picture f1-home 120
        nap 60
        shot f1-home2
        log "f1: home screen; $(blockstats); $(guest_errors)"
    fi
    rs 'grep -a "disk check\|room:" files/run/app.txt | tail -n 3' | tee -a "$OUT/fix.txt"
    save_close f1
    keep_logs f1
    rs 'ls -la files | grep ios26-user' | tee -a "$OUT/fix.txt"
;; esac

case $PHASES in *2*)
    B=$(( $(host_ticks) - 120 * 19200000 ))
    ios26txt "cache=none" "env.ASOFT_HOST_COUNTER_TEST_BASE=$B"
    log "f2: test base $B (the host's counter seen from ~120 s)"
    T0=$(date +%s)
    start_guest quick
    TOP=$SCREEN
    until_picture f2-resumed 300 || { keep_logs f2; exit 1; }
    log "f2: picture $(($(date +%s) - T0)) s after the start; $(rs 'grep -a "host counter" files/run/qemu-stderr.txt' | tr '\n' ' ')"
    nap 30
    log "f2: 30 s later: $(guest_errors)"
    send tap ${HOME_XY:-1346 1310}      # side bar Home: wakes iOS if it slept the display
    nap 10
    bash "$HERE/test-ios26-alive.sh" "$OUT" f2p 300 | tail -n 1 | tee -a "$OUT/fix.txt"
    log "f2: $(guest_errors); $(blockstats)"
    send tap ${HOME_XY:-1346 1310}
    nap 10
    save_close f2
    keep_logs f2
;; esac

case $PHASES in *3*)
    B=$(( $(host_ticks) - 120 * 19200000 ))
    ios26txt "cache=none" "env.ASOFT_HOST_COUNTER_TEST_BASE=$B" "env.ASOFT_HOST_COUNTER_NO_RESUME=1"
    log "f3: test base $B, no resume (the behaviour before the fix)"
    T0=$(date +%s)
    start_guest quick
    TOP=$SCREEN
    nap 60
    shot f3-60s
    log "f3: 60 s: $(rs 'grep -a "host counter" files/run/qemu-stderr.txt' | tr '\n' ' '); $(guest_errors)"
    rs 'grep -a -m2 "panic(" files/run/serial.txt' | cut -c1-220 | tee -a "$OUT/fix.txt"
    keep_logs f3
    stop_guest
;; esac

case $PHASES in *4*)
    adb shell "rm -f $EXT/ios26.txt"
    [ -z "$(qpid)" ] || stop_guest
    # the test disk and every side file of its saves go (a test .clock beside the user's old save would make it load)
    rs 'cd files && [ -e ios26-user.qcow2.keep ] && rm -f ios26-user.qcow2 ios26-user.*.leds ios26-user.*.cpus ios26-user.*.clock; for f in ios26-user.qcow2 ios26-user.asoft-quick.leds ios26-user.asoft-quick.cpus ios26-user.asoft-quick.clock; do [ -e $f.keep ] && mv $f.keep $f; done; ls -la | grep ios26'
    log "f4: the user's disk is back"
    # its one leaked cluster (the 10:23 crash's), freed as the app's own check would: the test disk took the app's once-check
    LIBDIR=$(adb shell pm path $PKG | tr -d '\r' | sed 's|package:||; s|/base.apk|/lib/arm64|')
    rs "cd files && LD_LIBRARY_PATH=\$PWD/lib $LIBDIR/libqemuimg.so check -r leaks ios26-user.qcow2 | tail -n 3" | tee -a "$OUT/fix.txt"
;; esac

case $PHASES in *5*)
    # the user's iOS 26 as the app starts it: its quick save is from before the clock fix and the phone
    # restarted since (09:39), so the app should boot it instead of loading it (the 10:15 panic); then a
    # save and close leaves a quick save of the new kind
    rs 'cd files && ls -la | grep ios26; grep -a "" /dev/null'
    T0=$(date +%s)
    start_guest quick
    TOP=$SCREEN
    until_picture f5-lock 900 || { keep_logs f5; exit 1; }
    log "f5: lock screen $(($(date +%s) - T0)) s after the start; $(blockstats); $(guest_errors)"
    rs 'grep -a "not loading\|stale\|disk check\|room:\|start iOS 26" files/run/app.txt | tail -n 5' | tee -a "$OUT/fix.txt"
    nap 20
    save_close f5
    nap 3
    keep_logs f5
    rs 'cd files && ls -la | grep ios26' | tee -a "$OUT/fix.txt"
;; esac
log "end: heat $(heat1)"
