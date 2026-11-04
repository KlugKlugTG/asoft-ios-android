# guarded.sh: source it, with OUT set. Input and screenshots go to the phone only while the app's
# screen is on top and the phone is unlocked: everything else waits. Another app is never touched,
# and its screen is never captured.
export MSYS_NO_PATHCONV=1
mkdir -p "$OUT"

# TOP: which of the app's screens input may go to (an awk regex on the activity), the iOS screen by default
TOP=${TOP:-'com[.]asoft[.]ios/[.]ScreenActivity'}

on_top() {
    adb shell "dumpsys activity activities | grep -m1 topResumedActivity; dumpsys window | grep -m1 isKeyguardShowing" 2>/dev/null |
        tr -d '\r' | awk -v top="$TOP" '/topResumedActivity/ { app = $0 ~ top } /isKeyguardShowing=true/ { lock = 1 } END { exit !(app && !lock) }'
}

wait_top() {
    until on_top; do
        [ "${said:-}" ] || { echo "$(date +%T) waiting for the app's screen on top"; said=1; }
        sleep 3
    done
    said=
}

# SLOW=3: the same steps with three times the waits, for a guest under TCG
nap() { sleep $(($1 * ${SLOW:-1})); }

send() { wait_top; adb shell "input $*"; }

shot() {
    wait_top
    adb exec-out screencap -p > "$OUT/$1.png"
    on_top || { rm -f "$OUT/$1.png"; echo "$(date +%T) $1: left the app during the shot, dropped"; }
}

running() { [ "$(adb shell "ps -A -o args | grep -c '[l]ibqemu.so -M'" | tr -d '\r')" -gt 0 ]; }

# the log is overwritten at every start: only trust it while this QEMU lives
wait_boot() {
    until running && [ "$(adb shell "run-as com.asoft.ios grep -c 'el-sample t=${1:-270}s all' files/run/qemu-stderr.txt" 2>/dev/null | tr -d '\r')" = 1 ]; do
        sleep 10
    done
    echo "$(date +%T) booted"
}

# a tap at x, y of a 540x1200 screenshot of the guest: the guest is 1404 px wide on this phone, centred
guest_tap() { send tap $((18 + $1 * 1404 / 540)) $(($2 * 3120 / 1200)); }

# iOS's own keyboard (Safari's URL layout), same coordinates
key_xy() {
    case "$1" in
        q) echo 17 815;; w) echo 72 815;; e) echo 129 815;; r) echo 185 815;; t) echo 241 815;;
        y) echo 297 815;; u) echo 354 815;; i) echo 411 815;; o) echo 466 815;; p) echo 522 815;;
        a) echo 44 897;; s) echo 100 897;; d) echo 157 897;; f) echo 213 897;; g) echo 270 897;;
        h) echo 327 897;; j) echo 382 897;; k) echo 439 897;; l) echo 495 897;;
        z) echo 100 978;; x) echo 157 978;; c) echo 213 978;; v) echo 270 978;; b) echo 327 978;;
        n) echo 382 978;; m) echo 439 978;; .) echo 417 1058;;
    esac
}

type_ios() {
    local text=$1 i
    for ((i = 0; i < ${#text}; i++)); do
        guest_tap $(key_xy "${text:i:1}")
        nap 1
    done
}

# the guest's kernel log and the host's report: process names and addresses only
crashes() {
    adb shell "run-as com.asoft.ios sh -c 'grep -aE \"CRASH .* sig\" files/run/serial.txt | cut -c1-80; grep -aE \"host signal|died\" files/run/qemu-stderr.txt | cut -c1-200 | head -n 3'" | tr -d '\r'
}

heat() {
    adb shell "dumpsys battery | grep -E '^  temperature'; for z in /sys/class/thermal/thermal_zone*; do case \$(cat \$z/type) in cpu-*-*) cat \$z/temp;; esac; done | sort -n | tail -n 1" | tr -d '\r'
}

# the guest's and QEMU's logs, before a restart overwrites them
save_logs() {
    adb shell "run-as com.asoft.ios cat files/run/serial.txt" > "$OUT/serial.txt"
    adb shell "run-as com.asoft.ios cat files/run/qemu-stderr.txt" > "$OUT/qemu-stderr.txt"
}

# -- reading the app's own screens (works on a release build too: no run-as)
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
