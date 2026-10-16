# Film Camera App for Galaxy S24+ (Exynos): Master Ideas, Reality-Checked

**Last updated:** 2026-10-14
**Based on:** `MASTER-IDEAS.md` (same date). The original is unchanged; this copy keeps every idea, tags it, and corrects the claims that won't hold on a real SM-S926B.

## How to read the tags

| Tag | Meaning |
| :---: | :--- |
| ✅ **Works** | Buildable as described with public Android APIs (Camera2/NDK, Vulkan, sensors, MediaCodec, LiteRT). |
| 🎨 **Fake it** | Buildable only as a stylised look. The physics claim behind it can't be done on this phone, so we simulate the result instead. |
| 🔍 **Phone check** | Depends on a capability we haven't confirmed on your S24+ yet. Phase 0's probe app answers it. Each one lists a fallback. |

Tier marks for the roadmap: **v1** = must have to ship, **v1.x** = next updates, **Later** = after the app is solid.

## The five corrections that affect the most ideas

1. **No direct access to Samsung's ISP blocks or the NPU.** The Exynos `3AA`/`ITP`/`MCFP` stages and the `GNPU`/`SNPU` have no public API. Apps get Camera2 (RAW, YUV, P010) and run ML through **LiteRT** (GPU delegate; NNAPI is deprecated since Android 15). Anything that says "taps 3AA" or "uses the SNPU" becomes "Camera2 stream" or "LiteRT/Vulkan model".
2. **No per-pixel hardware depth map is promised.** Dual-pixel data and `DEPTH_OUTPUT` are usually not exposed to third-party apps, and the 1x/3x pair sees different fields of view. Plan on **ML monocular depth** (a small Depth-Anything-class model via LiteRT) as the default, and use hardware depth only if Phase 0 finds it. Every defocus effect below (swirl, soap bubbles, anamorphic ovals, tilt) depends on this.
3. **The display is not a 2,600-nit full-screen light.** 2,600 nits is the peak for small HDR highlights / outdoor boost. A full white screen is far dimmer, and apps can't force the boost. Good for HDR highlights in the viewfinder and print inspector, weak as a light source.
4. **Infrared is blocked.** The IR-cut filter stops light above roughly 650–700 nm before it reaches the sensor, so there is no NIR signal for Aerochrome/NDVI, and H-alpha (656 nm) is heavily attenuated too.
5. **The "package-name spoof" is fragile.** Installing under a Samsung system package ID fails if that package already exists on the phone, Samsung can change the allowlist in any update, and it can't ship on the Play Store. Treat it as a Phase 0 experiment; the app must be fully usable with the standard Camera2 lenses Samsung exposes.

Smaller fix: the 1x main camera's RAW is unsharpened, so "MTF de-crisping" only matters on the processed YUV path. (The 97 profiles in the original are correct: 69 digitised datasheet profiles plus 28 spektrafilm profiles, in `Claude-Project-Files/datasheet-data/`.)

---

## 1. Executive Summary & Core Philosophy (corrected)

The app targets one phone: **Galaxy S24+ (SM-S926B), Exynos 2400, Xclipse 940 (RDNA 3-based), One UI 8.5 / Android 16.** Single-device targeting is real and lets us tune Vulkan shaders hard. The three pillars stand, with these edits:

1. **Physical photochemical truth** ✅: datasheet-driven sensitometry, spectral sensitivities, dye densities and grain models instead of `.cube` LUTs. Uses the 97 profiles (69 digitised + 28 spektrafilm).
2. **Physical lens optics** ✅/🎨: aberrations, PSFs and flare are simulated on the captured image. They can't undo the real lens "at the photon level"; they replace its look in post. Defocus effects need depth (see correction 2).
3. **Deep sensory soul** ✅: gyro, haptics, sound, and the AMOLED panel, minus the 2,600-nit full-screen claim (correction 3).

---

## 2. Lens Element & Wavefront Optics Emulation

The engine is a simulation applied after capture, driven by a depth map Z(x,y). **Depth source: ML monocular depth by default** 🔍 (hardware dual-pixel/stereo depth only if Phase 0 finds it).

### 2.1 Optical archetype presets

