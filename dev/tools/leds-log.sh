#!/system/bin/sh
# leds-log.sh OUT SECONDS (run as the dev app, in its data dir): logs files/run/leds (QEMU's display sleep byte)
# with the boot-time clock at every change, every 0.2 s.
OUT=$1; END=$(( $(date +%s) + $2 )); P=x
rm -f "$OUT"
while [ "$(date +%s)" -lt "$END" ]; do
    V=$(od -An -tu1 -N1 files/run/leds | tr -d ' ')
    if [ "$V" != "$P" ]; then
        echo "$(date +%s.%N) $V" >> "$OUT"
        P=$V
    fi
    sleep 0.2
done
