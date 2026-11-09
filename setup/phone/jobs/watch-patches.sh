# Install-watchOS.ps1's patches and trustcache steps
progress 5 "shared-cache patches, prefs, apps, the GPU driver"
step patches
progress 95 "its trust cache"
python3 -I $S/mktc.py -2 watchos/rootw.tc watchos/ipsw/{OS}.aea.trustcache watchos/rootw.qcow2.hashes watchos/rootw-p.hashes watchos/gpu/hashes.txt
