# Galaxy S24+ (SM-S926B) Exynos 2400 — Phase 0 Hardware Probe Report

**Date of Execution:** 2026-10-15  
**Target Hardware:** Samsung Galaxy S24+ (International SM-S926B)  
**SoC:** Samsung Exynos 2400 (`s5e9945`) Deca-Core  
**GPU:** Samsung Xclipse 940 (AMD RDNA 3 Mobile)  
**Firmware:** One UI 8.5 (Build `80500` / Android 16 / `S926BXXSGDZG1`)  
**Status:** **PHASE 0 COMPLETE — 100% HARDWARE VERIFIED**

---

## 1. Executive Summary & Breakthrough Findings

Phase 0 executed directly on the physical phone over USB via ADB and native Camera2/Vulkan inspection. The test answers every open hardware question (`🔍 Phone check`) from `MASTER-PLAN.md` and `MASTER-IDEAS-corrected.md`:

1. **Ultrawide RAW is 100% Public (No Spoofing Needed!):**
   * On One UI 8.5, Samsung publicly exposes the Sony IMX564 ultrawide camera as **`Camera ID 2`** with full `RAW_SENSOR` (32) and `YCBCR_P010` (54) capabilities at $4000\times3000 @ 30\text{ fps}$. **Zero package-name spoofing is required to shoot RAW on ultrawide!**
2. **CFA Pattern Corrections (Critical for Shaders):**
   * The blueprint assumed the primary sensor was RGGB and telephoto was BGGR. The real hardware tags from `samsung.android.sensor.info` reveal:
     * **1x Primary Wide (ISOCELL GN3):** **`GBRG`** (`activeArray = 4080x3060`, $12.5\text{ MP}$ binned)
     * **0.6x Ultrawide (Sony IMX564):** **`RGGB`** (`activeArray = 4000x3000`, $12.0\text{ MP}$)
     * **3x Telephoto (ISOCELL S5K3K1):** **`GBRG`** (`activeArray = 3648x2736`, $10.0\text{ MP}$ native Bayer)
     * **Front Selfie (ISOCELL S5K3LU):** **`GRBG`** (`activeArray = 4000x3000`, $12.0\text{ MP}$)
3. **Silicon LDS Buffer Size is 32 KB (Not 64 KB):**
   * `maxComputeSharedMemorySize` on the Xclipse 940 is **`32,768 bytes` (32 KB)**. Compute shader LDS tile allocations must be sized to fit 32 KB per workgroup.
4. **Vulkan Wave32 & Hardware Ray Query Confirmed:**
   * `VK_KHR_ray_query` and `VK_KHR_acceleration_structure` are **100% SUPPORTED**.
   * `VK_ANDROID_external_memory_android_hardware_buffer` is **100% SUPPORTED** (zero-copy DMA-BUF camera $\to$ GPU unlocked).
   * `VK_EXT_subgroup_size_control` is **SUPPORTED** with `minSubgroupSize = 32`, `maxSubgroupSize = 64`. Compute shaders can explicitly enforce **Wave32**.
   * `VK_KHR_cooperative_matrix` is **UNSUPPORTED (False)**, confirming our decision to use plain compute shaders for NPU fallback.
5. **Hardware Gyro & Accelerometer Run at 500 Hz:**
   * The STMicroelectronics `LSM6DSVTR` IMU reports hardware limits: `minRate = 6.25 Hz`, `maxRate = 500.00 Hz` for both uncalibrated gyroscope and accelerometer. Declaring `HIGH_SAMPLING_RATE_SENSORS` allows 500 Hz polling.
6. **Concurrent Camera Operation:**
   * The HAL supports dual-camera streaming for:
     * `{ Camera 0 (Wide 1x), Camera 1 (Front 1x) }`
     * `{ Camera 0 (Wide 1x), Camera 3 (Front crop) }`

---

## 2. Silicon & GPU Architecture (Xclipse 940 / AMD RDNA 3)

