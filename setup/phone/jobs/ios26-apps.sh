# Install-iOS26.ps1's apps step: the staged apps, PosterBoard's and the side button's patches (.42), UIKitCore's
# touch edge patch (.03), libMobileGestalt's Always On Display patch (.15)
progress 5 "the dyld cache"
step apps-get
progress 40 "the dyld cache patches"
pb=$(python3 -I $S/dsc-patch.py dsc {POSTERBOARD} dscpatch-pb); echo "$pb"
lp=$(python3 -I $S/dsc-patch.py dscpatch-pb {LOCKLONGPRESS} dscpatch-lp); echo "$lp"
edge=$(python3 -I $S/edge-patch.py dsc dscpatch-edge); echo "$edge"
aod=$(python3 -I $S/dsc-patch.py dsc {AOD} dscpatch-aod); echo "$aod"
h1=$(echo "$lp" | new_hash) h2=$(echo "$edge" | edge_hash) h3=$(echo "$aod" | new_hash)
for h in "$h1" "$h2" "$h3"; do
	[ "$(echo "$h" | wc -w)" = 1 ] || { echo "dsc-patch.py / edge-patch.py printed no new CDHash"; exit 1; }
done
mkdir -p dscpatch install/work
cp dscpatch-lp/{DSC0} dscpatch-edge/{DSC1} dscpatch-aod/{DSC2} dscpatch/
printf '%s\n' "$h1" "$h2" "$h3" > install/work/apps.hashes
python3 -I $S/mktc.py -2 root26-apps.tc root26-activation.tc install/work/apps.hashes
progress 60 "the apps into /Applications"
step apps-put
