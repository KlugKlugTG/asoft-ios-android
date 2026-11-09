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
#   setup/steps/{ios26,watchos}         the PC installers' Linux step bodies (ios26/steps, watchos/steps)
#   setup/scripts/{ios26,watchos}       the scripts those bodies and the phone's jobs run (ios26/scripts,
#                                       watchos/scripts): iOS 26 and watchOS from Apple (PhoneSetup26)
#   setup/phone                         the phone's own helper prelude, jobs and tools (setup/phone here)
# ONLY=setup copies just the setup/ part (the helper VM and the kernel stay as they are).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
src=${1:-$here/../ios-sweet}
a=$here/app/src/main/assets
mkdir -p "$a/setup/tools"
if [ "${ONLY:-}" != setup ]; then
    mkdir -p "$a/helper" "$a/xnu"
    rm -f "$a"/helper/*
    cp "$src/installer/helper/out/Image.gz" "$a/helper/Image.gz.bin"
    cp "$src/installer/helper/out/initrd.gz" "$a/helper/initrd.gz.bin"
    cp "${KERNEL16K:-$src/installer/helper/out/Image16k.gz}" "$a/helper/Image16k.gz.bin"
    cp "$src/xnu/out-0103/kernel.development.sweet" "$a/xnu/"
fi
# iOS 26 and watchOS: the shared step bodies and scripts (asoft-ios), the phone's jobs (here); text files only
rm -rf "$a/setup/steps" "$a/setup/scripts" "$a/setup/phone"
for os in ios26 watchos; do
    mkdir -p "$a/setup/steps/$os" "$a/setup/scripts/$os"
    cp "$src/$os/steps/"*.sh "$a/setup/steps/$os/"
    find "$src/$os/scripts" -maxdepth 1 -type f \( -name '*.py' -o -name '*.sh' -o -name '*.txt' -o -name '*.hex' \)         -exec cp {} "$a/setup/scripts/$os/" \;
done
mkdir -p "$a/setup/phone/jobs"
cp "$here"/setup/phone/*.sh "$here"/setup/phone/*.py "$a/setup/phone/"
cp "$here"/setup/phone/jobs/*.sh "$a/setup/phone/jobs/"
for t in ipsw-devicetree.py lzfse.py mkadt.py adt.py mkpayload.py; do
    cp "$src/tools/$t" "$a/setup/tools/"
done
if [ -f "$src/installer/asoft-setup.json" ]; then
    cp "$src/installer/asoft-setup.json" "$a/setup/"
else
    cp "$here/setup/asoft-setup.json" "$a/setup/"
fi
[ "${ONLY:-}" = setup ] || sha256sum "$a"/helper/* "$a"/xnu/*
sha256sum "$a"/setup/asoft-setup.json
echo "steps: $(ls "$a"/setup/steps/*/ | grep -c '\.sh$'), scripts: $(ls "$a"/setup/scripts/*/ | grep -vc '^$\|:$'), phone jobs: $(ls "$a"/setup/phone/jobs | wc -l)"
