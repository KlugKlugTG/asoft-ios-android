#!/bin/bash
# Copies QEMU and its library closure from the phone's Termux build into the APK's jniLibs.
# QEMU becomes libqemu.so; a library with the soname X becomes lib_X.so (Emu.linkLibraries undoes that).
#   bash android-app/pull-libs.sh [path of the QEMU binary on the phone]
set -eu
export MSYS_NO_PATHCONV=1
HERE=$(cd "$(dirname "$0")" && pwd)
TSH=/c/asoft-build/android/tsh
QEMU=${1:-/data/data/com.termux/files/home/qemu-build/qemu-system-aarch64}
OUT=$HERE/app/src/main/jniLibs/arm64-v8a
ASSETS=$HERE/app/src/main/assets
mkdir -p "$OUT" "$ASSETS"
rm -f "$OUT"/*.so
pull() { adb exec-out run-as com.termux cat "$1" > "$2"; }
pull "$QEMU" "$OUT/libqemu.so"
# qemu-img for the user disk and the quick-boot state; its libraries are a subset of QEMU's
pull "$(dirname "$QEMU")/qemu-img" "$OUT/libqemuimg.so"
LIST=$($TSH <<EOF
python -I - "$QEMU" <<'PY'
import os, subprocess, sys
pre = os.environ["PREFIX"] + "/lib"
seen, todo = {}, [sys.argv[1]]
while todo:
    out = subprocess.run(["readelf", "-d", todo.pop()], capture_output=True, text=True).stdout
    for ln in out.splitlines():
        if "(NEEDED)" in ln:
            n = ln.split("[")[1].split("]")[0]
            f = os.path.join(pre, n)
            if n not in seen and os.path.exists(f):
                seen[n] = os.path.realpath(f)
                todo.append(f)
for k, v in sorted(seen.items()):
    print(k, v)
PY
EOF
)
echo "$LIST" | tr -d '\r' | while read -r soname path; do
    pull "$path" "$OUT/lib_$soname.so"
done
# Termux's libslirp reads the DNS server from Termux's prefix, closed to other apps: point it at
# the file Guest.writeResolvConf() keeps (no longer than the old path, NUL padded)
python "$(cygpath -w "$HERE/patch-slirp.py")" "$(cygpath -w "$OUT/lib_libslirp.so.so")"
adb exec-out run-as com.termux cat /data/data/com.termux/files/home/el0bench/el0bench.elf > "$ASSETS/el0bench.elf"
ls "$OUT" | wc -l
du -sh "$OUT"
