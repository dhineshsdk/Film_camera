# Phone tests

Each batch adds its own section. Build the PR branch, install it, run the steps, and note pass / fail
with a screenshot or logcat line per step.

## Batch B: UI polish and menu layout (branch `claude/ui-polish-layout-tvqkr2`)

Modern UI, portrait, then turn the phone to both landscapes:
1. Top band shows: gear (left), film chip with the current film name (centre), sliders icon (right), and a
   one-line readout under them, e.g. `A 1/60   ISO 640   EI 400   1×   01/36`. Nothing overlaps the
   preview's top edge or the punch-hole camera.
2. Readout updates live: cover the lens and the shutter/ISO change; switch .6 / 1× / 3 / flip and the lens tag
   follows (`.6×`, `1×`, `3×`, `Front`); Tune > push +1 adds `+1` after EI; take a photo and the frame count goes up.
3. Tap the film chip: the list of all looks opens; pick one and the chip shows its name.
4. Tap the sliders icon: the sheet opens on **Tune** (push chips + 3 sliders) and stops above the shutter row;
   the shutter still works with it open. Tap the gear: switches to **Film**. Tabs Film / Camera / Display each
   show their chips. Close with Done, a tap on the preview, the back gesture, or the same icon again.
5. Long-press the word **Settings** in the sheet: a **Debug** tab appears (ROT, Stats, test series). Stats: On
   shows the fps / temperature pill top-left. Long-press again hides the tab. Debug state survives an app restart.
6. Status pill (under the readout): shows "Switching to …" on lens change and "Saved 02/36 · JPG …" after a
   shot, then fades out after ~2.5 s. While recording video it stays on with the timer.
7. Low light (cover the lens with an ISO 100 film like Ektar): the yellow "Not enough light" banner with Push
   appears under the readout, not over the buttons.
8. Landscape (both ways): gear, sliders, zoom pills, gallery, shutter and flip turn upright; text rows stay
   horizontal; nothing overlaps.
9. Bottom band: zoom pills sit on the preview's bottom edge; film labels, PHOTO / VIDEO and the shutter row are
   on black with no overlap. Swipe on PHOTO / VIDEO still switches mode.

Film themes (Display tab > Black SLR, then Silver Rangefinder; or `adb shell am broadcast -a com.s24film.camera.DEBUG --es cmd theme --ei idx 1|2`):
10. The chrome plate and the bottom leather end exactly at the live image edges: no plate over the top of the
    preview and no black gap at the bottom. Check portrait and both landscapes.
11. Lever, dials, counter and brand text sit fully on the plate; MENU opens the same settings sheet above the dials.
12. The low-light banner and status pill appear inside the viewfinder window, below the plate.
One section per batch. Each lists what to install, what to do on the phone, and what counts as a pass.

## Batch C: portrait tilt and orientation

Build: branch `claude/portrait-tilt-fftv7j` (PR "Portrait tilt fix"). Logcat tags: `S24FilmIMU` (grip seed), `S24FilmCamera` (`Capture grip ...` per shot), `S24FilmDebug` status line (`grip N ROT +M`).

1. **Portrait cold open.** Force-stop the app. Hold the phone upright in portrait, open the app, shoot within 1 s. Pass: JPEG upright, portrait shape; log shows `Grip seeded at 0` and `Capture grip 0`.
2. **Landscape then portrait reopen (the reported bug).** Shoot one photo in landscape (left edge down). Press Home. Turn the phone to portrait, reopen, shoot at once. Pass: second photo upright portrait, not sideways; controls do not spin on open.
3. **Pointing down / up.** Portrait, aim at a table (phone nearly flat, screen up) and shoot 3 times with small wobbles; repeat aiming at the ceiling. Pass: all portrait, no flip to landscape.
4. **Near 45 deg.** Tilt slowly from portrait toward landscape. Pass: controls and preview switch only past about 65 deg, and stay put when wobbling around 45 deg.
5. **Shutter jolt.** In portrait, tap the shutter hard so the phone rocks. Pass: photo uses the grip at the moment of the tap (log `Capture grip 0`).
6. **Both landscapes still fixed.** Left edge down and right edge down, one shot each on 1x. Pass: both upright landscape (no regression of the earlier landscape fix).
7. **All lenses.** Repeat 1 and 6 on 0.6x, 3x and front. For the front, take a real selfie in portrait and in landscape. Pass: all upright (also closes issue 3).
8. **ROT reset.** Press ROT once (+90), switch lens. Pass: button reads `ROT +0°`, next photo upright. Press ROT, start the test series. Pass: series starts at `ROT +0°`, all photos upright.

