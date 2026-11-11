#!/system/bin/sh
# drag-rate.sh HZ Y0 Y1 SECONDS (run as the dev app): one finger drag at x = 0.5 from Y0 to Y1 (0..32767) over SECONDS,
# a move HZ times a second, straight into QEMU's test QMP (no app in between); prints the boot-time clock at the down
# and the up (date: run-as cannot read /proc/uptime, the caller converts).
HZ=$1; Y0=$2; Y1=$3; S=$4
N=$((HZ * S)); D=$(awk "BEGIN{printf \"%.4f\", 1/$HZ}")
ev() { echo "{\"execute\":\"input-send-event\",\"arguments\":{\"events\":[{\"type\":\"abs\",\"data\":{\"axis\":\"x\",\"value\":16384}},{\"type\":\"abs\",\"data\":{\"axis\":\"y\",\"value\":$1}}$2]}}"; }
{
    echo '{"execute":"qmp_capabilities"}'
    echo "down $(date +%s.%N)" >&2
    ev "$Y0" ',{"type":"btn","data":{"down":true,"button":"left"}}'
    i=1
    while [ $i -le $N ]; do
        sleep "$D"
        ev $((Y0 + (Y1 - Y0) * i / N)) ''
        i=$((i + 1))
    done
    ev "$Y1" ',{"type":"btn","data":{"down":false,"button":"left"}}'
    echo "up $(date +%s.%N)" >&2
    sleep 1
} | toybox nc -q 2 -U files/run/qmp-test.sock > /dev/null
