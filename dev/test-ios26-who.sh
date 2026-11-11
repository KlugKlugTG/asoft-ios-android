#!/bin/bash
# test-ios26-who.sh OUTDIR [MIN=17]: what keeps iOS 26's vCPUs busy for ~13 minutes after a boot (APK-PLAN §6.20)?
# A boot of the user's disk in the dev app with ASOFT_WHO=1 (QEMU's sampler counts the busy samples by address
# space and kernel PC, and the syscalls by address space and number), left alone at the lock screen for MIN minutes;
# who-report.py sums them per phase. Ends with the app's off (no save): the quick save the user starts from stays.
OUT=${1:?output directory}
MIN=${2:-17}
PKG=com.asoft.ios.dev
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/guarded.sh"
SCREEN='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]ScreenActivity'
EXT=/sdcard/Android/data/$PKG/files
qpid() { adb shell "ps -A -o PID,ARGS | grep '[l]ibqemu26.so -name' | awk '{print \$1}'" | tr -d '\r' | head -n 1; }
log() { echo "$(date +%T) $*" | tee -a "$OUT/who-run.txt"; }
rs() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }
[ -z "$(qpid)" ] || { echo "a QEMU of the dev app runs already"; exit 1; }
printf '%s\n' "env.ASOFT_WHO=1" > "$OUT/ios26.in"
adb push "$(cygpath -w "$OUT/ios26.in")" "$EXT/ios26.txt" >/dev/null
log "start: $(heat | tr '\n' ' ')"
adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService --ez cold true" >/dev/null
nap 2
adb shell "am start -n $PKG/com.asoft.ios.ScreenActivity" >/dev/null
T0=$(date +%s)
while [ $(($(date +%s) - T0)) -lt $((MIN * 60)) ]; do
    sleep 60
    [ -z "$(qpid)" ] && { log "QEMU gone"; break; }
    log "$(rs 'grep -a "el-sample t=[0-9]*s all" files/run/qemu-stderr.txt | tail -n 1' | sed 's/ | native.*//')"
done
for f in qemu-stderr serial app; do rs "cat files/run/$f.txt" > "$OUT/$f.txt"; done
adb shell "rm -f $EXT/ios26.txt"
adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService -a off" >/dev/null
t=0; while [ -n "$(qpid)" ] && [ $t -lt 60 ]; do sleep 1; t=$((t + 1)); done
log "off; end: $(heat | tr '\n' ' ')"
for w in "0 300" "300 600" "600 780" "780 900" "900 99999"; do
    echo "== t $w s" >> "$OUT/who-report.txt"
    py -3.12 "$(cygpath -w "$HERE/who-report.py")" "$(cygpath -w "$OUT/qemu-stderr.txt")" $w >> "$OUT/who-report.txt" 2>&1
done
