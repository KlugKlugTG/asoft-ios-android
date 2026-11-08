# Install-iOS26.ps1's activation step: mobileactivationd answers should_hactivate YES, its new CDHash keeps the
# original's constraint category (tc-like.py)
progress 5 "activation"
step activation-get
python3 -I $S/hact_patch.py install/work/mobileactivationd.orig install/work/mobileactivationd
h=$(python3 -I $S/reattest.py install/work/mobileactivationd.orig install/work/mobileactivationd | tee /dev/stderr \
	| grep -E '^[0-9a-f]{40} [0-9a-f]{40}( |$)' | head -1)
[ -n "$h" ] || { echo "reattest.py printed no CDHashes"; exit 1; }
set -- $h
python3 -I $S/tc-like.py root26-activation.tc root26.tc "$1" "$2"
step activation-put
