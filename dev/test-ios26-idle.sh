#!/bin/bash
# test-ios26-idle.sh OUTDIR NAME [TAP_EVERY_S=5] [SPAN_S=120]: does a touch keep iOS 26's display awake? iOS 26 must run in the
# dev app (from test-ios26-open.sh). Wakes the display (Home) and opens the lock screen (Home), then taps an empty spot
# (the dev app's view, phone pixels 700 1700) every TAP_EVERY_S s (0: no taps) for SPAN_S s while a phone-side loop
# (tools/leds-log.sh) logs the display's sleep byte (files/run/leds, QEMU_SCROLL_LOCK_LED = asleep) with the boot-time
# clock. Prints when it slept.
OUT=${1:?output directory}; NAME=${2:?name}; EVERY=${3:-5}; SPAN=${4:-120}
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/guarded.sh"
PKG=com.asoft.ios.dev
TOP='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]ScreenActivity'
rs() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }
press() { WAIT=1 bash "$HERE/qmp26.sh" "{\"execute\":\"qom-set\",\"arguments\":{\"path\":\"/machine/peripheral/ucons\",\"property\":\"press\",\"value\":\"$1\"}}" >/dev/null; }
up() { adb shell date +%s.%N | tr -d ""; }
L=files/run/leds-$NAME.txt
adb push "$(cygpath -w "$HERE/tools/leds-log.sh")" /data/local/tmp/asoft/leds-log.sh >/dev/null
adb shell "chmod a+r /data/local/tmp/asoft/leds-log.sh; run-as $PKG sh -c 'nohup sh /data/local/tmp/asoft/leds-log.sh $L $((SPAN + 60)) >/dev/null 2>&1 &'"
sleep 1
led=$(rs 'od -An -tu1 -N1 files/run/leds' | tr -d ' ')
echo "$NAME start $(up) led $led" | tee -a "$OUT/idle.txt"
[ $(( ${led:-0} & 1 )) -ne 0 ] && { press home; sleep 5; }
press home; T0=$(up); echo "$NAME home (open) $T0" | tee -a "$OUT/idle.txt"
sleep 5
shot "$NAME-open"
S=$(date +%s)
while [ $(($(date +%s) - S)) -lt "$SPAN" ]; do
    if [ "$EVERY" -gt 0 ]; then
        send tap 700 1700; echo "$NAME tap $(up)" >> "$OUT/idle.txt"
        sleep "$EVERY"
    else
        sleep 5
    fi
done
sleep 5
rs "cat $L" > "$OUT/leds-$NAME.txt"
echo "$NAME display log (boot clock, byte; 1 = asleep):" | tee -a "$OUT/idle.txt"
awk -v t0="$T0" '{printf "  %.1f s after the open: %s\n", $1 - t0, $2}' "$OUT/leds-$NAME.txt" | tee -a "$OUT/idle.txt"
