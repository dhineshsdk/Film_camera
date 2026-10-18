#!/usr/bin/env bash
# Linux mirror of build_sprint1.ps1 (cloud build check). Needs NDK r26d, build-tools 34 and
# platform 34 unpacked under $ANDROID_DIR (default /opt/android); produces an unsigned-key
# debug APK at phase1-skeleton/build/aligned.apk.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
A="${ANDROID_DIR:-/opt/android}"
BT="$A/android-14"; [ -d "$BT" ] || BT="$(ls -d $A/android-* | grep -v ndk | head -1)"
PLAT="$A/android-34"; [ -f "$PLAT/android.jar" ] || PLAT="$(dirname "$(ls $A/*/android.jar | head -1)")"
NDK="$A/android-ndk-r26d"
GLSLC="$NDK/shader-tools/linux-x86_64/glslc"
P="$ROOT/phase1-skeleton"
cd "$ROOT"
# ONNX Runtime (Android AAR) + Depth Anything V2 Small int8 (Apache-2.0): downloaded once into a gitignored cache.
CACHE="$ROOT/.build_cache"; mkdir -p "$CACHE"
ORT_VER=1.24.3
ORT_URL="https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/$ORT_VER/onnxruntime-android-$ORT_VER.aar"
ORT_SHA=67397e4a970e75617f765d2015ceaf911917e1d822276cfb5792744e8085cbce
MODEL_URL="https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model_quantized.onnx"
MODEL_SHA=fcf51f1b230362b28690bb9d1809bf0431f29cad20534e3f589bd7285547f20d
MODEL_NAME=depth_anything_v2_small_int8.onnx
fetch() {  # $1 url, $2 sha256, $3 file
  if [ -f "$3" ] && [ "$(sha256sum "$3" | cut -d' ' -f1)" = "$2" ]; then return; fi
  curl -fSL --retry 3 -o "$3" "$1"
  [ "$(sha256sum "$3" | cut -d' ' -f1)" = "$2" ] || { echo "SHA-256 mismatch for $3" >&2; rm -f "$3"; exit 1; }
}
fetch "$ORT_URL" "$ORT_SHA" "$CACHE/onnxruntime-android-$ORT_VER.aar"
fetch "$MODEL_URL" "$MODEL_SHA" "$CACHE/$MODEL_NAME"
ORT_DIR="$CACHE/ort-$ORT_VER"
[ -f "$ORT_DIR/classes.jar" ] || { rm -rf "$ORT_DIR"; mkdir -p "$ORT_DIR"; unzip -q "$CACHE/onnxruntime-android-$ORT_VER.aar" -d "$ORT_DIR"; }
spv2h() {  # $1 spv, $2 header, $3 symbol
python3 - "$1" "$2" "$3" <<'PY'
import sys
data=open(sys.argv[1],'rb').read(); n=sys.argv[3]
with open(sys.argv[2],'w') as f:
    f.write('// Auto-generated SPIR-V header\n#pragma once\n#include <cstdint>\n#include <cstddef>\n\n')
    f.write(f'alignas(4) const uint32_t {n}[] = {{\n')
    for i in range(0,len(data),4):
        f.write(f'0x{int.from_bytes(data[i:i+4],"little"):08x}, ')
        if (i//4+1)%8==0: f.write('\n')
    f.write('\n};\n'); f.write(f'const size_t {n}_size = {len(data)};\n')
PY
}
S="$P/src/main/cpp/shaders"
for s in demosaic encode_hlg; do
  "$GLSLC" -fshader-stage=compute "$S/$s.comp" -o "$S/$s.spv"
  spv2h "$S/$s.spv" "$S/${s}_spv.h" "${s}_spv"
done
NB="$P/build_native"
cmake -B "$NB" -S "$P/src/main/cpp" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-34 -DCMAKE_BUILD_TYPE=Release >/dev/null
ninja -C "$NB"
B="$P/build"; rm -rf "$B"; mkdir -p "$B/classes" "$B/gen" "$B/lib/arm64-v8a" "$B/assets_extra"
cp "$NB/libs24film_vulkan.so" "$ORT_DIR"/jni/arm64-v8a/*.so "$B/lib/arm64-v8a/"
cp "$CACHE/$MODEL_NAME" "$B/assets_extra/"
"$BT/aapt2" compile --dir "$P/src/main/res" -o "$B/res.zip"
"$BT/aapt2" link -I "$PLAT/android.jar" "$B/res.zip" --manifest "$P/src/main/AndroidManifest.xml" \
  -A "$P/src/main/assets" -A "$B/assets_extra" -0 onnx --min-sdk-version 34 --target-sdk-version 34 --java "$B/gen" -o "$B/unaligned.apk" --auto-add-overlay
javac -XDstringConcat=inline -parameters -source 17 -target 17 -cp "$PLAT/android.jar:$ORT_DIR/classes.jar" -d "$B/classes" \
  $(find "$P/src/main/java" "$B/gen" -name '*.java') 2>&1 | grep -v "^warning\|^Picked up" || true
jar cf "$B/classes.jar" -C "$B/classes" .
"$BT/d8" --min-api 34 --lib "$PLAT/android.jar" --output "$B" "$B/classes.jar" "$ORT_DIR/classes.jar"
(cd "$B" && zip -qr unaligned.apk classes.dex lib)
"$BT/zipalign" -f -p 4 "$B/unaligned.apk" "$B/aligned.apk"
KS="$ROOT/phase0-probe/debug.keystore"
[ -f "$KS" ] || { KS="$B/debug.keystore"; keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -validity 10000 -dname "CN=Android Debug" >/dev/null 2>&1; }
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android "$B/aligned.apk"
ls -la "$B/aligned.apk"
