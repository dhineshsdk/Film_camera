param(
    [string]$PackageName = "com.s24film.camera"
)

$ErrorActionPreference = "Stop"

$sdk = "C:\Users\SDK\AppData\Local\Android\Sdk"
$buildTools = "$sdk\build-tools\34.0.0"
$platform = "$sdk\platforms\android-34"
$adb = "$sdk\platform-tools\adb.exe"
$ndk = "$sdk\ndk\26.3.11579264"
$cmake = "$sdk\cmake\3.22.1\bin\cmake.exe"
$ninja = "$sdk\cmake\3.22.1\bin\ninja.exe"
$glslc = "$ndk\shader-tools\windows-x86_64\glslc.exe"
$javaBin = "C:\Program Files\Java\jdk-21.0.10\bin"

Write-Host "=== Phase 1: Building Vulkan 1.3 RAW Camera & DNG App ($PackageName) ===" -ForegroundColor Cyan

# 1. Compile GLSL Compute Shader
Write-Host "Compiling demosaic.comp to SPIR-V..." -ForegroundColor Yellow
& "$glslc" -fshader-stage=compute "phase1-skeleton\src\main\cpp\shaders\demosaic.comp" -o "phase1-skeleton\src\main\cpp\shaders\demosaic.spv"

# Generate demosaic_spv.h
python -c "
with open(r'phase1-skeleton\src\main\cpp\shaders\demosaic.spv', 'rb') as f:
    data = f.read()
with open(r'phase1-skeleton\src\main\cpp\shaders\demosaic_spv.h', 'w') as f:
    f.write('// Auto-generated SPIR-V header\n#pragma once\n#include <cstdint>\n#include <cstddef>\n\n')
    f.write('alignas(4) const uint32_t demosaic_spv[] = {\n')
    for i in range(0, len(data), 4):
        chunk = data[i:i+4]
        val = int.from_bytes(chunk, 'little')
        f.write(f'0x{val:08x}, ')
        if (i // 4 + 1) % 8 == 0:
            f.write('\n')
    f.write('\n};\n')
    f.write(f'const size_t demosaic_spv_size = {len(data)};\n')
"

# 2. Build Native C++ Library with CMake and Ninja (arm64-v8a)
Write-Host "Configuring and compiling native C++ Vulkan ISP library (arm64-v8a)..." -ForegroundColor Yellow
$nativeBuildDir = "phase1-skeleton\build_native"
& "$cmake" -B "$nativeBuildDir" -S "phase1-skeleton\src\main\cpp" -G Ninja `
    -DCMAKE_MAKE_PROGRAM="$ninja" `
    -DCMAKE_TOOLCHAIN_FILE="$ndk\build\cmake\android.toolchain.cmake" `
    -DANDROID_ABI="arm64-v8a" `
    -DANDROID_PLATFORM=android-34 `
    -DCMAKE_BUILD_TYPE=Release
& "$ninja" -C "$nativeBuildDir"

# 3. Setup build directories
$buildDir = "phase1-skeleton\build"
if (Test-Path "$buildDir") { Remove-Item -Recurse -Force "$buildDir" }
New-Item -ItemType Directory -Force -Path "$buildDir\classes" | Out-Null
New-Item -ItemType Directory -Force -Path "$buildDir\gen" | Out-Null
New-Item -ItemType Directory -Force -Path "$buildDir\lib\arm64-v8a" | Out-Null

Copy-Item "$nativeBuildDir\libs24film_vulkan.so" -Destination "$buildDir\lib\arm64-v8a\"

# 4. Compile resources with aapt2
Write-Host "Compiling resources with aapt2..." -ForegroundColor Yellow
& "$buildTools\aapt2.exe" compile --dir "phase1-skeleton\src\main\res" -o "$buildDir\res.zip"

# 5. Link APK and generate R.java
Write-Host "Linking APK with aapt2..." -ForegroundColor Yellow
& "$buildTools\aapt2.exe" link -I "$platform\android.jar" "$buildDir\res.zip" `
    --manifest "phase1-skeleton\src\main\AndroidManifest.xml" `
    -A "phase1-skeleton/src/main/assets" --min-sdk-version 34 --target-sdk-version 34 `
    --java "$buildDir\gen" `
    -o "$buildDir\unaligned.apk" --auto-add-overlay

# 6. Compile Java sources
Write-Host "Compiling Java with javac..." -ForegroundColor Yellow
$javaSources = Get-ChildItem -Path "phase1-skeleton\src\main\java" -Filter "*.java" -Recurse | ForEach-Object { $_.FullName }
$rJava = Get-ChildItem -Path "$buildDir\gen" -Filter "R.java" -Recurse | ForEach-Object { $_.FullName }
& "$javaBin\javac.exe" -source 17 -target 17 -cp "$platform\android.jar" -d "$buildDir\classes" ($javaSources + $rJava)

# 7. DEX with d8
Write-Host "Creating classes.dex with d8..." -ForegroundColor Yellow
$classFiles = (Get-ChildItem -Path "$buildDir\classes" -Filter "*.class" -Recurse).FullName
& cmd /c """$buildTools\d8.bat"" --min-api 34 --lib ""$platform\android.jar"" --output ""$buildDir"" $classFiles"

# 8. Package classes.dex and lib into unaligned APK
Write-Host "Adding classes.dex and arm64-v8a native libraries to APK..." -ForegroundColor Yellow
& "$javaBin\jar.exe" uf "$buildDir\unaligned.apk" -C "$buildDir" "classes.dex"
& "$javaBin\jar.exe" uf "$buildDir\unaligned.apk" -C "$buildDir" "lib"

# 9. Zipalign
Write-Host "Aligning APK with zipalign..." -ForegroundColor Yellow
& "$buildTools\zipalign.exe" -f -p 4 "$buildDir\unaligned.apk" "$buildDir\aligned.apk"

# 10. Sign APK
Write-Host "Signing APK with apksigner..." -ForegroundColor Yellow
& cmd /c """$buildTools\apksigner.bat"" sign --ks ""phase0-probe\debug.keystore"" --ks-pass pass:android ""$buildDir\aligned.apk"""

Write-Host "=== Phase 1 Build Complete: $buildDir\aligned.apk ===" -ForegroundColor Green

