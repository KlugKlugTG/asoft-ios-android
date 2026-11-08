#!/bin/bash
# tap.sh OUTDIR NAME X Y [WAIT=4]: one tap on iOS 26 in the dev app (phone pixels), then a screenshot NAME.png; through
# guarded.sh (only while the app's screen is on top).
OUT=${1:?}; NAME=${2:?}; X=${3:?}; Y=${4:?}; W=${5:-4}
. "$(dirname "$0")/guarded.sh"
TOP='com[.]asoft[.]ios[.]dev/com[.]asoft[.]ios[.]ScreenActivity'
send tap "$X" "$Y"
sleep "$W"
shot "$NAME"
