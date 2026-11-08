#!/bin/bash
# test-release-smoke.sh OUTDIR: a short check of an installed release build (com.asoft.ios) on the S25.
# iOS 26 and then iOS 16, each from its quick save: Start, wait for "resumed", Home, Settings, Home, Save and close.
# The side bar must be open; its Home and Saves buttons are found by their content descriptions in the app's
# window (uiautomator), since the user drags the bar and fixed rows tapped iOS instead (a screenshot finder took
# iOS 16's battery icon for the bar's first icon); without a bar the run stops untouched. Input waits for the app
# on top and the phone unlocked (guarded.sh); a release build has no run-as, so the app's log is read from
# Android/data. Ends with both iOS versions saved and closed.
OUT=${1:?output directory}
. "$(dirname "$0")/guarded.sh"
LOG=/sdcard/Android/data/com.asoft.ios/files/log
log() { echo "$(date +%T) $*" | tee -a "$OUT/smoke.txt"; }
applog() { adb shell "cat $LOG/app.txt" | tr -d '\r'; }
qemus() { adb shell "ps -A -o ARGS | grep -c '[l]ibqemu'" | tr -d '\r'; }
. "$(dirname "$0")/tools/bar-xy.sh"
mean() { python -c "
from PIL import Image, ImageStat; im=Image.open(r'$(cygpath -w "$OUT/$1.png")').convert('L').crop((0,300,1300,2900)); print(round(ImageStat.Stat(im).mean[0]))"; }

run_one() {     # run_one 16|26
    local os=$1 n0 t0
    adb shell "am start -n com.asoft.ios/.MainActivity" >/dev/null
    TOP='com[.]asoft[.]ios/[.]MainActivity'
    nap 3
    shot "m$os"
    n0=$(applog | wc -l)
    tap_text "Start iOS $os" || { log "iOS $os: no Start button"; return 1; }
    t0=$(date +%s)
    until applog | tail -n +$((n0 + 1)) | grep -q -E "resumed in|boot$"; do
        sleep 2
        [ $(($(date +%s) - t0)) -lt 120 ] || { log "iOS $os: no start line in 120 s"; break; }
    done
    applog | tail -n +$((n0 + 1)) | tee -a "$OUT/smoke.txt"
    # this start kept the run before it (a watch's, say) as last-qemu-stderr.txt
    [ "$os" = 26 ] && adb shell "cat $LOG/last-qemu-stderr.txt" > "$OUT/before26-qemu-stderr.txt"
    TOP='com[.]asoft[.]ios/[.]ScreenActivity'
    nap 25
    shot "i$os-1"; log "iOS $os resumed: screen mean $(mean "i$os-1"), QEMU threads $(qemus)"
    local home saves
    home=$(bar_xy Home); saves=$(bar_xy Saves)
    [ -n "$home" ] && [ -n "$saves" ] || { log "iOS $os: no side bar on screen (closed?), stopping with iOS running"; return 1; }
    log "iOS $os side bar: Home $home, Saves $saves"
    send tap $home; nap 4
    # Settings: first icon of iOS 26's grid; second row, first column on iOS 16's home screen
    if [ "$os" = 26 ]; then send tap 216 410; else send tap 209 765; fi
    nap 12
    shot "i$os-settings"; log "iOS $os Settings: mean $(mean "i$os-settings")"
    send tap $home; nap 6
    shot "i$os-home"; log "iOS $os Home: mean $(mean "i$os-home"), QEMU threads $(qemus)"
    send tap $saves; nap 2
    tap_text "Save and close" || { log "iOS $os: no Save and close"; return 1; }
    t0=$(date +%s)
    while [ "$(qemus)" -gt 0 ] && [ $(($(date +%s) - t0)) -lt 300 ]; do sleep 2; done
    nap 3
    log "iOS $os closed after $(($(date +%s) - t0)) s: $(applog | grep 'save:' | tail -n 1)"
}

log "start: $(adb shell dumpsys package com.asoft.ios | grep -m1 versionName | tr -d ' \r'), free $(adb shell df -h /data | tail -n 1 | awk '{print $4}')"
run_one 26 || { log "stopped after iOS 26"; exit 1; }
run_one 16
# the app keeps a run's stderr as last-qemu-stderr.txt when the next QEMU starts: iOS 26's is there once iOS 16 started
adb shell "cat $LOG/last-qemu-stderr.txt" > "$OUT/i26-qemu-stderr.txt"
log "done: free $(adb shell df -h /data | tail -n 1 | awk '{print $4}')"
