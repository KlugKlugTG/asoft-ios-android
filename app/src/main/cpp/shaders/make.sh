#!/bin/bash
# Compiles the GPU check's shaders into ../gpucheck-spv.h (NDK glslc)
set -e
cd "$(dirname "$0")"
G=${GLSLC:-$LOCALAPPDATA/Android/Sdk/ndk/29.0.13846066/shader-tools/windows-x86_64/glslc.exe}
for s in comp vert frag; do "$G" -O --target-env=vulkan1.2 -o check.$s.spv check.$s; done
python - <<'PY'
out = ['/* SPIR-V of shaders/check.{comp,vert,frag} (NDK glslc -O --target-env=vulkan1.2); made by shaders/make.sh */', '#include <stdint.h>']
for s in ('comp', 'vert', 'frag'):
    b = open('check.%s.spv' % s, 'rb').read()
    w = [int.from_bytes(b[i:i + 4], 'little') for i in range(0, len(b), 4)]
    out.append('static const uint32_t spv_%s[] = {' % s)
    for i in range(0, len(w), 8):
        out.append('    ' + ', '.join('0x%08x' % x for x in w[i:i + 8]) + ',')
    out.append('};')
open('../gpucheck-spv.h', 'w', newline='\n').write('\n'.join(out) + '\n')
PY
rm -f check.*.spv
