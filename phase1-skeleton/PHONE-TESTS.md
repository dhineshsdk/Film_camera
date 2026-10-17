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
