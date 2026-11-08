# Install-iOS26.ps1's rootfs and trustcache steps
progress 2 "copying iOS onto its disk"
step rootfs
progress 97 "its trust cache"
python3 -I $S/mktc.py -2 root26.tc iphone/{OS}.aea.trustcache iphone/{SYSOS}.aea.trustcache iphone/{APPOS}.trustcache root26.qcow2.hashes
