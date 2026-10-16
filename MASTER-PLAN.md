# Film Camera App for Galaxy S24+ (Exynos): Master Plan

**Last updated:** 2026-10-14
**Status:** planning and film-data work done. Nothing has been built yet.
**Next step:** Phase 0 phone capability check, with the S24+ plugged into this Windows PC over USB.

This is the one document to read first, whichever AI model or tool you use. It pulls together everything produced so far and says where every file lives. All paths below are relative to this `Camera-App` folder.

---

## 1. The goal

A native Android camera app that runs only on **your Samsung Galaxy S24+ Exynos (SM-S926B), on One UI 8.5 (Android 16)**. It:

- Bypasses Samsung's image processing and reads RAW Bayer data from the cameras (all four, if the phone allows it).
- Runs its own ISP and film or cinema emulation on the GPU, in C++20 with NDK Camera2 and Vulkan 1.3, in linear ACEScg.
- Shows a live film-look viewfinder at 60 fps, and does a separate, slower, full-quality FP32 "master render" for final stills and video.
- Emulates about **105 looks**: real film stocks modelled from manufacturer datasheets (parametric "EmulsionDNA" plus Spektrafilm spectral data, not static LUTs), 9 cinema camera looks and 14 Fujifilm-style simulations.
- Includes a darkroom editor, an HDR display, and exports to DNG, Ultra HDR JPEG, HEVC/AV1 and `.mcraw` RAW video.

The full engineering spec is the blueprint: your v2 is [s24_plus_master_film_camera_engineering_blueprint.md](s24_plus_master_film_camera_engineering_blueprint.md), and the latest is v3 at [Claude-Project-Files/blueprint-v3.md](Claude-Project-Files/blueprint-v3.md).

---

## 2. Decisions made so far

### Product decisions (Q1–Q14, all accepted)

| # | Decision |
|---|---|
| Q1 | Personal sideload only, never on the Play Store. |
| Q2 | Stills first, then video. |
| Q3 | Phase 0 starts with a small probe app that checks what the cameras really give on One UI 8.5. |
| Q4 | Looks are parametric (EmulsionDNA + Spektrafilm). `.cube` LUTs are export only, for Lightroom and DaVinci. |
| Q5 | First five stocks, to prove the engine: Portra 400, Ektar 100, Tri-X 400, Vision3 500T, CineStill 800T. |
| Q6 | Private GitHub repo. |
| Q7 | Android Studio isn't set up yet; Phase 0 covers it. |
| Q8 | S24+ Exynos is the only supported phone. |
| Q9 | No ColorChecker; start with a cheap printed chart. |
| Q10 | No Galaxy Watch, so no watch remote. |
| Q11 | You don't shoot real film, so negative scanning and the light meter are Extras. |
| Q12 | Free mode by default; roll mode is one tap away. |
| Q13 | Include the Fujifilm-style recipe engine, rebuilt as parametric. |
| Q14 | PC test harness now; the desktop master renderer is an Extra. |

### Other decisions

- **Build machine:** your Windows PC (`DESKTOP-S7SLO8K`), not the Mac. This `Camera-App` folder is the shared home for every AI tool you use.
- **Every look works in photo and video**, with Auto mode (pick a look, the app sets exposure) and Pro mode (full manual). See §4.
- **Features moved to Phase 6 (Extras):** Bayer-shift / handheld super-resolution, ray-traced lens flare, Scheimpflug tilt-shift, and film soup / static / bromide drag / X-ray fog.
- **All brainstorm ideas are in** (you said "all ideas are great"). The 20 that blueprint v2 dropped were added back in v3.
- **Film data:** the blueprint's sensitometry tables are wrong (checked against real datasheets for 18 stocks). Real datasheet curves replace them. See §6.
- **Which AI model does what:**
  - **Opus:** the phone check, RAW capture and native camera code, the Vulkan pipeline, film and colour maths, auto exposure, video, and debugging.
  - **Sonnet:** app screens and menus, settings and sliders UI, filling in stock data from the datasheets, the editor layout, docs, and simple fixes.

