# Camera-App: build plan

This is the plan only. Nothing has been built yet.

It is based on three things:
- The assignment summary, [assignment-understanding.md](assignment-understanding.md)
- Both rounds of ideas in [ideas.md](ideas.md). You liked all of them, so they're all in the plan below.
- Your answers so far:
  - You have the S24+ Exynos, and it runs One UI 8.5 (Android 16).
  - You'll build on your Windows PC, and the code can go on GitHub too.

**Wherever a question below is still open, the plan uses my recommended default. Change any answer and I'll adjust the plan.**

---

## Questions for you (each can be answered in one word)

| # | Question | My default |
|---|---|---|
| Q1 | Is it OK that the app is personal sideload only, never on the Play Store? (yes / no) | **yes** |
| Q2 | Should we do stills first or video first? (stills / video) | **stills** |
| Q3 | Should the first step be the small test app that checks which cameras give RAW on One UI 8.5? (yes / no) | **yes** |
| Q4 | Should film looks be parametric, LUTs, or both? (parametric / LUT / both) | **parametric** (LUT export only, for Lightroom and DaVinci) |
| Q5 | Should the first stocks be Portra 400, Ektar 100, Tri-X 400, Vision3 500T and CineStill 800T? (yes / or name yours) | **yes** |
| Q6 | Should the GitHub repo be private or public? (private / public) | **private** |
| Q7 | Do you already have Android Studio installed on the Windows PC? (yes / no) | **no** (Phase 0 covers the setup) |
| Q8 | Is the S24+ Exynos the only phone the app must support? (yes / no) | **yes** |
| Q9 | Do you own a ColorChecker or other colour chart? (yes / no) | **no** (we'll use a cheap printed chart first) |
| Q10 | Do you own a Galaxy Watch? (yes / no) | **no** (drops the watch-remote idea) |
| Q11 | Do you shoot real film cameras too? (yes / no) | **no** (negative scanning and the light meter move to Extras) |
| Q12 | Should roll mode be on by default when the app opens? (yes / no) | **no** (free mode by default, roll mode one tap away) |
| Q13 | Should the Fujifilm-style recipe engine from the mobile pipeline PDF be included? (yes / no) | **yes** (rebuilt as parametric, not as LUTs) |
| Q14 | Should we build the PC desktop renderer, or only the PC test harness? (renderer / harness) | **harness** (renderer goes in Extras) |

---

## How the plan is ordered

1. **Prove the phone first.** One UI 8.5 is newer than anything the research covers, so we confirm what the cameras actually give before building on it.
2. **Fix known bugs before reusing code.** The research shaders have known bugs (assignment-understanding.md §5). Each one gets fixed when its stage is built and is checked by the PC test harness.
3. **Stills before video.** Video adds encoding, storage and heat problems on top of everything stills need.
4. **Each phase ends with something usable on your phone.**

---

## Phase 0: Setup and phone check

Goal: a working toolchain, and a clear answer on what your phone allows.

- Set up the Windows toolchain: Android Studio, NDK, CMake and the Vulkan SDK, plus a USB debugging connection to the phone.
- Create the repo, local and GitHub (private by default), with a C++20 + Kotlin project skeleton.
- **Camera probe app (round-1 idea 1).** It lists every camera with its RAW sizes, frame rates, CFA order, black and white levels, and whether two RAW streams can run at once. It runs once with the normal package name and once with the Samsung package name (`com.samsung.android.ruler` / `scan3d`), and saves a report.
- Write down the results. They decide what's possible for the ultrawide, tele, front, dual-sensor and 60 fps RAW features.

**Done when:** the probe report from your phone is saved in the repo.

**Decision point:** if the Samsung package-name trick doesn't work on One UI 8.5, the plan falls back to the main camera only (plus whatever RAW the other cameras still give) and everything else stays the same.

## Phase 1: Camera to GPU skeleton

Goal: a live RAW preview on screen through our own GPU code.

- NDK Camera2 capture with a zero-copy `AHardwareBuffer` → Vulkan path.
- A simple live demosaic for the preview only.
- **Save first, process later.** DNG is written to disk before any heavy processing.
- **Fallback mode.** If the spoof fails, use main-camera RAW through standard Camera2.
- **Startup self-check** on every launch, compared against the saved probe report.
- **Performance overlay** showing per-stage GPU time, frame time and temperature.
- **PC test harness**, first version: runs the same shaders on saved DNGs on Windows.

**Done when:** you can open the app, see a live RAW preview and save a DNG.

## Phase 2: RAW processing for stills (stages 1–6)

Goal: clean, accurate, natural-looking RAW photos before any film look is applied.

- Stages 1–6: unpack, black level, defect pixels, lens shading, denoise, demosaic, highlight recovery, colour. **Fix bug B3** (demosaic shader) here.
- **Calibration:** dark frame (lens cap on) and flat field (white paper) for each camera, plus a colour chart matrix for each camera.
- **Cross-camera matching**, so a look is the same on every lens.
- **Burst merge**: align and merge 4–8 RAW frames, plus an HDR bracket mode.
- **Pre-capture buffer**: about 1 s of RAW frames before you press the shutter.
- Exports: DNG and Ultra HDR JPEG. HDR viewfinder display.
- **Background render queue** with a progress notification.

**Done when:** a stills shot beats the Samsung camera's RAW for noise and colour in the PC harness comparison.

## Phase 3: Film looks (stages 7–11)

Goal: the film emulation itself, starting with five stocks.

- EmulsionDNA parametric stock model, starting with **Portra 400, Ektar 100, Tri-X 400, Vision3 500T and CineStill 800T** (Q5).
- **Fix the known bugs here:** B1 (film-curve gamma), B2 (RA-4 paper polarity), B4 (halation matrix and kernel size), B5 (grain shader) and B6 (colour matrix). Also settle contradictions C3–C6.
- Check each stock's data against the real datasheet before it ships.
- Grain (live and master), halation, MTF, diffusion filters, diffraction and bokeh.
- **Push/pull processing**, **expired film**, light leaks, multiple exposure.
- **Fujifilm-style recipe engine**, rebuilt as parametric (Q13).
- **Live stock comparison** (2-up and 4-up viewfinder).
- **Roll mode**: stock locked per roll, frame counter, delayed developing.
- **Film rebate borders**, 90s date stamp, instant film mode.
- **Recipe QR codes** and `.cube` LUT export.

**Done when:** the five stocks run live under about 16 ms per frame (measured, not estimated) and the master renders match the datasheet curves.

## Phase 4: Shooting experience and darkroom editor

Goal: the app feels like a camera, and you can edit and manage photos in it.

- **Virtual camera bodies** (rangefinder, 6×6, half-frame, compact), **dial controls** with haptics, viewfinder styles, focus peaking and split prism.
- Zone System meter, Sunny 16 mode, zone focusing, subject tracking, shots-left display.
- Haptic shutter and film advance. Half-frame diptychs. Film vs digital toggle.
- Samsung integration: side-key launch, Quick Settings tile, volume-key controls, home-screen widget, DeX layout.
- **Darkroom editor**: dodge and burn, scopes, contact sheet and enlarger modes, **edit history with undo**, **batch edits and virtual copies**.
- Library: smart library, roll journal, grain at print size, print soft-proofing, share-ready export, auto backup to your PC.
- Smart help: stock suggestions, exposure coach, golden-hour alerts.

**Done when:** you can shoot a full roll, develop it and edit it in the app.

## Phase 5: Video

Goal: cinematic video, starting with the simpler recording mode.

- **Mode B first**: 10-bit HLG / F-Log2 with the film look baked in through MediaCodec.
- Then **Mode A**: compressed RAW `.mcraw` with a gyro track (check the format against MotionCam first).
- **Computational ND and true shutter angle.**
- Classic frame rates (18, 24, 25, 48, 60 fps), plus monitoring tools: zebras, aspect-ratio guides, tally light.
- Record clean log while viewing the film look on screen.
- Audio: level meters, manual gain, USB-C microphone, wind reduction. Timecode and shot notes.
- Motion clip saved with each still.
- Thermal pacer and storage checks for long recordings.

**Done when:** a 10-minute 4K clip records without overheating or dropped frames.

## Phase 6: Extras

These can be added in any order once the core is done:

- Creative modes: light painting, rear-curtain flash, wigglegram 3D, astro star alignment, intervalometer and star trails, voice notes.
- Dual-sensor features from the blueprint: split exposure and NPU masks. These depend on what the Phase 0 probe found.
- Darkroom-emergence long exposure and the datasheet-to-shader compiler.
- Vintage lens profiles, LED direct-flash look, handheld super-resolution, match a reference photo.
- For film shooters (Q11): negative scanning, phone light meter, roll tracker.
- PC side: desktop master renderer (Q14), Wi-Fi transfer, tethered control.
- For you as the developer: custom shader slot.
- Galaxy Watch remote shutter, only if Q10 is yes.

---

## Risks to keep watching

- A One UI update could break the Samsung package-name trick. The startup self-check catches this.
- Heat and storage speed limit long video and heavy master renders.
- There's no public API for the Samsung NPU, so masks may need to run on the GPU instead.
- The film data tables in the research look generated, so each stock needs checking against its real datasheet.