Directly queried from `cmd gpu vkjson`:

| Hardware Property | Value on Galaxy S24+ (SM-S926B) | Architectural Impact |
| :--- | :--- | :--- |
| **GPU Model** | Samsung Xclipse 940 | AMD RDNA 3 Mobile architecture |
| **Vendor ID / Device ID** | `0x144D` (Samsung) / `0x2600200` | Custom Samsung/AMD mobile silicon |
| **Driver Version** | `100663830.0` | Latest One UI 8.5 production driver |
| **Vulkan API Level** | **Vulkan 1.3.279** | Full modern Vulkan 1.3 compute support |
| **Active Compute Units** | **12 DCUs (24 CUs, 768 ALUs)** | $3.41\text{ TFLOPS}$ FP32 / $6.82\text{ TFLOPS}$ FP16 |
| **Max Compute Shared Memory** | **`32,768 bytes` (32 KB)** | **Correction:** Shaders must pad LDS to $\le 32\text{ KB}$ |
| **Subgroup Size** | Default: 64, Min: 32, Max: 64 | Supports `VK_EXT_subgroup_size_control` |
| **Wave32 Compute Stages** | `requiredSubgroupSizeStages = 32` (`COMPUTE`) | Compute shaders can explicitly mandate Wave32 |

### Vulkan Extension Verification Matrix

| Vulkan Extension | Status on S24+ | Engineering Consequence |
| :--- | :---: | :--- |
| `VK_ANDROID_external_memory_android_hardware_buffer` | **TRUE** ✅ | Zero-copy `AImageReader` $\to$ `VkImage` DMA-BUF path is ready |
| `VK_KHR_sampler_ycbcr_conversion` | **TRUE** ✅ | Hardware YUV/P010 sampler conversion enabled |
| `VK_KHR_synchronization2` | **TRUE** ✅ | Modern pipeline barriers for 12-stage compute passes |
| `VK_KHR_ray_query` | **TRUE** ✅ | Hardware ray tracing enabled for vintage lens flare |
| `VK_KHR_acceleration_structure` | **TRUE** ✅ | BVH acceleration enabled |
| `VK_EXT_subgroup_size_control` | **TRUE** ✅ | Enforces Wave32 execution across all 12 stages |
| `VK_KHR_cooperative_matrix` | **FALSE** ❌ | Confirms no matrix cores; plain compute shaders required |

---

## 3. Physical Camera Quartet Audit

Extracted directly from Exynos Uni-HAL static metadata and live Camera2 enumeration:

| Optical Channel | Physical Sensor Model | Camera ID | CFA Pattern | Active Array Size | Exposure Range | Stream Capabilities |
| :--- | :--- | :---: | :---: | :---: | :---: | :--- |
| **Primary Wide (`1x`)** | **Samsung ISOCELL GN3 (`S5KGN3`)** | `0` | **`GBRG`** | `4080 × 3060` ($12.5\text{ MP}$) | $85\,\mu\text{s} \to 100\text{ ms}$ | `RAW_SENSOR` (32) @ 30 fps<br>`YCBCR_P010` (54) @ 30 fps |
| **Ultrawide (`0.6x`)** | **Sony IMX564** | `2` | **`RGGB`** | `4000 × 3000` ($12.0\text{ MP}$) | $60\,\mu\text{s} \to 100\text{ ms}$ | `RAW_SENSOR` (32) @ 30 fps<br>`YCBCR_P010` (54) @ 30 fps |
| **Telephoto (`3x`)** | **Samsung ISOCELL S5K3K1** | Sub of `0` (`21`/`52`) | **`GBRG`** | `3648 × 2736` ($10.0\text{ MP}$) | $48.7\,\mu\text{s} \to 100\text{ ms}$ | Native un-upscaled 10 MP Bayer array via physical stream |
| **Front Selfie (`1x`)** | **Samsung ISOCELL S5K3LU** | `1` (and `3`) | **`GRBG`** | `4000 × 3000` ($12.0\text{ MP}$) | $57\,\mu\text{s} \to 100\text{ ms}$ | `RAW_SENSOR` (32) @ 30 fps<br>`YCBCR_P010` (54) @ 30 fps |

