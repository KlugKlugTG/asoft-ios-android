#!/bin/bash
# test-release.sh OUTDIR: the signed release build end to end, with the bundle already in the import folder:
# first start opens Set up > Import > Start iOS 16 > lock screen > unlock > 💾 Save and close > open again >
# Start iOS 16 (quick boot). A release build is not debuggable (no run-as): everything is read from the screen
# (uiautomator, screenshots) and ps. Input goes through guarded.sh.
OUT=${1:?output directory}
. "$(dirname "$0")/guarded.sh"
TOP='com[.]asoft[.]ios/[.](MainActivity|SetupActivity|ScreenActivity)'

ui() { adb shell "uiautomator dump /sdcard/ui-asoft.xml >/dev/null && cat /sdcard/ui-asoft.xml" | tr -d '\r'; }
# the centre of the node whose text is $1 (exact first, else the first starting with it)
find_text() {
    ui | py -3.12 -c "
import re, sys
x = sys.stdin.read()
nodes = [m.groups() for m in re.finditer(r'<node [^>]*?text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', x)]
for t, *b in sorted(nodes, key=lambda n: n[0] != sys.argv[1]):
    if t.startswith(sys.argv[1]):
        print((int(b[0]) + int(b[2])) // 2, (int(b[1]) + int(b[3])) // 2)
        break
" "$1"
}
tap_text() {
    wait_top
    local xy
    xy=$(find_text "$1")
    [ -n "$xy" ] || { echo "$(date +%T) no \"$1\" on screen"; return 1; }
    adb shell "input tap $xy"
}
qemu_up() { [ "$(adb shell "ps -A -o args | grep -c '[l]ibqemu.so -M'" | tr -d '\r')" -gt 0 ]; }
# colours in a small copy of a screenshot: the Apple logo on black has few, a lock screen many
colours() { py -3.12 -c "
from PIL import Image
im = Image.open(r'$(cygpath -w "$OUT")/' + '$1.png').convert('RGB').resize((72, 156))
print(len(im.getcolors(99999)))"; }
wait_screen() {     # wait_screen NAME MAX_S: until iOS draws more than its boot logo
    local t0=$(date +%s)
    while :; do
        shot "$1"
        [ -f "$OUT/$1.png" ] && [ "$(colours "$1")" -gt 300 ] && break
        [ $(($(date +%s) - t0)) -gt "$2" ] && { echo "$(date +%T) $1: nothing after $2 s"; return 1; }
        sleep 10
    done
}

adb shell "am start -n com.asoft.ios/.MainActivity" >/dev/null
nap 4
shot r1-first-start
t0=$(date +%s)
tap_text "Import " || exit 1
until [ -n "$(find_text "Start iOS 16 (")" ]; do
    sleep 3
    [ $(($(date +%s) - t0)) -gt 600 ] && { echo "no import in 600 s"; exit 1; }
done
echo "$(date +%T) imported, $(($(date +%s) - t0)) s after Import"
shot r2-set-up
tap_text "Start iOS 16 (" || exit 1
t1=$(date +%s)
nap 60
TOP='com[.]asoft[.]ios/[.]ScreenActivity'
wait_screen r3-lock 600 && echo "$(date +%T) lock screen, $(($(date +%s) - t1)) s after Start"
send swipe 720 3080 720 1500 120
nap 8
shot r4-home
send tap 1393 1735                  # 💾
nap 3
shot r5-saves
TOP='com[.]asoft[.]ios/[.](MainActivity|ScreenActivity)'
tap_text "Save and close" || exit 1
t2=$(date +%s)
while qemu_up; do sleep 1; done
echo "$(date +%T) saved and closed, $(($(date +%s) - t2)) s"
nap 3
adb shell "am start -n com.asoft.ios/.MainActivity" >/dev/null
nap 4
TOP='com[.]asoft[.]ios/[.]MainActivity'
shot r6-home-screen
tap_text "Start iOS 16" || exit 1
t3=$(date +%s)
TOP='com[.]asoft[.]ios/[.]ScreenActivity'
nap 5
wait_screen r7-resumed 120 && echo "$(date +%T) back from the save, $(($(date +%s) - t3)) s after Start"
heat
echo "$(date +%T) done"
