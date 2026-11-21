# StuDO Camera — Development Log

## Entry: Phase 1 — Project Genesis & Core Foundation
- **Date**: 2026-10-30
- **Author**: Lead Android Engineer

### Goals & Outcomes:
1. **Identity & Metadata**:
   - Initialized `metadata.json` with App Name "StuDO" and description.
   - Updated `app_name` in `res/values/strings.xml` and `rootProject.name` in `settings.gradle.kts`.
   - Set unique `applicationId = "com.aistudio.studo.camera"`.
   - Designed custom adaptive launcher icon featuring a brushed silver aperture blade mechanism on `#0D0D0E` dark carbon, generated density-specific PNGs and round variants.
2. **Dependencies & Permissions**:
   - Integrated CameraX `camera2`, `camera-lifecycle`, `camera-view`, `camera-core` (v1.5.0).
   - Added `coil-compose` for lightweight gallery thumbnail caching.
   - Declared `android.permission.CAMERA` with optional camera hardware feature requirements in `AndroidManifest.xml`.
3. **StuDO Design System**:
   - Established true AMOLED black (`#000000`) viewfinder background and obsidian control surfaces (`#121214`).
   - Clean typographic scale with silver/white high-legibility accents.
4. **CameraX Engine**:
   - Created `CameraViewfinder` embedding `PreviewView` with pinch-to-zoom and tap-to-focus metering overlay.
   - Implemented `ImageCapture` pipeline outputting directly to device gallery under `Pictures/StuDO`.
   - Added front/rear lens switching with capability checks.
   - Implemented Flash mode controls (Auto / On / Off / Torch).
   - Added recent photo thumbnail preview lightbox with share intent.

### Verification & Testing:
- Build: Verified via `compile_applet` (Success).
- Automated Tests: Executed `gradle :app:testDebugUnitTest` (BUILD SUCCESSFUL, all unit and Robolectric tests passed).
- Integration: Verified CameraX lifecycle binding, scoped storage directory formatting (`Pictures/StuDO`), lens toggle state handling, animated focus bracket reticle, and zoom clamp logic.

---

## Entry: CameraX Preview Pipeline Investigation & Surface Provider Fix
- **Date**: 2026-10-30
- **Author**: Lead Android Engineer

### Problem Analysis:
- User reported that on launch, the camera screen opens and permission is granted, but the preview displays colored blocks instead of a real-world camera image.
- Investigation confirmed that in Google AI Studio's in-browser streaming Android emulator, the Android Camera HAL uses an emulated camera sensor that outputs a moving checkerboard / color grid pattern by design.
- Further inspection revealed an asynchronous lifecycle race condition in `CameraViewfinder.kt`: `DisposableEffect` was initialized before `AndroidView.factory` assigned `previewViewRef`, creating a risk where `preview.setSurfaceProvider()` was skipped or attached asynchronously.

### Remediation & Fixes:
1. **Deterministic Surface Provider Attachment**:
   - Rewrote `CameraViewfinder.kt` so the instantiated `PreviewView` is captured and bound as a Compose state key in `DisposableEffect(previewViewInstance, uiState.lensFacing, lifecycleOwner)`.
   - Ensures `preview.setSurfaceProvider(pv.surfaceProvider)` is called synchronously with a verified, non-null surface provider.
2. **Performance Mode (SurfaceView)**:
   - Configured `PreviewView.ImplementationMode.PERFORMANCE` for direct hardware buffer composition, avoiding TextureView OpenGL conversion overhead.
3. **Live Stream Monitoring**:
   - Observed `PreviewView.previewStreamState` to detect transition to `StreamState.STREAMING`, actively confirming that valid camera frames are being consumed by the viewfinder.
4. **Media Persistence Hardening**:
   - Updated `MediaCaptureManager.capturePhoto` with fallback resolution and safe `IS_PENDING` clearing, guaranteeing valid JPEG files are written to `Pictures/StuDO` and correctly resolved for gallery thumbnails and full-screen lightbox previews.
5. **No Unsupported Camera2 Forcing**:
   - Avoided hardcoded vendor-specific Camera2 tags, ensuring complete stability across both virtualized emulators and physical hardware.

---

## Entry: Video Recording, Live Zoom, Ratio Alignment & Full Gallery Lightbox
- **Date**: 2026-10-30
- **Author**: Lead Android Engineer

### Problem Analysis:
1. **Aspect Ratio Discrepancy**:
   - Live viewfinder was previously rendering unconstrained full-screen with `ScaleType.FILL_CENTER`, cropping 4:3 and 16:9 aspect ratios to the phone screen's 20:9 dimensions. Consequently, live preview was cropped while captured photos were uncropped.
   - Video mode lacked aspect ratio selection entirely.
2. **Zoom Bar Hiding During Video**:
   - In `CameraBottomBar.kt`, `!uiState.isRecordingVideo` checks hid the zoom chips and slider when recording started, preventing zoom adjustments during active capture.
3. **Video Playback & Gallery Limitation**:
   - After video capture, the preview was broken/blank because Coil `AsyncImage` cannot play MP4 videos.
   - The preview dialog only showed the single most recent item rather than all captured photos and videos.

### Remediation & Architecture:
1. **Accurate Aspect Ratio Framing**:
   - Constrained `CameraViewfinder` within container-level aspect ratios: 4:3 (`3:4`), 16:9 (`9:16`), 1:1 (`1:1`), and FULL.
   - Applied `cropToSquare` center-crop logic in `MediaCaptureManager` when capturing in 1:1 ratio.
   - Enabled aspect ratio cycling in `CameraTopBar` for both `PHOTO` and `VIDEO` modes, with `Recorder.Builder.setAspectRatio(targetRatio)`.
