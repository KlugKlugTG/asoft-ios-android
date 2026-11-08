#!/bin/bash
# test-a26-use.sh OS OUT [PKG]: after Set up installed it from Apple (PhoneSetup26), start iOS 26 (OS=ios26) or the
# watch (OS=watch) from the home screen, wait for its screen, use it (watch: side button, crown, swipe; iOS 26:
# side button presses, Home), Save and close, wait 3 min, start it again (quick boot) and check it comes back.
# Shots and the log lines into OUT. Guarded input only (guarded.sh), bar buttons from the window (bar-xy.sh).
set -u
OS=$1 OUT=$2 PKG=${3:-com.asoft.ios.dev}
export ANDROID_SERIAL=${ANDROID_SERIAL:-R5GYB4V4PKL} MSYS_NO_PATHCONV=1 OUT
cd "$(dirname "$0")"
. ./guarded.sh
. tools/bar-xy.sh
P=${PKG//./[.]}
LOG=files/run/app.txt
applog() { adb shell "run-as $PKG cat $LOG" | tr -d '\r'; }
qemus() { adb shell "ps -A -o args | grep -c '[l]ibqemu26'" | tr -d '\r'; }
mean() { python -I -c "
from PIL import Image, ImageStat; im=Image.open(r'$(cygpath -w "$OUT/$1.png")').convert('L').crop((150,500,1300,2600)); print(round(ImageStat.Stat(im).mean[0]))"; }
cols() {    # iOS 26: colours on the whole screen; watch: saturated colours inside the watch's screen only (the drawn
            # case and band have plenty of colours of their own; the logo has 0 saturated ones, the face ~250)
    if [ "$OS" = watch ]; then python -I -c "
from PIL import Image; im=Image.open(r'$(cygpath -w "$OUT/$1.png")').convert('RGB').crop((130,920,1180,2210)).resize((96,118))
print(sum(1 for c,(r,g,b) in im.getcolors(99999) if max(r,g,b)-min(r,g,b)>40))"
    else python -I -c "
from PIL import Image; im=Image.open(r'$(cygpath -w "$OUT/$1.png")').convert('RGB').resize((72,156)); print(len(im.getcolors(99999)))"
    fi; }
UPMIN=120; [ "$OS" = watch ] && UPMIN=20
name=$([ "$OS" = watch ] && echo watchOS || echo "iOS 26")

start() {   # start TAG: from the home screen, until the guest draws more than its logo (or 25 min)
    adb shell "am start -n $PKG/com.asoft.ios.MainActivity" > /dev/null 2>&1
    TOP="$P/com[.]asoft[.]ios[.]MainActivity"; nap 3
    local n0; n0=$(applog | wc -l)
    tap_text "Start $name" || { echo "no Start $name"; exit 1; }
    TOP="$P/com[.]asoft[.]ios[.]ScreenActivity"
    local t0; t0=$(date +%s)
    until applog | tail -n +$((n0 + 1)) | grep -qE "resumed in|-> boot|did not load|not loading"; do
        sleep 2; [ $(($(date +%s) - t0)) -lt 180 ] || { echo "no start line in 180 s"; exit 1; }
    done
    applog | tail -n +$((n0 + 1)) | grep -E "start $name|start watchOS|start iOS 26|resumed in|boot|not load|did not|device tree"
    while :; do
        sleep 15; shot "$1"
        [ -f "$OUT/$1.png" ] && [ "$(cols "$1")" -gt $UPMIN ] && break
        [ "$(qemus)" -gt 0 ] || { echo "$(date +%T) QEMU ended"; applog | tail -n 5; exit 2; }
        [ $(($(date +%s) - t0)) -lt 1500 ] || { echo "$(date +%T) $1: only the logo after 25 min"; exit 1; }
    done
    echo "$(date +%T) $1: up after $(($(date +%s) - t0)) s (colours $(cols "$1"), mean $(mean "$1"))"
}

step() {    # step NAME input-args...: one input, 6 s, QEMU still there?, a shot
    local n=$1; shift
    send "$@"; sleep 6
    [ "$(qemus)" -gt 0 ] || { echo "$(date +%T) QEMU ENDED after $n"; applog | tail -n 5; exit 2; }
    shot "$n"; echo "$(date +%T) $n: mean $(mean "$n")"
}

close() {   # Save and close from the side bar, until QEMU is gone
    local s; s=$(bar_xy Saves) || { echo "no Saves on the bar"; exit 1; }
    send tap $s; nap 2
    tap_text "Save and close" || { echo "no Save and close"; exit 1; }
    local t0; t0=$(date +%s)
    while [ "$(qemus)" -gt 0 ] && [ $(($(date +%s) - t0)) -lt 300 ]; do sleep 2; done
    echo "$(date +%T) closed after $(($(date +%s) - t0)) s: $(applog | grep -E 'save' | tail -n 1)"
}

start u00-first
if [ "$OS" = watch ]; then
    CROWN="1271 1334"; SIDE="1256 1700"; MX=660; TY=960; MY=1560; BY=2160
    step u01-side tap $SIDE
    step u02-side-again tap $SIDE
    step u03-crown tap $CROWN
    step u04-crown-again tap $CROWN
    step u05-swipe-down swipe $MX $TY $MX $MY 300
    step u06-swipe-up swipe $MX $BY $MX $MY 300
    step u07-crown tap $CROWN
else
    pxy=$(bar_xy "Side button"); hxy=$(bar_xy Home)
    [ -n "$pxy" ] && [ -n "$hxy" ] || { echo "no side bar"; exit 1; }
    for i in 1 2 3 4; do step "u0$i-side" tap $pxy; sleep 4; done
    step u05-home tap $hxy
    step u06-up swipe 700 2900 700 1500 250
fi
close
echo "$(date +%T) waiting 3 min before the quick boot"
sleep 180
start u10-resumed
applog | grep -E "resumed in|clock|catch" | tail -n 4
shot u11-after
echo "$(date +%T) after the resume: mean $(mean u11-after)"
close
echo "test-a26-use $OS done"
