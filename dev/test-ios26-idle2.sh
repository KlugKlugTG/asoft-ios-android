#!/bin/bash
# test-ios26-idle2.sh OUTDIR NAME [TAP_EVERY_S=5] [TAPS_S=60] [AFTER_S=60]: like test-ios26-idle.sh, but after TAPS_S s of taps
# it waits AFTER_S s without input: how long after the last tap does iOS 26 put its display to sleep? Wall-clock stamps
# (date +%s.%N) on both sides.
OUT=${1:?output directory}; NAME=${2:?name}; EVERY=${3:-5}; TAPS=${4:-60}; AFTER=${5:-60}
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/guarded.sh"
PKG=com.asoft.ios.dev
TOP='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]ScreenActivity'
rs() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }
press() { WAIT=1 bash "$HERE/qmp26.sh" "{\"execute\":\"qom-set\",\"arguments\":{\"path\":\"/machine/peripheral/ucons\",\"property\":\"press\",\"value\":\"$1\"}}" >/dev/null; }
now() { adb shell date +%s.%N | tr -d '\r'; }
L=files/run/leds-$NAME.txt
adb push "$(cygpath -w "$HERE/tools/leds-log.sh")" /data/local/tmp/asoft/leds-log.sh >/dev/null
adb shell "chmod a+r /data/local/tmp/asoft/leds-log.sh; run-as $PKG sh -c 'nohup sh /data/local/tmp/asoft/leds-log.sh $L $((TAPS + AFTER + 40)) >/dev/null 2>&1 &'"
sleep 1
led=$(rs 'od -An -tu1 -N1 files/run/leds' | tr -d ' ')
[ $(( ${led:-0} & 1 )) -ne 0 ] && { press home; sleep 5; }
press home; T0=$(now); echo "$NAME open $T0" | tee -a "$OUT/idle.txt"
sleep 4
S=$(date +%s)
while [ $(($(date +%s) - S)) -lt "$TAPS" ]; do
    send tap 700 1700; LAST=$(now); echo "$NAME tap $LAST" >> "$OUT/idle.txt"
    sleep "$EVERY"
done
echo "$NAME last tap $LAST" | tee -a "$OUT/idle.txt"
sleep "$AFTER"
rs "cat $L" > "$OUT/leds-$NAME.txt"
awk -v t0="$T0" -v tl="$LAST" '{printf "  %s: %.1f s after the open, %.1f s after the last tap\n", ($2 == 1 ? "asleep" : "awake"), $1 - t0, $1 - tl}' "$OUT/leds-$NAME.txt" | tee -a "$OUT/idle.txt"