---

## 3. Next step: Phase 0 phone check

You want to run it with the phone connected over USB to this PC, working together live, rather than installing an APK by hand.

What it needs:
1. Your S24+ plugged into the Windows PC by USB, with USB debugging switched on (Settings → About phone → Software information → tap Build number 7 times, then Developer options → USB debugging).
2. Android SDK platform-tools (`adb`) on the PC, and later Android Studio, NDK, CMake and the Vulkan SDK.
3. Enough Claude usage left for an Opus session.

What the probe app records:
- Every camera ID with its RAW sizes, frame rates, CFA order, black and white levels, and whether two RAW streams can run at once.
- It runs once with the normal package name and once with the Samsung package name (`com.samsung.android.ruler` / `scan3d`).
- GPU extensions: `VK_KHR_ray_query` and `VK_KHR_cooperative_matrix`.
- Real gyro and accelerometer rates (Android caps apps at 200 Hz without `HIGH_SAMPLING_RATE_SENSORS`).

**Done when:** the probe report is saved in the repo. **If the Samsung package-name trick doesn't work on One UI 8.5,** the plan falls back to the main camera (plus any RAW the other cameras still give), and everything else stays the same.

---

## 4. Looks, shooting modes and controls

**About 105 looks:**

| Group | Count |
|---|---|
| Colour negative (C-41) | 24 |
| Slide (E-6 / K-14) | 16 |
| Black & white | 23 table rows (some rows cover a family, e.g. Rollei Retro 80S/400S/IR/Ortho) |
| Motion picture negative | 16 |
| Print stocks | 3 |
| Cinema camera looks (ARRI, RED, Sony, Canon, BMD, Fuji, Apple, DJI) | 9 |
| Fujifilm-style film simulations | 14 |

- **Video:** every look and effect works in video too. Live 10-bit video (Mode B) uses the real-time versions (Gabor grain, pyramid halation, steerable diffraction). RAW video (`.mcraw`, Mode A) is recorded clean and rendered later at master quality.
- **Auto mode:** you pick a look and the app sets ISO, shutter and white balance. For photos, exposure follows the film type: negative protects shadows, slide protects highlights, and B&W uses the Zone System. For video, the shutter stays at 180°, with ISO first and then computational ND. White balance is neutral by default, and a *film-true* switch keeps the stock's native balance (500T in daylight goes blue).
- **Pro mode:** ISO, shutter or shutter angle, WB (Kelvin and tint), focus, exposure compensation, metering, lens. Photos add RAW format and burst. Video adds frame rate, codec / log / RAW, and audio gain. Each setting can stay on auto by itself.
- **Live viewfinder:** shows the selected look at 60 fps. Master-only effects are shown with their close real-time versions.
- **Sliders:** each look loads its own stock defaults. Optional sliders are grain, halation, bloom/diffusion, look strength, sharpness, gate weave and vignette, each with a reset. Tweaks are saved in the photo's recipe and can be saved as a custom look.

---

## 5. Build plan (7 phases)

Ordering rules: prove the phone first, fix known bugs before reusing research code, stills before video, and every phase ends with something usable on the phone.

### Phase 0: Setup and phone check
Windows toolchain (Android Studio, NDK, CMake, Vulkan SDK, USB debugging). Private GitHub repo with a C++20 + Kotlin skeleton. The camera probe app (§3).
**Done when:** the probe report from your phone is saved in the repo.

### Phase 1: Camera to GPU skeleton
NDK Camera2 → zero-copy `AHardwareBuffer` → Vulkan live preview with a simple demosaic. Save DNG first, process later. Fallback to standard main-camera RAW if the spoof fails. Startup self-check against the probe report. Performance overlay (per-stage GPU ms, frame time, temperature). First version of the Windows PC test harness (same shaders on saved DNGs).
**Done when:** you open the app, see a live RAW preview and save a DNG.

