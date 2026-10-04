# TapLoop

Small auto clicker / macro recorder for Android (made for Android 14, works on 8.0+). No root.

You hit **Rec**, use your phone like normal, hit **Stop**, and it plays back what you did. You can edit every step afterwards and loop it as many times as you want.

## Features

**Recording**
- **Record taps, holds, swipes, double taps and two finger gestures** from a floating panel. Your touches still go through to the app while recording.
- Swipes keep their real path and speed, not just a straight line.
- **Smart record**: also grabs the color / a small image of what you tapped. On playback it skips the recorded delays and taps the moment that thing shows up again. Long press **Smart** to pick what it waits for (image, color, either, both).

**Playback**
- **Loop** a set number of times or forever, pause between loops (with optional random extra), speed %, stop after N minutes.
- **Pause / resume** from the panel, progress bar, and an optional marker where each tap lands.
- Panel can be collapsed into a small bubble, resized and made see-through. It moves out of the way if a tap would land on it.
- Macros recorded on another resolution get scaled. Rotated screen gives a warning.
- If screen capture stops mid-run it keeps going on the recorded timing instead of hanging.

**Conditions** (need screen capture)
- Wait for a color, for a color to go away, for an image, for an image to go away, either or both. Optional "has to hold for X ms" and check interval.
- **Find image** steps: take a screenshot, drag a box around a button, and the step taps wherever that button is on screen (with offset, threshold and optional search area).
- Crosshair picker to set the point / color right on the screen instead of typing numbers.

**Editor**
- Change delays, hold time, coordinates, swipe end point. Drag to reorder, duplicate, delete, multi select + bulk edit, undo / redo, timeline bar, on screen preview of all steps.
- Labels, repeat a step N times, random delay (±ms) and random tap offset so it looks less robotic.
- **Go to step** / **Stop macro** steps and "found → go to / else → go to" jumps, so you can build simple if / loop logic.

**Starting / stopping**
- Countdown before start, home screen shortcuts, a quick settings tile, and a daily schedule.
- Stop on volume key, when the screen turns off, or after a time limit.

**Other**
- Folders, search and sort in the macro list. Export / import / share macros as JSON files.
- Settings for default delays / tolerance / timeouts, smart record defaults, capture quality, haptic tick while recording, keep screen on while playing, accent color.
- Simple dark UI.

## Install

Grab `TapLoop-vX.X.apk` from [Releases](../../releases) and install it.

### Permissions it asks for

| What | Why |
|---|---|
| Accessibility service | Sends taps/swipes and draws the floating panel. Doesn't read any screen text (`canRetrieveWindowContent=false`). |
| Screen capture (MediaProjection) | Only for color / image checks and smart record. Optional. |
| Notifications | Android needs a notification while screen capture is on. |
| Schedule (no extra permission) | Uses a normal (inexact) alarm, no exact alarm or boot permission. It gets re-armed when the accessibility service starts. |

No internet permission, no storage permission. Macros are saved as JSON in the app's private folder.

### Android 13/14 "restricted setting"

Since the APK is sideloaded, Android greys out the accessibility toggle at first. Fix:

1. Settings → Apps → TapLoop
2. ⋮ menu (top right) → **Allow restricted settings**
3. Go back to Accessibility and turn TapLoop on

When the screen share popup shows up, pick **Entire screen**, not a single app, otherwise the coordinates won't match.

### Updating

Install the new APK over the old one, macros stay. If an installer refuses (Play Protect etc.) `adb install -r` or Shizuku based installers work fine. After updating from 1.x turn the TapLoop accessibility service off and on once, otherwise the volume key stop won't work (Android only picks up the new key filter flag on rebind).

## How to use

1. Open TapLoop, enable accessibility, (optional) start screen capture.
2. **Show floating panel**.
3. Go to the app you want to automate, press **Rec** (or **Smart**), do your taps, press **Stop**.
4. Press **Play**. Press it again to stop.
5. Back in TapLoop you can **Edit** the recording: change delays, set loops to 0 for forever, add color conditions etc. **Use** loads a macro into the panel.

Drag the panel by the dots at the top. Keep it away from where the macro taps or it'll press its own buttons.

## Building

```
./gradlew assembleDebug
./gradlew test   # json / scaling unit tests, plain JVM
```

Needs JDK 17 and the Android SDK (platform 34). For a signed release build create `keystore.properties` in the project root:

```
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

then `./gradlew assembleRelease`. The GitHub workflow does the same thing from repo secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) whenever a release is published.

## Notes / limits

- Up to two fingers. Pinch etc. with more fingers is recorded as the first finger.
- Coordinates are absolute. Different resolutions get scaled, but a rotated screen won't line up.
- Screen frames are captured at half resolution by default (changeable in settings) to keep it fast. Image matching is a simple per-pixel diff, not some fancy ML thing, so big UI changes (themes, animations) can throw it off. Bump the tolerance if it misses.
- While recording, a touch that lands in the ~50 ms right after the previous one is passed through to the app but not recorded.

## License

MIT
