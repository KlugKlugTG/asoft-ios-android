#!/bin/bash
# pull-setup.sh [IOS_SWEET]: what the setup from Apple (PhoneSetup) brings, into app/src/main/assets
# (gitignored, like jniLibs), from an asoft-ios checkout (default ../ios-sweet):
#   helper/Image.gz.bin, helper/initrd.gz.bin   the PC installer's helper VM (installer/helper/out, build-*.sh);
#                                       ".bin": the Android build un-gzips assets named *.gz and drops the suffix
#   helper/Image16k.gz.bin              its kernel for the native EL0 engine: setup/helper-kernel16k.config;
#                                       $KERNEL16K, else installer/helper/out/Image16k.gz
#   xnu/kernel.development.sweet        our iOS 16 kernel (xnu/out-0103, the one the app's quick boot needs)
#   setup/tools/*.py                    what makes payload.bin (Send-ToPhone.ps1's tools)
#   setup/asoft-setup.json              the member and step list the installers share (installer/ there,
#                                       else setup/ here)
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
src=${1:-$here/../ios-sweet}
a=$here/app/src/main/assets
mkdir -p "$a/helper" "$a/xnu" "$a/setup/tools"
rm -f "$a"/helper/*
cp "$src/installer/helper/out/Image.gz" "$a/helper/Image.gz.bin"
cp "$src/installer/helper/out/initrd.gz" "$a/helper/initrd.gz.bin"
cp "${KERNEL16K:-$src/installer/helper/out/Image16k.gz}" "$a/helper/Image16k.gz.bin"
cp "$src/xnu/out-0103/kernel.development.sweet" "$a/xnu/"
for t in ipsw-devicetree.py lzfse.py mkadt.py adt.py mkpayload.py; do
    cp "$src/tools/$t" "$a/setup/tools/"
done
if [ -f "$src/installer/asoft-setup.json" ]; then
    cp "$src/installer/asoft-setup.json" "$a/setup/"
else
    cp "$here/setup/asoft-setup.json" "$a/setup/"
fi
sha256sum "$a"/helper/* "$a"/xnu/* "$a"/setup/asoft-setup.json