adb shortcut for 1, 6, 7 without tapping: `adb shell am broadcast -a com.s24film.camera.DEBUG --es cmd capture`, `--es cmd lens --es id 0|2|6|1`, `--es cmd status`.

## Batch A: stock browser

Install the build, then check:

1. Tap the **All 120** chip at the start of the stock strip: a full-screen browser opens. Header shows "120 of 120".
2. Tap the film canister window (film theme) or long-press any stock chip: the same browser opens.
3. Tabs: All, Favourites, Recent, C-41, ECN-2, Slide, B&W, Print, Cinema, Fuji Sims. Counts should add up to 120 (C-41 33, B&W 26, Slide 21, Fuji 14, ECN-2 9, Cinema 9, Print 8).
4. Search: "portra", "400", "velvia", "ilford" each narrow the list; two words narrow further.
5. Tap a stock: browser closes, status text and canister show that stock with its box ISO, and the preview look changes. Take a photo; the file name carries the stock tag.
6. Star a stock, switch to Favourites: it is listed. Picked stocks appear under Recent.
7. Close and reopen the app: the last picked stock is still selected. Tapping one of the 12 chips resets that.
8. Render check (item 4 of the TODO): shoot the same scene on a few stocks per category and note any that are very dark, have a colour cast, or show a wrong ISO in the list. Report their names.

Not done in this batch: real-pipeline thumbnails (the list shows a category-colour swatch with the box ISO), Aerochrome IR profile (button still uses the legacy path), per-stock push/pull control.

## Batch F: video (branch `claude/project-thread-6f6h1x`)