### Phase 2: Stills RAW processing (stages 1–6)
Unpack, black level, defect pixels, Gr/Gb balance, lens shading, denoise, demosaic (**fix B3**), highlight recovery, colour (**fix B6**). Dark-frame, flat-field and colour-chart calibration per camera. Cross-camera look matching. 4–8 frame burst merge and HDR bracket. 1 s pre-capture RAW buffer. DNG and Ultra HDR JPEG export, HDR viewfinder. Background render queue.
**Done when:** a still beats Samsung's own RAW for noise and colour in the PC harness comparison.

### Phase 3: Film looks (stages 7–11)
Hybrid Spektrafilm + EmulsionDNA engine (**fix B1, B2, B4, B5**; settle the open contradictions). Start with the five reference stocks, then all ~105 looks in batches, each checked against its datasheet. Live viewfinder look preview and effect sliders. Grain (Gabor live, Newson Monte Carlo master), halation, MTF, diffusion, diffraction, bokeh. Push/pull, expired film, light leaks, multiple exposure, instant film mode, LED direct-flash look. Fujifilm-style recipe engine (parametric). Spektrafilm spectral upsampling (**with the corrected gamut-compression formula, E1**) and the Beer-Lambert enlarger. 2-up / 4-up live stock comparison. Roll mode. Rebate borders, 90s date stamp, recipe QR codes, `.cube` export.
**Done when:** all looks are in, the five reference stocks run live under about 16 ms per frame (measured), and master renders match the datasheet curves.

### Phase 4: Shooting experience and darkroom editor
Photo Auto and Pro modes. Virtual camera bodies (rangefinder with split prism, 6×6 TLR, half-frame diptychs, 90s compact). Clockwork shutter sound and haptics. Physical dial controls (haptic detents, rearrangeable, left-handed). Focus peaking, zone focusing, subject tracking, shots-left display, film vs digital toggle, Zone System meter, Sunny 16 coach. One UI integration: side-key launch, Quick Settings tile, volume keys, widget, DeX layout. Darkroom editor: dodge and burn, scopes, contact sheet and enlarger modes, tray agitation, baryta paper sheen viewer, undo history, batch edits, virtual copies, auto backup to the PC. Library: smart library, roll journal, grain at print size, print soft-proofing, share-ready export, print mockup. Smart help: stock suggestions, exposure coach, golden-hour alerts.
**Done when:** you can shoot a full roll, develop it and edit it in the app.

### Phase 5: Video
Every look, effect and slider in video, with Video Auto and Pro modes. Mode B first (10-bit HLG / F-Log2 with the look baked in through MediaCodec, or clean log with a live look preview), then Mode A (`.mcraw` + gyro track; check the format against MotionCam first). Computational ND and true shutter angle. Wong Kar-wai step-printer mode. 18/24/25/48/60 fps. Zebras, aspect guides, tally light. Audio meters, manual gain, USB-C mic, wind reduction. Timecode and shot notes. Motion clip with each still. Thermal pacer and storage checks.
**Done when:** a 10-minute 4K clip records without overheating or dropped frames.

### Phase 6: Extras (any order)
Handheld super-resolution, ray-traced or compute-traced lens flare, Scheimpflug tilt-shift, film soup / static / bromide drag / X-ray fog, vintage lens profiles, intervalometer and time-lapse, voice note per frame, match a reference photo, Wi-Fi transfer and tethered PC control, out-of-frame sun flare tracker, dual-sensor split exposure and wigglegram, Harris shutter trichrome, darkroom-emergence long exposure, datasheet-to-shader compiler, light painting, rear-curtain flash, astro star alignment and star trails, negative scanning, phone light meter, roll tracker, custom shader slot, desktop master renderer. Dual-camera features depend on what the Phase 0 probe finds.

---

## 6. Fixes the blueprint still needs

Blueprint v3 has the roadmap and decisions, but these items from the reviews are **not yet applied in its text**. Whoever builds each stage must apply them.

