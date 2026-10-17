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
