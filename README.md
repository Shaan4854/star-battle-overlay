# Star Battle Overlay

An Android overlay app that reads **one** screen frame, solves the Star Battle puzzle visible on it and draws the solution on top of the game.

The app takes a single screenshot through `MediaProjection`, detects the puzzle board, works out the grid size and the coloured regions, solves the puzzle exactly and paints a ring on every star position with a transparent overlay window on top of the game.

<p align="center">
  <img src="artifacts/synthetic-puzzle.png" alt="Synthetic Star Battle board used to test the detector" width="420">
</p>

## How it works

| Stage | What happens | Where |
|---|---|---|
| Capture | `MediaProjection` + a single `ImageReader`/`VirtualDisplay` pair grab **exactly one** frame, which is then copied into a software `Bitmap` and the projection is released immediately. | `CaptureService.kt` |
| Detect | The frame is downscaled, the background colour is estimated with a 5-bit histogram, and the largest connected blob is flood-filled to find the board. | `PuzzleAnalyzer` in `CaptureService.kt` |
| Grid size | Column/row edge profiles are built, the cell pitch is recovered by normalised autocorrelation and cross-checked against boundary coverage; the candidate that yields exactly N regions **and** a solvable board wins. | `PuzzleAnalyzer` |
| Regions | Each cell is sampled with a patch median, and sampled colours are merged with a Manhattan-distance tolerance into region ids. | `PuzzleAnalyzer` |
| Solve | A recursive backtracking CSP places one star per row, per column and per region, with a no-touching rule and two forward checks that prune dead branches. | `StarBattleSolver.kt` |
| Draw | A `WindowManager` overlay view paints a ring on every solved cell. | `OverlayCanvasView.kt` |

## Requirements

* Android 8.0 (API 26) or newer
* `compileSdk` / `targetSdk` 34, Java 17, Kotlin 1.9.24, Gradle 8.7 (wrapper included)

## Build

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew :app:assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
```

The build needs an Android SDK with platform 34 and build-tools 34; point `ANDROID_HOME` at it and create `local.properties` with `sdk.dir=/path/to/android-sdk` if the SDK is not auto-detected.

To produce a distributable build, add a `signingConfig` to the `release` build type and run:

```bash
./gradlew :app:assembleRelease
```

The debug APK is signed with the local debug keystore, so it is for sideloading only - it is not suitable for the Play Store.

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

or copy the APK to the phone and allow "install unknown apps" for your file manager.

## Use

1. Open the app and grant the **overlay** permission (it opens the system settings page for "Display over other apps").
2. Open the puzzle in the game and make sure the whole board is visible.
3. Tap **Solve** in the app, accept the screen-capture consent dialog, and switch back to the game within the capture delay (2 seconds by default).
4. The solution appears on top of the game. The notification has a **Clear overlay** action, and so does the app.

## Project layout

```
app/src/main/java/com/starbattle/overlay/
├── MainActivity.kt              # permission flow, capture request, settings entry points
├── CaptureService.kt            # foreground service, screen capture, board detection
├── overlay/OverlayCanvasView.kt # transparent overlay view that draws the stars
└── solver/StarBattleSolver.kt   # exact backtracking solver
```

## Permissions

| Permission | Why |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Draw the solution on top of the game. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Android 14 refuses `getMediaProjection()` unless a foreground service of type `mediaProjection` is already running. |
| `POST_NOTIFICATIONS` | The foreground-service notification has to be visible on Android 13+. |

## Limitations

* The detector assumes an **axis-aligned** board that draws its cell borders and uses **distinct region colours**. Boards without visible grid lines, or with two regions sharing a colour, are not detected.
* The board must be fully visible on screen when the frame is captured, and no other large UI element may touch the board (the background/blob separation would fail).
* Grid sizes from 2x2 to 14x14 are supported.
* The app is **debug-signed** in this repository; create your own release signing config before distributing it.
