# Film-Camera Handoff Document (18 Oct 2026)

**Target Device:** Samsung Galaxy S24+ (`SM-S926B`, Exynos 2400, Android 16, One UI 8.5)  
**Connected Hardware ID:** `RZCY2069HBL`  
**Current Branch:** `claude/ois-stabilisation`  
**Base Commit:** `c8bbf9f` (`main`, PRs #1–#12 merged)  
**Package:** `com.s24film.camera`

---

## 1. What was Completed & Verified in this Session

### A. Hardware OIS & Video EIS Flags (Step 1)
- **Files Modified:**
  - `phase1-skeleton/src/main/java/com/s24film/camera/CameraEngine.java`
  - `phase1-skeleton/src/main/java/com/s24film/camera/DebugControl.java`
- **Implementation:**
  - Evaluated hardware capabilities dynamically using `CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION` and `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES`.
  - Added `hasOis` and `hasVideoStabilization` tracking in `CameraEngine.checkStabilizationSupport()`.
  - Added `applyStabilization(CaptureRequest.Builder builder)`:
    - Sets `LENS_OPTICAL_STABILIZATION_MODE_ON` for lenses with physical voice-coil motors (Wide `0` ISOCELL GN3 and Tele `6` S5K3K1).
    - Falls back cleanly to `LENS_OPTICAL_STABILIZATION_MODE_OFF` on lenses without physical OIS (Ultrawide `2` IMX564 and Front `1` S5K3LU).
    - Enables `CONTROL_VIDEO_STABILIZATION_MODE_ON` during video recording when supported.
  - Attached `applyStabilization` to `onConfigured()`, `applyManualExposure()`, and `applyFocus()`.
  - Added `hasOis()` and `hasVideoStabilization()` public getters.
- **Hardware Verification (Logcat Proof on device `RZCY2069HBL`):**
  - Wide (`0`): `Stabilization for 0 (1x Wide): OIS=true, VideoStab=true` -> `HAL active stabilization: OIS=true, VideoEIS=false`
  - Tele (`6`): `Stabilization for 0 (3x Tele #6): OIS=true, VideoStab=true` -> `HAL active stabilization: OIS=true, VideoEIS=false`
  - Ultrawide (`2`): `Stabilization for 2 (0.6x UW): OIS=false, VideoStab=false` -> `HAL active stabilization: OIS=false, VideoEIS=false`

### B. User Interface, Sound & Feedback Polish
- **Files Modified:**
  - `phase1-skeleton/src/main/res/layout/activity_main.xml`
  - `phase1-skeleton/src/main/java/com/s24film/camera/MainActivity.java`
  - `phase1-skeleton/src/main/java/com/s24film/camera/DebugControl.java`
- **Implementation:**
  - **Live On-Screen OIS Badge:** Added ` \u00b7 OIS` to `MainActivity.refreshReadout()`. Shows `1× · OIS` or `3× · OIS` in the top readout banner in real-time; automatically disappears on Ultrawide and Front.
  - **Mechanical Shutter Audio & Haptics:** Wired `FilmFx.shutterOpen()` and `FilmFx.shutterClose()` into Modern mode shutter clicks (`onClick` and `onCaptureSaved`/`onDngError`).
  - **Viewfinder Curtain Flash:** Added `view_shutter_flash` overlay in `activity_main.xml` with a 120 ms mirror/curtain blackout animation and shutter button spring bounce (`ShutterBounceTask` / `FlashDismissTask`).
  - **ADB Automation Routing:** Routed `cmd capture` in `DebugControl` through `MainActivity.triggerUiCapture()` so scripted test captures trigger full on-screen visual animations, audio clicks, roll frame counter increments, and thumbnail updates.

---

## 2. Hard-Won Gotchas & Critical Constraints

1. **Samsung Exynos Uni-HAL Watchdog:**
   - **NEVER leave the camera stream unread.** If the companion YUV or RAW sensor stream is left unread or blocked during stacking, long exposure, or lens switching, the Samsung Uni-HAL watchdog triggers an immediate kernel reboot of the device. Every stacking path must keep consuming frames or cancel cleanly.
2. **Preview Shutter HAL Wedge:**
   - Exposure times $\ge 1/15\text{ s}$ (66 ms) on repeating preview requests will wedge the Exynos HAL and freeze frame delivery. Long exposures must go through a separate still request or the burst/stack path.
3. **Build Script & Toolchain (`.\build_sprint1.ps1`):**
   - Javac requires `-parameters`, `-XDstringConcat=inline`, `-source 17 -target 17`.
   - Single `classes.jar` input to `d8` to prevent exceeding cmd.exe's 8191-character command line limit.
   - Use named static inner classes for runnables/listeners where possible to avoid anonymous `$1` class issues.
4. **Orientation:**
   - Front camera orientation is mounted $+180^\circ$ relative to rear sensors.
5. **Git Merging:**
   - Merges to `main` require explicit user approval from SDK. Always work in feature branches and open a PR.

---

## 3. Next Steps (In Priority Order)

### Step 2: Multi-Frame RAW Burst Merge with Gyro Homography Alignment (HIGH PRIORITY)
- **Goal:** Fix high noise across all lenses (especially front selfie and 3x tele) and eliminate HDR motion ghosting.
- **Assets Ready:**
  - `phase2_isp_pack/shaders/stage3_burst_merge.comp`: 500 Hz gyro homography alignment, CFA parity snapping, patch-based ghost rejection.
  - `CameraEngine.java`: `stackBegin()`, `stackAdd()`, `stackFinish()` exist.
- **Tasks:**
  1. Route standard photo capture through a 4–6 frame RAW burst merge.
  2. Wire `ImuTelemetry` gyro homography matrices into push constants for `stage3_burst_merge.comp` in `vulkan_raw_pipeline.cpp`.
  3. Ensure CompanionDrain continues running to prevent Uni-HAL watchdog reboot.

### Step 3: Highlight-Weighted / Matrix Metering with Clipping Guard
- **Goal:** Replace simple mean-luminance auto-exposure in `meterDesiredIsoSeconds()` with a 5x5 zone-matrix metering algorithm that guards against highlight blowout on skies and backlit scenes.

### Step 4: Spectral Film Model as Default Look + Halation / Bloom
- **Goal:** Make `SpectralFilm.h` the default look pipeline, adding highlight bloom and remjet halation tied to linear Bayer highlight thresholds.

### Step 5: Menu & Settings Redesign
- **Goal:** Consolidate the tabbed sheet and pro panel into a clean pull-up interface.

---

## 4. Build, Deploy & Verify Commands

```powershell
# 1. Build APK
powershell -ExecutionPolicy Bypass -File .\build_sprint1.ps1

# 2. Install on Device
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r phase1-skeleton\build\aligned.apk

# 3. Launch App
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am start -n com.s24film.camera/.MainActivity

# 4. Check OIS in Logcat
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -d -s S24FilmCamera | Select-String "Stabilization|HAL active"

# 5. Trigger Capture via ADB (now animates UI and plays sound)
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am broadcast -a com.s24film.camera.DEBUG --es cmd capture
```
