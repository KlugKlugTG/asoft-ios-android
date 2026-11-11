#!/bin/bash
# test-ios26-ab.sh OUTDIR [ios26.txt line ...]: one arm of an A/B on iOS 26 in the dev app: the user's quick save
# loaded (test-ios26-peaks.sh quick: one unlock, then rounds of six apps, ended with the app's off, so the save stays
# as it was), with reims' frame stamps (ASOFT_LAT=1) and the given ios26.txt lines (adpf=1, ...). Beside it once a
# second the GPU's clock and busy counters and the big and little cores' clocks. settle-report.py turns the stamps
# into first-reaction and settle times per input; gpu.txt sums the GPU clocks over the rounds.
OUT=${1:?output directory}
shift
PKG=com.asoft.ios.dev
HERE=$(cd "$(dirname "$0")" && pwd)
export MSYS_NO_PATHCONV=1
EXT=/sdcard/Android/data/$PKG/files
mkdir -p "$OUT"
# the same start for every arm: the hottest CPU zone below COOL °C and the battery below BATT °C (at most 15 min)
hot() { adb shell "for z in /sys/class/thermal/thermal_zone*; do case \$(cat \$z/type) in cpu-*-*) cat \$z/temp;; esac; done | sort -n | tail -n 1" | tr -d '\r'; }
batt() { adb shell "dumpsys battery | grep -m1 '^  temperature'" | tr -d '\r' | awk '{print $2}'; }
T=$(date +%s)
while { [ "$(hot)" -gt $((${COOL:-55} * 1000)) ] || [ "$(batt)" -gt $((${BATT:-37} * 10)) ]; } && [ $(($(date +%s) - T)) -lt 900 ]; do sleep 20; done
echo "cooled $(($(date +%s) - T)) s: hottest CPU $(hot), battery $(batt)" > "$OUT/cool.txt"
# autosave-maps high: AutoSave's guard must not save a test's state over the user's quick save
printf '%s\n' "env.ASOFT_LAT=1" "autosave-maps=999999" "$@" > "$OUT/ios26.in"
adb push "$(cygpath -w "$OUT/ios26.in")" "$EXT/ios26.txt" >/dev/null
adb shell 'nohup sh -c "end=\$((\$(date +%s) + 1200)); while [ \$(date +%s) -lt \$end ]; do echo \"\$(date +%s) \$(cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq) \$(cat /sys/class/kgsl/kgsl-3d0/gpubusy) \$(cat /sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq) \$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq)\"; sleep 1; done" > /data/local/tmp/asoft/gpu-ab.txt 2>&1 &'
bash "$HERE/test-ios26-peaks.sh" "$OUT" quick 30 "${ROUNDS:-2}" > "$OUT/run-peaks.log" 2>&1
adb shell "pkill -f 'kgsl-3d0/devfreq/cur_freq' ; true" >/dev/null 2>&1
adb shell "cat /data/local/tmp/asoft/gpu-ab.txt" | tr -d '\r' > "$OUT/gpu-sample.txt"
adb shell "rm -f $EXT/ios26.txt"
grep -a "adpf\|auto save" "$OUT/app.txt" | tail -n 4 > "$OUT/adpf.txt"
py -3.12 "$(cygpath -w "$HERE/settle-report.py")" "$(cygpath -w "$OUT")" > "$OUT/report.txt" 2>&1
# GPU over the rounds (marks: start .. end, on the boot clock; the samples carry wall time: the rounds' wall span from peaks.txt)
S=$(grep -m1 "unlocked" "$OUT/peaks.txt" | awk '{print $1}')
E=$(grep -m1 "60 s after the rounds" "$OUT/peaks.txt" | awk '{print $1}')
py -3.12 - "$(cygpath -w "$OUT/gpu-sample.txt")" "$S" "$E" > "$OUT/gpu.txt" <<'PY'
import sys, time, collections
path, s, e = sys.argv[1:4]
day = time.strftime('%Y-%m-%d')
t0 = time.mktime(time.strptime(day + ' ' + s, '%Y-%m-%d %H:%M:%S'))
t1 = time.mktime(time.strptime(day + ' ' + e, '%Y-%m-%d %H:%M:%S'))
fr, busy, tot, big, little, n = collections.Counter(), 0, 0, [], [], 0
for l in open(path):
    p = l.split()
    if len(p) < 6 or not p[0].isdigit():
        continue
    t = int(p[0])
    if not t0 <= t <= t1:
        continue
    n += 1
    fr[int(p[1]) // 1000000] += 1
    busy += int(p[2]); tot += int(p[3])
    big.append(int(p[4]) / 1e6); little.append(int(p[5]) / 1e6)
if n:
    print('%d samples; GPU busy %.1f%%; GPU MHz: %s; big core avg %.2f GHz, little %.2f GHz' % (
        n, 100.0 * busy / tot if tot else 0, ', '.join('%d x%d' % kv for kv in sorted(fr.items())),
        sum(big) / n, sum(little) / n))
else:
    print('no samples in %s .. %s' % (s, e))
PY
cat "$OUT/gpu.txt" "$OUT/adpf.txt"
