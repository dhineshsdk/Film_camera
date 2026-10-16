import os
import sys
import subprocess
import shutil
import time

SDK = r"C:\Users\SDK\AppData\Local\Android\Sdk"
BUILD_TOOLS = os.path.join(SDK, "build-tools", "34.0.0")
PLATFORM_JAR = os.path.join(SDK, "platforms", "android-34", "android.jar")
ADB = os.path.join(SDK, "platform-tools", "adb.exe")
NDK = os.path.join(SDK, "ndk", "26.3.11579264")
CMAKE = os.path.join(SDK, "cmake", "3.22.1", "bin", "cmake.exe")
NINJA = os.path.join(SDK, "cmake", "3.22.1", "bin", "ninja.exe")
GLSLC = os.path.join(NDK, "shader-tools", "windows-x86_64", "glslc.exe")
JAVA_BIN = r"C:\Program Files\Java\jdk-21.0.10\bin"
JAVAC = os.path.join(JAVA_BIN, "javac.exe")
JAR = os.path.join(JAVA_BIN, "jar.exe")
KEYSTORE = r"c:\Users\SDK\Downloads\Camera-App\phase0-probe\debug.keystore"

BASE_DIR = r"c:\Users\SDK\Downloads\Camera-App\phase0-lab-bench"
BUILD_DIR = os.path.join(BASE_DIR, "build")
BUILD_NATIVE = os.path.join(BASE_DIR, "build_native")

def run(cmd, desc):
    print(f"[*] {desc}...")
    res = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')
    if res.returncode != 0:
        print(f"[ERROR] {desc} failed (code {res.returncode}):")
        if res.stdout: print("Stdout:\n" + res.stdout)
        if res.stderr: print("Stderr:\n" + res.stderr)
        sys.exit(1)
    return res.stdout

