# StuDO Camera — Known Issues & Hardware Notes

## 1. Testing Environment: In-Browser Streaming Emulator vs. Physical Hardware

### A. Google AI Studio Streaming Emulator Behavior
- **Default Emulated Camera Sensor**: In Google AI Studio's in-browser streaming Android emulator (and standard Android AVDs without host webcam passthrough), the Android Camera HAL uses the built-in emulated camera driver.
- **Why Moving Coloured Blocks Appear**: The Android emulator's emulated camera driver generates a synthetic moving checkerboard / colored grid test pattern with bouncing blocks. **This is normal and expected behavior for the virtual camera driver**, confirming that the CameraX preview pipeline is actively receiving and rendering valid camera frames from the HAL.
- **Stream Verification**: StuDO attaches a lifecycle observer to `PreviewView.previewStreamState`. When the emulator camera starts, this transitions to `STREAMING`, verifying active frame delivery.
- **Photo & Video Persistence**: Tapping the shutter button takes photos to `Pictures/StuDO` via Android `MediaStore`, and recording saves MP4 videos to `Movies/StuDO`. Both photos and videos appear in the bottom-left thumbnail and open in the full-screen media gallery.

### B. Physical Android Hardware Differences
The following physical device behaviors cannot be verified inside the browser emulator and remain pending on-device validation:
- **Optical Sensors & Real-World Scene Capture**: Multi-lens switching (wide, ultra-wide, telephoto) will capture real physical photons and optical autofocus instead of the synthetic emulator pattern.
- **Hardware Flash / Torch LED**: Emulators report `hasFlashUnit() == false`, so StuDO displays "Flash N/A". On a physical device with an LED flash unit, flash and continuous torch modes activate the real hardware LED.
- **Optical & Sensor Zoom**: Physical devices report true sensor zoom ranges (e.g. 0.5x to 10x or 30x). Emulators typically cap at 1.0x–4.0x.
- **Physical Tap-to-Focus Actuators**: On physical hardware, `FocusMeteringAction` physically moves the camera lens voice coil motor (VCM). On emulators, metering points are accepted and the UI reticle animates, but no physical focus shift occurs.

---

## 2. Resolved Features & Enhancements

### Resolved: Aspect Ratio Alignment (Live Preview & Capture)
- **Problem**: Live viewfinder previously rendered unconstrained full-screen with `ScaleType.FILL_CENTER`, causing 4:3 and 16:9 frames to be center-cropped to the phone's 20:9 dimensions. Captures were uncropped while the live view was cropped. Video mode had no ratio selection.
- **Fix**: Viewfinder container now applies strict aspect ratio framing matching `CaptureAspectRatio` (4:3, 16:9, 1:1, FULL) for both live preview and output media. 1:1 photos are center-cropped on save to match the live framing square. Video recording binds target aspect ratio to `Recorder.Builder`.

### Resolved: Live Zoom During Video Capture
- **Problem**: Zoom chips and slider were hidden when video recording began.
- **Fix**: Removed recording-state visibility restrictions on zoom controls. Zoom chips (0.5x, 1x, 2x, 5x) and the fine-grain zoom slider remain visible and responsive throughout video recording.

### Resolved: Video Playback & Complete Media Gallery
- **Problem**: Captured videos could not be played in the preview dialog, and only the single most recent item was accessible.
- **Fix**: Rebuilt the preview into a complete lightbox gallery querying all photos and videos from `Pictures/StuDO` and `Movies/StuDO`. Embedded native `VideoView` with play/pause/replay controls, navigation chevrons, a bottom filmstrip carousel, complete file metadata details, and item deletion.

### Resolved: Encoder Profiles Hardening in Emulators & Constrained Devices
- **Problem**: In video mode, querying `Quality.HIGHEST` caused CameraX to check UHD/FHD profiles. On emulators and devices lacking 4K/1080p profiles, CameraX logged `E/CapabilitiesByQuality: No supported EncoderProfiles`.
- **Fix**: Replaced blind `Quality.HIGHEST` selection with dynamic hardware capability inspection using `Recorder.getVideoCapabilities(cameraInfo).getSupportedQualities(DynamicRange.SDR)`. If profiles are missing or binding fails, the viewfinder gracefully falls back to preview mode, avoiding crashes or broken video states.

