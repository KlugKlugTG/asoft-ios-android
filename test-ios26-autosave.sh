#!/bin/bash
# test-ios26-autosave.sh OUTDIR [MAPS=25000]: AutoSave and the 2x screen's mapping peaks in one run of the dev app.
# The app is switched to the 2x screen (its own, new disk: ios26-2x-user.qcow2; the user's 3x disk is not touched),
# iOS 26 boots, sits at its lock screen (its display sleeps) until AutoSave makes the "settled" quick save, then the
# rounds of test-ios26-peaks.sh run on it with AutoSave's mapping guard lowered to MAPS (ios26.txt autosave-maps), so
# its save before a crash is seen too. At the end the app goes back to 3x, the 2x test disk and ios26.txt go.
OUT=${1:?output directory}
MAPS=${2:-25000}
PKG=com.asoft.ios.dev
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/guarded.sh"
SCREEN='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]ScreenActivity'
EXT=/sdcard/Android/data/$PKG/files
qpid() { adb shell "ps -A -o PID,ARGS | grep '[l]ibqemu26.so -name' | awk '{print \$1}'" | tr -d '\r' | head -n 1; }
log() { echo "$(date +%T) $*" | tee -a "$OUT/autosave.txt"; }
rs() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }
scale() {   # scale 2|3: the app's iOS 26 screen setting (the app stopped first: it keeps its preferences in memory)
    adb shell "am force-stop $PKG"
    rs "sed -i \"s/name=\\\"ios26-scale\\\" value=\\\"[23]\\\"/name=\\\"ios26-scale\\\" value=\\\"$1\\\"/\" shared_prefs/guest.xml; grep ios26-scale shared_prefs/guest.xml"
}
[ -z "$(qpid)" ] || { echo "a QEMU of the dev app runs already"; exit 1; }
rs 'ls files | grep -c "^ios26-2x-user"' | grep -q '^0$' || { echo "a 2x disk exists already: not a test disk"; exit 1; }
log "start: $(heat | tr '\n' ' ')"
log "scale: $(scale 2)"
printf '%s\n' "autosave-maps=$MAPS" > "$OUT/ios26.in"
adb push "$(cygpath -w "$OUT/ios26.in")" "$EXT/ios26.txt" >/dev/null
adb shell "am start -n $PKG/com.asoft.ios.MainActivity" >/dev/null
nap 3
adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService --ez cold true" >/dev/null
nap 2
adb shell "am start -n $PKG/com.asoft.ios.ScreenActivity" >/dev/null
T0=$(date +%s)
TOP=$SCREEN
# the lock screen: many colours in a small copy of a screenshot
while :; do
    sleep 10
    shot lock-cur
    [ "$(colours lock-cur)" -gt 3000 ] && break
    [ -z "$(qpid)" ] && { log "QEMU gone before the lock screen"; exit 1; }
    [ $(($(date +%s) - T0)) -gt 900 ] && { log "no lock screen in 900 s"; exit 1; }
done
log "lock screen $(($(date +%s) - T0)) s after the start; $(rs 'grep -a "screen \|auto save" files/run/app.txt | tail -n 2' | tr '\n' ' ')"
# AutoSave's settled save (the lock screen sleeps its display by itself)
until rs 'grep -a "auto save (iOS has settled" files/run/app.txt' | grep -q saved; do
    sleep 60
    [ -z "$(qpid)" ] && { log "QEMU gone while settling"; exit 1; }
    log "$(rs 'grep -a "el-sample t=[0-9]*s all" files/run/qemu-stderr.txt | tail -n 1' | sed 's/ | native.*//')"
    [ $(($(date +%s) - T0)) -gt 2400 ] && { log "no settled save in 40 min"; break; }
done
log "settled save: $(rs 'grep -a "auto save" files/run/app.txt' | tr '\n' ' ')"
# the rounds of test-ios26-peaks.sh on this iOS (2x)
mkdir -p "$OUT/peaks"
bash "$HERE/test-ios26-peaks.sh" "$OUT/peaks" running 0 2 > "$OUT/peaks/run.log" 2>&1
log "rounds done: $(tail -n 3 "$OUT/peaks/peaks.txt" | tr '\n' ' ')"
rs 'grep -a "auto save" files/run/app.txt' | tee -a "$OUT/autosave.txt"
for f in qemu-stderr serial app; do rs "cat files/run/$f.txt" > "$OUT/$f.txt"; done
[ -z "$(qpid)" ] || { adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService -a off" >/dev/null; t=0; while [ -n "$(qpid)" ] && [ $t -lt 60 ]; do sleep 1; t=$((t + 1)); done; }
adb shell "rm -f $EXT/ios26.txt"
log "scale back: $(scale 3)"
rs 'cd files && rm -f ios26-2x-user.qcow2 ios26-2x-user.*.leds ios26-2x-user.*.cpus ios26-2x-user.*.clock; ls -la | grep ios26'
adb shell "am start -n $PKG/com.asoft.ios.MainActivity" >/dev/null
log "end: $(heat | tr '\n' ' ')"
