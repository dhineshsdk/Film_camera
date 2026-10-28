# Stage A0: golden scenes and test kit

Goal: shoot 15 fixed scenes once as RAW bursts, then re-render them with any build and compare
numbers instead of eyeballing. Every later stage (A1 onwards) is judged on these same bursts.

## 1. Install the build
`.\build_sprint1.ps1`, then `adb install -r <buildDir>\aligned.apk`, open the app in photo mode.

## 2. Shoot the 15 scenes (tripod or a firm rest; the "mean" renderer does not align frames)

Lens ids: 0 = 1x, 2 = 0.6x, 6 = 3x, 1 = front.

| # | Tag | Lens | Scene |
|---|---|---|---|
| 1 | day_1x | 0 | Daylight, mixed shadow and sky, some fine detail (brick, leaves, text) |
| 2 | day_06x | 2 | Same style of scene, wide |
| 3 | day_3x | 6 | Same, tele, subject about 5 m away |
| 4 | day_front | 1 | Selfie in daylight, face plus background |
| 5 | backlight_1x | 0 | Subject against a bright window or sky |
| 6 | backlight_06x | 2 | Same, wide |
| 7 | backlight_3x | 6 | Same, tele |
| 8 | backlight_front | 1 | Selfie with your back to the window |
| 9 | night_1x | 0 | Street or room at night, a few light sources in frame |
| 10 | night_06x | 2 | Same, wide |
| 11 | night_3x | 6 | Same, tele |
| 12 | night_front | 1 | Selfie at night, indoor light only |
| 13 | portrait_1x | 0 | Person at 1.5 to 2 m, background 3 m or more behind, use `-Portrait` |
| 14 | portrait_3x | 6 | Same, tele, use `-Portrait` |
| 15 | portrait_front | 1 | Selfie portrait, use `-Portrait` |

Per scene, from the repo root in PowerShell:

```
.\tools\a0_shoot.ps1 -Tag day_1x -Lens 0
.\tools\a0_shoot.ps1 -Tag portrait_3x -Lens 6 -Portrait
```

It switches lens, waits 3 s, saves 8 consecutive RAW frames to
`/sdcard/DCIM/S24Film/a0/<tag>/0001.dng ...` with a `burst.json` (ISO, exposure, timestamps per
frame, skipped-frame count), and pulls them to `.\bursts\<tag>`. Raw adb equivalent:

```
adb shell am broadcast -a com.s24film.camera.DEBUG --es cmd rawburst --ei n 8 --es tag day_1x
```

Check `burst.json`: `"skipped"` should be 0 or small. Each DNG is about 25 MB, so the 15 bursts
take about 3 GB; delete `/sdcard/DCIM/S24Film/a0` on the phone afterwards.

## 3. Compare builds on a PC (numpy and Pillow only)

```
python tools/ab_compare.py bursts/* --a single --b mean
python tools/ab_compare.py bursts/* --a "cmd:python build_old.py {burst} {out}" --b "cmd:python build_new.py {burst} {out}"
```

Built-in renderers: `single` (frame 1) and `mean` (average of all frames). An external build is
any command that reads the burst folder and writes `{out}` as a `.npy` of float32 H x W x 3 linear,
white-balanced RGB in 0..1. For each burst it prints, A versus B:

- noise: flat-area noise estimate in 8-bit code values after an sRGB curve (lower is better)
- sharpness: p99 edge gradient after a 3x3 blur (higher is better)
- clip hi / clip lo: share of pixels at white or at black in the render (lower is better)
- a raw line: temporal sigma across frames in DN and raw clipping share

It also writes `ab_out/<burst>_A_vs_B.png` (side by side) and `ab_out/ab_results.json`. Verdict
thresholds: 5% on noise and sharpness, 0.05 points on clipping. `python tools/ab_compare.py --selftest`
checks the script on a synthetic burst.

## Rules
- Never leave the RAW stream undrained: the dump copies a frame and leaves the Image to the normal
  path, with at most 2 copies in flight, so the HAL keeps being drained.
- Re-shoot a scene only if the scene itself changed; the point is that the bursts stay fixed.