def build_apk():
    # 1. Compile Shaders
    shader_dir = os.path.join(BASE_DIR, "src", "main", "cpp", "shaders")
    for s in ["gaussian_mipmap", "newson_grain", "bokeh_gather"]:
        comp_file = os.path.join(shader_dir, f"{s}.comp")
        spv_file = os.path.join(shader_dir, f"{s}.spv")
        run([GLSLC, "-fshader-stage=compute", comp_file, "-o", spv_file], f"Compiling {s}.comp")

        with open(spv_file, "rb") as f:
            data = f.read()
        h_file = os.path.join(shader_dir, f"{s}_spv.h")
        with open(h_file, "w") as f:
            f.write("#pragma once\n#include <cstdint>\n#include <cstddef>\n\n")
            f.write(f"alignas(4) const uint32_t {s}_spv[] = {{\n")
            for i in range(0, len(data), 4):
                val = int.from_bytes(data[i:i+4], "little")
                f.write(f"0x{val:08x}, ")
                if (i // 4 + 1) % 8 == 0:
                    f.write("\n")
            f.write("\n};\n")
            f.write(f"const size_t {s}_spv_size = {len(data)};\n")

    # 2. Build Native C++ Library
    os.makedirs(BUILD_NATIVE, exist_ok=True)
    cmake_cmd = [
        CMAKE, "-B", BUILD_NATIVE, "-S", os.path.join(BASE_DIR, "src", "main", "cpp"),
        "-G", "Ninja",
        f"-DCMAKE_MAKE_PROGRAM={NINJA}",
        f"-DCMAKE_TOOLCHAIN_FILE={NDK}/build/cmake/android.toolchain.cmake",
        "-DANDROID_ABI=arm64-v8a",
        "-DANDROID_PLATFORM=android-34",
        "-DCMAKE_BUILD_TYPE=Release"
    ]
    run(cmake_cmd, "Configuring CMake for liblabbench_native")
    run([NINJA, "-C", BUILD_NATIVE], "Compiling liblabbench_native.so with Ninja")

    # 3. Setup Directories
    if os.path.exists(BUILD_DIR):
        shutil.rmtree(BUILD_DIR)
    classes_dir = os.path.join(BUILD_DIR, "classes")
    gen_dir = os.path.join(BUILD_DIR, "gen")
    lib_dir = os.path.join(BUILD_DIR, "lib", "arm64-v8a")
    os.makedirs(classes_dir, exist_ok=True)
    os.makedirs(gen_dir, exist_ok=True)
    os.makedirs(lib_dir, exist_ok=True)

    shutil.copy(os.path.join(BUILD_NATIVE, "liblabbench_native.so"), lib_dir)

    # 4. AAPT2 Compile Resources
    res_zip = os.path.join(BUILD_DIR, "res.zip")
    run([os.path.join(BUILD_TOOLS, "aapt2.exe"), "compile", "--dir", os.path.join(BASE_DIR, "src", "main", "res"), "-o", res_zip], "Compiling resources with aapt2")

    # 5. AAPT2 Link APK
    unaligned_apk = os.path.join(BUILD_DIR, "unaligned.apk")
    manifest = os.path.join(BASE_DIR, "src", "main", "AndroidManifest.xml")
    link_cmd = [
        os.path.join(BUILD_TOOLS, "aapt2.exe"), "link",
        "-I", PLATFORM_JAR,
        res_zip,
        "--manifest", manifest,
        "--min-sdk-version", "34",
        "--target-sdk-version", "34",
        "--java", gen_dir,
        "-o", unaligned_apk,
        "--auto-add-overlay"
    ]
    run(link_cmd, "Linking APK with aapt2")

    # 6. Javac
    java_files = []
    java_src_dir = os.path.join(BASE_DIR, "src", "main", "java")
    for root, _, files in os.walk(java_src_dir):
        for f in files:
            if f.endswith(".java"):
                java_files.append(os.path.join(root, f))
    for root, _, files in os.walk(gen_dir):
        for f in files:
            if f.endswith(".java"):
                java_files.append(os.path.join(root, f))

    javac_cmd = [JAVAC, "-source", "17", "-target", "17", "-cp", PLATFORM_JAR, "-d", classes_dir] + java_files
    run(javac_cmd, "Compiling Java classes with javac")

    # 7. D8 DEX
    class_files = []
    for root, _, files in os.walk(classes_dir):
        for f in files:
            if f.endswith(".class"):
                class_files.append(os.path.join(root, f))

    d8_bat = os.path.join(BUILD_TOOLS, "d8.bat")
    d8_cmd = [d8_bat, "--min-api", "34", "--lib", PLATFORM_JAR, "--output", BUILD_DIR] + class_files
    print("[*] Creating classes.dex with d8...")
    res = subprocess.run(d8_cmd, capture_output=True, text=True, shell=True)
    if res.returncode != 0:
        print("[ERROR] d8 failed:", res.stderr)
        sys.exit(1)

    # 8. Package DEX and native libs into APK
    run([JAR, "uf", unaligned_apk, "-C", BUILD_DIR, "classes.dex"], "Adding classes.dex to APK")
    run([JAR, "uf", unaligned_apk, "-C", BUILD_DIR, "lib"], "Adding arm64-v8a native libraries to APK")

    # 9. Zipalign
    aligned_apk = os.path.join(BUILD_DIR, "aligned.apk")
    run([os.path.join(BUILD_TOOLS, "zipalign.exe"), "-f", "-p", "4", unaligned_apk, aligned_apk], "Aligning APK with zipalign")

    # 10. Apksigner
    apksigner_bat = os.path.join(BUILD_TOOLS, "apksigner.bat")
    sign_cmd = [apksigner_bat, "sign", "--ks", KEYSTORE, "--ks-pass", "pass:android", aligned_apk]
    print("[*] Signing APK with apksigner...")
    res = subprocess.run(sign_cmd, capture_output=True, text=True, shell=True)
    if res.returncode != 0:
        print("[ERROR] apksigner failed:", res.stderr)
        sys.exit(1)

    print(f"\n[+] Build successful: {aligned_apk}")
    return aligned_apk

if __name__ == "__main__":
    build_apk()