| Preset | Tag | Note | Tier |
| :--- | :---: | :--- | :---: |
| Helios 44-2 / Biotar swirl | 🔍 | Swirl needs depth to separate subject from background. Fallback: subject mask (ML segmentation) + radial-tangential blur, which looks close. | v1.x |
| Leica Summilux "glow" | ✅ | Spherical-aberration halo is a PSF on highlights; no depth needed. | **v1** |
| Canon 50 f/0.95 "Dream" | 🔍 | Coma and veiling flare work; "paper-thin DOF" needs depth. | v1.x |
| Trioplan soap-bubble bokeh | 🔍 | Bubble kernel is easy; placing it needs depth. | v1.x |
| Super-Takumar thorium warmth | 🎨 | Yellowed glass is a fixed warm transmission tint plus rainbow flare. "Alpha decay" is the story, not the math. | **v1** |
| Zeiss Planar T* | ✅ / 🔍 | Contrast, colour of ghosts and flare work; "ninja star" bokeh needs depth. | **v1** (without bokeh) |
| Petzval 85 swirl tunnel | 🔍 | Same depth dependency as Helios; vignette and field curvature work alone. | Later |
| Panavision C-Series anamorphic | ✅ / 🔍 | Streak flares and 2× desqueeze framing work; oval bokeh and breathing need depth. | v1.x |

### 2.2 Lens element customizer

| Feature | Tag | Note | Tier |
| :--- | :---: | :--- | :---: |
| Blade count 5–18, curved/straight | ✅ | Shapes the bokeh kernel and sunstars. | v1.x |
| Diffraction sunstars (even N → N spikes, odd N → 2N) | ✅ | The rule in the original is correct. Only needs bright point sources, not depth. | **v1** |
| Coatings: uncoated / MgF₂ / multi-coated | ✅ | Ghost colour, flare strength, shadow veiling. | **v1** |
| Thorium decay slider | 🎨 | Warm tint strength. | **v1** |
| Longitudinal CA (bokeh fringing) | 🔍 | Direction depends on in front of / behind focus, so it needs depth. | v1.x |
| Lateral CA (corner red/cyan) | ✅ | Radial per-channel scale. | **v1** |
| Canada balsam delamination | 🎨 | Edge highlight diffusion. | Later |
| Decentering / Scheimpflug tilt | 🔍 | A tilted focal plane needs depth. Fallback: gradient tilt-shift blur. | Later |
| Cinema focus breathing | ✅ | Small crop/zoom tied to manual focus. Note: the real lenses also breathe, so we correct theirs first. | v1.x |

---

## 3. Hollywood Motion Picture & Video Stage

| Feature | Tag | Note | Tier |
| :--- | :---: | :--- | :---: |
| Gate weave / claw oscillation | ✅ | Sub-pixel transform per frame. | v1.x |
| Per-frame emulsion flicker (±1.2%) | ✅ | Luminance jitter. | v1.x |
| Thermal gate curvature on long takes | 🎨 | Not a documented real effect at that scale; keep as a subtle stylised corner softening. | Later |
| Wong Kar-wai step-printing | 🔍 / ✅ | True 1/6 s or 1/12 s exposures in video depend on Camera2's max frame duration per mode. Fallback that always works: record 24/30 fps and blend N frames, then repeat them. | v1.x |
| 400 ft magazine counter | ✅ | Maths checks out: 400 ft × 16 frames/ft = 6,400 frames = 4 min 26.7 s at 24 fps. | v1.x |
| Tail-flap sound at reel end | ✅ | Audio cue + auto stop. | v1.x |
| 16mm optical soundhead audio | ✅ | Standard DSP (saturation, wow/flutter, EQ), offline or real-time. | Later |

---

## 4. Multi-Sensor & Silicon Alchemy

| Feature | Tag | Correction | Tier |
| :--- | :---: | :--- | :---: |
| Kubrick "Director's Matrix", 4 lenses live | 🔍 | **Not via 3AA.** Uses Camera2 concurrent cameras / logical multi-camera. Most phones allow at most 2 streams at once (often front + back only) for third-party apps; 4 live RAW streams is very unlikely. Fallback: 2-up matrix, or fast-switch between lenses with a frozen last frame for the others. | Later |
| Split-negative dual-sensor HDR | 🔍 | **Two claims are wrong.** The 3x sensor is much smaller and noisier than the 1x GN3, so it is the worse choice for shadows, and the two lenses only overlap in the 3x crop and see from different positions (parallax, needs alignment and still ghosts). "16+ stops, no ghosting" is not realistic. **Replace with:** exposure-bracketed burst on the 1x sensor (HDR+ style), about 13–14 usable stops, merged in linear space before the H&D curve. Keep the dual-sensor version as an experiment. | **v1** (single-sensor bracket) / Later (dual) |
| Rolleiflex waist-level finder | ✅ | Accelerometer trigger, square mirrored ground glass, Fresnel rings, loupe. | v1.x |
| Gyro "fluid cinema head" | 🔍 / ✅ | Gyro-driven stabilisation with heavy, damped motion is very doable. The rate: Android caps apps at 200 Hz unless they hold `HIGH_SAMPLING_RATE_SENSORS`; the actual max on your phone (400–500 Hz?) is a Phase 0 check. Also needs OIS-aware correction on the 1x lens. | v1.x |

