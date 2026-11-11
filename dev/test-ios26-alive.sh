#!/bin/bash
# test-ios26-alive.sh OUTDIR NAME [MAXS=600]: when does iOS 26 take touches? Every 30 s a 120 ms touch on
# Calculator's icon (home screen row 1, column 4: phone x 1214, y 384); Calculator open = a mostly black screen (~660 colours in a 72x156 copy; the home screen 7000-8000).
OUT=${1:?}; NAME=${2:?}; MAXS=${3:-600}
. "$(dirname "$0")/guarded.sh"
TOP='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]ScreenActivity'
T0=$(date +%s)
while :; do
    send swipe 1214 384 1214 384 120
    t=$(( $(date +%s) - T0 ))
    sleep 20
    shot "$NAME-cur"
    c=$(colours "$NAME-cur")
    echo "$(date +%T) $NAME t=$t touch, 20 s later colours $c" | tee -a "$OUT/alive.txt"
    if [ "$c" -lt 3000 ]; then cp "$OUT/$NAME-cur.png" "$OUT/$NAME-open.png"; echo "$(date +%T) $NAME OPEN after the touch at t=$t" | tee -a "$OUT/alive.txt"; break; fi
    [ $(( $(date +%s) - T0 )) -ge "$MAXS" ] && { echo "$(date +%T) $NAME NO TOUCH in $MAXS s" | tee -a "$OUT/alive.txt"; break; }
    sleep 10
done