2. **Persistent Live Zoom During Video Recording**:
   - Removed `!uiState.isRecordingVideo` gating from quick zoom chips (0.5x, 1x, 2x, 5x) and fine-grain slider, allowing real-time focal adjustments while recording video.
3. **Native Video Playback & Complete Media Gallery**:
   - Created `PhotoPreviewDialog` supporting all media items (`List<MediaItem>`) queried from both `Pictures/StuDO` and `Movies/StuDO`.
   - Embedded native `VideoView` with play/pause/replay overlay and completion listeners.
   - Added horizontal navigation chevrons and a bottom filmstrip carousel to browse through all captured items.
   - Added full "FILE DETAILS" slide-up panel and item deletion with automatic state synchronization.

### Automated Tests:
- Executed `gradle :app:testDebugUnitTest`: BUILD SUCCESSFUL (all unit and Robolectric tests passing).
- Verified clean compilation with `compile_applet`.

---

## Entry: CameraX Encoder Profiles Hardening & STU•D•O Brand Update
- **Date**: 2026-10-30
- **Author**: Lead Android Engineer

### Problem Analysis:
1. **CapabilitiesByQuality: No supported EncoderProfiles**:
   - In video mode, CameraX was previously configured with `Quality.HIGHEST` in `QualitySelector`.
   - When CameraX constructs `CapabilitiesByQuality`, it systematically inspects UHD, FHD, HD, and SD. In emulator environments and devices lacking 4K or 1080p video encoder profiles, CameraX prints `E/CapabilitiesByQuality: No supported EncoderProfiles` for each missing profile.
   - If a camera or virtual sensor has no video encoder profiles at all, binding `VideoCapture` could fail or destabilize the viewfinder.
2. **Brand Typography**:
   - Header text was previously formatted as `STU•DO` instead of `STU•D•O`.

### Remediation & Architecture:
1. **Dynamic Video Capabilities Inspection**:
   - Updated `CameraViewfinder.kt` to inspect `cameraInfo` before creating `Recorder`.
   - Utilizes `Recorder.getVideoCapabilities(cameraInfo).getSupportedQualities(DynamicRange.SDR)` to discover the hardware's supported video qualities.
   - Configures `QualitySelector.fromOrderedList(supportedQualities)` restricted to available qualities, avoiding queries for unsupported profiles.
   - Added resilient `try-catch` fallback in `CameraViewfinder.kt`: if `VideoCapture` binding fails due to hardware profile absence, the app automatically falls back to binding `Preview`, ensuring the camera viewfinder never crashes or blacks out.
2. **Recording Safety & Graceful Error Messaging**:
   - In `CameraViewModel.kt`, added safety checks for null `activeVideoCapture` and wrapped `startVideoRecording` with a robust `try-catch`.
   - Added explicit user feedback for emulator or hardware encoder limitations (`VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED`, `ERROR_NO_VALID_DATA`, `ERROR_SOURCE_INACTIVE`).
3. **Brand Typography**:
   - Updated `CameraTopBar.kt` brand title to `STU•D•O`.
4. **Automated Testing**:
   - Added unit test `camera view model video recording safety without bound video capture shows error message` in `ExampleRobolectricTest.kt`.
   - Verified all unit and Robolectric tests pass (`gradle :app:testDebugUnitTest`).
   - Verified compilation (`compile_applet`).

---

## Entry: Liquid Glass Material 3 Redesign, Aesthetic Opening Animation & API 31+ EncoderProfiles
- **Date**: 2026-10-30
- **Author**: Lead Android Engineer

### Objectives & Deliverables:
1. **CapabilitiesByQuality Root Cause Hardening**:
   - Identified that on Android 12+ (API 31+), `CamcorderProfile.hasProfile(cameraId, quality)` is legacy and returns true on emulator dummy profiles, but modern CameraX queries `CamcorderProfile.getAll(cameraId, quality)`.
   - Updated `isVideoRecordingSupported()` in `CameraViewfinder.kt` to inspect `CamcorderProfile.getAll(cameraId, q)` on API 31+, verifying non-null `EncoderProfiles` and non-empty `videoProfiles`.
   - On emulators or cameras without encoder profiles, `VideoCapture` binding is safely skipped, completely eliminating the 5 `E/CapabilitiesByQuality: No supported EncoderProfiles` errors while keeping the viewfinder streaming smoothly.
2. **Cinematic Opening Intro Animation**:
   - Built `StuDOSplashAnimation.kt` displaying the STU•D•O aperture logo with a smooth organic spring zoom-in, mechanical aperture ring rotation, shutter snap flash simulation, and tracked `STU•D•O` typography.
   - Smoothly fades out and transitions into the live camera viewfinder after ~1.4 seconds (or instantly on user tap).
3. **Liquid Glass Material 3 Redesign**:
   - **CameraTopBar**: Floating frosted liquid glass capsule with gradient background, specular top highlight, and translucent control pills for aspect ratio, flash mode, lens flip, and brand header.
   - **CameraBottomBar**: Glassmorphic floating control dock with frosted quick zoom chips, liquid glass mode selector pill (PHOTO / VIDEO), liquid-glowing shutter button ring, and frosted squircle gallery thumbnail and lens switch buttons.
   - **PhotoPreviewDialog**: Liquid glass chevrons and frosted action bar buttons (Close, Info, Share, Delete).
4. **Verification**:
   - Verified with `compile_applet`: Clean build.
   - Verified with `gradle :app:testDebugUnitTest`: 33 actionable tasks executed, all passed.