### Errors in the blueprint (from the v2 review)

| # | Problem | Fix |
|---|---|---|
| E1 | §10.1 gamut-compression formula gives NaN (the base of the 1/p power goes negative) and ρ almost never reaches the threshold. | Use the ACES RGC form `((1−t)/(l−t))^(−p) − 1` on a normalised distance (gamut edge = 1), with l > 1 (e.g. 1.147 or 1.264). |
| E2 | Ray-traced flare via `VK_KHR_ray_query` is unproven and unnecessary. | Compute-shader sequential tracing; ray query only if the probe finds it and it's faster. (Already in Phase 6.) |
| E3 | "Bayer-shift true RGB at every pixel" is overstated. | Treat it as Google-style handheld super-resolution. (Already in Phase 6.) |
| E4 | 500 Hz gyro isn't available to apps by default. | Add `HIGH_SAMPLING_RATE_SENSORS`, measure the real rate in Phase 0. |
| E5 | Spektrafilm source and licence. | Data is CC BY-SA 4.0 (fine for personal use, with credit). Reimplement from the maths rather than copying code. Your `Spektrafilm-OFX Emulation Analysis.pdf` is now in this folder. |
| E6 | The spectral pre-integration 3D LUT conflicts with the "no static LUT" rule. | Lookup tables generated from the parametric model are allowed; hand-made look LUTs are not. |
| E7 | Two speed targets (10.3 ms vs 16 ms). | 16 ms is the measured target; 10.3 ms is an estimate. |
| E8 | GPU fallback for the NPU needs `VK_KHR_cooperative_matrix`, unconfirmed. | Probe it in Phase 0; plain compute shaders are the fallback. |

### Shader bugs to fix (combined list)

| # | Bug | Phase |
|---|---|---|
| B1 | CINE PDF's `CalculateNegativeDensity` puts raw gamma into a base-10 logistic, so slopes are wrong. (The Richards `k` formula itself is correct, and the inflection is at x0, not `x0 + ln ν/k`.) | 3 |
| B2 | RA-4 paper inversion uses `exp(+k…)`, which flips paper polarity. | 3 |
| B3 | Hamilton-Adams demosaic: vertical gradient reads the horizontal neighbour; warped coordinate is computed but not used for the load. **Also (missing from v3):** `u_cfa_phase` is ignored, which breaks the BGGR tele camera, and RAW10 unpack ignores row stride. | 2 |
| B4 | Halation `mat3` is transposed (red and green bleed swapped) and the ±8 px kernel is far too small. Fix: transpose, and use a 5-level mipmap pyramid. | 3 |
| B5 | Grain uses a fixed 0.65 gamma instead of the real density buffer. **Also (missing from v3):** the Boolean grain shader redraws grains per sample and never checks neighbouring cells, so it isn't the Newson model. | 3 |
| B6 | Row-major matrices loaded into column-major GLSL `mat3`. **Also (missing from v3):** rows 2–3 of the XYZ→ACEScg matrix in the Optics PDF don't match the published AP1 values (check against the ACES docs). | 2 |
| B7 | The HDR PDF calls the Xclipse/RDNA GPU tile-based; questionable, and it affects render-pass design. | 1 |

### Contradictions still to settle

- Nelson adjacency effect: added in density (blueprint) vs multiplied in log exposure (B&W PDF).
- Three different grain-strength-vs-tone curves (Mobile PDF, Optics text, Optics shader).
- Halation highlight knee: formula vs shader.
- Diffusion filter sizes in pixels vs film millimetres (would look different at 1080p vs 4K).
- Settled in v3: GN3 active array (query `SENSOR_INFO_ACTIVE_ARRAY_SIZE` at runtime), printer lights (12 points = 1 stop), One UI 8.5 as the target, real datasheets over the generated tables.

---

## 7. Film stock data: coverage and gaps

**97 stocks have real datasheet curves** (characteristic curves, spectral sensitivity, and dye density where published), all in the same spektrafilm JSON format:

