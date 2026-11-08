#!/bin/bash
# iOS 26's part of the APK, into app/src/main/jniLibs26 (pull-libs.sh keeps jniLibs, iOS 16's):
#   libqemu26.so                    the NDK build of qemu-sptm asoft-android with reims-vgpu + metal2vulkan
#                                   (C:\asoft-build\android\wsl\13-qemu.sh in WSL ios-emu, debug info stripped)
#   lib_libvulkan_freedreno.so.so   Mesa Turnip with VK_EXT_external_memory_host over KGSL (12-turnip.sh,
#                                   turnip-host-pointer.diff): the stock Adreno driver lacks that extension
#   lib_libvulkan.so.so             Termux's Khronos Vulkan loader (metal2vulkan dlopens libvulkan.so on
#                                   Android; Guest.java points it at Turnip with VK_DRIVER_FILES)
# QEMU's other libraries are iOS 16's (pull-libs.sh): the same Termux set.
#   bash android-app/pull-libs26.sh
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$HERE/app/src/main/jniLibs26/arm64-v8a
mkdir -p "$OUT"
# WSL expands $ in a -c string before bash sees it: the copy is a script file, the folder its argument
to_wsl() { cygpath -w "$1" | sed 's|\\|/|g; s|^\([A-Za-z]\):|/mnt/\L\1|'; }
S=$(mktemp -p "$HERE" .pull26.XXXXXX.sh)
trap 'rm -f "$S"' EXIT
cat > "$S" <<'EOF'
set -e
B=/root/android-build
cp "$B/qemu-system-aarch64.s" "$1/libqemu26.so"
cp "$B/turnip-dist/data/data/com.termux/files/home/turnip/lib/libvulkan_freedreno.so" "$1/lib_libvulkan_freedreno.so.so"
cp -L /data/data/com.termux/files/usr/lib/libvulkan.so.1 "$1/lib_libvulkan.so.so"
EOF
MSYS_NO_PATHCONV=1 wsl -d ios-emu bash "$(to_wsl "$S")" "$(to_wsl "$OUT")"
ls -la "$OUT"
