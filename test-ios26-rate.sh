#!/bin/bash
# test-ios26-rate.sh OUTDIR RATE...: iOS 26 (running in the dev app, ASOFT_LAT=1) — for each RATE (moves a second; "app" = the
# app's own path through `input swipe`, ~120/s): Home, Settings, then a 3 s drag up and, 6 s later, a 3 s drag down in its
# list; counts the frames iOS presents during and after each drag (QEMU's "LAT present" stamps). Screenshots after each drag.
OUT=${1:?output directory}; shift
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/guarded.sh"
PKG=com.asoft.ios.dev
TOP='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]ScreenActivity'
export MSYS_NO_PATHCONV=1
q() { WAIT=1 bash "$HERE/qmp26.sh" "{\"execute\":\"qom-set\",\"arguments\":{\"path\":\"/machine/peripheral/ucons\",\"property\":\"$1\",\"value\":\"$2\"}}" >/dev/null 2>&1; }
up() { adb shell cat /proc/uptime | awk '{print $1}' | tr -d '\r'; }
drag() {     # RATE Y0 Y1 (0..32767)
    if [ "$1" = app ]; then
        send swipe 720 $(($2 * 3120 / 32767)) 720 $(($3 * 3120 / 32767)) 3000
    else
        wait_top; adb shell "run-as $PKG sh /data/local/tmp/asoft/drag-rate.sh $1 $2 $3 3" >/dev/null 2>&1
    fi
}
adb push "$(cygpath -w "$HERE/tools/drag-rate.sh")" /data/local/tmp/asoft/drag-rate.sh >/dev/null && adb shell chmod a+r /data/local/tmp/asoft/drag-rate.sh
for R in "$@"; do
    led=$(adb shell "run-as $PKG od -An -tu1 -N1 files/run/leds" | tr -d ' \r')
    [ $(( ${led:-0} & 1 )) -ne 0 ] && { q press home; sleep 4; }
    q press home; sleep 4; q press home; sleep 4
    send tap 221 409; sleep 7
    echo "$R up $(up)" >> "$OUT/rate-marks.txt"; drag "$R" 26000 10000; echo "$R up-end $(up)" >> "$OUT/rate-marks.txt"
    sleep 6; shot "rate-$R-a"
    echo "$R down $(up)" >> "$OUT/rate-marks.txt"; drag "$R" 10000 26000; echo "$R down-end $(up)" >> "$OUT/rate-marks.txt"
    sleep 6; shot "rate-$R-b"
done
adb shell "run-as $PKG cat files/run/qemu-stderr.txt" | tr -d '\r' > "$OUT/qemu-stderr.txt"
py -3.12 - "$(cygpath -w "$OUT")" <<'PY'
import sys, os
d = sys.argv[1]
off, pres, edges = None, [], []
for l in open(os.path.join(d, 'qemu-stderr.txt'), errors='replace'):
    p = l.split()
    if l.startswith('LAT clocks'):
        off = (int(p[5]) - int(p[3])) / 1e9
    elif l.startswith('LAT present'):
        pres.append(int(p[2]) / 1e9)
    elif l.startswith('LAT report'):
        edges.append((int(p[2]) / 1e9, p[3]))
m = [l.split() for l in open(os.path.join(d, 'rate-marks.txt'))]
for i in range(0, len(m) - 1, 2):
    r, what, t0 = m[i][0], m[i][1], float(m[i][2]) - off
    t1 = float(m[i + 1][2]) - off
    reps = [t for t, s in edges if t0 <= t <= t1 + 0.5]
    dn = [t for t, s in edges if t0 <= t <= t1 + 0.5 and s == 'tip=1'][:1]
    upt = [t for t, s in edges if t0 <= t <= t1 + 0.5 and s == 'tip=0'][-1:]
    if not dn or not upt:
        print('%s %s: no drag seen' % (r, what)); continue
    a, b = dn[0], upt[0]
    during = [t for t in pres if a <= t <= b]
    first = [t for t in pres if t > a][:1]
    after = [t for t in pres if b < t <= b + 6]
    print('rate %-4s drag %-4s: %d guest reads over %.2f s; frames during the drag %d (%.1f/s), first %.2f s after the down; in 6 s after %d' % (
        r, what, len(reps), b - a, len(during), len(during) / (b - a), (first[0] - a) if first else -1, len(after)))
PY
