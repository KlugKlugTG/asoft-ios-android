#!/bin/bash
# test-ios26-chunk-ab.sh OUTDIR [ARMS="4 16 2"] [WAIT_S=200] [ROUNDS=2]: metal2vulkan's gather chunk size
# (an arm "N" is N MiB, METAL2VULKAN_GATHER_CHUNK_MIB, 16 the default; "Nk" N KiB, METAL2VULKAN_GATHER_CHUNK_KIB)
# against memory and speed, in the dev app: per arm a cold boot of its iOS 26 with that chunk size (ios26.txt; AutoSave
# off, so nothing is saved; with FRESH=1, the default, on a new user disk over the base each time, so every arm starts
# alike), test-ios26-peaks.sh's rounds of six apps, and the app's off. Beside each arm: MemAvailable and SwapFree every
# 15 s, the arm's lmkd kills and low-memory events (logcat's events buffer since the arm's start), the gather lines of
# its qemu-stderr (chunk MiB live, imports and binds and their ms) with any device loss, and with LAT=1 (the default)
# settle-report.py's reaction times per input (settle.txt). HOME_XY is passed on to the peaks test (the side bar's
# Home). summary.txt in OUTDIR has a line per arm; chunk-ab-report.py OUTDIR compares them.
OUT=${1:?output directory}
ARMS=${2:-4 16 2}
WAIT=${3:-200}
ROUNDS=${4:-2}
PKG=com.asoft.ios.dev
HERE=$(cd "$(dirname "$0")" && pwd)
export ANDROID_SERIAL=${ANDROID_SERIAL:-R5GYB4V4PKL} MSYS_NO_PATHCONV=1
EXT=/sdcard/Android/data/$PKG/files
mkdir -p "$OUT"
# ios26.txt written as the app (a file adb pushes is the shell's, which the app cannot read there)
put_txt() { adb shell "run-as $PKG sh -c 'cat > $EXT/ios26.txt'" < "$1"; }
# the test's own user disk: the app makes a new one over the base at the next start
new_disk() { adb shell "run-as $PKG sh -c 'rm -f files/ios26-user.qcow2 files/ios26-user.*'"; }
qpid() { adb shell "ps -A -o PID,ARGS | grep '[l]ibqemu26.so -name' | awk '{print \$1}'" | tr -d '\r' | head -n 1; }
[ -z "$(qpid)" ] || { echo "an iOS 26 QEMU runs already: one at a time"; exit 1; }
# the caller's ios26.txt, put back at the end
adb shell "run-as $PKG sh -c 'test -f $EXT/ios26.txt && cat $EXT/ios26.txt'" | tr -d '\r' > "$OUT/ios26.before"
for arm in $ARMS; do
    A="$OUT/chunk-$arm"
    mkdir -p "$A"
    {
        printf 'autosave-maps=999999\nautosave-settle-s=999999\n'
        case "$arm" in
            *k) printf 'env.METAL2VULKAN_GATHER_CHUNK_KIB=%s\n' "${arm%k}" ;;
            16) ;;
            *) printf 'env.METAL2VULKAN_GATHER_CHUNK_MIB=%s\n' "$arm" ;;
        esac
        [ "${LAT:-1}" = 1 ] && printf 'env.ASOFT_LAT=1\n'
    } > "$A/ios26.in"
    [ "${FRESH:-1}" = 1 ] && new_disk
    put_txt "$A/ios26.in"
    since=$(adb shell "date +'%m-%d %H:%M:%S.000'" | tr -d '\r')
    echo "$since" > "$A/since.txt"
    # memory every 15 s until the arm ends
    (
        while [ ! -f "$A/done" ]; do
            echo "$(date +%T) $(adb shell "grep -E 'MemAvailable|SwapFree' /proc/meminfo" | tr -d '\r' | awk '{printf "%s %d ", $1, $2/1024}')"
            sleep 15
        done
    ) > "$A/mem.txt" &
    sampler=$!
    # a guest that dies mid-arm leaves the peaks test waiting for the app's screen: 25 min at most, then the app's off
    timeout 1500 bash "$HERE/test-ios26-peaks.sh" "$A" cold "$WAIT" "$ROUNDS" > "$A/run.log" 2>&1
    if [ -n "$(qpid)" ]; then
        for f in qemu-stderr serial app; do
            adb shell "run-as $PKG sh -c 'cat files/run/$f.txt'" | tr -d '\r' > "$A/$f.txt"
        done
        adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService -a off" >/dev/null
        sleep 10
    fi
    touch "$A/done"
    wait "$sampler" 2>/dev/null
    adb logcat -b events -d -T "$since" | tr -d '\r' > "$A/events.txt"
    kills=$(grep -cE 'am_kill|am_proc_died' "$A/events.txt")
    low=$(grep -c 'am_low_memory' "$A/events.txt")
    g=$(grep -a 'gathered:' "$A/qemu-stderr.txt" | tail -n 1 | grep -o 'gathered: .* ms)')
    most=$(grep -ao '[0-9]* chunks of [0-9]* MiB live' "$A/qemu-stderr.txt" | awk '{if ($4 > m) m = $4} END {print m + 0}')
    lost=$(grep -ac 'lost the device\|device was lost' "$A/qemu-stderr.txt")
    retry=$(adb logcat -d -T "$since" | grep -c 'bind submit out of memory, retry')
    died=$(adb logcat -d -T "$since" | grep -c 'PhantomProcessRecord.*libqemu26.so.* died')
    avail=$(awk '{for (i = 1; i < NF; i++) if ($i == "MemAvailable:") print $(i + 1)}' "$A/mem.txt" |
        sort -n | awk 'NR == 1 {lo = $1} {s += $1; n++} END {printf "MemAvailable min %d mean %d MB", lo, n ? s / n : 0}')
    [ "${LAT:-1}" = 1 ] && py -3.12 "$(cygpath -w "$HERE/settle-report.py")" "$(cygpath -w "$A")" > "$A/settle.txt" 2>&1
    echo "chunk $arm: chunks live at most $most MiB; last $g; $avail; kills $kills, low-memory $low; device lost $lost, bind retries $retry, QEMU died $died" |
        tee -a "$OUT/summary.txt"
    sleep 30
done
if [ -s "$OUT/ios26.before" ]; then
    put_txt "$OUT/ios26.before"
else
    adb shell "run-as $PKG rm -f $EXT/ios26.txt"
fi
[ "${FRESH:-1}" = 1 ] && new_disk
echo "done; ios26.txt as it was"