Logcat tags: `S24FilmCinema` (one `Recording WxH @fps ...` line per take, with the renderer's `10-bit HLG` / `8-bit SDR`), `S24FilmVulkan` (`Zero-Copy MediaCodec Vulkan Swapchain active ... colorspace N`), `S24FilmSoak` (every 10 s). Soak CSVs: `Android/data/com.s24film.camera/files/soak/`.

1. Switch to video. The first time, the app asks for the microphone. Settings sheet, Camera tab, VIDEO section: FPS, SHUTTER, RANGE, SIZE, AUDIO chips; one selected per row; they don't react while recording.
2. 24 fps, 180°, SDR, 1440p, audio on: record 20 s of a moving scene with sound. Status reads `● REC 00:12 · 24 fps · 180° · SDR · NNN ft`. Saved line reads `Saved N MB · X fps · N dropped · SDR`. The clip plays in Samsung Gallery with the film look, sound in sync (clap on camera), not stretched.
3. Shutter angle: 24 fps at 45° then 360°, panning: 45° is crisp and stuttery, 360° smeared. Brightness stays about the same (ISO takes up the change; the readout ISO moves). Repeat at 30 fps.
4. HLG: RANGE = HLG 10-bit, record 15 s. The start toast shows any fallback ("HLG not supported", "HLG rejected by encoder"). Check the `S24FilmVulkan` line: `10-bit HLG` with colorspace 1000104010 is the good case. Gallery should show the HDR badge and the clip should look like the SDR one, a bit brighter in highlights, not washed out or with wrong colours. If the clip is garbage/green or the encoder errors, note it and use SDR.
5. Orientation: record 5 s in portrait, both landscapes and upside down. Each should play upright in Gallery (same as photos shot in that pose).
6. 4K soak: SIZE = 4K, 24 fps, SDR, record 10 minutes on a table. Note the last `S24FilmSoak` line (fps, drops, max gap, thermal) and whether the take stopped itself ("Stopped: phone too hot"). Send the soak CSV. Repeat with HLG if 4 passed.
7. Stop/start: stop, then press record again straight away; a second take starts once the first has saved. Lock the phone mid-take: the take saves.

Known limits: the film render runs at the viewfinder size, so 4K is upscaled from it (the soak test checks encoder heat and dropped frames, not 4K detail). The take runs at the RAW preview rate, so if the preview only manages ~22 fps the soak line and drop count will show it.
## Batch E: burst, HDR and long-exposure stacking

Build: branch `claude/project-thread-g6ey3c` (PR "Burst, HDR and long-exposure stacking"). Logcat tags: `S24FilmCamera` (`HDR frame ...`, `Master still ...`), native `Stack begin / Stack finish`. Settings sheet > Camera > CAPTURE row > new chip cycles **Single, Burst 8, HDR 3, Long 2s, Long 4s, Long 8s, Trails 8s**. No new camera streams: every mode reads the existing RAW stream, and the DNG (if on) is the last single frame, the merge is in the JPEG.

Use a tripod or rest the phone on something for 2 to 6.
1. **Single is unchanged.** Chip on Single, shoot as before. Pass: same speed and look as main, no extra log lines.
2. **Burst 8.** Dim room, hand on a table, shoot. Status counts `Burst n/8`, then `Merged 8 frames`, then Saved. Pass: less shadow noise than Single; a hand waved through the frame does not leave ghosts.
3. **HDR 3.** Window scene (bright sky, dark room), phone resting. Log shows three `HDR frame` lines near `+0.00`, `-2.00` and `+2.00 EV` (shutter may clamp, ISO makes up the rest). Pass: sky detail back, shadows cleaner than Single, no flicker or crash, preview exposure returns to normal afterwards and the preview stays live.
4. **HDR with motion.** Same, with a person walking. Pass: no hard double edges (moving areas fall back to the base frame).
5. **Long 4s.** Tap or a running tap, tripod. Status `Exposing n / 4.0 s`. Pass: water or moving lights smear, static parts stay sharp and the brightness matches Single (it is a mean, like an ND filter).
6. **Trails 8s.** Night, car lights or a torch swung around. Pass: bright paths accumulate; the dark background stays dark.
7. **Stability.** Run each mode 5 times in a row, then switch lens (0.6 / 1 / 3 / front) in the middle of a Long 8s. Pass: no HAL restart or phone reboot, shutter button re-enables (`Stack cancelled` if the lens switch cut a shot).
8. **Film looks.** Repeat 2, 3 and 5 on Portra 400 and on a B&W stock. Pass: same look as Single, exposure and grain consistent.

## Stage A1: zero-lag RAW ring

Build: branch `claude/project-thread-4h4og3` (PR "Stage A1: zero-lag RAW ring"). Logcat tags: native `RAW ring: N slots`, `Zero-lag: ...`, Java `Burst: n of m frames from before the press (ring N)`. The GPU RAW ring grows from 4 to 10 slots (6 or 4 if the memory is not there); the camera's own RAW buffer stays at 4 and every frame is still released at once. Burst and night merges now start from the frames already on screen at the press instead of the frames after it.

1. **Ring size.** Open the app. Pass: `RAW ring: 10 slots (260 MB)` in logcat (6 or 4 is a soft fail: note it, the merge still works with fewer pre-press frames).
2. **Daylight single shot is unchanged.** Chip on Single, daylight, 10 shots in a row. Pass: shutter feels as before, no `Zero-lag` lines, no extra wait.
3. **Burst 8 is zero lag.** Chip on Burst 8, dim room. Aim at something with text, press, and straight away swing the phone to the floor. Pass: the photo shows the scene you were aiming at when you pressed, sharp; log shows `Burst: 8 of 8 frames from before the press`.
4. **Night auto (12 frames).** Dark room with night auto on so it pushes 3 stops. Pass: `Burst: 8 of 12` (the other 4 come after the press), `Merged 12 frames`, no ghosts on a still scene.
5. **Exposure change right before the press.** Point at a lamp, then quickly at a dark corner and press at once. Pass: fewer than 8 ring frames are used (frames from the other exposure are skipped), no dark or bright blotches.
6. **Lens switch.** Switch .6 / 1 / 3 / front and shoot a Burst 8 right after each switch. Pass: no frames from the previous lens (no double image), log ring count may be low on the first shot.
7. **Soak.** 10 minutes of live view with a lens switch every minute and a Burst 8 shot every minute. Pass: no HAL restart or phone reboot, preview stays live, no `ring slot ... skipped` lines.
8. **A0 bursts.** `rawburst` still saves N consecutive frames as before.

## Stage A2: GPU tile merge

Same branch as A1. Logcat: `GPU merge ready` (tag `S24FilmMerge`) at start, `Stack begin mode 0 ... (GPU)`, one `GPU merge frame n (slot s): x ms, median shift dx,dy px` per frame, `Stack finish mode 0, n frames merged on the GPU`. Bursts now align every 32 x 32 RAW-pixel tile separately (hand shake and some subject motion), on the GPU; HDR and long exposures still use the CPU merge. Desktop check that already passed: `tools/gpu_merge_test.sh` (synthetic burst with hand shake and a moving square: 2.7x less noise of an ideal 2.8x, no ghost).

1. **GPU path is on.** Burst 8 in a dim room. Pass: `(GPU)` on the Stack begin line, 8 `GPU merge frame` lines, each under 40 ms; `Merged 8 frames` comes much sooner than with A1 alone.
2. **Handheld night.** Burst 8 handheld (no table) on text or a brick wall. Pass: sharp, no double edges; median shifts in the log are a few to tens of pixels.
3. **Walking people.** Burst 8 of people walking past. Pass: no ghosts or half-transparent copies; walkers look like one frame (a little noisier than the static background is fine).
4. **Rotation.** Twist the phone slightly while the burst runs. Pass: corners as sharp as the centre (tiles follow the rotation).
5. **Fallback.** If `GPU merge unavailable` appears instead, bursts must still work as in A1 (CPU merge). Note it.
6. **Golden set (A0).** Run `tools/ab_compare.py` on the walking-people and handheld-night bursts, A1 vs A2. Pass: lower noise, no ghost in the side-by-side.

## Stage A3: merge on every photo, HDR+ style

Same branch. Every normal shot is now a zero-lag GPU burst sized to the light: 4 frames at ISO 200 or less, 6 up to ISO 800, 8 above (the night push still goes to 8 or 12). Turn it off with `adb shell am broadcast -a com.s24film.camera.DEBUG --es cmd automerge --ez on false`. HDR is now the HDR+ style: a 6-frame zero-lag burst at the metered exposure, then two -2 EV frames that are used only where the burst clips (the old base / -2 / +2 bracket is only used if the GPU merge is unavailable). Desktop check that passed: `tools/gpu_merge_test.sh` HDR case recovers clipped highlights to within 0.017 of the true values (the clipped frame was off by 0.36).

1. **Every shot merges, without waiting.** Single on, daylight, 10 shots in a row. Pass: log shows `Burst: 4 of 4 frames from before the press` and `GPU merge frame` lines; the shutter feels the same as before A1; no `Stack failed`.
2. **Noise.** Indoor evening light (ISO 400 to 1600), the same scene with automerge on and off. Pass: visibly cleaner shadows with it on; A0 compare on the golden indoor scenes shows shadow noise down by about half.
3. **Moving subject.** A person walking across at ISO 800. Pass: no ghost or double edge.
4. **HDR with people.** HDR on, a person moving in front of a bright window. Pass: the person is as sharp as a normal shot (no bracket blur), the window shows detail instead of white; log shows the base `GPU merge frame` lines, then two `HDR short frame at -2.00 EV` lines; preview exposure returns to normal afterwards.
5. **HDR timeout.** HDR aimed at a scene where the exposure cannot drop 2 stops (already at the shortest shutter and lowest ISO). Pass: still saves a photo (the base burst) within about 3 s.
6. **Off switch.** `automerge --ez on false`: shots are single frames again, exactly like main.
