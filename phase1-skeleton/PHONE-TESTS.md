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

## UI overhaul (branch `claude/project-thread-n9smr3`)

Replaces the Batch B top band and sheet layout. Install, open in the Modern theme, portrait first.

Top band
1. One quick bar: flash icon (left), **Single** chip, **3:4** chip, timer icon, portrait icon, gear (right), with the
   readout under it. Labels in the readout (`ISO`, `EI`, `left`) are dim and values bright; nothing overlaps the punch hole.
2. Flash: each tap cycles Auto (bolt with A), On (bolt, gold), Off (crossed bolt), and the status pill says
   "Flash on/off/auto". Shoot once on On with the rear camera: flash behaves as before.
3. Capture chip: each tap cycles Single, Burst, HDR, Long 2s, Long 4s, Long 8s, Trails (gold when not Single), with a
   one-line hint in the status pill. Take one Burst and one HDR shot: the stack status shows and both save.
4. Frame chip: 3:4, 16:9, 2.39 (pill says "Frame 16:9" etc.); the preview mask changes as the old Gate chip did.
5. Timer: tap cycles off, 3s, 10s. On 3s press the shutter: a big 3, 2, 1 counts down on the preview with a click
   each second, then the shot is taken. Press the shutter again during the count: it cancels ("Timer cancelled").
6. Portrait icon toggles portrait blur (gold when on); same behaviour as the old Portrait chip.
7. Gear opens the sheet; gear again, Done, a tap outside or Back closes it. The sheet reopens on the last tab.

Viewfinder
8. Tap the preview: a white ring appears at the finger and fades after ~2.5 s. With Portrait on, the blur focus
   follows the tap as before.
9. Touch the preview and drag up/down: a gold sun scale appears beside the ring and exposure brightens (up) or
   darkens (down) in 0.1 EV steps, with a light tick every half stop, clamped at ±2.0. Open the sheet: Look >
   EXPOSURE shows the same value.
10. Pinch out on the preview: steps .6 → 1× → 3; pinch in goes back. On the front camera pinch does nothing.
11. Display > Grid: 3×3 draws thirds lines inside the preview only (not on the black bands). Survives restart.

Film strip and modes
12. The first chip of the strip is the active film (gold outline, film icon); tapping it opens the stock browser.
    Picking a stock label fills that label gold and the first chip shows its full name.
13. Mode strip reads **PRO  PHOTO  VIDEO**. Tap each, and swipe left/right across the strip: PRO ← PHOTO → VIDEO.
    VIDEO hides the capture, timer and portrait items in the quick bar and shows the record button.

PRO mode
14. In PRO a dark bar appears above the film strip with ISO, SPEED, WB, TINT, FOCUS, f/, EV, each showing AUTO
    (or its value). Tap ISO: a ruler opens above the row with a gold index; drag it or tap left/right of the index.
    Each step clicks (sound + haptic) and the value turns gold when manual. Tap ISO again to close the ruler.
15. SPEED 1/1000 on a bright scene darkens the preview and the readout starts with a gold `M`. Set it back to AUTO.
    Also try 1/4 and 1": the preview keeps running (the same speeds the film-body dial already offers); log any freeze.
16. WB 3000K and 7000K shift the colour the same way the old WB slider did; TINT G/M shifts green/magenta.
17. FOCUS: ∞ then MIN on the 1× lens; the image racks focus. Switch lens: FOCUS goes back to AF.
18. Leave PRO for PHOTO: the readout loses the `M`, ISO is auto again. Go back to PRO: the last PRO values return.
    Restart the app in PRO: it reopens in PRO with the values kept (focus starts on AF).
19. f/ and EV in PRO are the same values as portrait blur and Look > EXPOSURE (change one, the other follows).

Sheet
20. Three tabs: **Look** (push/pull, exposure, grain, halation, format, lens character), **Capture** (Film ISO, Auto
    Gain, WB, Save RAW, 4F Burst, 24fps step print, Desqueeze, plus the VIDEO rows in video mode), **Display**
    (theme, grid, peaking, histogram, zones, sounds, new roll). Long-press "Settings" still shows the Debug tab.
21. Display > Sounds: Off silences shutter / detent / click sounds but haptics stay.

Film themes and performance
22. Switch to Black SLR from PRO: the app drops to PHOTO and the speed dial controls the shutter. Back to Modern:
    shutter is auto again (readout `A`).
23. Take 10 shots quickly: the shutter button and the readout do not stutter right after each shot (the gallery
    thumbnail now decodes off the UI thread), and the thumbnail pops in with a small scale animation.
24. Landscape both ways: zoom pills, shutter, gallery, flip, flash, timer and portrait icons turn upright.
