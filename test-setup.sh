#!/bin/bash
# test-setup.sh OUTDIR: Set up with the bundle already pushed to the import folder (pc/Send-ToPhone.ps1):
# main screen > Set up > Import > Replace > wait > Start iOS 16 > first boot. Input goes through guarded.sh
# (here: only while one of the app's own screens is on top). Tap positions are this phone's (1440 x 3120).
OUT=${1:?output directory}
. "$(dirname "$0")/guarded.sh"

# one of the app's screens; the coordinates of a button come from its text (uiautomator dump)
TOP='com[.]asoft[.]ios/[.](MainActivity|SetupActivity|ScreenActivity)'
tap_text() {
    wait_top
    local xy
    xy=$(adb shell "uiautomator dump /sdcard/ui-asoft.xml >/dev/null && cat /sdcard/ui-asoft.xml" | tr -d '\r' |
        py -3.12 -c "
import re, sys
x = sys.stdin.read()
nodes = [m.groups() for m in re.finditer(r'<node [^>]*?text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', x)]
# the exact text first (a button), else the first that starts with it
for t, *b in sorted(nodes, key=lambda n: n[0] != sys.argv[1]):
    if t.startswith(sys.argv[1]):
        print((int(b[0]) + int(b[2])) // 2, (int(b[1]) + int(b[3])) // 2)
        break
" "$1")
    [ -n "$xy" ] || { echo "$(date +%T) no button \"$1\""; return 1; }
    adb shell "input tap $xy"
}

applog() { adb shell "run-as com.asoft.ios cat files/run/app.txt" 2>/dev/null | tr -d '\r'; }

adb shell "am start -n com.asoft.ios/.MainActivity" >/dev/null
nap 3
tap_text "More" || exit 1
nap 1
tap_text "Set up iOS 16" || exit 1
nap 3
shot s1-setup
t0=$(date +%s)
tap_text "Import " || exit 1
nap 2
shot s2-confirm
tap_text "Replace"                  # only when there was something to replace
until adb shell "run-as com.asoft.ios ls files/ios16/payload.bin" >/dev/null 2>&1 && ! adb shell "run-as com.asoft.ios ls files/ios16.part" >/dev/null 2>&1; do
    sleep 5
    [ $(($(date +%s) - t0)) -gt 1800 ] && { echo "no import in 30 min"; break; }
done
echo "$(date +%T) imported in $(($(date +%s) - t0)) s"
nap 2
shot s3-done
adb shell "run-as com.asoft.ios ls -la files/ios16" | tr -d '\r'
adb shell "ls -la /sdcard/Android/data/com.asoft.ios/files/import/; df -h /data | tail -n 1" | tr -d '\r'
tap_text "Start iOS 16" || exit 1
t1=$(date +%s)
TOP='com[.]asoft[.]ios/[.]ScreenActivity'
wait_boot 270
echo "$(date +%T) booted, $(($(date +%s) - t1)) s after Start"
adb shell "run-as com.asoft.ios cat files/run/cmdline.txt" | tr -d '\r' | tr ' ' '\n' | grep -E "ios16|loader,file"
shot s4-boot
crashes | cut -c1-80
heat
save_logs
echo "$(date +%T) done"
