# Phone tests

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
