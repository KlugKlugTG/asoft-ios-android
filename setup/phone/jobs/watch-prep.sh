# Install-watchOS.ps1's firmware and gpu steps: the watch kernel (kc-watch.py on cloudOS 26.4b's research kernel,
# the shared one), its device tree with the Series 9's identity (dt-watch.py on dtree-smp4), the phone's 3 GiB /
# 19.2 MHz tree, and the GPU driver retargeted to watchOS (its gpu body)
progress 5 "the watch kernel"
mkdir -p watchos
python3 -I $S/kc-watch.py watchos/bootkc-w cloud/kernelcache.research.vphone600.decompressed
progress 40 "the watch's device tree"
export QEMU_SPTM=$A/tools/qemu-sptm
python3 -I $S/dt-watch.py dtree-smp4 watchos/ipsw/DeviceTree.n207sap.raw watchos/dtree-w
python3 -I $S/dt-phone.py watchos/dtree-w watchos/dtree-w-3g-19m2 --ram-gib 3 --timebase 19200000
progress 60 "the GPU driver for watchOS"
step gpu
