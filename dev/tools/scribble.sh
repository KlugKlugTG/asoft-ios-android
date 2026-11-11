#!/system/bin/sh
# scribble.sh N: one finger down, N zigzag moves across the middle of the screen (each `input` run ~0.1-0.2 s), up.
N=${1:-40}
input motionevent DOWN 300 1300
i=0
while [ $i -lt $N ]; do
    if [ $((i % 2)) -eq 0 ]; then X=1100; else X=300; fi
    Y=$((1300 + i * 12))
    input motionevent MOVE $X $Y
    i=$((i + 1))
done
input motionevent UP 300 $((1300 + N * 12))
