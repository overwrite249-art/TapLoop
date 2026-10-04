# TapLoop

Small auto clicker / macro recorder for Android (made for Android 14, works on 8.0+). No root.

You hit **Rec**, use your phone like normal, hit **Stop**, and it plays back what you did. You can edit every step afterwards and loop it as many times as you want.

## Features

- **Record taps, holds and swipes** from a floating panel. Your touches still go through to the app while recording.
- **Edit timings**: wait before each step, how long the finger is held, coordinates, swipe end point. Reorder / duplicate / delete steps, add new ones.
- **Loop** a set number of times or forever, with an optional pause between loops and a speed % for the delays.
- **Color checks**: a step can wait until a certain color shows up at its X/Y before tapping.
- **Smart record**: records like normal, but also grabs a small image of whatever you tapped. On playback it ignores the recorded delays and taps the moment that image shows up again. Way faster than fixed delays, and it won't tap if the button isn't there yet. Can also search a few px around if the button moves a little.
- Simple dark UI.

## Install

Grab `TapLoop-vX.X.apk` from [Releases](../../releases) and install it.

### Permissions it asks for

| What | Why |
|---|---|
| Accessibility service | Sends taps/swipes and draws the floating panel. Doesn't read any screen text (`canRetrieveWindowContent=false`). |
| Screen capture (MediaProjection) | Only for color / image checks and smart record. Optional. |
| Notifications | Android needs a notification while screen capture is on. |

No internet permission, no storage permission. Macros are saved as JSON in the app's private folder.

### Android 13/14 "restricted setting"

Since the APK is sideloaded, Android greys out the accessibility toggle at first. Fix:

1. Settings → Apps → TapLoop
2. ⋮ menu (top right) → **Allow restricted settings**
3. Go back to Accessibility and turn TapLoop on

When the screen share popup shows up, pick **Entire screen**, not a single app, otherwise the coordinates won't match.

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

- Single finger only. Multi-touch gestures are recorded as the first finger.
- Coordinates are absolute, so if you rotate the screen the macro won't line up.
- Screen frames are captured at half resolution to keep it fast. Image matching is a simple per-pixel diff, not some fancy ML thing, so big UI changes (themes, animations) can throw it off. Bump the tolerance if it misses.
- While recording, a touch that lands in the ~50 ms right after the previous one is passed through to the app but not recorded.

## License

MIT
