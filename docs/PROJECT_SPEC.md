# StuDO Camera — Project Specification

## 1. Product Identity
- **Application Name**: StuDO (Pronunciation: *Studio*)
- **Category**: Photography & Video Tools
- **Platform**: Native Android (Kotlin + Jetpack Compose)
- **Camera Core**: CameraX (`camera-camera2`, `camera-lifecycle`, `camera-view`, `camera-core`)
- **Tagline**: *See the world differently.*

## 2. Target Audience & Positioning
StuDO bridges casual photography with serious manual controls. It eliminates bloated gimmicks, social feeds, and advertising from the camera experience, offering an experience reminiscent of precision photographic equipment.

## 3. Design System Specifications
- **Backgrounds**: Deep True Black (`#000000`) for AMOLED energy efficiency and maximum viewfinder contrast; Charcoal (`#121214`) for control bars; Dark Zinc (`#1C1C1F`) for interactive chips.
- **Accents**: Titanium Silver (`#E4E4E7`), Brushed Silver (`#D4D4D8`), Soft White (`#F4F4F5`).
- **Status Indicators**: Warm Amber (`#E5A93C`) reserved for active modifier states (flash active, timer active); Focus Ring Yellow (`#FACC15`) for point of focus.
- **Typography**: Clean, sans-serif high-legibility tabular figures for zoom ratios and camera telemetry.
- **Gestures**:
  - Pinch-in / Pinch-out: Smooth viewfinder zoom with clamp between minRatio and maxRatio.
  - Single tap: Point focus + exposure metering with animated square reticle.
  - Quick tap zoom pills: 0.5x (if ultra-wide available), 1.0x, 2.0x, 5.0x.

## 4. Phase 1 Scope & Functional Requirements
- **Camera Initialization**:
  - Check `Manifest.permission.CAMERA`. If missing, present the minimalist StuDO Permission screen.
  - Bind CameraX `ProcessCameraProvider` to `LifecycleOwner`.
  - Preview view rendered via Compose `AndroidView(factory = { PreviewView(it) })`.
- **Lens Selection**:
  - Support `CameraSelector.LENS_FACING_BACK` and `CameraSelector.LENS_FACING_FRONT`.
  - Graceful fallback if a lens is physically absent on the target device.
- **Capture Pipeline**:
  - `ImageCapture.Builder` with `CAPTURE_MODE_MINIMIZE_LATENCY`.
  - Save targets: `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`.
  - Target directory: `Pictures/StuDO`.
  - Manage `IS_PENDING = 1` during disk write and `0` on completion.
  - Support portrait and landscape orientation tags.
- **Hardware Controls**:
  - Flash modes: `FLASH_MODE_OFF`, `FLASH_MODE_AUTO`, `FLASH_MODE_ON`, and Torch toggle.
  - Zoom range inspection via `CameraInfo.zoomState`.
  - Focus metering using `SurfaceOrientedMeteringPointFactory` and `FocusMeteringAction`.
- **Media Feedback**:
  - Tactile haptic click via `HapticFeedbackType.LongPress` / `LocalHapticFeedback`.
  - Viewfinder shutter flash animation (subtle dip opacity).
  - Bottom-left thumbnail updating instantly with Coil from the freshly captured URI.
  - Click thumbnail to open quick full-screen preview lightbox with dismiss & share intent.
