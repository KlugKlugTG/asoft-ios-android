#!/bin/bash
# test-ios26-ui.sh OUTDIR: on a running iOS 26 home screen (test-ios26.sh first), through the app's own paths:
# tap Calculator, then the side bar's ⌂ (Home), screenshots every 10 s, memory, map count and heat.
OUT=${1:?output directory}
PKG=com.asoft.ios.dev
. "$(dirname "$0")/guarded.sh"
TOP='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]ScreenActivity'
T0=$(date +%s)
qpid() { adb shell "ps -A -o PID,ARGS | grep '[l]ibqemu26.so -name' | awk '{print \$1}'" | tr -d '\r' | head -n 1; }
log() { echo "$(date +%T) t=$(( $(date +%s) - T0 )) $*" | tee -a "$OUT/ui.txt"; }
state() {
    local p; p=$(qpid)
    [ -n "$p" ] || { echo "QEMU gone"; return; }
    adb shell "run-as $PKG sh -c 'grep -E \"VmRSS|VmSwap\" /proc/$p/status | tr -s \" \" | tr \"\n\" \" \"; echo maps \$(wc -l < /proc/$p/maps)'; grep MemAvailable /proc/meminfo | tr -s ' '; dumpsys battery | grep -E '^  temperature' | awk '{print \"batt\", \$2/10}'; for z in /sys/class/thermal/thermal_zone*; do case \$(cat \$z/type) in cpu-*-*) cat \$z/temp;; esac; done | sort -n | tail -n 1 | awk '{print \"cpu\", \$1/1000}'" | tr -d '\r' | tr '\n' ' '
}
log "state $(state)"
shot d0-home
# Calculator: row 1, column 4 of the home screen (guest 0.76, 0.13; the guest fills this phone's screen)
send tap 1214 384
log "tapped Calculator"
for i in 1 2 3 4 5 6; do nap 10; shot "d1-calc-$i"; done
log "state $(state)"
tap_text "⌂" || log "no ⌂ on screen"
log "tapped the side bar's Home"
for i in 1 2 3; do nap 10; shot "d2-home-$i"; done
log "state $(state)"
