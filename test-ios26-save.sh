#!/bin/bash
# test-ios26-save.sh OUTDIR: quick boot for iOS 26 in the app (dev package): side bar 💾 > Save and close,
# time until QEMU has gone; then the start screen's Start iOS 26 (-loadvm asoft-quick), time until a picture.
OUT=${1:?output directory}
PKG=com.asoft.ios.dev
. "$(dirname "$0")/guarded.sh"
qpid() { adb shell "ps -A -o PID,ARGS | grep '[l]ibqemu26.so -name' | awk '{print \$1}'" | tr -d '\r' | head -n 1; }
log() { echo "$(date +%T) $*" | tee -a "$OUT/save.txt"; }
TOP='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]ScreenActivity'
log "free $(adb shell df -h /data | tail -n 1 | awk '{print $4}'), heat $(heat | tr '\n' ' ')"
send tap 1380 1740          # 💾
nap 3
shot s1-saves
TOP='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.](MainActivity|ScreenActivity)'
tap_text "Save and close" || exit 1
t0=$(date +%s)
log "Save and close tapped"
while [ -n "$(qpid)" ]; do
    sleep 1
    [ $(($(date +%s) - t0)) -gt 600 ] && { log "QEMU still there after 600 s"; exit 1; }
done
log "QEMU gone $(($(date +%s) - t0)) s after Save and close"
adb shell "run-as $PKG sh -c 'tail -n 5 files/run/app.txt'" | tr -d '\r' | tee -a "$OUT/save.txt"
log "free $(adb shell df -h /data | tail -n 1 | awk '{print $4}')"
nap 5
adb shell "am start -n $PKG/com.asoft.ios.MainActivity" >/dev/null
nap 4
TOP='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]MainActivity'
shot s2-start-screen
tap_text "Start iOS 26" || exit 1
t1=$(date +%s)
log "Start iOS 26 tapped (quick boot)"
TOP='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]ScreenActivity'
while :; do
    sleep 3
    shot s3-cur
    c=$(colours s3-cur)
    if [ "$c" -gt 300 ]; then cp "$OUT/s3-cur.png" "$OUT/s3-resumed.png"; log "picture $(($(date +%s) - t1)) s after Start (colours $c)"; break; fi
    [ -z "$(qpid)" ] && { log "QEMU gone while loading"; break; }
    [ $(($(date +%s) - t1)) -gt 300 ] && { log "no picture in 300 s"; break; }
done
adb shell "run-as $PKG sh -c 'tail -n 6 files/run/app.txt'" | tr -d '\r' | tee -a "$OUT/save.txt"
log "heat $(heat | tr '\n' ' ')"
