#!/usr/bin/env bash
# Builds and runs tools/gpu_merge_test.cpp against the app's merge shader on the desktop Vulkan
# driver (Mesa lavapipe works: apt install mesa-vulkan-drivers libvulkan-dev). Needs glslc
# (from the NDK, or set GLSLC).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CPP="$ROOT/phase1-skeleton/src/main/cpp"
GLSLC="${GLSLC:-${ANDROID_DIR:-/opt/android}/android-ndk-r26d/shader-tools/linux-x86_64/glslc}"
OUT="$(mktemp -d)"
"$GLSLC" -fshader-stage=compute "$CPP/shaders/merge.comp" -o "$OUT/merge.spv"
python3 - "$OUT/merge.spv" "$OUT/merge_spv.h" <<'PY'
import sys
d=open(sys.argv[1],'rb').read()
with open(sys.argv[2],'w') as f:
    f.write('#pragma once\n#include <cstdint>\n#include <cstddef>\nalignas(4) const uint32_t merge_spv[] = {')
    f.write(','.join(f'0x{int.from_bytes(d[i:i+4],"little"):08x}' for i in range(0,len(d),4)))
    f.write(f'}};\nconst size_t merge_spv_size = {len(d)};\n')
PY
g++ -std=c++20 -O2 -I"$CPP" -I"$OUT" "$ROOT/tools/gpu_merge_test.cpp" "$CPP/gpu_merge.cpp" -lvulkan -o "$OUT/gpu_merge_test"
"$OUT/gpu_merge_test"
