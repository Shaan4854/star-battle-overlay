# Star Battle Floating Overlay & Solver

An elite, hyper-lightweight, **zero-idle-CPU** Android utility that solves $N \times N$ Star Battle (LinkedIn Queens) puzzles using a floating GameGuardian-style HUD mod menu and a transparent, touch-passthrough screen overlay.

Built with **100% native Android APIs** — zero OpenCV, zero external ML dependencies.

---

## ⚡ Core Architecture

```
                                    +------------------------------+
                                    |       MainActivity           |
                                    |  • Overlay Permission Check  |
                                    |  • MediaProjection Consent   |
                                    +--------------+---------------+
                                                   |
                                                   v Starts ForegroundService
                                    +------------------------------+
                                    |     FloatingMenuService      |
                                    +-------+--------------+-------+
                                            |              |
                      TYPE_APPLICATION_OVERLAY             TYPE_APPLICATION_OVERLAY
                      FLAG_NOT_FOCUSABLE                   FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE
                                            |              |
                                            v              v
               +--------------------------------+      +---------------------------------+
               |             View A             |      |             View B              |
               |      (Floating Mod Menu)       |      |       (OverlayCanvasView)       |
               |  • Collapsed Glass Bubble      |      |  • 100% Touch Pass-Through      |
               |  • Drag with rawX / rawY       |      |  • Animated Neon Target Reticles|
               |  • Expanded Cyberpunk Menu     |      |  • Zero Idle CPU when cleared   |
               +----------------+---------------+      +---------------------------------+
                                |
                                | User taps "SOLVE PUZZLE"
                                v
               +------------------------------------------------+
               | 1. ImageReader.acquireLatestImage() (1 frame)   |
               | 2. Immediate image.close() & VirtualDisplay cut|
               | 3. GridDetector: Board bounds & RGB clustering |
               | 4. StarBattleSolver: Recursive backtracking CSP|
               | 5. Animate target dots on View B               |
               +------------------------------------------------+
```

---

## Features

### 1. Pure Recursive Backtracking CSP Solver (`StarBattleSolver.kt`)
- Solves arbitrary $N \times N$ Star Battle / Queens puzzles in milliseconds.
- Uses fast primitive `BooleanArray` buffers (`usedCols`, `usedRegions`).
- $O(1)$ adjacency verification ensures placed items never touch orthogonally or diagonally.

### 2. GameGuardian-Style Glassmorphism HUD (`layout_floating_bubble.xml` & `layout_floating_menu.xml`)
- Translucent dark glass aesthetics (`#B3000000` / `#CC101526`) with 24dp rounded corners.
- Smooth dragging across the screen via `event.rawX` and `event.rawY` touch handlers with drag vs. tap disambiguation.
- Compact floating bubble collapses and expands smoothly on user tap.

### 3. Touch Pass-Through Overlay Canvas (`OverlayCanvasView.kt`)
- User touches pass **completely through** the target overlay to the underlying game.
- Renders glowing emerald reticles (`#00FF88`) with smooth radial aura waves.
- **Zero Idle CPU**: When no targets are displayed, all animations are halted completely.

### 4. Zero-Idle-CPU Screen Capture Pipeline (`FloatingMenuService.kt` & `GridDetector.kt`)
- `VirtualDisplay` and `ImageReader` are created on-demand for **exactly one frame** when the "Solve" button is tapped.
- The `Image` is immediately closed (`image.close()`) and capture surfaces are disposed to eliminate memory leaks and battery drain.
- Pure Android native vision detects board edges and clusters cell colors into region IDs.

---

## Building & Installing

### Requirements
- Android SDK 34 (Android 14)
- Java 17+
- Kotlin 1.9.24+

### Build Debug APK
```bash
./gradlew assembleDebug
```
The resulting APK will be located at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Run Unit Tests
```bash
./gradlew test
```

### Install via ADB
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Usage Guide

1. **Launch the App**: Open **Star Battle Mod** and grant **Overlay Permission** (`Display over other apps`).
2. **Launch Game & Menu**: Tap **🚀 Launch Game & Menu**, approve the screen capture prompt, and the app will start the floating service and launch your target game (e.g. LinkedIn).
3. **Solve In-Game**: Tap the floating bubble to expand the HUD, then tap **⚡ SOLVE PUZZLE**. Animated glowing green reticles will instantly highlight every target cell without blocking your touches.
4. **Clear or Close**: Tap **CLEAR TARGETS** to dismiss the reticles or **CLOSE MENU** to terminate the foreground service.

---

## License
MIT
