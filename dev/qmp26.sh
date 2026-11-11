#!/bin/bash
# qmp26.sh 'JSON' ['JSON' ...]: commands to the running iOS 26's test QMP (debug builds: files/run/qmp-test.sock
# of com.asoft.ios.dev, beside the app's own QMP), the answers on stdout. HMP: qmp26.sh hmp 'info usb'.
export MSYS_NO_PATHCONV=1
PKG=${PKG:-com.asoft.ios.dev}
if [ "$1" = hmp ]; then
    shift
    set -- "{\"execute\":\"human-monitor-command\",\"arguments\":{\"command-line\":\"$1\"}}"
fi
{
    echo '{"execute":"qmp_capabilities"}'
    for c in "$@"; do echo "$c"; done
} > "${TMPDIR:-/tmp}/qmp26.in"
adb push "$(cygpath -w "${TMPDIR:-/tmp}/qmp26.in")" /data/local/tmp/qmp26.in >/dev/null
adb shell "run-as $PKG sh -c 'cat /data/local/tmp/qmp26.in | toybox nc -q ${WAIT:-3} -U files/run/qmp-test.sock'" | tr -d '\r' | grep -v '"QMP"' | grep -v '^{"return": {}}$'
