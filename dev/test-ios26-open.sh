#!/bin/bash
# test-ios26-open.sh OUTDIR [ios26.txt line ...]: iOS 26 from the user's quick save, woken and unlocked, for steps done by
# hand afterwards (tap.sh, shots), with reims' frame stamps and AutoSave's guard off (no test state over the user's save);
# the GPU and core clocks sampled once a second. End it with the app's off (GuestService -a off): no save.
OUT=${1:?output directory}
shift
PKG=com.asoft.ios.dev
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/guarded.sh"
SCREEN='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]ScreenActivity'
EXT=/sdcard/Android/data/$PKG/files
qpid() { adb shell "ps -A -o PID,ARGS | grep '[l]ibqemu26.so -name' | awk '{print \$1}'" | tr -d '\r' | head -n 1; }
rs() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }
press() { WAIT=1 bash "$HERE/qmp26.sh" "{\"execute\":\"qom-set\",\"arguments\":{\"path\":\"/machine/peripheral/ucons\",\"property\":\"press\",\"value\":\"$1\"}}" >/dev/null; }
[ -z "$(qpid)" ] || { echo "a QEMU of the dev app runs already"; exit 1; }
hot() { adb shell "for z in /sys/class/thermal/thermal_zone*; do case \$(cat \$z/type) in cpu-*-*) cat \$z/temp;; esac; done | sort -n | tail -n 1" | tr -d '\r'; }
batt() { adb shell "dumpsys battery | grep -m1 '^  temperature'" | tr -d '\r' | awk '{print $2}'; }
T=$(date +%s)
while { [ "$(hot)" -gt $((${COOL:-55} * 1000)) ] || [ "$(batt)" -gt $((${BATT:-37} * 10)) ]; } && [ $(($(date +%s) - T)) -lt 900 ]; do sleep 20; done
echo "cooled $(($(date +%s) - T)) s: hottest CPU $(hot), battery $(batt)" | tee "$OUT/cool.txt"
printf '%s\n' "env.ASOFT_LAT=1" "autosave-maps=999999" "$@" > "$OUT/ios26.in"
adb push "$(cygpath -w "$OUT/ios26.in")" "$EXT/ios26.txt" >/dev/null
adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService" >/dev/null
nap 2
adb shell "am start -n $PKG/com.asoft.ios.ScreenActivity" >/dev/null
TOP=$SCREEN
sleep 20
led=$(rs 'od -An -tu1 -N1 files/run/leds' | tr -d ' ')
[ $(( ${led:-0} & 1 )) -eq 0 ] && { press power; nap 4; }
press home; nap 5
press home; nap 12
shot opened
echo "opened: $(rs 'grep -a "resumed in" files/run/app.txt | tail -n 1')"
