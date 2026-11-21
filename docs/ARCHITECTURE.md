# StuDO Camera — Architecture & Technical Design

## 1. Architectural Layers

StuDO follows a modern Android Clean Architecture with Model-View-ViewModel (MVVM) and unidirectional data flow (UDF):

```
┌────────────────────────────────────────────────────────┐
│                   UI Layer (Compose)                   │
│   CameraScreen ── TopControlBar ── ViewfinderOverlay   │
│   BottomControlBar ── QuickThumbnailPreview            │
└───────────────────────────▲────────────────────────────┘
                            │ StateFlow<CameraUiState>
                            │ User Intent Events
┌───────────────────────────┴────────────────────────────┐
│                    CameraViewModel                     │
│   Manages UI state (lens, flash, zoom, focus, capture) │
└───────────────────────────▲────────────────────────────┘
                            │
┌───────────────────────────┴────────────────────────────┐
│            CameraX Controller & Media Layer            │
│   ProcessCameraProvider, Preview, ImageCapture         │
│   CameraControl & CameraInfo, MediaStore Output        │
└────────────────────────────────────────────────────────┘
```

## 2. Key Components

### `CameraViewModel`
- Holds immutable `CameraUiState` via Kotlin `StateFlow`.
- Exposes clean callbacks: `onCaptureClick()`, `onLensSwitch()`, `onFlashModeChange()`, `onZoomRatioChange()`, `onTapToFocus()`, `onToggleTorch()`.
- Coordinates asynchronous image capture without freezing the UI thread.

### `CameraViewfinder` (Composable)
- Embeds Android `androidx.camera.view.PreviewView` inside Jetpack Compose using `AndroidView`.
- Configured with `PreviewView.ImplementationMode.PERFORMANCE` for lowest preview latency, or fallback to `COMPATIBLE` if needed.
- Captures pointer gestures:
  - `detectTransformGestures` for pinch-to-zoom mapping directly to `cameraControl.setZoomRatio`.
  - `detectTapGestures` to calculate normalized coordinates for `FocusMeteringAction`.

### `MediaCaptureManager`
- Direct encapsulation of Android `MediaStore.Images.Media` logic.
- Safely handles API levels:
  - Android 10+ (API 29+): `RELATIVE_PATH = "Pictures/StuDO"` with `IS_PENDING` transaction.
  - Generates ISO-formatted timestamped filenames: `StuDO_YYYYMMDD_HHMMSS.jpg`.
  - Handles rotation metadata so saved images render right-side up in all gallery applications.

## 3. Hardware State & Fallbacks
- **Missing Front/Back Camera**: Queries `cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)` before enabling lens toggle.
- **Flash/Torch Availability**: Queries `cameraInfo.hasFlashUnit()` and disables flash controls if unavailable (e.g. on front camera or emulator).
- **Zoom Constraints**: Listens to `cameraInfo.zoomState` to establish actual physical `minZoomRatio` and `maxZoomRatio` (e.g. 1.0x to 8.0x) instead of hardcoding assumptions.
