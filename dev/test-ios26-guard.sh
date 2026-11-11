#!/bin/bash
# test-ios26-guard.sh OUTDIR [MAPS=12000]: AutoSave's mapping guard on a fresh 3x test disk (the user's iOS 26 disk and its
# quick save are set aside first and put back at the end). iOS 26 boots with the guard lowered to MAPS and the settled
# save off (ios26.txt); the lock screen of a 3x boot holds ~17,500 mappings, so the guard should save once on the way
# there, and not again (it re-arms only below 80% of MAPS). Then the app's off, the test disk goes, the user's comes back.
OUT=${1:?output directory}
MAPS=${2:-12000}
PKG=com.asoft.ios.dev
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/guarded.sh"
SCREEN='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]ScreenActivity'
EXT=/sdcard/Android/data/$PKG/files
qpid() { adb shell "ps -A -o PID,ARGS | grep '[l]ibqemu26.so -name' | awk '{print \$1}'" | tr -d '\r' | head -n 1; }
log() { echo "$(date +%T) $*" | tee -a "$OUT/guard.txt"; }
rs() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }
[ -z "$(qpid)" ] || { echo "a QEMU of the dev app runs already"; exit 1; }
rs 'grep -c "ios26-scale\" value=\"3\"" shared_prefs/guest.xml' | grep -q '^1$' || { echo "the app is not on the 3x screen"; exit 1; }
rs 'cd files && for f in ios26-user.qcow2 ios26-user.asoft-quick.leds ios26-user.asoft-quick.cpus ios26-user.asoft-quick.clock; do [ -e $f ] && mv $f $f.keep; done; ls | grep ios26' | tee -a "$OUT/guard.txt"
printf '%s\n' "autosave-maps=$MAPS" "autosave-settle-s=999999" > "$OUT/ios26.in"
adb push "$(cygpath -w "$OUT/ios26.in")" "$EXT/ios26.txt" >/dev/null
log "start: $(heat | tr '\n' ' ')"
adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService --ez cold true" >/dev/null
nap 2
adb shell "am start -n $PKG/com.asoft.ios.ScreenActivity" >/dev/null
T0=$(date +%s)
until rs 'grep -a "auto save (" files/run/app.txt' | grep -q "kernel mappings"; do
    sleep 5
    [ -z "$(qpid)" ] && { log "QEMU gone"; break; }
    [ $(($(date +%s) - T0)) -gt 420 ] && { log "no guard save in 420 s"; break; }
done
log "guard: $(rs 'grep -a "auto save" files/run/app.txt' | tr '\n' ' ')"
TOP=$SCREEN
shot after-save
sleep 90
log "90 s later: $(rs 'grep -a "auto save" files/run/app.txt' | tail -n 2 | tr '\n' ' ')"
WAIT=3 bash "$HERE/qmp26.sh" hmp "info snapshots" | tee -a "$OUT/guard.txt"
for f in qemu-stderr serial app; do rs "cat files/run/$f.txt" > "$OUT/$f.txt"; done
adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService -a off" >/dev/null
t=0; while [ -n "$(qpid)" ] && [ $t -lt 60 ]; do sleep 1; t=$((t + 1)); done
adb shell "rm -f $EXT/ios26.txt"
rs 'cd files && [ -e ios26-user.qcow2.keep ] && rm -f ios26-user.qcow2 ios26-user.*.leds ios26-user.*.cpus ios26-user.*.clock; for f in ios26-user.qcow2 ios26-user.asoft-quick.leds ios26-user.asoft-quick.cpus ios26-user.asoft-quick.clock; do [ -e $f.keep ] && mv $f.keep $f; done; ls -la | grep ios26' | tee -a "$OUT/guard.txt"
log "end: $(heat | tr '\n' ' ')"
