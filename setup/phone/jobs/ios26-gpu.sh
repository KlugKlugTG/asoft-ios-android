# Install-iOS26.ps1's gpu step on its root26-gpu layer: the driver kept in gpu-driver/ (the shared build)
progress 5 "the guest GPU driver"
find install/work/gpu -type f | sort | xargs python3 -I $S/cdhash.py | tee /dev/stderr \
	| grep -E '^[0-9a-f]{40} ' | awk '{print $1}' > install/work/gpu.hashes
[ "$(wc -l < install/work/gpu.hashes)" -ge 2 ] || { echo "the GPU driver has fewer than two signed Mach-Os"; exit 1; }
python3 -I $S/mktc.py -2 root26-gpu.tc root26-apps.tc install/work/gpu.hashes
step gpu