- **28 Spektrafilm profiles** (18 match blueprint stocks, plus Fujifilm C200, Kodak Verita 200D, the 2383 and 2393 print stocks, and 6 print papers). Folder: `Claude-Project-Files/datasheet-data/spektrafilm-profiles/`. Licence CC BY-SA 4.0.
- **69 profiles digitised from 68 manufacturer PDFs** (Kodak, Fuji, Ilford, Agfa, Konica, Foma, ORWO). Folder: `Claude-Project-Files/datasheet-data/datasheet-profiles/`, with the raw CSVs and QA overlay charts in `digitized/`, and the source PDFs in `pdfs/`.

**The blueprint's numbers are wrong for all 18 stocks checked:** negatives are about 0.1–0.25 too contrasty, slides and prints are off more, and the "690/550/440 nm" spectral peaks are fake. Use the profiles, not the blueprint tables.

**Caveats:** many colour negative sheets only print mid-scale and minimum dye density (not separate C/M/Y); several spectral axes are relative only; Ilford, Foma, Neopan, Acros II, Velvia 50, Double-X and P3200 were traced from scans. Details per stock are in `datasheet-check.md`.

### Gaps and substitutes

| Stock | Why | Use instead |
|---|---|---|
| CineStill 50D / 400D / 800T | No datasheet exists | Vision3 50D / 250D / 500T (800T = 500T without remjet) |
| Kodak ColorPlus 200 | No datasheet | Gold 200 |
| Kodak Pro Image 100 | Sell sheet only, no curves | Portra 160 |
| LomoChrome Purple / Metropolis / Turquoise | Creative films, no datasheet | Start from Vision3 250D, Portra 160, Gold 200; tune by eye |
| Rollei Retro 80S / 400S / IR 400 / Ortho 25 | PDFs have no characteristic curve | Agfa APX 100 / APX 400 or similar, tuned by eye |
| ORWO N74 Plus | No datasheet found | ORWO UN54 |
| Kodak Color Negative II 5247 | No datasheet found | EXR 50D or Vision3 |
| Fuji Eterna-CP 3513DI (print) | Only in the Fuji motion-picture manual, not digitised | Kodak 2383 |
| Fuji 3523XD | It's an intermediate film, not a print film | Drop from the print list |
| Fujicolor Natura 1600 | Published curves are identical to Superia 1600 | Treat as one emulsion |

Gemini's datasheet link list was mostly wrong (21 of 59 links worked, several document codes were incorrect). The corrected codes are in `datasheet-check.md`.

---

## 8. Risks to keep watching

- **A One UI update could break the Samsung package-name trick.** The startup self-check catches it. Turn off auto-download of One UI updates and re-run the probe before accepting each one. Fallback ladder: physical camera IDs through standard Camera2 → 10-bit YUV with Samsung processing reduced → main camera only. Rooting isn't recommended (trips Knox).
- **No public Samsung NPU API**, so masks may need to run on the GPU.
- **RAW from the other cameras, 60 fps RAW and dual RAW streams are unconfirmed** until Phase 0.
- **`.mcraw` header** in the research may not match the real MotionCam format.
- **Performance numbers in the research are estimates**, not measurements.
- **Heat and storage speed:** you said these aren't a concern, so the thermal pacer and storage checks stay only as background safety in Phase 5.
- **Trademarks** (Kodak, Fujifilm names) are fine for personal use only.

---

## 9. Open questions

The 14 planning questions are answered. These are still open:

1. **When to start Phase 0:** waiting on your usage limit. Say "start the phone check" when you're ready with the S24+ plugged into this PC.
2. **GitHub repo:** not created yet. It'll be private (Q6); confirm the name when Phase 0 starts.
3. **Android toolchain:** Android Studio, NDK, CMake and Vulkan SDK still need installing on the PC (Phase 0 covers it).
4. **Contradictions in §6** (Nelson adjacency, grain curve, halation knee, filter sizes) get decided when Phase 3 builds those stages, using the PC harness.
5. **Blueprint v3 text fixes** (E1, the extra B3/B5/B6 items): apply them to the blueprint now, or as each stage is built. Default: as each stage is built.

