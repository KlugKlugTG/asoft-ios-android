#!/bin/bash
# test-cpus.sh OUTDIR PLAN [max_min]: one cold iOS 16 boot with a CPU plan, timed from Start to the
# lock screen (a guarded screenshot every 15 s; the boot logo has ~100 colours, the lock screen 300+).
# PLAN: "auto" (CpuPlan's own choice) or cpu.txt's words, e.g. "smp=2 pin=6,7", "smp=4 pin=".
# The app is force-stopped first (iOS goes down without a save). Refuses when a quick-boot state
# would make the start a load. Set ANDROID_SERIAL.
OUT=${1:?output directory}
PLAN=${2:?plan}
MAX=${3:-30}
. "$(dirname "$0")/guarded.sh"
EXT=/sdcard/Android/data/com.asoft.ios/files
TOP='com[.]asoft[.]ios/[.]MainActivity'
adb shell am force-stop com.asoft.ios
if [ "$PLAN" = auto ]; then
    adb shell rm -f $EXT/cpu.txt
else
    adb shell "echo '$PLAN' > $EXT/cpu.txt"
fi
adb shell "am start -n com.asoft.ios/.MainActivity" >/dev/null
nap 5
[ -n "$(find_text "Goes on from where")" ] && { echo "a quick-boot state is there: this would be a load"; exit 1; }
clocks() {
    adb shell 'echo "power saving $(settings get global low_power);"; for c in /sys/devices/system/cpu/cpu[0-9]*; do
        echo "$(basename $c) $(($(cat $c/cpufreq/scaling_max_freq) / 1000))/$(($(cat $c/cpufreq/cpuinfo_max_freq) / 1000))"; done' |
        tr -d '\r' | tr '\n' ' '
}
echo "$(date +%T) $(clocks)"
tap_text "Start iOS 16" || exit 1
nap 2
[ -n "$(find_text "Start anyway")" ] && { echo "power saving prompt shown: Start anyway"; tap_text "Start anyway"; }
t0=$(date +%s)
echo "$(date +%T) started, plan: $PLAN"
TOP='com[.]asoft[.]ios/[.]ScreenActivity'
threads() {
    adb shell 'p=$(ps -A -o PID,ARGS | grep "[l]ibqemu.so -M" | head -n 1 | awk "{print \$1}"); [ -n "$p" ] &&
        ps -T -p $p -o PSR,PCPU,CMD | grep "CPU [0-9]/TCG" | awk "{printf \"%s@cpu%s %s%%  \", \$4, \$1, \$2}"' | tr -d '\r'
}
i=0
while :; do
    sleep 15
    i=$((i + 1))
    n=$(printf 's%03d' $i)
    shot "$n"
    s=$(($(date +%s) - t0))
    if [ -f "$OUT/$n.png" ] && [ "$(colours "$n")" -gt 300 ]; then
        echo "$(date +%T) lock screen at $s s"
        break
    fi
    if [ $((i % 8)) = 0 ]; then
        a=$(adb shell "grep MemAvailable /proc/meminfo" | tr -d '\r' | awk '{print int($2/1024)}')
        echo "$(date +%T) +$s s: MemAvailable $a MB; vCPUs $(threads)"
        [ $((i % 32)) = 0 ] && echo "$(date +%T) $(clocks)"
    fi
    qemu_up || { echo "$(date +%T) QEMU gone at $s s"; break; }
    [ $s -gt $((MAX * 60)) ] && { echo "$(date +%T) no lock screen in $MAX min"; break; }
done
echo "vCPUs at the end: $(threads)"
echo "--- app log"
adb shell "cat $EXT/log/app.txt" 2>/dev/null | tr -d '\r' | tail -n 4
heat
