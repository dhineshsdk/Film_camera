# Master Engineering Blueprint (v4.1 — Phase 0 Hardware-Verified Edition): Native Raw Film & Cinema Camera Pipeline for Samsung Galaxy S24+ (Exynos 2400 / One UI 8.5)

## 1. Executive Vision & System Design Philosophy

This document is the **v4.1 Hardware-Verified Master Engineering Blueprint** for building a dedicated, zero-compromise photochemical film and digital cinema camera application exclusively for your **Samsung Galaxy S24+ (SM-S926B)** running **One UI 8.5 (Android 16, Build `S926BXXSGDZG1`, SDK 36)** on the deca-core [Samsung Exynos 2400 SoC (s5e9945)](https://www.google.com/search?q=https%3A%2F%2Fsemiconductor.samsung.com%2Fprocessor%2Fmobile-processor%2Fexynos-2400%2F) and **Samsung Xclipse 940 (AMD RDNA 3 Mobile, Driver `100663830.0`, Vulkan `1.3.279`) GPU**. It integrates all live hardware findings from `phase0-probe-report.md`, `MASTER-PLAN.md`, `MASTER-IDEAS.md`, all 97 verified manufacturer datasheet profiles, and all review corrections (`E1–E8` and `B1–B7`).

The application is engineered around five core principles:

1. **Single-Target Silicon Optimization (Verified on Device):** Every memory barrier, workgroup dimension (`16×16` threads = 8 lock-step **Wave32** wavefronts enforced via `VK_EXT_subgroup_size_control`), Local Data Share (LDS) tile allocation (**strictly** $\le 32\text{ KB}$ per workgroup as verified by `maxComputeSharedMemorySize = 32768`), bank-conflict padding (`+1` DWORD offset), hardware ray-tracing query (`VK_KHR_ray_query = TRUE`), and DMA-BUF zero-copy descriptor (`VK_ANDROID_external_memory_android_hardware_buffer = TRUE`) is hard-tuned to your physical S24+.

2. **Public Multi-Camera RAW Access (Verified Without Fragile Spoofing):** Phase 0 hardware probing proved that on **One UI 8.5 (Android 16)**, Samsung publicly exposes `RAW_SENSOR (32)` and 10-bit `YCBCR_P010 (54)` on **Camera 0 (`1x` Wide ISOCELL GN3)**, **Camera 2 (`0.6x` Ultrawide Sony IMX564)**, and **Camera 1 & 3 (`Front` ISOCELL S5K3LU)** without requiring package-name spoofing, while the **3x Telephoto (ISOCELL S5K3K1)** is exposed as a physical sub-camera (`["2", "5", "6"]` / internal `21`/`52`) of logical Camera `0`.

3. **Split-Speed "Live Viewfinder vs. Master Darkroom Render" Topology:** Decouples **Real-Time Viewfinder Pre-Visualization** (`<16.0 ms` measured frame target) from an **Uncompromised Offline `FP32` Master Render Pipeline** ("Save DNG First, Process Later") that executes iterative [RCD](https://www.google.com/search?q=https%3A%2F%2Fgithub.com%2FLuisSR%2FRCD-Demosaicing) or AMaZE/LMMSE demosaicing, Poisson SOR highlight reconstruction, 16-band 2D FFT Fraunhofer aperture diffraction, Seidel wavefront lens physics, and [Newson et al. stochastic Monte Carlo Boolean film grain](https://www.google.com/search?q=https%3A%2F%2Fwww.lirmm.fr%2F%7Enfaraj%2Fpublications%2Ffilm_grain_ipol%2F2017_Newson_film_grain.pdf).

4. **97 Real Digitised Manufacturer Datasheet Profiles + Cinema & Fujifilm Engines (\~105 Total Looks):** Replaces synthetic placeholder tables with **97 real digitised film & paper profiles** (28 [Spektrafilm](https://www.google.com/search?q=https%3A%2F%2Fgithub.com%2Fandreavolpato%2Fspektrafilm) CC BY-SA 4.0 profiles + 69 profiles digitised directly from 68 Kodak, Fujifilm, Ilford, Agfa, Konica, Foma, and ORWO datasheet PDFs), paired with **9 Digital Cinema Camera pipelines** and **14 parametric Fujifilm-style simulations**.

5. **Tactile Analog Soul & Built-In 2,600-Nit HDR Darkroom:** Combines physical lens archetypes (Helios 44-2 swirl, Leica Summilux glow, Trioplan soap-bubble, Super-Takumar Thorium glass, Panavision 2× anamorphic), virtual camera bodies, binaural mechanical acoustics, and an accelerometer-driven darkroom developer tray with a full `ACEScct`/`Oklch` non-destructive editor on the S24+'s **2,600-nit Dynamic AMOLED 2X display**.

## 2. Phase 0 Verified Hardware Topology & Mandatory Silicon Rules

### 2.1 Verified Physical Camera Quartet on Your Galaxy S24+ (`phase0-probe-report.json`)

| Optical Channel | Physical Sensor Model | Public / Physical Camera ID | Verified CFA Pattern (`u_cfa_phase`) | Verified Active Array & Max RAW / P010 Rates | Verified White / Black Level & Exposure Range | Mandatory Session & Demosaic Rules | 
| ----- | ----- | ----- | ----- | ----- | ----- | ----- | 
| **Primary Wide (`1x`, 24 mm)** | [Samsung ISOCELL GN3 (S5KGN3)](https://www.google.com/search?q=https%3A%2F%2Fsemiconductor.samsung.com%2Fimage-sensor%2Fmobile-image-sensor%2Fisocell-gn3%2F), $f=5.4\text{ mm}$ | Logical **`"0"`** (Physical sub-IDs: `["2","5","6"]`) | **`GBRG` (`cfaCode = 2`)** | `4080 × 3060` (12.5 MP):  • `RAW_SENSOR (32)` @ `30.0 fps`  • `YCBCR_P010 (54)` @ `30.0 fps` (`4080×3060`, `3840×2160`) & `60.0 fps` (`1920×1440`, `1920×1080`) | `WL = 1023`  `BL = [0,0,0,0]`  `85 µs – 100 ms`  `ISO 25 – 3200` | Must pass `u_cfa_phase = 2 (GBRG)` to demosaicer; must attach a companion Preview/YUV surface alongside `RAW_SENSOR` in `SessionConfiguration`. | 
| **Ultrawide (`0.6x`, 13 mm)** | **Sony IMX564**, $f=2.2\text{ mm}$ | Public **`"2"`** *(No spoofing needed!)* | **`RGGB` (`cfaCode = 0`)** | `4000 × 3000` (12.0 MP):  • `RAW_SENSOR (32)` @ `30.0 fps`  • `YCBCR_P010 (54)` @ `30.0 fps` (4K) & `60.0 fps` (1440p/1080p) | `WL = 1023`  `BL = [0,0,0,0]`  `60 µs – 100 ms`  `ISO 48 – 3200` | Directly openable on `"2"` with `u_cfa_phase = 0 (RGGB)` and a companion preview surface. | 
| **Telephoto (`3x`, 67 mm)** | **Samsung ISOCELL S5K3K1**, $f=7.0\text{ mm}$ | Physical Sub-ID of **`"0"`** (Internal `21`/`52`) | **`GBRG` (`cfaCode = 2`)** | `3648 × 2736` (**10.0 MP true native Bayer** @ `30.0 fps`) | `WL = 1023`  `BL = [0,0,0,0]`  `48.7 µs – 100 ms` | Request via `OutputConfiguration.setPhysicalCameraId(...)` on Logical Camera `"0"` with `u_cfa_phase = 2 (GBRG)`. | 
| **Front Selfie (`1x`, 26 mm)** | **Samsung ISOCELL S5K3LU**, $f=3.3\text{ mm}$ | Public **`"1"`** (Full) & **`"3"`** (Crop) | **`GRBG` (`cfaCode = 1`)** | `"1"`: `4000 × 3000` @ `30.0 fps` (`RAW_SENSOR` & `P010`)  `"3"`: `3392 × 2544` @ `30.0 fps` | `WL = 1023`  `BL = [0,0,0,0]`  `57 µs – 100 ms`  `ISO 25 – 3200` | Must pass `u_cfa_phase = 1 (GRBG)`. Supports concurrent streaming with Camera `"0"` (`["0","1"]` and `["0","3"]`). | 

### 2.2 Verified Xclipse 940 GPU & IMU Specifications (`phase0_vulkan_caps.json`)

| Silicon Block | Phase 0 Verified Value on SM-S926B | Mandatory Engineering Rule for Phase 1–6 | 
| ----- | ----- | ----- | 
| **GPU & Driver** | **Samsung Xclipse 940** (AMD RDNA 3 Mobile, 12 DCUs / 768 ALUs, Vulkan `1.3.279`, Driver `100663830.0`) | Immediate-mode RDNA 3 compute architecture (`B7` fix). Keep per-pixel grading stages fused in registers. | 
| **Max Shared Memory (LDS)** | **`32,768 bytes` (`32 KB`)** (`maxComputeSharedMemorySize`) | **Critical Phase 0 Correction:** Never allocate $> 32\text{ KB}$ of `shared` memory in any compute shader (`8,192` `float`s max). A `20×20` tile with `+1` bank-conflict padding (`20 × 21 × 4 = 1,680 bytes`) fits comfortably. | 
| **Subgroup (Wavefront) Control** | `subgroupSize_default = 64`, `min = 32`, `max = 64`; `VK_EXT_subgroup_size_control = TRUE` (`requiredSubgroupSizeStages = COMPUTE`) | Because the driver defaults to Wave64 (`64`), **every compute pipeline creation call must chain `VkPipelineShaderStageRequiredSubgroupSizeCreateInfoEXT` with `requiredSubgroupSize = 32`** to force RDNA 3 **Wave32** execution. | 
| **Verified Vulkan Extensions** | • `VK_ANDROID_external_memory_android_hardware_buffer = TRUE`  • `VK_KHR_synchronization2 = TRUE`  • `VK_KHR_sampler_ycbcr_conversion = TRUE`  • `VK_KHR_ray_query = TRUE` & `VK_KHR_acceleration_structure = TRUE`  • `VK_KHR_cooperative_matrix = FALSE` | • Zero-copy `AImageReader` (`AHardwareBuffer`) $\to$ `VkImage` is 100% supported.  • Hardware Ray Tracing (`VK_KHR_ray_query`) is available for Phase 6 lens flare.  • `cooperative_matrix` is false: use standard Wave32 compute shaders for GPU semantic masks (`E8`). | 
| **500 Hz IMU (`LSM6DSVTR`)** | STMicroelectronics `LSM6DSVTR` Uncalibrated Gyro & Accelerometer (`minDelayUs = 2000` $\implies$ **`500.00 Hz`**) + `com.samsung.sensor.vdis_gyro` | Must register `SensorEventListener` (or NDK `ASensorEventQueue`) on a dedicated non-blocking `HandlerThread` / `ALooper` with `HIGH_SAMPLING_RATE_SENSORS`. | 
| **Exynos Uni-HAL Session Rule** | Single-surface `RAW_SENSOR`-only sessions fail with `errorCode = 6`. | **Mandatory:** Always configure `CameraCaptureSession` with **two targets**: (1) a Preview `Surface` (`SurfaceView`/`TextureView`/`YUV_420_888`) and (2) the `ImageFormat.RAW_SENSOR (32)` `AImageReader` (`AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE`). | 
| **100 ms Hardware Exposure Cap** | `maxExposureMs = 100 ms` ($1/10\text{s}$) reported in standard Camera2 metadata. | Confirms our **Frame-Integration Long-Exposure Engine**: for shutter speeds $> 100\text{ ms}$ (e.g., $1/4\text{s}$ to Bulb), stack continuous back-to-back `100 ms` `RAW_SENSOR` frames in an `FP32` accumulation buffer. | 

## 3. Looks, Shooting Modes & Controls Architecture (`MASTER-PLAN.md` §4)

### 3.1 The \~105 Photochemical & Cinema Looks

| Category | Count | Source & Implementation | 
| ----- | ----- | ----- | 
| **Color Negative (C-41 Stills)** | 24 | Digitised manufacturer datasheet curves + Spektrafilm spectral profiles (`Claude-Project-Files/datasheet-data/`). | 
| **Color Reversal / Slide (E-6 & K-14)** | 16 | Digitised manufacturer Status A curves + K-14/E-6 dye-cloud scattering pre-filter. | 
| **Black & White Silver Halide** | 23 | Digitised $H\&D$ curves + spectral sensitivity curves + Rodinal/D-76/XTOL Nelson adjacency PDE. | 
| **Motion Picture Negative (ECN-2 / C-41)** | 16 | Digitised Kodak Vision3 / EXR / Fuji Eterna Status M curves + CineStill C-41 cross-processing & remjet-free TIR halation. | 
| **Motion Picture Print Stocks (ECP-2E)** | 3 | Kodak Vision 2383, Kodak Vision Premier 2393 (from Spektrafilm profiles), and Fuji Eterna-CP 3513DI (mapped from 2383; 3523XD intermediate dropped). | 
| **Digital Cinema Camera Pipelines** | 9 | ARRI ALEXA 35 (`AWG4`/`LogC4`/REVEAL), ALEXA Mini LF (`AWG3`/`LogC3`), RED IPP2 (`RWG`/`Log3G10`), Sony Venice 2 (`S-Gamut3.Cine`/`S-Log3`/`S-Cinetone`), Canon (`C-Log2/3`), Blackmagic Gen 5, Fujifilm `F-Log2`, Apple Log, DJI `D-Log`. | 
| **Fujifilm-Style Film Simulations** | 14 | Rebuilt as a 100% parametric scene-referred engine (`FujiRecipeUBO`: Provia, Velvia, Astia, Classic Chrome, Classic Neg, Nostalgic Neg, Reala Ace, PRO Neg Hi/Std, Eterna, Bleach Bypass, ACROS, Monochrome, Sepia). | 

### 3.2 Photo & Video Auto Mode vs. Pro Mode

Every look, lens archetype, and optical effect works in **both Stills and Video**:

* **Auto Mode (Film-Aware Intelligent Exposure):**

  * **Photo Auto:** Adapts metering to the active emulsion chemistry: **Color Negative** protects shadow density ($+0.5\text{ to }+1.0\text{ EV}$ bias), **Color Slide** protects specular highlights ($-0.7\text{ EV}$ ceiling clamp), and **Black & White** places textured shadows at Zone III and highlights at Zone VII.

  * **Video Auto:** Locks a **180° shutter angle** ($\text{shutter} = \frac{1}{2 \times \text{fps}}$), adjusting analog ISO (`25–3200`) first and engaging **Computational ND** in bright daylight.

  * **White Balance ("Neutral" vs. "Film-True" Toggle):** Defaults to scene-adaptive neutral CAT16 white balance, with a 1-tap **Film-True Switch** that locks the stock's native daylight ($5500\text{ K}$) or tungsten ($3200\text{ K}$) balance.

* **Pro Mode (Full Manual Override with Per-Dial Auto Latch):**

  * Physical haptic dials for **ISO (`25–3200`)**, **Shutter Speed (`48.7 µs – 100 ms` hardware + `>100 ms` frame-stacking Bulb) / Shutter Angle**, **White Balance (Kelvin + Duv Tint)**, **Manual Focus (Split-Prism, Microprism, & Peaking)**, **Exposure Compensation (**$\pm 5\text{ EV}$**)**, **Metering Mode**, and **Lens (`0.6x` ID 2, `1x` ID 0, `3x` Sub-ID, `Front` ID 1/3)**.

* **Per-Look Effect Sliders:** Resettable sliders for **Grain**, **Halation**, **Bloom / Diffusion**, **Look Strength**, **Sharpness / MTF**, **Gate Weave**, and **Vignette**.

## 4. Custom User-Space Vulkan 1.3 RAW ISP & `.mcraw` Video Engine (with Phase 0 & `B3` Updates)

### 4.1 Dual-Mode Stage 1 Ingest: Unpacked 16-Bit `RAW_SENSOR (32)` & Packed `RAW10 (37)`

Because Phase 0 showed that One UI 8.5 publicly delivers **`ImageFormat.RAW_SENSOR (32)`** (`16-bit unsigned integer per pixel`, `whiteLevel = 1023`, `blackLevel = [0,0,0,0]`), Stage 1 supports both layouts via a push constant `u_RawFormat`:

* **Mode 0 (`RAW_SENSOR = 32`, Primary 16-Bit Unpacked Path):**

  * Binds the imported `AHardwareBuffer` as `VK_FORMAT_R16_UINT` (or `uint16_t` storage buffer with row pitch `u_RowStrideBytes / 2u`).

  * Reads `uint raw_val = texelFetch(u_RawTex, pixel_coord, 0).r;` (or `raw_buf[pixel_coord.y * (u_RowStrideBytes >> 1u) + pixel_coord.x]`), subtracts `u_BlackLevel[cfa_idx]`, and divides by `float(u_WhiteLevel - u_BlackLevel[cfa_idx])` (`1023.0`).

* **Mode 1 (`RAW10 = 37`, 5-Byte Packed MIPI Path):**

  * Uses row-stride-aware byte indexing `uint byte_offset = pixel_coord.y * u_RowStrideBytes + (pixel_coord.x >> 2u) * 5u;` (`B3` fix).

### 4.2 Pre-Demosaic Conditioning & Dual-Tier Demosaicing (`GBRG`, `RGGB`, `GRBG` Verified)

* **Stage 2 & 3:** Airy-disk-aware $5\times 5$ Defective Pixel Correction ($\Psi_{\text{spec}} > 0.15$), $G_r/G_b$ green equilibration, Catmull-Rom $C^1$ bicubic Lens Shading Correction, and Generalized Anscombe Transform (GAT) spatiotemporal burst/Kalman denoising + [Mäkitalo-Foi unbiased inverse GAT](https://www.google.com/search?q=https%3A%2F%2Fwww.researchgate.net%2Fpublication%2F225301482_Optimal_Inversion_of_the_Generalized_Anscombe_Transformation_for_Poisson-Gaussian_Noise).

* **Stage 4 Demosaicing (Verified CFA Mapping & `32 KB` LDS Compliance):**

  * Maps `u_cfa_phase` dynamically from `CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT`:

    * `0 = RGGB` (Camera `2` Ultrawide IMX564)

    * `1 = GRBG` (Camera `1` & `3` Front S5K3LU)

    * `2 = GBRG` (Camera `0` Primary Wide S5KGN3 & Sub-Camera Telephoto S5K3K1)

    * `3 = BGGR`

  * Uses a `20×20` shared-memory tile with `+1` bank-conflict padding (`shared float s_tile[20][21];` = **`1,680 bytes`**, utilizing only **5.1%** of the Xclipse 940's `32 KB` LDS limit) and fixes the vertical Laplacian stencil (`s_tile[l.y - 2][l.x] + s_tile[l.y + 2][l.x]`).

* **Stage 5 Highlight Reconstruction:** Red-Black SOR Poisson gradient solver ($\omega_{\text{sor}} = 1.65$) + Oklab chromaticity roll-off above $L = 0.95$.

## 5. Spektrafilm + EmulsionDNA Photochemical Engine & The 97 Real Datasheet Profiles

1. **Corrected ACES RGC Input Gamut Compression (`E1` Fix):** 

   $$
   s = (l - t)\cdot\left(\left(\frac{1.0 - t}{l - t}\right)^{-p} - 1.0\right)^{-1/p}, \quad v_{\text{comp}} = \begin{cases} v, & v < t \\ t + s \cdot \left(\dfrac{(v - t)/s}{\left(1.0 + ((v - t)/s)^p\right)^{1/p}}\right), & v \ge t \end{cases}
   $$

    with $t = 0.815, l = 1.147, p = 1.20$.

2. **81-Band Sigmoidal Spectral Upsampling (`380–780 nm`) & 97 Verified JSON Profiles:**

   * Evaluates $S(\lambda; \mathbf{c}) = \frac{1}{2}\left(\frac{c_0 \lambda^2 + c_1 \lambda + c_2}{\sqrt{1 + (c_0 \lambda^2 + c_1 \lambda + c_2)^2}} + 1\right)$ against the **28 Spektrafilm profiles** and **69 digitised manufacturer datasheet profiles** in `Claude-Project-Files/datasheet-data/` (pre-baked into an `O(1)` 3D spectral basis LUT for live viewfinder speed, or integrated across all 81 bands in the master renderer).

3. **Exact Richards** $H\&D$ **Curve (`B1`, `B2` Fixes), Nelson Adjacency & Beer-Lambert Enlarger:**

   * Enforces $k_c = \frac{\gamma_c}{D_{c,\max} - D_{c,\min}}(1 + \nu_c)^{\frac{1+\nu_c}{\nu_c}}$ and $s = +1$ for both negative emulsions and RA-4 positive papers (`B2` fix), followed by $3050\text{ K}$ Planckian Beer-Lambert enlarger transmission, Hazeltine 0–50 printer lights (`12 points = 1 EV`, `C3` fix), CIE 1931 $2^\circ$ Standard Observer integration, and the official SMPTE ST 2065-1 $\mathbf{M}_{\text{XYZ}\to\text{ACEScg}}$ matrix (`B6` fix).

## 6. Wavefront Optics, 8 Legendary Lens Archetypes & Rounds 1–3 Feature Catalog

* **8 Legendary Optical Presets (Stage 7):**

  1. **Helios 44-2 58mm f/2 (1958 Biotar):** Cat-eye mechanical vignette + $r^4$ astigmatic **Swirly Bokeh**.

  2. **Leica Summilux 35mm f/1.4 "Steel Rim" v1 (1961):** Under-corrected $W_{040}$ spherical aberration **"Leica Glow"**.

  3. **Canon 50mm f/0.95 "Dream Lens" (1961):** Third-order coma + warm bronze veiling glare floor.

  4. **Meyer-Optik Trioplan 100mm f/2.8 (1916):** Over-corrected spherical aberration **"Soap Bubble" hollow bokeh rings** + 15-blade iris.

  5. **Asahi Pentax Super-Takumar 50mm f/1.4 (1964):** **Radioactive Thorium glass** warm spectral transmission + 7-color rainbow flare.

  6. **Carl Zeiss Planar 50mm f/1.4 T\* (1975):** Multi-coated T\* micro-contrast + **"Ninja Star" sawtooth iris** at $f/2\text{–}f/2.8$.

  7. **Joseph Petzval 85mm f/2.2 (1840):** $\cos^4(\theta)$ falloff + quadratic sagittal/tangential tunnel swirl.

  8. **Panavision C-Series 2× Anamorphic (1968):** 2:1 elliptical squeeze + horizontal blue $\text{MgF}_2$ streak flares + focus breathing.

* **Custom Lens & Optical Physics:** Iris blade count (5–18) & curvature, $\text{LoCA}$ green/magenta bokeh fringing, Canada Balsam delamination, millimeter-scaled Pro-Mist/Glimmerglass/CineBloom diffusion, 5-level mipmap TIR halation (`B4` fix), and **Hardware Ray-Traced (`VK_KHR_ray_query = TRUE`) Vintage Lens Flare**.

* **Multi-Sensor, Cinema & Tactile Craft:**

  * **Dual-Camera Streaming (`["0","1"]` & `["0","3"]` confirmed + `"0"`/`"2"` switcher):** Director's B-Cam viewfinder, Out-of-Frame Sun Flare Tracker, and Split-Negative HDR.

  * **500 Hz `LSM6DSVTR` Gyro & Accel Features:** O'Connor 2575D 15 kg Fluid Cinema Head stabilization, Accelerometer-Driven Darkroom Developer Tray Agitation, Rolleiflex Waist-Level auto-trigger, and Gyro-Reactive Baryta Paper Sheen Inspector.

  * **Hollywood Cinema Stage:** Wong Kar-wai Two-Stage Optical Step-Printer (6/8/12 fps Neg $\to$ 24 fps Print), 400ft Virtual Magazine Counter, 16mm Optical Soundhead Audio, and 100ms-stacked "Darkroom Emergence" Long Exposure.

## 7. Complete 7-Phase Execution Roadmap (Updated with Phase 0 Complete)

| Phase | Status | Milestone Name | Core Engineering & Feature Deliverables | Completion Criteria ("Done When") | 
| ----- | ----- | ----- | ----- | ----- | 
| **Phase 0** | **✅ DONE** | **Setup & One UI 8.5 Phone Probe** | Windows SDK/ADB toolchain installed on `DESKTOP-S7SLO8K`; S24+ probed over USB (`phase0-probe-report.md`). Verified public Ultrawide RAW (`Cam 2`), exact CFA codes (`GBRG`/`RGGB`/`GRBG`), `32 KB` LDS, Wave32 `subgroup_size_control`, `VK_KHR_ray_query`, and `500 Hz` `LSM6DSVTR` IMU. | `phase0-probe-report.md` and `phase0-probe-report.json` saved in repo. | 
| **Phase 1** | **👉 NEXT** | **Camera-to-GPU Zero-Copy Skeleton** | Install NDK + CMake on `DESKTOP-S7SLO8K`. Build Camera2 (`Preview Surface` + `RAW_SENSOR (32)` `ImageReader`) $\to$ zero-copy `AHardwareBuffer` (`VK_FORMAT_R16_UINT`) $\to$ Wave32 Vulkan 1.3 live preview with `GBRG`/`RGGB`/`GRBG`-aware fast demosaic; **"Save DNG First, Process Later"** disk writer; camera switcher (`1x` Cam 0, `0.6x` Cam 2, `Front` Cam 1); live HUD overlay (GPU ms, FPS, SoC temp); and v1 **Windows PC Test Harness**. | You open the app on your S24+, see a live RAW-demosaiced preview across `1x`, `0.6x`, and `Front`, and tap shutter to save a valid DNG. | 
| **Phase 2** | Pending | **Stills RAW ISP (Stages 1–6) & Calibration** | Implement Stages 1–6 (`rowStride` + `u_cfa_phase` + vertical Laplacian `B3` fixed; column-major AP1 `B6` fixed). Per-camera dark-frame, flat-field, and color-chart calibration; 4–8 frame burst merge & `>100 ms` long-exposure frame stacker; 1s pre-capture RAW ring buffer; background render queue; and 2,600-nit Ultra HDR viewfinder & `JPEG_R` export. | A still shot beats Samsung's native RAW for noise and color accuracy in the PC harness comparison. | 
| **Phase 3** | Pending | **The 97 Film Stocks & Optical Engine (Stages 7–11)** | Hybrid **Spektrafilm + EmulsionDNA** engine (`E1` ACES RGC gamut compression & `B1, B2, B4, B5` fixed). Load the 5 reference stocks first (**Portra 400, Ektar 100, Tri-X 400, Vision3 500T, CineStill 800T**), then all \~105 looks from `datasheet-data/`. Gabor live + stationary Newson Monte Carlo master grain, 5-level mipmap halation, millimeter-scaled diffusion, diffraction, bokeh, push/pull, expired film, light leaks, parametric Fujifilm recipes, 2-up/4-up comparison, Roll Mode, rebate borders, 90s date stamp, QR codes, and `.cube` export. | All \~105 looks are in, the 5 reference stocks run live under `~16 ms/frame`, and master renders match the datasheet curves. | 
| **Phase 4** | Pending | **Tactile Camera Bodies, Lenses & Darkroom Editor** | Photo Auto & Pro modes; 8 Legendary Lens Presets + LoCA/coating customizer; virtual bodies (Rangefinder split-prism, 6×6 waist-level TLR, Half-Frame diptychs, 90s compact); binaural acoustics & clockwork haptics; Zone System meter & Sunny 16 coach; One UI 8.5 integration; and the **Darkroom Editor** (dodge/burn, scopes, Contact Sheet + red grease pencil, Dichroic/Split-Grade enlarger, 500 Hz accelerometer tray agitation, baryta paper sheen viewer, 2,600-nit studio beauty light, undo/batch/virtual copies, auto PC backup). | You can load a virtual cassette, shoot a 36-exposure roll, agitate/develop it, mark up the contact sheet, and edit prints non-destructively. | 
| **Phase 5** | Pending | **Hollywood Cinema Video Pipeline** | Video Auto & Pro modes. **Mode B first** (10-bit `P010` @ 60fps 1440p/1080p & 30fps 4K baked or clean Log with live LUT preview), then **Mode A** (`.mcraw` + 500 Hz `LSM6DSVTR` gyro track). 500 Hz O'Connor fluid-head gyro physics, computational ND & 180° shutter angle, Wong Kar-wai step-printer, gate weave & emulsion drift, 400ft magazine counter, 16mm optical soundhead audio, zebras/guides/tally, USB-C mic gain, and Live Motion Clips. | A 10-minute 4K cinema clip records without overheating or dropped frames. | 
| **Phase 6** | Pending | **Advanced Multi-Sensor & Creative Extras** | Hardware Ray-Traced Vintage Lens Flare (`VK_KHR_ray_query`), Director's B-Cam Matrix (`["0","1"]`), Out-of-Frame Sun Flare Tracker, handheld multi-frame super-resolution (`E3`), 3D Scheimpflug tilt-shift, Aerochrome IR, Prokudin-Gorsky trichrome, Sabattier solarization, Film Soup / Static / Bromide Drag / X-Ray fog, H-Alpha astro plates, Datasheet-to-Shader compiler, 3D wigglegram, negative scanner, pocket light meter, custom GLSL slot, and Windows Desktop Master Renderer. | All modular Phase 6 extras verified on device. | 