---

## 10. Where every file lives

### Your original files (top of `Camera-App`)

| File | What it is |
|---|---|
| `MASTER-PLAN.md` | This document. Start here. |
| `MASTER-IDEAS.md` | Unified master innovation catalog (Rounds 1, 2 & 3: lenses, cinema video, multi-sensor). |
| `s24_plus_master_film_camera_engineering_blueprint.md` | Your blueprint **v2** (595 lines). |
| `plan.md` | An **older copy** of the build plan. The current one is `Claude-Project-Files/plan.md`, and this master plan supersedes both. |
| `Galaxy S24 Camera System Research.pdf` | Sensors, readout, firmware (research). |
| `Android and Exynos Camera Architecture.pdf` | Camera HAL, package-name trick, RAW access. |
| `Custom Vulkan Android ISP Architecture.pdf` | GPU ISP stages, demosaic, `.mcraw`. |
| `Vulkan HDR Darkroom Architecture.pdf` | HDR display, editor, scopes. |
| `Mobile Camera Film Emulation Pipeline.pdf` | Mobile pipeline, Fujifilm-style recipes. |
| `Film Emulation Shader Engineering Research.pdf` | Film curve and print shaders. |
| `B&W Film Emulation Shader Research.pdf` | B&W emulation, adjacency effects. |
| `Cinema Color Science Emulation.pdf` | Cinema camera log curves and matrices. |
| `Physical Optical Shader Simulation Report.pdf` | Grain, halation, diffusion, diffraction. |
| `Spektrafilm-OFX Emulation Analysis.pdf` | Spektrafilm spectral pipeline analysis. |
| `Film Stock Technical Datasheet Research.pdf` | Gemini's datasheet link research (mostly unverified; see §7). |

### Everything Claude produced (`Claude-Project-Files/`)

| Path | What it is |
|---|---|
| `assignment-understanding.md` | First full read of the assignment: requirements, constraints, contradictions C1–C7, bugs B1–B7, risks. |
| `master-ideas.md` | Complete master ideas catalog (Rounds 1, 2 & 3). |
| `ideas.md` | Both brainstorm rounds (all accepted). |
| `plan.md` | The latest build plan with Q1–Q14 and the datasheet-check method. |
| `blueprint-v2-review.md` | Review of your v2: errors E1–E8, dropped bugs, roadmap moves. |
| `blueprint-v3.md` | Blueprint v3: your v2 plus looks/modes requirements and the updated 7-phase roadmap. |
| `datasheet-check.md` | Film data check: blueprint vs real datasheets, coverage table, gaps, corrected document codes. |
| `gemini-datasheet-prompt.md` | The prompt used for Gemini's datasheet research. |
| `project-input-notes.md` | Short notes on each input file (sizes, formats, caveats). |
| `history/blueprint-v1-original.md` | The original blueprint v1 (523 lines), kept for reference. |
| `datasheet-data/datasheet-urls.txt` | Gemini's 59 links (only 21 worked). |
| `datasheet-data/download-datasheets.ps1` | PowerShell script that downloads those links. |
| `datasheet-data/pdfs/` | 68 verified manufacturer datasheet PDFs. |
| `datasheet-data/datasheet-profiles/` | 69 digitised stock profiles (spektrafilm JSON format). |
| `datasheet-data/spektrafilm-profiles/` | 28 Spektrafilm profiles plus its licence. |
| `datasheet-data/digitized/<stock>/` | Per-stock CSVs (density curves, spectral sensitivity, dye density) and QA overlay PNGs. |
| `datasheet-data/extraction-tools/` | Python scripts (`tools/`) and per-stock specs (`specs/`) that rebuild the profiles from the PDFs. |