---

## 5. Chemical Degradation, Analog Entropy & "Invisible NPU"

| Feature | Tag | Correction | Tier |
| :--- | :---: | :--- | :---: |
| Aerochrome III false-colour IR | 🎨 | **Not possible as written:** no 700–740 nm leakage reaches the sensor and there's no NIR channel for NDVI. **Do instead:** an ML vegetation mask (LiteRT segmentation) plus the Aerochrome colour mapping, so foliage goes crimson/pink and sky stays blue. Looks right in daylight foliage; won't "see" chlorophyll. | v1.x |
| DIR coupler inter-layer inhibition | ✅ | Real film-model maths (inter-image matrix + spatial spread) running in a Vulkan shader. No NPU needed. Drop "porcelain skin without smoothing" as a promise; it's a colour/edge effect, not retouching. | **v1** |
| Cross-processing (E-6 in C-41 etc.) | ✅ / 🎨 | Built from datasheet curves for both films and processes. That's a credible model, not chemical kinetics; keep the look, soften "accurately reproduces". | v1.x |
| Sabattier / solarisation + Mackie lines | 🎨 | Curve reversal plus edge-detected Mackie lines. | Later |
| Glovebox heat, X-ray fog, film soup, static sparks | 🎨 | All stylised overlays/curve shifts. | Later |
| Deep-sky astro stacking | 🔍 / ✅ | Stacking with star alignment works. Max single exposure per lens is a Phase 0 check. | Later |
| H-alpha photochemical plate | 🎨 | The IR-cut filter heavily attenuates 656 nm, so this can only be a red-emphasis colour rendering, not recovered H-alpha signal. | Later |

---

## 6. Sensory Immersion, Tactile Rituals & Darkroom Craft

| Feature | Tag | Note | Tier |
| :--- | :---: | :--- | :---: |
| Darkroom tray agitation | ✅ | Accelerometer drives a fluid sim and development timing. Signature feature. | **v1** |
| Prokudin-Gorsky trichrome | ✅ | 3 RAW frames in a fast burst, take R from frame 1, G from 2, B from 3. Moving things fringe as described. | v1.x |
| One Roll Per Trip / no preview | ✅ | Pure app logic. | **v1** |
| Contact sheet + red grease pencil | ✅ | Layout render + stylus/finger drawing layer. | v1.x |
| Film loading ritual | ✅ | Animation, sound, haptics. | **v1** |
| Specular print inspector | ✅ | Gyro + ambient light sensor + HDR highlights. Small specular glints are exactly where the panel's high peak brightness does apply. | v1.x |
| AMOLED "studio beauty light" | 🔍 / 🎨 | **Weaker than claimed:** a full-screen white is well below 2,600 nits and apps can't force the peak. Screen flash with 3200–5600 K colour still works as a soft fill at arm's length. | v1.x |
| Binaural shutter sounds + haptics | ✅ / 🔍 | Works; needs your own recordings or licensed sounds (no ripped Leica/Hasselblad audio). Rich haptic "primitives" support on your phone's motor is a Phase 0 check; basic waveforms always work. | **v1** |

---

## 7. Round 1 & Round 2 Catalog

### 7.1 Reliability & phone safety

| Feature | Tag | Note | Tier |
| :--- | :---: | :--- | :---: |
| Startup self-check & safe fallback | ✅ | Compare against the Phase 0 report. | **v1** |
| Package spoof for hidden lenses | 🔍 | See correction 5. Experiment only. | Later |
| Save first, process later (DNG) | ✅ | | **v1** |
| Background render queue | ✅ | WorkManager / foreground service. | **v1** |
| Performance HUD | ✅ | GPU timestamps work; SoC temperature via thermal API gives headroom/status, not exact °C per block. | **v1** |

### 7.2 Calibration & correction

| Feature | Tag | Note | Tier |
| :--- | :---: | :--- | :---: |
| Dark-frame + flat-field per camera | ✅ | | v1.x |
| Cross-camera look matching | 🔍 | Works for every lens that gives RAW; which ones do is a Phase 0 check. | v1.x |
| ColorChecker profiling | ✅ | | v1.x |
| 4–8 frame burst merge | ✅ | | **v1** |
| Pre-capture rolling buffer | ✅ | Size it in binned 12.5 MP mode; a 1 s buffer of full 50 MP RAW won't fit in memory comfortably. | v1.x |

### 7.3 Shooting experience

