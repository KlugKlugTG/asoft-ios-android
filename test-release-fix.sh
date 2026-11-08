#!/bin/bash
# test-release-fix.sh OUTDIR: the user's iOS 16 in the release app after the 0.1.1.1 fix is installed over 0.1.1.
# 0.1.1's cold boot failed (10:54: a read of the disk failed, launchd found no dyld cache) and its Turn off left a
# quick save it meant to delete. The fixed app reads its old log (the stale quick save goes, unloaded), checks the
# disk once, then boots. A release build has no run-as: the app's log comes from Android/data, the rest from the
# screen. Input goes through guarded.sh. Ends with a Save and close, so the user's next Start is a quick boot.
OUT=${1:?output directory}
. "$(dirname "$0")/guarded.sh"
LOG=/sdcard/Android/data/com.asoft.ios/files/log
log() { echo "$(date +%T) $*" | tee -a "$OUT/fix.txt"; }
applog() { adb shell "cat $LOG/app.txt" | tr -d '\r'; }
TOP='com[.]asoft[.]ios/[.](MainActivity|ScreenActivity)'

log "start: heat $(heat | tr '\n' ' '), free $(adb shell df -h /data | tail -n 1 | awk '{print $4}')"
adb shell "am start -n com.asoft.ios/.MainActivity" >/dev/null
nap 6
TOP='com[.]asoft[.]ios/[.]MainActivity'
shot h1-home
tap_text "Start iOS 16" || exit 1
t1=$(date +%s)
nap 20
applog | tail -n 12 | tee -a "$OUT/fix.txt"
TOP='com[.]asoft[.]ios/[.]ScreenActivity'
wait_screen h2-lock 900 && log "lock screen $(($(date +%s) - t1)) s after Start"
nap 15
shot h3-lock2
send swipe 720 3080 720 1500 120
nap 10
shot h4-home
TOP='com[.]asoft[.]ios/[.]ScreenActivity'
send tap 1393 1735                  # 💾
nap 3
TOP='com[.]asoft[.]ios/[.](MainActivity|ScreenActivity)'
shot h5-saves
tap_text "Save and close" || exit 1
t2=$(date +%s)
while qemu_up; do sleep 1; done
log "saved and closed, $(($(date +%s) - t2)) s"
nap 5
applog | tail -n 8 | tee -a "$OUT/fix.txt"
adb shell "cat $LOG/last-serial.txt" | tr -d '\r' > "$OUT/serial-tail.txt"
adb shell "cat $LOG/last-qemu-stderr.txt" | tr -d '\r' > "$OUT/qemu-stderr-tail.txt"
log "VBLK failures in the boot's serial tail: $(grep -c 'SWEET VBLK' "$OUT/serial-tail.txt"), I/O errors $(grep -c 'I/O error' "$OUT/serial-tail.txt")"
log "end: heat $(heat | tr '\n' ' ')"
