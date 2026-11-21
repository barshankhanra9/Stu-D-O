# StuDO Camera

> *See the world differently.*

StuDO (pronounced *Studio*) is a professional-quality, minimalist native Android camera application crafted with Kotlin, Jetpack Compose, and CameraX.

StuDO is designed for photography purists: an AMOLED-friendly, distraction-free viewfinder interface coupled with responsive camera controls, offline privacy by default (zero tracking, zero account required), and seamless saving to the Android device media store.

---

## Product Vision & Principles

1. **Viewfinder First**: The live preview receives maximum visual priority with edge-to-edge immersion and unobstructed framing.
2. **Minimalist & Restrained**: Deep black (`#000000`) and charcoal surfaces (`#121214`), soft white typography (`#F4F4F5`), and subtle brushed silver accents (`#E4E4E7`). No garish buttons or clutter.
3. **Genuine Native Performance**: Built directly on modern Android CameraX with Camera2 interoperability, ensuring real hardware sensor control, zero fake simulation, and correct camera lifecycle management.
4. **Privacy & Offline Reliability**: Completely local operation. All captures are saved directly to the device's public `Pictures/StuDO` gallery collection via Android `MediaStore`.

---

## Roadmap & Development Stages

- [x] **PHASE 1: Core Foundation & Camera Experience** *(Current)*
  - Modern AMOLED StuDO design system (theme, typography, custom adaptive aperture icon).
  - Native Android camera runtime permission handling with graceful rationale and recovery.
  - Full-screen CameraX live preview with smooth lifecycle binding.
  - Front and rear lens switching with smooth transitions.
  - Photo capture pipeline saving full-resolution JPEG photos directly to `MediaStore.Images`.
  - Flash modes (Auto, On, Off, Torch/Flashlight) with hardware capability detection.
  - Multi-gesture zoom controls: smooth pinch-to-zoom, linear zoom slider, and 1x / 2x / max quick toggles.
  - Tap-to-focus and metering with animated focus reticle.
  - Shutter feedback (visual dip + tactile haptics) and recent capture thumbnail with full-screen preview.
- [ ] **PHASE 2: Live Filters & Composition** (Grid overlays, golden ratio, virtual horizon level, timer, exposure compensation).
- [ ] **PHASE 3: Professional Camera2 Controls** (Manual ISO, shutter speed, manual focus, white balance, RAW/DNG capture, live histogram).
- [ ] **PHASE 4: Video Recording** (High-bitrate recording, framerate/resolution selection, stabilization).
- [ ] **PHASE 5: Gallery & EXIF Inspector** (Integrated lightbox, EXIF metadata viewer, lossless adjustments).
- [ ] **PHASE 6: Creative & On-Device Processing** (Scene intelligence, local enhancement).

---

## Getting Started

### Requirements
- Android SDK 24+ (Android 7.0 Nougat or higher)
- Target SDK 36 (Android 16)
- Device with camera hardware (or Android Studio emulator with Camera configured to Virtual Scene / Webcam)

### Building
```bash
gradle assembleDebug
```
For local testing:
```bash
gradle :app:testDebugUnitTest
```
