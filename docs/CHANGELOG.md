# StuDO Changelog

All notable changes to the StuDO Camera project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [0.2.0-alpha] - 2026-10-30
### Added
- **Aspect Ratio Selection**: Real-time viewfinder and capture ratio selection (`4:3`, `16:9`, `1:1`, and `FULL`) with a dedicated top bar chip and authentic 1:1 square letterbox framing masks.
- **Video Recording Option**:
  - Seamless mode switching between `PHOTO` and `VIDEO`.
  - CameraX `VideoCapture` & `Recorder` integration saving MP4 videos to `Movies/StuDO` via Android MediaStore.
  - Adaptive shutter button morphing between photo capture, video record trigger, and active recording stop square.
  - Real-time pulsing recording indicator with `MM:SS` duration timer.
  - Audio recording permission handling (`RECORD_AUDIO`).
- **Photo Preview Delete & Details**:
  - Delete option with confirmation dialog, removing media directly from MediaStore and updating the recent capture thumbnail.
  - Comprehensive "File Details" panel displaying filename, capture date & time, resolution & megapixels, file size, storage directory, and MIME type.

---

## [0.1.0-alpha] - 2026-10-30
### Added
- **Project Structure**: Native Android Kotlin + Jetpack Compose architecture with CameraX v1.5.0.
- **Design System**: AMOLED dark palette (`#000000` / `#121214`), titanium silver accents, custom adaptive aperture launcher icon.
- **Permission Flow**: Minimalist in-app camera permission screen with clear rationale and recovery flow.
- **Viewfinder**: Full-screen CameraX live preview with aspect ratio preservation and lifecycle-aware provider management.
- **Lens Control**: Front and rear camera toggle with hardware availability detection.
- **Photo Capture**: Low-latency `ImageCapture` pipeline saving JPEG photographs to `Pictures/StuDO` via Android `MediaStore`.
- **Hardware Controls**:
  - Flash modes (Auto, On, Off, and Torch toggle) with flash unit capability detection.
  - Multi-tiered zoom (pinch gesture, smooth zoom slider, and 0.5x / 1x / 2x / max quick chips).
  - Tap-to-focus and auto-exposure metering with animated focus reticle.
- **Feedback & Gallery**:
  - Visual shutter dip animation and haptic tactile feedback.
  - In-app thumbnail preview of the most recently captured photo with lightbox dialog.
- **Documentation**: Complete project documentation suite (Project Spec, Architecture, Dev Log, Changelog, Known Issues).
