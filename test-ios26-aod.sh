#!/bin/bash
# test-ios26-aod.sh OUTDIR DTREE [BOOT_WAIT_S=240]: does iOS 26 offer its Always On Display with a device tree
# that has /arm-io/disp0 always-on-time (APK-PLAN §6.23)? The user's iOS 26 disk (the scale the app is on) and
# every file of its saves are set aside (*.keep), a fresh test disk boots with ios26.txt dtree=DTREE (a file in
# the phone's iOS 26 folder) and AutoSave off, then: unlock, Settings > Display & Brightness (screenshots),
# lock with the side button (screenshots and the display byte). Always ends with the app's off, the test disk
# deleted and the user's files put back (a trap), and the user's ios26.txt restored. Input through guarded.sh.
OUT=${1:?output directory}; DTREE=${2:?device tree name}; BOOT=${3:-240}
PKG=com.asoft.ios.dev
HERE=$(cd "$(dirname "$0")" && pwd)
export MSYS_NO_PATHCONV=1
. "$HERE/guarded.sh"
TOP='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]ScreenActivity'
EXT=/sdcard/Android/data/$PKG/files
rs() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }
qpid() { adb shell "ps -A -o PID,ARGS | grep '[l]ibqemu26.so -name' | awk '{print \$1}'" | tr -d '\r' | head -n 1; }
log() { echo "$(date +%T) $*" | tee -a "$OUT/aod.txt"; }
press() { WAIT=1 bash "$HERE/qmp26.sh" "{\"execute\":\"qom-set\",\"arguments\":{\"path\":\"/machine/peripheral/ucons\",\"property\":\"press\",\"value\":\"$1\"}}" >/dev/null 2>&1; }
led() { rs 'od -An -tu1 -N1 files/run/leds' | tr -d ' '; }
[ -z "$(qpid)" ] || { echo "a QEMU of the dev app runs already"; exit 1; }
SCALE=$(rs 'grep -o "ios26-scale\" value=\"[0-9]" shared_prefs/guest.xml' | tail -c 2)
DISK=$([ "$SCALE" = 2 ] && echo ios26-2x-user || echo ios26-user)
adb shell "test -f /data/local/tmp/asoft/ios26/$DTREE" || { echo "no $DTREE in the phone's iOS 26 folder"; exit 1; }
adb shell "cat $EXT/ios26.txt" | tr -d '\r' > "$OUT/ios26-user.txt"
log "user disk $DISK (scale $SCALE): set aside"
rs "cd files && for f in $DISK.*; do [ -e \$f ] && mv \$f \$f.keep; done; ls | grep $DISK"
restore() {
    log "ending: the app's off"
    adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService -a off" >/dev/null 2>&1
    t=0; while [ -n "$(qpid)" ] && [ $t -lt 60 ]; do sleep 1; t=$((t + 1)); done
    rs "cat files/run/qemu-stderr.txt" > "$OUT/qemu-stderr.txt"; rs "cat files/run/app.txt" > "$OUT/app.txt"; rs "cat files/run/serial.txt" > "$OUT/serial.txt"
    rs "cd files && if ls $DISK.qcow2.keep > /dev/null 2>&1; then for f in $DISK.*; do case \$f in *.keep) ;; *) rm -f \$f;; esac; done; for f in $DISK.*.keep; do mv \$f \${f%.keep}; done; fi; ls -la | grep $DISK"
    adb push "$(cygpath -w "$OUT/ios26-user.txt")" "$EXT/ios26.txt" >/dev/null
    log "user files back; ios26.txt: $(adb shell cat $EXT/ios26.txt | tr -d '\r' | tr '\n' ' ')"
}
trap restore EXIT
printf '%s\n' "touchlog=1" "env.ASOFT_LAT=1" "dtree=$DTREE" "autosave-maps=999999" "autosave-settle-s=999999" > "$OUT/ios26.in"
adb push "$(cygpath -w "$OUT/ios26.in")" "$EXT/ios26.txt" >/dev/null
adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService --ez cold true" >/dev/null
sleep 2
adb shell "am start -n $PKG/com.asoft.ios.ScreenActivity" >/dev/null
sleep 15
[ -n "$(qpid)" ] || { log "QEMU ended at once: $(rs 'tail -n 3 files/run/qemu-stderr.txt' | tr '\n' ' ')"; exit 1; }
log "cold boot with $DTREE; waiting $BOOT s"
T0=$(date +%s)
while [ $(($(date +%s) - T0)) -lt "$BOOT" ]; do
    sleep 10
    [ -n "$(qpid)" ] || { log "QEMU gone"; exit 1; }
done
log "$(rs 'grep -a "device tree\|start iOS 26" files/run/app.txt | tail -n 2' | tr '\n' ' ')"
shot boot
[ $(( $(led) & 1 )) -ne 0 ] && { press home; sleep 6; }
press home; sleep 8
shot unlocked
press home; sleep 6
send tap 221 409; sleep 20          # Settings, home screen page 1
shot settings
send tap 556 2432; sleep 20         # Display & Brightness (a fresh disk has no Ethernet row)
shot display
send swipe 720 2400 720 1000 600; sleep 12
shot display-2
send swipe 720 2400 720 1000 600; sleep 12
shot display-3
log "display before the lock: led $(led)"
press power; sleep 15
log "15 s after the side button: led $(led)"
shot locked-15
sleep 30
log "45 s after: led $(led)"
shot locked-45
sleep 45
log "90 s after: led $(led)"
shot locked-90
press power; sleep 20
log "a second side button press, 20 s later: led $(led)"
shot locked-2nd
log "reims display lines: $(rs 'grep -a "guest display" files/run/qemu-stderr.txt | tail -n 4' | tr '\n' ' ')"