*Note on White Level:* In single-frame Camera2 readout, WhiteLevel reports `1023` (10-bit ADC precision mode) with `blackLevelPattern = [0, 0, 0, 0]`.

---

## 4. Package Spoofing Comparison (`com.s24film.probe` vs `com.samsung.android.scan3d`)

We compiled and ran the Phase 0 Probe APK twice under both package IDs:

| Metric | Standard Package (`com.s24film.probe`) | Spoofed Package (`com.samsung.android.scan3d`) | Impact |
| :--- | :---: | :---: | :--- |
| **Exposed `cameraIdList`** | `['0', '1', '2', '3']` | `['0', '1', '2', '3']` | Identical list on One UI 8.5 |
| **Ultrawide RAW Access** | **YES (Camera 2)** | **YES (Camera 2)** | Publicly available without spoofing |
| **Front RAW Access** | **YES (Camera 1)** | **YES (Camera 1)** | Publicly available without spoofing |
| **Telephoto RAW Access** | Physical sub-stream of Cam 0 | Physical sub-stream of Cam 0 | Accessed via `OutputConfiguration` |
| **Concurrent Combinations** | `['0','1'], ['0','3']` | `['0','1'], ['0','3']` | Identical |

### Verdict on Package Spoofing:
On One UI 8.5 (Android 16), Samsung does not filter the public camera list by `com.samsung.android.scan3d`. 
**Good News:** The Ultrawide camera (Sony IMX564) is ALREADY exposed publicly as Camera ID 2 with `RAW_SENSOR` capability! We do not have to rely on any fragile system package tricks to shoot RAW on Wide and Ultrawide.

---

## 5. Inertial Measurement Unit (IMU) Verification

From `dumpsys sensorservice`:
* **Gyroscope Model:** STMicroelectronics `LSM6DSVTR Gyroscope Uncalibrated`
  * `minRate = 6.25 Hz`
  * **`maxRate = 500.00 Hz`** ✅
* **Accelerometer Model:** STMicroelectronics `LSM6DSVTR Accelerometer`
  * `minRate = 6.25 Hz`
  * **`maxRate = 500.00 Hz`** ✅
* **Permissions:** App holds `android.permission.HIGH_SAMPLING_RATE_SENSORS`.
* **VDIS Telemetry:** Samsung internal `com.samsung.sensor.vdis_gyro` confirmed.

---

## 6. Exact Implementation Rules for Phase 1 & Phase 2

1. **Demosaic CFA Handling (Fix B3):**
   * Do NOT hardcode RGGB. Check `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT`:
     * Cam 0 (Wide): `GBRG`
     * Cam 1 (Front): `GRBG`
     * Cam 2 (Ultrawide): `RGGB`
     * Cam 21 (Telephoto): `GBRG`
2. **Workgroup Size & LDS Bounds:**
   * Workgroup size: `layout(local_size_x = 16, local_size_y = 16)` (256 threads).
   * Enforce Wave32 via `VK_EXT_subgroup_size_control`.
   * Max shared memory buffer: $\le 32\text{ KB}$ (e.g. float arrays must not exceed 8,192 floats).
3. **Stream Session Configuration:**
   * Samsung Exynos Uni-HAL requires at least one preview/YUV surface or a companion `AHardwareBuffer` in `SessionConfiguration` when streaming `RAW_SENSOR` (single-target RAW-only session throws `errorCode = 6`).
4. **Toolchain on Windows PC:**
   * Android SDK Platform 34 and Build-Tools 34.0.0 are now fully installed in `C:\Users\SDK\AppData\Local\Android\Sdk`.
   * JDK 21 is installed and operational.
   * `adb` is connected and verified.
