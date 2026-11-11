#!/bin/bash
# maps-sample.sh OUTFILE [SECONDS=1200] [PERIOD=1]: the dev app's iOS 26 QEMU's kernel mappings (vm.max_map_count
# 65,530 ends it), once a PERIOD on the phone: epoch seconds, all mappings, reims' views of guest RAM (memfd:dram),
# other memfd mappings, the rest. Runs as the app (run-as: it cannot read /proc/uptime, hence the wall clock), in
# the background on the phone; stops with QEMU.
OUT=${1:?output file}; SECS=${2:-1200}; PERIOD=${3:-1}
PKG=com.asoft.ios.dev
export MSYS_NO_PATHCONV=1
PID=$(adb shell "ps -A -o PID,ARGS | grep '[l]ibqemu26.so -name' | awk '{print \$1}'" | tr -d '\r' | head -n 1)
[ -n "$PID" ] || { echo "no iOS 26 QEMU"; exit 1; }
adb shell "run-as $PKG sh -c 'end=\$((\$(date +%s) + $SECS)); while [ -d /proc/$PID ] && [ \$(date +%s) -lt \$end ]; do awk -v t=\$(date +%s) \"{ n++ } /memfd:dram/ { d++ } /memfd:/ && !/memfd:dram/ { m++ } END { printf \\\"%s %d %d %d %d\\\\n\\\", t, n, d, m, n - d - m }\" /proc/$PID/maps; sleep $PERIOD; done'" | tr -d '\r' > "$OUT"
