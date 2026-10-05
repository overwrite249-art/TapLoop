# CLAUDE.md

Notes for AI assistants (and humans) working on TapLoop. Read this first.

## What this is

TapLoop is an auto clicker / macro recorder for Android, mainly for Android 14 (minSdk 26). It doesn't need root.
You record taps, edit them, and loop them. It can also wait for colors or images on screen before tapping
("smart" mode). The owner is GitHub user `overwrite249-art`. The repo is public and MIT licensed.

The owner wants the code and commits to look like a person wrote them: short commit messages, casual comments, no
"AI-sounding" docs, and no big generated comment blocks. Keep that style.

## Stack / layout

- Plain Java, **no AndroidX**, no extra dependencies. AGP 8.5.2, Gradle 8.7 wrapper, compile/target SDK 34, JDK 17.
- Package `dev.overwrite.taploop`:
  - `service/`: the AccessibilityService and everything that runs on top of other apps
    - `TapService`: the service. Owns the panel, Recorder, Player, overlays, and gesture dispatch
    - `FloatingPanel` (+ `BubbleView`): the floating panel. Its ⋮ menu holds the macro loop settings and panel settings
    - `Recorder`: full screen touch layer. Each touch gets recorded, then replayed through so the app still gets it
    - `Player`: the playback thread (loops, jumps, pause, conditions, time limit, screen scaling)
    - `Gestures`: turns a Step into GestureDescriptions (paths, double tap, two fingers)
    - `TouchBlocker`: invisible layer that blocks the user's touches during playback
    - `TapIndicator`, `PickerOverlay`, `PreviewOverlay`, `ScreenOverlay`, `StopTriggers`, `RunGuard`, `ScreenFit`, `SmartMode`
  - `capture/`: MediaProjection (`CaptureService`, `ScreenGrabber`), `Matcher` (color/image conditions),
    `TemplateMatcher` ("Find image" steps)
  - `model/`: `Macro`, `Step` (JSON), `MacroStore` (files in app private dir), `Jumps`
  - `ui/`: activities (Main, Editor, Settings, Crop) and editor helpers
  - `trigger/`: shortcuts, quick settings tile, schedule, countdown/stop prefs
  - `io/`: import / export / share
  - `Prefs`: global settings
- Unit tests live in `app/src/test`. They run on the plain JVM, and `app/src/testShim` provides a small `org.json` copy.

## Build

```
export JAVA_HOME=/path/to/jdk17
bash gradlew assembleDebug
bash gradlew assembleRelease lintDebug testDebugUnitTest
```

`gradlew` is committed without the exec bit, so CI uses `chmod +x` / `bash gradlew`.

Release signing reads `keystore.properties` from the project root. That file is gitignored and the keystore is never
committed. The owner keeps the keystore and passwords privately, outside this repo. The cert SHA-256 starts with
`82:52:3E:AA` and ends with `DF:31`. Keep using that key, or updates won't install over old versions.

## CI / releases

- `.github/workflows/build.yml`: builds a debug APK on every push.
- `.github/workflows/release.yml`: when a GitHub release is **published**, it builds the signed release APK from repo
  secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) and attaches `TapLoop-vX.Y.Z.apk`.
- To release, bump `versionCode` / `versionName` in `app/build.gradle`, push, and create a release with tag `vX.Y.Z`.
  Then verify the APK cert with `apksigner verify --print-certs`.
- The owner installs through Shizuku, because Play Protect blocks normal sideloading.

## Gotchas learned the hard way

- `WindowManager.removeView()` detaches **asynchronously**. If you remove and re-add a view straight away
  (`bringToFront`), use `removeViewImmediate()`. Otherwise `isAttachedToWindow()` still reads true and the re-add gets
  skipped. This bug made the panel vanish when recording started (fixed in 2.0.1).
- Gestures we dispatch hit our own overlays too. Anything touchable that covers the target (panel, record layer,
  TouchBlocker) has to set `FLAG_NOT_TOUCHABLE` during the gesture and needs about 80 ms before the flag takes effect.
- While recording, a touch is passed through by re-dispatching it. A touch that lands in the next ~50 ms can be
  swallowed, so `Recorder` retries with growing delays.
- Overlays use `FLAG_NOT_FOCUSABLE`, so they **can't show a keyboard**. Panel settings use −/+ steppers instead of text fields.
- The volume key stop needs `canRequestFilterKeyEvents`. After an update the user has to turn the accessibility service
  off and back on once.
- `Step.copy()` must copy **every** field, because editor undo and duplicate depend on it. When you add a field, also
  add it to `copy()`, `toJson()`, and `fromJson()` (optional in JSON, so old macros still load).
- Step action ids: TAP 0, SWIPE 1, WAIT 2, GOTO 3, STOP 4, FIND_IMAGE 5. Never renumber them, because saved macros
  store the numbers.
- Capture scale can be changed in settings (0.25 / 0.5 / 1). Image patches and templates store the scale they were
  captured at (`patchScale`, `tplFrame`). Don't hardcode `ScreenGrabber.SCALE`.
- New macros default to `loops = 0` (forever). `Macro.fromJson` still defaults a missing `loops` key to 1, and a test
  checks that.

## History (summary of how this got built)

1. **v1.0**: first version. Record / edit / loop, color checks, smart record (small image patch per tap, plays back as
   soon as it shows up), dark UI, floating panel, MIT, CI with signed releases.
2. **v1.1**: fixed taps getting swallowed during smart recording (pass-through retries).
3. **v2.0**: 10 features were built in parallel on `feat/*` branches, then merged by hand:
   - picker (crosshair point/color picker, preview)
   - paths (real swipe paths, double tap, two fingers)
   - detect (color/image gone, either/both, stable time, interval)
   - template (Find image steps with crop screen)
   - flow (labels, repeat, jitter, offset, go to / stop, jumps)
   - editor (drag reorder, multi select, bulk edit, undo/redo, timeline)
   - share (folders, search, sort, import/export)
   - panel (bubble, pause, progress, tap markers, size/opacity)
   - triggers (countdown, shortcuts, tile, schedule, volume/screen-off/time-limit stop)
   - settings (settings screen, resolution scaling, capture-loss fallback, unit tests)
4. **v2.0.1**: fixed the panel disappearing when Rec / Smart was pressed, which left no way to stop recording.
5. **v2.0.2**: the panel ⋮ menu got loops / speed / pause between loops / random extra pause / stop after (−/+
   buttons). The panel became smaller by default (80%, slider down to 50%), and new macros loop forever by default.
6. **v2.0.3**: user touches are blocked while a macro plays (can be turned off in ⋮). The first recorded step no longer
   keeps the wait between pressing Rec and the first tap, which used to look like a pause at the start of every loop.

## Open ideas / not verified

- Nothing has been tested on a real device by the AI side. The owner tests on their phone.
- If looping still seems to stop, a run that ends without a message probably means the Player thread crashed. The next
  step would be to catch `RuntimeException` in `Player.run()` and show the message.
- StopTriggers' time limit doesn't pause while the macro is paused (the macro's own "stop after" does).