| Feature | Tag | Note | Tier |
| :--- | :---: | :--- | :---: |
| Virtual camera bodies (Leica, Hasselblad, half-frame, compact) | ✅ | | **v1** (2 bodies) / v1.x (rest) |
| Physical dials with haptic detents | ✅ | | **v1** |
| Zone focusing scale | ✅ | Uses `LENS_FOCUS_DISTANCE` (diopters; calibration quality varies per lens). | v1.x |
| Zone System meter / Sunny 16 coach | ✅ | | v1.x |
| Rebate borders & date stamp | ✅ | | **v1** |
| LED direct-flash look | ✅ | | v1.x |
| Push/pull ±3 EV | ✅ | | **v1** |

### 7.4 Cinema video foundation

| Feature | Tag | Note | Tier |
| :--- | :---: | :--- | :---: |
| Mode A: RAW video (`.mcraw`) + gyro track | 🔍 | RAW video bandwidth and sustained write speed at your resolutions/frame rates is a Phase 0 check. | Later |
| Mode B: 10-bit HLG / log with shader baked in | ✅ | HEVC Main10 via MediaCodec. Use our own log curve; "F-Log2" is Fujifilm's and shouldn't be claimed. | v1.x |
| Computational ND / 180° shutter | 🔍 / ✅ | Blend high-frame-rate frames to fake longer shutter; how close to 180° depends on the fps modes Phase 0 finds. | Later |
| False colour, parade, zebras, guides | ✅ | | v1.x |
| USB-C mic, gain, limiter, wind NR | ✅ | | v1.x |

### 7.5 Darkroom editor, archiving & PC

| Feature | Tag | Note | Tier |
| :--- | :---: | :--- | :---: |
| Non-destructive editor, dodge/burn, printer lights | ✅ | | **v1** (basic) |
| Split-grade Multigrade printing | ✅ | | v1.x |
| Rodinal / D-76 / Xtol developer models | ✅ | Acutance and grain parameters per developer. | v1.x |
| Soft-proofing & 16-bit TIFF lab export | ✅ | | v1.x |
| Film-XMP sidecar | ✅ | | **v1** |
| Recipe QR codes & roll journal | ✅ | | v1.x |
| PC shader harness & desktop renderer | ✅ | Same SPIR-V shaders on Windows Vulkan. | **v1** (harness) / Later (renderer) |

---

## 8. Roadmap Allocation Matrix (Phases 0–6, corrected and tiered)

Your phase structure is unchanged. Each phase now says what is **v1 (must ship)** and what moves later. v1 is everything in bold below; the app is shippable when those are done.

| Phase | Milestone | **v1 must have** | v1.x / Later |
| :---: | :--- | :--- | :--- |
| **0** | Setup & Phone Probe | **Probe app that answers every 🔍 item:** which lenses give RAW, concurrent-camera combos, hardware depth, max exposure/frame duration per mode, gyro max rate, haptic primitives, Vulkan extensions (`VK_KHR_ray_query`, `VK_KHR_cooperative_matrix`), RAW video throughput, and whether the package spoof installs at all. | (none) |
| **1** | Camera → GPU Skeleton | **Zero-copy `AHardwareBuffer` → `VkImage`, live preview, save-first DNG, self-check + safe fallback, HUD, PC harness v1.** | |
| **2** | Stills RAW Processing | **1x RAW pipeline: DPC, black level, LSC, denoise, 4–8 frame burst merge, single-sensor bracketed HDR, background queue.** | Pre-capture buffer, other lenses (per Phase 0), calibration tools. |
| **3** | Film Stocks | **~12 hero stocks from the 97 profiles, spectral upsampling, live + master grain, halation, DIR inter-layer effects, push/pull, Film-XMP.** | Remaining stocks, developer chemistry, cross-processing, recipe QR. |
| **4** | Tactile & Darkroom | **2 camera bodies, dials + haptics + sounds, film loading ritual, One Roll mode, darkroom tray agitation, rebate borders, basic editor, non-depth lens looks (Leica glow, thorium, Planar, coatings, sunstars, lateral CA).** | Depth-based presets (Helios, Trioplan, Canon, Petzval, LoCA), contact sheet, split-grade, print inspector, beauty light, waist-level finder, trichrome, more bodies. |
| **5** | Cinema Video | Not in v1. | **v1.x:** Mode B with film look, gate weave, flicker, step-printing (frame-blend version), 400 ft magazine, gyro fluid head, monitoring, audio. **Later:** Mode A RAW video, computational ND, optical soundhead. |
| **6** | Advanced Extras | Not in v1. | Director's Matrix (2-up if that's all the phone allows), dual-sensor HDR experiment, anamorphic stereo, 4×5 synthetic aperture (needs depth), Aerochrome look, Sabattier, degradation effects, astro stacking + H-alpha look, lab packager, DeX enlarger, package-spoof lens unlock. |
