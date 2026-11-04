#!/bin/bash
# test-phone.sh OUTDIR: from any guest screen, Home, open Phone, walk its tabs, Home. Input goes through guarded.sh.
OUT=${1:?output directory}
. "$(dirname "$0")/guarded.sh"

send tap 1392 1472              # the side bar's Home
nap 8
guest_tap 140 1124              # Phone in the dock
nap 30
shot p1-phone
for x in 162 270 378 486 54; do
    guest_tap $x 1130
    nap 12
done
shot p2-tabs
send tap 1392 1472
nap 10
shot p3-home
crashes | cut -c1-70
heat
save_logs
echo "$(date +%T) done"
