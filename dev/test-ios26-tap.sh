#!/bin/bash
# test-ios26-tap.sh OUTDIR NAME X Y [MS=150] [SHOTS=6]: one touch of MS milliseconds at phone pixel X,Y on the
# running iOS 26 (through ScreenActivity), then a screenshot every 10 s: NAME-1.png ...
OUT=${1:?}; NAME=${2:?}; X=${3:?}; Y=${4:?}; MS=${5:-150}; SHOTS=${6:-6}
. "$(dirname "$0")/guarded.sh"
TOP='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]ScreenActivity'
T0=$(date +%s)
send swipe "$X" "$Y" "$X" "$Y" "$MS"
echo "$(date +%T) $NAME: touch $X,$Y for $MS ms" | tee -a "$OUT/ui.txt"
for i in $(seq 1 "$SHOTS"); do nap 10; shot "$NAME-$i"; done
cd "$OUT" && py -3.12 -c "
from PIL import Image
import sys
n, k = sys.argv[1], int(sys.argv[2])
ims = [Image.open(f'{n}-{i}.png').convert('RGB').resize((200, 433)) for i in range(1, k + 1)]
s = Image.new('RGB', (200 * k, 433))
for i, im in enumerate(ims): s.paste(im, (200 * i, 0))
s.save(f'{n}-strip.jpg', quality=80)" "$NAME" "$SHOTS"
