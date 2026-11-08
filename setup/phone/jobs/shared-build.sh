# iOS 26 and watchOS's shared part (PhoneSetup26 "shared" build): Install-iOS26.ps1's ramdisk step (its body),
# its devicetree step and the phone's 19.2 MHz trees, the kernel with OpenGL ES's keys (its gpu step's
# Set-BootKc) and the guest GPU driver out of the cloudOS image (its gpu-driver body), with our compiler plugin
progress 5 "the shell ramdisk"
step ramdisk
progress 55 "the device trees"
export QEMU_SPTM=$A/tools/qemu-sptm
python3 -I $S/dt-root26smp.py fw/dtree.orig dtree-smp4 --cpus 4
python3 -I phone/dt-phone.py dtree-smp4 dtree-phone --timebase 19200000
python3 -I $S/dt-aod.py dtree-phone dtree-phone-aod
progress 65 "the kernel with OpenGL ES's keys"
python3 -I $S/kc-gles.py cloud/kernelcache.research.vphone600.decompressed fw/bootkc
progress 75 "the guest GPU driver"
mkdir -p install/work
bash $S/cloudos-gpu-driver.sh cloudos/{IMG} install/work/gpu cloudos/Firmware/{IMG}.aea.trustcache
cp downloads/{PLUGIN} install/work/gpu/{BUNDLE}/{PLUGIN}
python3 -I $S/cdhash.py install/work/gpu/{BUNDLE}/AppleParavirtGPUMetalIOGPUFamily install/work/gpu/{BUNDLE}/{PLUGIN}
progress 100 "done"
