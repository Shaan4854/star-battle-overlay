# Star Battle & LinkedIn Queens Android Solver

An elite, hyper-lightweight native **Android App** that effortlessly solves **LinkedIn Queens** and $N \times N$ Star Battle puzzles with **automatic screenshot color & grid detection**.

📦 **[Download Latest Signed Release APK (v1.2.0)](https://github.com/Shaan4854/star-battle-overlay/releases/download/v1.2.0/star-battle-overlay-v1.2.0-release.apk)**

---

## 📱 Native Android App Features

### 1. 📁 Screenshot Auto-Detection
- Tap **📁 Pick Screenshot** and select any screenshot taken from LinkedIn Queens (desktop or mobile).
- The built-in native computer vision engine automatically:
  - Locates the square board boundaries.
  - Detects the grid size $N$ (e.g. 7x7, 8x8, 9x9, 10x10, etc.).
  - Samples cell median RGB values and clusters them into distinct color regions.
  - Automatically loads and solves the puzzle without any manual painting!

### 2. 🎨 Interactive Starting Layout
- Custom hardware-accelerated `InteractiveGridView` with authentic LinkedIn Queens thick borders between different regions.
- Tap or drag across cells to paint and tweak colors if desired.
- Color palette with live color swatches and active indicator.

### 3. 👑 Solved Layout with Gold Crowns
- Instant recursive backtracking CSP solver with $O(1)$ adjacency verification.
- Displays the solved board with glowing gold crown badges (👑) placed on the exact coordinates.

### 4. ⚡ Presets & Size Selector
- Switch grid dimensions from 4x4 up to 12x12 with 1 tap.
- Preloaded with authentic LinkedIn Queens layouts (Presets 1 to 5) and Clear button.

### 5. 🚀 Floating GameGuardian HUD (Optional In-Game Overlay)
- Transparent touch pass-through overlay (`OverlayCanvasView`) that draws glowing animated target reticles right on top of your LinkedIn game.
- Zero idle CPU: screen capture surfaces are created on-demand for exactly one frame and immediately released.
- Crash-proof: Full Android 14 `MediaProjection.Callback` lifecycle integration and safe graphic buffer streaming.

---

## 🛠️ Building & Installing

### Download Pre-built APK:
Grab the signed release APK directly from the GitHub release:
👉 **[star-battle-overlay-v1.2.0-release.apk](https://github.com/Shaan4854/star-battle-overlay/releases/download/v1.2.0/star-battle-overlay-v1.2.0-release.apk)**

### Install via ADB:
```bash
adb install -r app/build/outputs/apk/release/star-battle-overlay-v1.2.0-release.apk
```

### Build from Source:
```bash
./gradlew assembleRelease
```
The output APK is generated at:
```
app/build/outputs/apk/release/app-release.apk
```

---

## 🌐 Web Companion (Optional)

A lightweight web version is also included in `/web`:
```bash
cd web
bun install
bun run dev
```

---

## 📄 License
MIT
