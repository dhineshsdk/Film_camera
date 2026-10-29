PHONE TESTS, 29 Oct 2026 build (e417394: new UI + stages A1-A6 + B1-B6 + speed pass)
====================================================================================

How to report: for each line write PASS / FAIL / ? plus a screenshot or photo when it fails.
Photos are in DCIM/S24Film. Do part 1 and 2 first; part 3 needs a dark place, part 4 is optional.
(The border chip in this build is labelled "Film rebate / Print"; it means "Negative carrier / 35mm edge".)

PART 1. NEW UI (5 to 10 min, any light)
---------------------------------------
Top bar
[ ] 1. Top row: flash icon, "Single", "3:4", timer icon, portrait icon, gear. Readout under it. Nothing hits the punch hole.
[ ] 2. Flash: tap cycles Auto -> On (gold) -> Off; a pill says which.
[ ] 3. Capture chip: Single -> Burst -> HDR -> Long 2s/4s/8s -> Trails, with a hint pill. Take one Burst and one HDR: both save.
[ ] 4. Frame chip: 3:4 -> 16:9 -> 2.39; the preview mask changes.
[ ] 5. Timer: off -> 3s -> 10s. Shutter on 3s: big 3,2,1 on screen, then the shot. Shutter again during count cancels it.
[ ] 6. Portrait icon turns portrait blur on (gold) and off.
[ ] 7. Gear opens settings; gear, Done, tap outside or Back closes it.

Viewfinder
[ ] 8. Tap the preview: white ring at your finger, fades in ~2.5 s.
[ ] 9. Touch and drag up/down: gold sun scale, picture gets brighter/darker, light tick every half stop, stops at +/-2.0.
[ ] 10. Pinch out: .6 -> 1x -> 3. Pinch in goes back.
[ ] 11. Settings > Display > Grid: 3x3 lines only inside the picture.

Film strip and modes
[ ] 12. First chip of the film strip = current film (gold outline); tap opens the film list. Tapping a stock fills it gold.
[ ] 13. Mode strip shows PRO  PHOTO  VIDEO; tap or swipe switches. VIDEO hides capture/timer/portrait and shows record.

PRO mode
[ ] 14. PRO shows a bar: ISO, SPEED, WB, TINT, FOCUS, f/, EV. Tap ISO: a dial opens; drag or tap sides; each step clicks.
[ ] 15. SPEED 1/1000 in daylight darkens the picture, readout starts with gold "M". Try 1/4 too: preview keeps running.
[ ] 16. WB 3000K and 7000K change colour; TINT G/M shifts green/magenta.
[ ] 17. FOCUS: infinity then MIN on 1x; the picture refocuses. Switching lens puts FOCUS back to AF.
[ ] 18. Go to PHOTO: "M" disappears (auto again). Back to PRO: your values return.

Settings sheet
[ ] 19. Three tabs: Look, Capture, Display. Long-press "Settings" still shows Debug.
[ ] 20. Display > Sounds Off: no shutter/click sounds, vibration stays.
[ ] 21. Look > PRINT AND SCAN: Paper, Scan, B&W, Edge effects chips each change the picture; kept after app restart.
[ ] 22. Look > BORDER, AGE AND LEAKS: Border / Expired / Light leak chips change the picture and the saved photo.

Speed and themes
[ ] 23. Take 10 quick shots: no stutter after each shot; gallery thumbnail pops in.
[ ] 24. Turn phone to both landscapes: icons and shutter turn upright.
[ ] 25. Display > Black SLR from PRO: drops to PHOTO, speed dial works. Back to Modern: readout shows "A" again.

PART 2. EVERYDAY PHOTOS (10 min, daylight + indoors)
----------------------------------------------------
[ ] 26. Startup: app opens fast; second open is faster than the first.
[ ] 27. Daylight, Single, 10 shots in a row: shutter feels instant, every shot saves.
[ ] 28. Indoor evening light, same scene: shadows look clean (every photo now merges 4-8 frames).
[ ] 29. Person walking past at ISO 800: no ghost or double edges.
[ ] 30. Person in front of a bright window, no compensation: face exposed properly, not a silhouette.
[ ] 31. Selfie outdoors with sky behind: face exposed properly.
[ ] 32. HDR on, bright sky / window: detail in the bright part, person still sharp.
[ ] 33. Grain: plain wall on Pan F, HP5, Delta 3200 (100% zoom): Pan F fine, HP5 coarser, Delta 3200 clumpy.
[ ] 34. Portra 400 vs Velvia 50 on a wall: Portra has faint colour speckle, Velvia almost none.
[ ] 35. Dark window frame on bright sky, HP5: thin crisp rim on the edge, no glow.
[ ] 36. Paper chip on Portra (Auto, Crystal Archive, Supra, Ultra): greys stay neutral, Ultra most saturated.
[ ] 37. Scan chip on Portra (Neutral, Frontier, Noritsu): Frontier punchier/cooler, Noritsu softer/warmer.
[ ] 38. B&W chip on HP5 (Scan, Multigrade RC, FB Warmtone): RC deeper blacks, Warmtone brown-black and cream white.
[ ] 39. Border: negative carrier = thin rough black frame; 35mm edge = film edge with holes on the long sides.
[ ] 40. Expired on Portra at Medium/Strong: lifted faded blacks, less contrast, more grain, still a photo.
[ ] 41. Light leak Medium, 5 photos: a different red-orange glow from an edge on each.

PART 3. LOW LIGHT AND NIGHT (10 min, dark room or street)
---------------------------------------------------------
[ ] 42. Burst in a dim room, press then swing the phone away: photo shows what you aimed at, sharp.
[ ] 43. Handheld Burst on text/brick at night: sharp, no double edges.
[ ] 44. Ektar 100 at night, Auto, handheld: "Hold still" shows, photo correctly exposed and sharp.
[ ] 45. Repeat 44 twenty times, then switch lens and 5 more: no freeze, no phone restart, every shot saves.
[ ] 46. Street lamps with HDR, CineStill 800T vs Portra 400: 800T wide red-orange glow, Portra almost none.
[ ] 47. If any photo does NOT save within ~6 s: note the time and tell me (the app logs why now).
     Quick workaround from the PC (PowerShell):
       adb shell am broadcast -a com.s24film.camera.DEBUG --es cmd automerge --ez on false
       adb shell am broadcast -a com.s24film.camera.DEBUG --es cmd slowstill --ez on false

PART 4. STABILITY AND VIDEO (optional, 10 min)
----------------------------------------------
[ ] 48. 10 minutes of preview, switch lens and take a shot every minute: no freeze or phone restart.
[ ] 49. VIDEO: record 30 s, stop: it saves and plays; the photo shutter comes back after switching to PHOTO.
[ ] 50. Lens switch .6 / 1 / 3 / front, Burst right after each: no double image from the previous lens.

Send back: the list of FAILs with a photo or screenshot each, and the time of any shot that did not save.
