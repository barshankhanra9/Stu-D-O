package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.model.ActiveCameraPanel
import com.example.model.CameraMode
import com.example.model.CaptureAspectRatio
import com.example.model.ProControlTab
import com.example.ui.components.CameraBottomBar
import com.example.ui.components.CameraDiagnosticsDialog
import com.example.ui.components.CameraFilterSelector
import com.example.ui.components.CameraQuickSettingsDrawer
import com.example.ui.components.CameraSettingsDialog
import com.example.ui.components.CameraTopBar
import com.example.ui.components.CameraViewfinder
import com.example.ui.components.CountdownTimerOverlay
import com.example.ui.components.FloatingLiveHistogramOverlay
import com.example.ui.components.NightControlsBar
import com.example.ui.components.PermissionScreen
import com.example.ui.components.PhotoPreviewDialog
import com.example.ui.components.PortraitBlurEditorDialog
import com.example.ui.components.PortraitControlsBar
import com.example.ui.components.ProControlsBar
import com.example.ui.components.RecycleBinDialog
import com.example.ui.components.VideoSettingsPanel
import com.example.ui.components.WatermarkStudioScreen
import com.example.ui.components.liquidGlassSurface
import com.example.ui.theme.StuDOBlack
import com.example.ui.theme.StuDOWhite
import com.example.viewmodel.CameraViewModel

@Composable
private fun SettingsOverlayHost(
    viewModel: CameraViewModel,
    onToggleVideoStabilization: () -> Unit,
    onToggleAudio: () -> Unit
) {
    val isSettingsOpen by viewModel.isSettingsOpenState.collectAsStateWithLifecycle()
    if (!isSettingsOpen) return

    val settingsState by viewModel.settingsUiState.collectAsStateWithLifecycle()
    val onDismissSettings = remember(viewModel) { { viewModel.closeSettings() } }
    val onToggleHistogram = remember(viewModel) { { viewModel.toggleHistogram() } }
    val onToggleLevel = remember(viewModel) { { viewModel.toggleLevelIndicator() } }
    val onSelectGrid = remember(viewModel) { { grid: com.example.model.GridOption -> viewModel.setGrid(grid) } }
    val onToggleShutterSound = remember(viewModel) { { viewModel.toggleShutterSound() } }
    val onSelectTimer = remember(viewModel) { { timer: com.example.model.CaptureTimer -> viewModel.setTimer(timer) } }
    val onSetCustomTimerSeconds = remember(viewModel) { { seconds: Int -> viewModel.setCustomTimerSeconds(seconds) } }
    val onSelectAspectRatioSetting = remember(viewModel) { { ratio: CaptureAspectRatio -> viewModel.setAspectRatio(ratio) } }
    val onSelectPhotoResolution = remember(viewModel) { { res: com.example.model.PhotoResolution -> viewModel.setPhotoResolution(res) } }
    val onSelectCapturePriority = remember(viewModel) { { priority: com.example.model.CapturePriority -> viewModel.setCapturePriority(priority) } }
    val onSelectRawCaptureMode = remember(viewModel) { { mode: com.example.model.RawCaptureMode -> viewModel.setRawCaptureMode(mode) } }
    val onToggleHdr = remember(viewModel) { { viewModel.toggleHdr() } }
    val onToggleWatermark = remember(viewModel) { { viewModel.toggleWatermark() } }
    val onOpenWatermarkStudio = remember(viewModel) { { viewModel.openWatermarkStudio() } }
    val onSetPostCaptureWatermarkEditing = remember(viewModel) {
        { enabled: Boolean, limit: com.example.model.WatermarkCacheLimit? ->
            viewModel.setPostCaptureWatermarkEditing(enabled, limit)
        }
    }
    val onSetWatermarkCacheLimit = remember(viewModel) {
        { limit: com.example.model.WatermarkCacheLimit ->
            viewModel.setWatermarkCacheLimit(limit)
        }
    }
    val onClearAllPrivateOriginals = remember(viewModel) { { viewModel.clearAllPrivateOriginals() } }
    val onSelectVideoQuality = remember(viewModel) { { q: com.example.model.VideoQualityOption -> viewModel.setVideoQuality(q) } }
    val onSelectVideoCodec = remember(viewModel) { { codec: com.example.model.VideoCodecOption -> viewModel.setVideoCodec(codec) } }
    val onSelectVideoFps = remember(viewModel) { { fps: com.example.model.VideoFpsOption -> viewModel.setVideoFps(fps) } }
    val onToggleSelfieMirror = remember(viewModel) { { viewModel.toggleSelfieMirror() } }
    val onToggleSaveLocation = remember(viewModel) { { enabled: Boolean -> viewModel.toggleSaveLocation(enabled) } }
    val onToggleHapticFeedback = remember(viewModel) { { viewModel.toggleHapticFeedback() } }
    val onToggleKeepScreenAwake = remember(viewModel) { { viewModel.toggleKeepScreenAwake() } }
    val onSelectVolumeKeyAction = remember(viewModel) { { action: com.example.model.VolumeKeyAction -> viewModel.setVolumeKeyAction(action) } }
    val onSelectOemEnhancementMode = remember(viewModel) { { mode: com.example.model.OemEnhancementMode -> viewModel.setOemEnhancementMode(mode) } }
    val onOpenRecycleBin = remember(viewModel) { { viewModel.openRecycleBin() } }
    val onVersionTapped = remember(viewModel) { { viewModel.onVersionTapped() } }

    CameraSettingsDialog(
        settingsState = settingsState,
        onDismiss = onDismissSettings,
        onToggleHistogram = onToggleHistogram,
        onToggleLevel = onToggleLevel,
        onSelectGrid = onSelectGrid,
        onToggleShutterSound = onToggleShutterSound,
        onSelectTimer = onSelectTimer,
        onSetCustomTimerSeconds = onSetCustomTimerSeconds,
        onSelectAspectRatio = onSelectAspectRatioSetting,
        onSelectPhotoResolution = onSelectPhotoResolution,
        onSelectCapturePriority = onSelectCapturePriority,
        onSelectRawCaptureMode = onSelectRawCaptureMode,
        onToggleHdr = onToggleHdr,
        onToggleWatermark = onToggleWatermark,
        onOpenWatermarkStudio = onOpenWatermarkStudio,
        onSetPostCaptureWatermarkEditing = onSetPostCaptureWatermarkEditing,
        onSetWatermarkCacheLimit = onSetWatermarkCacheLimit,
        onClearAllPrivateOriginals = onClearAllPrivateOriginals,
        onSelectVideoQuality = onSelectVideoQuality,
        onSelectVideoCodec = onSelectVideoCodec,
        onSelectVideoFps = onSelectVideoFps,
        onToggleVideoStabilization = onToggleVideoStabilization,
        onToggleAudio = onToggleAudio,
        onToggleSelfieMirror = onToggleSelfieMirror,
        onToggleSaveLocation = onToggleSaveLocation,
        onToggleHapticFeedback = onToggleHapticFeedback,
        onToggleKeepScreenAwake = onToggleKeepScreenAwake,
        onSelectVolumeKeyAction = onSelectVolumeKeyAction,
        onSelectOemEnhancementMode = onSelectOemEnhancementMode,
        onOpenRecycleBin = onOpenRecycleBin,
        onVersionTapped = onVersionTapped
    )
}

@Composable
private fun WatermarkStudioOverlayHost(
    viewModel: CameraViewModel
) {
    val isWatermarkStudioOpen by viewModel.isWatermarkStudioOpenState.collectAsStateWithLifecycle()
    if (!isWatermarkStudioOpen) return

    val initialConfig by viewModel.watermarkStudioConfigState.collectAsStateWithLifecycle()
    val savedPresets by viewModel.watermarkStudioPresetsState.collectAsStateWithLifecycle()
    val activeFocalLength by viewModel.activeFocalLengthTextState.collectAsStateWithLifecycle()
    val postCaptureTarget by viewModel.postCaptureWatermarkTargetState.collectAsStateWithLifecycle()
    val editorWatermarkTarget by viewModel.editorWatermarkTargetState.collectAsStateWithLifecycle()
    val isPostCaptureSaving by viewModel.isPostCaptureSavingState.collectAsStateWithLifecycle()
    val onSaveConfig = remember(viewModel) { { cfg: com.example.model.WatermarkConfig -> viewModel.saveWatermarkConfig(cfg) } }
    val onSaveEditorWatermark = remember(viewModel) { { cfg: com.example.model.WatermarkConfig -> viewModel.saveWatermarkFromEditorSession(cfg) } }
    val onSavePreset = remember(viewModel) { { name: String, cfg: com.example.model.WatermarkConfig -> viewModel.saveWatermarkPreset(name, cfg) } }
    val onDeletePreset = remember(viewModel) { { id: String -> viewModel.deleteWatermarkPreset(id) } }
    val onDismiss = remember(viewModel) { { viewModel.closeWatermarkStudio() } }
    val onPostCaptureUpdateExisting = remember(viewModel) {
        { cfg: com.example.model.WatermarkConfig -> viewModel.savePostCaptureUpdateExisting(cfg) }
    }
    val onPostCaptureSaveNewCopy = remember(viewModel) {
        { cfg: com.example.model.WatermarkConfig -> viewModel.savePostCaptureNewCopy(cfg) }
    }
    val onPostCaptureSaveUnwatermarkedCopy = remember(viewModel) {
        { viewModel.savePostCaptureUnwatermarkedCopy() }
    }

    WatermarkStudioScreen(
        initialConfig = initialConfig,
        savedPresets = savedPresets,
        onSaveConfig = onSaveConfig,
        onSavePreset = onSavePreset,
        onDeletePreset = onDeletePreset,
        onDismiss = onDismiss,
        activeFocalLength = activeFocalLength,
        postCaptureTarget = postCaptureTarget,
        isPostCaptureSaving = isPostCaptureSaving,
        onPostCaptureUpdateExisting = onPostCaptureUpdateExisting,
        onPostCaptureSaveNewCopy = onPostCaptureSaveNewCopy,
        onPostCaptureSaveUnwatermarkedCopy = onPostCaptureSaveUnwatermarkedCopy,
        editorWatermarkTarget = editorWatermarkTarget,
        onSaveEditorWatermark = onSaveEditorWatermark
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val mediaCaptureManager = viewModel.mediaCaptureManager
    val snackbarHostState = remember { SnackbarHostState() }
    val initialCameraPermission = remember(context) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
    var hasSyncedInitialPermission by remember { mutableStateOf(false) }
    var savedBrightnessBeforeScreenFlash by remember { mutableStateOf<Float?>(null) }

    fun restoreScreenFlashBrightness() {
        val prevBrightness = savedBrightnessBeforeScreenFlash ?: return
        savedBrightnessBeforeScreenFlash = null
        val window = activity?.window ?: return
        val lp = window.attributes
        if (lp.screenBrightness != prevBrightness) {
            lp.screenBrightness = prevBrightness
            window.attributes = lp
        }
    }

    LaunchedEffect(uiState.isScreenFlashOverlayVisible, activity) {
        if (uiState.isScreenFlashOverlayVisible) {
            val window = activity?.window
            if (window != null) {
                val lp = window.attributes
                if (savedBrightnessBeforeScreenFlash == null) {
                    savedBrightnessBeforeScreenFlash = lp.screenBrightness
                }
                if (lp.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL) {
                    lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
                    window.attributes = lp
                }
            }
            withFrameNanos { }
            kotlinx.coroutines.delay(65L)
            viewModel.onScreenFlashSurfaceReady()
        } else {
            restoreScreenFlashBrightness()
        }
    }

    DisposableEffect(lifecycleOwner, activity, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val hasCamera = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                if (hasCamera != viewModel.uiState.value.hasPermission) {
                    viewModel.onPermissionResult(isGranted = hasCamera)
                    if (hasCamera) {
                        viewModel.loadGallery(mediaCaptureManager)
                    }
                }
            } else if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                restoreScreenFlashBrightness()
                viewModel.dismissScreenFlash()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            restoreScreenFlashBrightness()
            viewModel.dismissScreenFlash()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.onPermissionResult(isGranted = isGranted)
        if (isGranted) {
            viewModel.loadGallery(mediaCaptureManager)
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.setAudioEnabled(isGranted)
    }

    val galleryPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.openPhotoEditor(uri, isImported = true)
        }
    }

    val filesDocPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Throwable) {}
            viewModel.openPhotoEditor(uri, isImported = true)
        }
    }

    // Initial permission check on launch
    LaunchedEffect(Unit) {
        val currentPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val hasAudioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasAudioPermission && uiState.isAudioEnabled) {
            viewModel.setAudioEnabled(false)
        }

        val hasLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.syncLocationPermission(hasLocationPermission)

        viewModel.onPermissionResult(isGranted = currentPermission)
        hasSyncedInitialPermission = true
        if (currentPermission) {
            viewModel.loadGallery(mediaCaptureManager)
        }
    }

    // Handle user status toast / messages
    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearUserMessage()
        }
    }

    val isGalleryActive = uiState.isGalleryOpen && uiState.galleryItems.isNotEmpty()

    // Hardware orientation sensor for electronic level indicator (only active when level indicator is visible and no full-screen dialogs are open)
    DisposableEffect(
        uiState.isLevelIndicatorEnabled,
        uiState.isSettingsOpen,
        uiState.isPhotoEditorOpen,
        uiState.isPortraitEditorOpen,
        uiState.isWatermarkStudioOpen,
        uiState.isRecycleBinOpen,
        isGalleryActive
    ) {
        if (!uiState.isLevelIndicatorEnabled ||
            uiState.isSettingsOpen ||
            uiState.isPhotoEditorOpen ||
            uiState.isPortraitEditorOpen ||
            uiState.isWatermarkStudioOpen ||
            uiState.isRecycleBinOpen ||
            isGalleryActive
        ) {
            return@DisposableEffect onDispose {}
        }

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val listener = object : SensorEventListener {
            private val rotationMatrix = FloatArray(9)
            private val orientationAngles = FloatArray(3)
            private var lastDispatchTime = 0L
            private var lastRoll = 0f
            private var lastPitch = 0f

            override fun onSensorChanged(event: SensorEvent?) {
                event ?: return
                val now = System.currentTimeMillis()
                if (now - lastDispatchTime < 100L) return // Cap sensor processing at 10Hz for buttery smooth UI without recomposition lag

                val roll: Float
                val pitch: Float
                if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    pitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
                    roll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
                } else {
                    val ax = event.values[0]
                    val ay = event.values[1]
                    val az = event.values[2]
                    roll = Math.toDegrees(kotlin.math.atan2(-ax.toDouble(), ay.toDouble())).toFloat()
                    pitch = Math.toDegrees(
                        kotlin.math.atan2(
                            az.toDouble(),
                            kotlin.math.sqrt((ax * ax + ay * ay).toDouble())
                        )
                    ).toFloat()
                }

                if (kotlin.math.abs(roll - lastRoll) >= 0.5f || kotlin.math.abs(pitch - lastPitch) >= 0.5f) {
                    lastDispatchTime = now
                    lastRoll = roll
                    lastPitch = pitch
                    viewModel.onOrientationChanged(roll, pitch)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager?.registerListener(listener, rotationVectorSensor, SensorManager.SENSOR_DELAY_NORMAL)

        onDispose {
            sensorManager?.unregisterListener(listener)
        }
    }

    val hasCameraPermission = if (hasSyncedInitialPermission) uiState.hasPermission else initialCameraPermission

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(StuDOBlack)
    ) {
        if (!hasCameraPermission) {
            PermissionScreen(
                permanentlyDenied = uiState.permissionPermanentlyDenied,
                onRequestPermission = {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            )
        } else {
            // Stable interaction callbacks to prevent unnecessary child recompositions
            val onCameraBound = remember(viewModel) {
                { cam: androidx.camera.core.Camera, prov: androidx.camera.lifecycle.ProcessCameraProvider, img: androidx.camera.core.ImageCapture?, vid: androidx.camera.video.VideoCapture<*>? ->
                    viewModel.onCameraBound(cam, prov, img, vid)
                }
            }
            val onStreamStateChanged = remember(viewModel) { { isStreaming: Boolean -> viewModel.onStreamStateChanged(isStreaming) } }
            val onPinchZoom = remember(viewModel) { { delta: Float -> viewModel.onPinchZoom(delta) } }
            val onTapToFocus = remember(viewModel) { { factory: androidx.camera.core.MeteringPointFactory, x: Float, y: Float -> viewModel.triggerTapToFocus(factory, x, y) } }
            val onExposureChanged = remember(viewModel) { { ev: Int -> viewModel.setExposureIndex(ev) } }
            val onKeepFocusActive = remember(viewModel) { { viewModel.keepFocusRingActive() } }
            val onResolutionConfigured = remember(viewModel) { { size: android.util.Size -> viewModel.onResolutionConfigured(size) } }
            val onCameraError = remember(viewModel) { { msg: String, fatal: Boolean -> viewModel.onCameraHardwareError(msg, fatal) } }
            val onTargetRotationChanged = remember(viewModel) { { rot: Int -> viewModel.onTargetRotationChanged(rot) } }
            val onHistogramData = remember(viewModel) { { bins: List<Float> -> viewModel.updateHistogramBins(bins) } }
            val onHistogramSampled = remember(viewModel) { { data: com.example.model.HistogramData -> viewModel.updateHistogramData(data) } }
            val onActiveLensFocalLengthChanged = remember(viewModel) {
                { focalMm: Float?, physicalId: String? ->
                    viewModel.onActiveLensFocalLengthChanged(focalMm, physicalId)
                }
            }
            val onFullScreenAspectRatioChanged = remember(viewModel) {
                { ratio: Float -> viewModel.onFullScreenAspectRatioChanged(ratio) }
            }

            val onCycleFlash = remember(viewModel) { { viewModel.cycleFlashMode() } }
            val onOpenSettings = remember(viewModel) { { viewModel.toggleSettings() } }
            val onCycleAspectRatio = remember(viewModel) { { viewModel.cycleAspectRatio() } }
            val onCycleVideoAspectRatio = remember(viewModel) { { viewModel.cycleVideoAspectRatio() } }
            val onToggleQuickSettings = remember(viewModel) { { viewModel.toggleQuickSettings() } }
            val onToggleFilterSelector = remember(viewModel) { { viewModel.toggleFilterSelector() } }
            val onCycleRawMode = remember(viewModel) { { viewModel.cycleRawModeFromTopBar() } }
            val onCycleVideoQualityAndFps = remember(viewModel) { { viewModel.cycleVideoQualityAndFps() } }
            val onToggleVideoSettingsPanel = remember(viewModel) { { viewModel.toggleVideoSettingsPanel() } }
            val onDismissActivePanel = remember(viewModel) { { viewModel.dismissActivePanel() } }
            val onToggleAudio = remember(viewModel, context, audioPermissionLauncher) {
                {
                    if (!viewModel.uiState.value.isAudioEnabled) {
                        val hasAudio = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasAudio) {
                            viewModel.toggleAudioEnabled()
                        } else {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    } else {
                        viewModel.toggleAudioEnabled()
                    }
                }
            }
            val onToggleVideoStabilization = remember(viewModel) { { viewModel.toggleVideoStabilization() } }

            val onCaptureClick = remember(viewModel, mediaCaptureManager) { { viewModel.onShutterTrigger(mediaCaptureManager) } }
            val onModeSelect = remember(viewModel, context, audioPermissionLauncher) {
                { mode: CameraMode ->
                    if (mode == CameraMode.VIDEO && viewModel.uiState.value.isAudioEnabled &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    viewModel.setCameraMode(mode)
                }
            }
            val onToggleLens = remember(viewModel) { { viewModel.toggleLens() } }
            val onZoomChange = remember(viewModel) { { zoom: Float -> viewModel.setZoomRatio(zoom) } }
            val onThumbnailClick = remember(viewModel, mediaCaptureManager) { { uri: android.net.Uri -> viewModel.openGallery(mediaCaptureManager, uri) } }
            val onTogglePauseResume = remember(viewModel) { { viewModel.togglePauseResumeVideoRecording() } }

            val onLowLightChanged = remember(viewModel) {
                { isLowLight: Boolean -> viewModel.onLowLightConditionChanged(isLowLight) }
            }
            val onScreenFlashApplyRequested = remember(viewModel) {
                { expirationMs: Long, listener: androidx.camera.core.ImageCapture.ScreenFlashListener ->
                    viewModel.onScreenFlashApplyRequested(expirationMs, listener)
                }
            }
            val onScreenFlashClearRequested = remember(viewModel) {
                { viewModel.dismissScreenFlash() }
            }

            val onPortraitStatusUpdate = remember(viewModel) {
                { hasSubject: Boolean, subjectCount: Int, depthSource: com.example.model.PortraitDepthSource, qualityTier: com.example.model.PortraitQualityTier ->
                    viewModel.onPortraitPreviewUpdate(hasSubject, subjectCount, depthSource, qualityTier)
                }
            }
            val onNormalizedFocusPoint = remember(viewModel) {
                { nx: Float, ny: Float -> viewModel.setPortraitFocusPoint(nx, ny) }
            }
            val onPortraitOemExtensionChanged = remember(viewModel) {
                { active: Boolean -> viewModel.onPortraitExtensionBindingChanged(active) }
            }
            val onNightBindingChanged = remember(viewModel) {
                { isExtensionActive: Boolean, estimatedLatencyMs: Long? ->
                    viewModel.onNightBindingChanged(isExtensionActive, estimatedLatencyMs)
                }
            }
            val onExposureTelemetry = remember(viewModel) {
                { exposureNs: Long, iso: Int ->
                    viewModel.onExposureTelemetry(exposureNs, iso)
                }
            }
            val onProAssistFrame = remember(viewModel) {
                { frame: com.example.util.ProAssistOverlayFrame? ->
                    viewModel.updateProAssistFrame(frame)
                }
            }

            // Live Viewfinder (CameraX Preview + Gestures + Framing Grid + Level + Filters + Portrait Live Bokeh)
            CameraViewfinder(
                uiState = uiState,
                orientationState = viewModel.orientationState,
                proAssistFrameState = viewModel.proAssistFrame,
                onProAssistFrame = onProAssistFrame,
                onCameraBound = onCameraBound,
                onStreamStateChanged = onStreamStateChanged,
                onPinchZoom = onPinchZoom,
                onTapToFocus = onTapToFocus,
                onExposureChanged = onExposureChanged,
                onKeepFocusActive = onKeepFocusActive,
                onResolutionConfigured = onResolutionConfigured,
                onCameraError = onCameraError,
                onTargetRotationChanged = onTargetRotationChanged,
                onHistogramData = onHistogramData,
                onHistogramSampled = onHistogramSampled,
                onActiveLensFocalLengthChanged = onActiveLensFocalLengthChanged,
                onFullScreenAspectRatioChanged = onFullScreenAspectRatioChanged,
                onLowLightChanged = onLowLightChanged,
                onScreenFlashApplyRequested = onScreenFlashApplyRequested,
                onScreenFlashClearRequested = onScreenFlashClearRequested,
                onPortraitStatusUpdate = onPortraitStatusUpdate,
                onNormalizedFocusPoint = onNormalizedFocusPoint,
                portraitPipelineManager = viewModel.portraitPipelineManager,
                onPortraitOemExtensionChanged = onPortraitOemExtensionChanged,
                nightPipelineManager = viewModel.nightPipelineManager,
                onNightBindingChanged = onNightBindingChanged,
                onExposureTelemetry = onExposureTelemetry
            )

            val isFullScreenModalOpen = uiState.isSettingsOpen ||
                uiState.isWatermarkStudioOpen ||
                uiState.isPhotoEditorOpen ||
                uiState.isPortraitEditorOpen ||
                uiState.isRecycleBinOpen ||
                isGalleryActive

            // Skip drawing translucent camera HUD layers while full-screen modal screens cover the window
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        if (!isFullScreenModalOpen) {
                            drawContent()
                        }
                    }
            ) {
                // Floating Draggable Liquid Glass Live Histogram HUD Overlay (Luma & RGB + Clipping)
                if (uiState.isHistogramEnabled && !isFullScreenModalOpen) {
                    val onToggleHistogramMode = remember(viewModel) { { viewModel.toggleHistogramMode() } }
                    val onToggleHistogramCollapsed = remember(viewModel) { { viewModel.toggleHistogramCollapsed() } }
                    FloatingLiveHistogramOverlay(
                        exposureIndex = uiState.exposureIndex,
                        histogramDataState = viewModel.histogramData,
                        histogramMode = uiState.histogramMode,
                        isCollapsed = uiState.isHistogramCollapsed,
                        hapticsEnabled = uiState.isHapticFeedbackEnabled,
                        onToggleMode = onToggleHistogramMode,
                        onToggleCollapsed = onToggleHistogramCollapsed
                    )
                }

                // Outside-tap scrim: dismissing any open top drawer/panel or Pro slider without triggering tap-to-focus
                if (uiState.activeCameraPanel != ActiveCameraPanel.NONE ||
                    uiState.activeProTab != ProControlTab.NONE ||
                    uiState.activeProAssistPopover != com.example.model.ProAssistPopover.NONE
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures {
                                    onDismissActivePanel()
                                }
                            }
                            .testTag("camera_panel_dismiss_scrim")
                    )
                }

                val onOpenGalleryPicker = remember(galleryPhotoPickerLauncher) {
                    {
                        galleryPhotoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                }
                val onOpenFilesPicker = remember(filesDocPickerLauncher) {
                    {
                        filesDocPickerLauncher.launch(arrayOf("image/*"))
                    }
                }
                val onTriggerHaptic = remember(viewModel) { { viewModel.triggerHapticFeedback() } }

                // Top Control Bar (Brand, Aspect Ratio, Flash, Settings, Tools, Filter Pill)
                CameraTopBar(
                    uiState = uiState,
                    onCycleFlash = onCycleFlash,
                    onOpenSettings = onOpenSettings,
                    onCycleAspectRatio = onCycleAspectRatio,
                    onToggleQuickSettings = onToggleQuickSettings,
                    onToggleFilterSelector = onToggleFilterSelector,
                    onCycleRawMode = onCycleRawMode,
                    onCycleVideoQualityAndFps = onCycleVideoQualityAndFps,
                    onToggleVideoSettingsPanel = onToggleVideoSettingsPanel,
                    onCycleVideoAspectRatio = onCycleVideoAspectRatio,
                    onToggleAudio = onToggleAudio,
                    onToggleVideoStabilization = onToggleVideoStabilization,
                    onOpenGallery = onOpenGalleryPicker,
                    onOpenFiles = onOpenFilesPicker,
                    onTriggerHaptic = onTriggerHaptic,
                    modifier = Modifier.align(Alignment.TopCenter)
                )

                // Slide-down Quick Settings Drawer (Timer, Grid, Level, Sound)
                AnimatedVisibility(
                    visible = uiState.isQuickSettingsOpen,
                    enter = slideInVertically { -it } + fadeIn(),
                    exit = slideOutVertically { -it } + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 64.dp, start = 14.dp, end = 14.dp)
                ) {
                    CameraQuickSettingsDrawer(
                        uiState = uiState,
                        onSelectTimer = { viewModel.setTimer(it) },
                        onSelectGrid = { viewModel.setGrid(it) },
                        onToggleLevel = { viewModel.toggleLevelIndicator() },
                        onToggleShutterSound = { viewModel.toggleShutterSound() },
                        onSelectCapturePriority = { viewModel.setCapturePriority(it) },
                        onSetCustomTimerSeconds = { viewModel.setCustomTimerSeconds(it) }
                    )
                }

                // Slide-down Live Tone Filter Selector anchored symmetrically under CameraTopBar
                AnimatedVisibility(
                    visible = uiState.isFilterSelectorOpen,
                    enter = slideInVertically { -it } + fadeIn(),
                    exit = slideOutVertically { -it } + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 64.dp, start = 14.dp, end = 14.dp)
                ) {
                    CameraFilterSelector(
                        selectedFilter = uiState.selectedFilter,
                        onSelectFilter = { viewModel.setFilter(it) },
                        onClose = { viewModel.toggleFilterSelector() }
                    )
                }

                // Slide-down Video Settings Panel (Resolution, Dynamic FPS, Codecs, EIS, Mic)
                AnimatedVisibility(
                    visible = uiState.cameraMode == CameraMode.VIDEO && uiState.isVideoSettingsPanelOpen,
                    enter = slideInVertically { -it } + fadeIn(),
                    exit = slideOutVertically { -it } + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 64.dp, start = 14.dp, end = 14.dp)
                ) {
                    VideoSettingsPanel(
                        uiState = uiState,
                        onSelectQuality = { viewModel.setVideoQuality(it) },
                        onSelectFps = { viewModel.setVideoFps(it) },
                        onSelectCodec = { viewModel.setVideoCodec(it) },
                        onToggleStabilization = onToggleVideoStabilization,
                        onToggleAudio = onToggleAudio,
                        onClose = { viewModel.dismissVideoSettingsPanel() }
                    )
                }

                // Night Mode Top Viewfinder Liquid Glass Status & Auto/Max Pill
                AnimatedVisibility(
                    visible = uiState.cameraMode == CameraMode.NIGHT &&
                        !uiState.isQuickSettingsOpen &&
                        !uiState.isFilterSelectorOpen &&
                        !uiState.isSettingsOpen,
                    enter = slideInVertically { -it / 2 } + fadeIn(),
                    exit = slideOutVertically { -it / 2 } + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 64.dp, start = 14.dp, end = 14.dp)
                ) {
                    NightControlsBar(
                        uiState = uiState,
                        onSelectExposureMode = { viewModel.setNightExposureMode(it) }
                    )
                }

                // Portrait Mode Optical Aperture & Blur Controls Dock
                AnimatedVisibility(
                    visible = uiState.cameraMode == CameraMode.PORTRAIT,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 200.dp)
                ) {
                    PortraitControlsBar(
                        uiState = uiState,
                        onBlurIntensityChanged = { viewModel.setPortraitBlurIntensity(it) }
                    )
                }

                // Pro Mode Manual Controls Dock (ISO, Shutter, EV, Focus, White Balance + PEAK / ZEBRA Assist)
                AnimatedVisibility(
                    visible = uiState.cameraMode == CameraMode.PRO,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 200.dp, start = 10.dp, end = 10.dp)
                ) {
                    ProControlsBar(
                        uiState = uiState,
                        onTabSelected = { viewModel.setActiveProTab(it) },
                        onIsoChanged = { viewModel.setProIso(it) },
                        onShutterChanged = { viewModel.setProShutter(it) },
                        onEvChanged = { viewModel.setProEv(it) },
                        onFocusChanged = { dist, isManual -> viewModel.setProFocus(dist, isManual) },
                        onWhiteBalanceChanged = { viewModel.setProWhiteBalance(it) },
                        onResetAll = { viewModel.resetProSettings() },
                        onToggleFocusPeaking = { viewModel.toggleFocusPeaking() },
                        onToggleZebraStripes = { viewModel.toggleZebraStripes() },
                        onSelectAssistPopover = { viewModel.setActiveProAssistPopover(it) },
                        onFocusPeakingColorChanged = { viewModel.setFocusPeakingColor(it) },
                        onFocusPeakingSensitivityChanged = { viewModel.setFocusPeakingSensitivity(it) },
                        onZebraThresholdChanged = { viewModel.setZebraThreshold(it) }
                    )
                }

                // Professional Bottom Control Bar (Mode selector, Shutter/Record, Zoom, Thumbnail)
                CameraBottomBar(
                    uiState = uiState,
                    onCaptureClick = onCaptureClick,
                    onModeSelect = onModeSelect,
                    onToggleLens = onToggleLens,
                    onZoomChange = onZoomChange,
                    onThumbnailClick = onThumbnailClick,
                    onTogglePauseResume = onTogglePauseResume,
                    onToggleAudio = onToggleAudio,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )

                // Self-Timer Animated Countdown Overlay
                if (uiState.countdownSecondsLeft != null) {
                    CountdownTimerOverlay(
                        secondsLeft = uiState.countdownSecondsLeft!!,
                        onCancel = { viewModel.cancelCountdown() }
                    )
                }
            }

            // Full Media Gallery Dialog (browsing ALL captured photos & videos with playback, delete, details)
            if (uiState.isGalleryOpen && uiState.galleryItems.isNotEmpty()) {
                val unavailableUri = uiState.watermarkOriginalUnavailableUri
                PhotoPreviewDialog(
                    items = uiState.galleryItems,
                    currentIndex = uiState.currentGalleryIndex,
                    onIndexChange = { idx -> viewModel.selectGalleryIndex(idx) },
                    onDismiss = { viewModel.closeGallery() },
                    onDelete = { item ->
                        viewModel.deleteGalleryItem(mediaCaptureManager, item)
                    },
                    onGetDetails = { item ->
                        viewModel.getMediaDetails(mediaCaptureManager, item)
                    },
                    onEditPhoto = { item ->
                        viewModel.openPhotoEditor(item.uri)
                    },
                    onEditWatermark = { item ->
                        viewModel.requestEditWatermark(item)
                    },
                    isPortraitEditable = remember(uiState.portraitSidecarRevision) {
                        { item -> viewModel.hasPortraitSidecar(item) }
                    },
                    onEditPortrait = { item ->
                        viewModel.openPortraitEditor(item)
                    },
                    binCount = uiState.binCount,
                    onOpenRecycleBin = { viewModel.openRecycleBin() },
                    mediaRevision = uiState.galleryMediaRevision,
                    watermarkUnavailableMessage = uiState.watermarkOriginalUnavailableMessage,
                    onDismissWatermarkUnavailable = {
                        viewModel.dismissWatermarkOriginalUnavailableDialog()
                    },
                    onFallbackEditPhoto = if (unavailableUri != null) {
                        {
                            viewModel.dismissWatermarkOriginalUnavailableDialog()
                            viewModel.openPhotoEditor(unavailableUri)
                        }
                    } else null
                )
            }

            // Non-Destructive Post-Capture Portrait Depth & Blur Editor
            val portraitTarget = uiState.postCapturePortraitTarget
            if (uiState.isPortraitEditorOpen && portraitTarget != null) {
                PortraitBlurEditorDialog(
                    target = portraitTarget,
                    sidecarStorage = viewModel.portraitSidecarStorage,
                    isSaving = uiState.isPortraitEditorSaving,
                    hapticsEnabled = uiState.isHapticFeedbackEnabled,
                    onUpdateExisting = { blur, fx, fy ->
                        viewModel.savePortraitUpdateExisting(blur, fx, fy)
                    },
                    onSaveCopy = { blur, fx, fy ->
                        viewModel.savePortraitSaveCopy(blur, fx, fy)
                    },
                    onDismiss = { viewModel.closePortraitEditor() }
                )
            }

            // Professional Photo Studio Editor
            if (uiState.isPhotoEditorOpen && uiState.editingPhotoUri != null) {
                com.example.editor.PhotoEditorScreen(
                    sourceUri = uiState.editingPhotoUri!!,
                    onSaveSuccess = { savedCopyUri ->
                        viewModel.onPhotoEdited(mediaCaptureManager, savedCopyUri)
                    },
                    onDismiss = { viewModel.closePhotoEditor() },
                    onOpenWatermarkStudio = { snapshot ->
                        viewModel.openWatermarkStudioFromEditor(snapshot)
                    },
                    isImportedPhoto = uiState.isEditingPhotoImported,
                    isSaveLocationEnabled = uiState.isSaveLocationEnabled,
                    pendingWatermarkResult = uiState.pendingEditorWatermarkResult,
                    onConsumeWatermarkResult = { eventId ->
                        viewModel.consumePendingEditorWatermarkResult(eventId)
                    }
                )
            }

            // Full-Screen Categorized Settings Dialog (isolated state collection avoids camera recomposition churn)
            SettingsOverlayHost(
                viewModel = viewModel,
                onToggleVideoStabilization = onToggleVideoStabilization,
                onToggleAudio = onToggleAudio
            )

            // Dedicated Full-Screen Watermark Studio (isolated state collection)
            WatermarkStudioOverlayHost(viewModel = viewModel)

            // StuDO Recycle Bin Dialog (Restore & Permanent Deletion)
            if (uiState.isRecycleBinOpen) {
                RecycleBinDialog(
                    binItems = uiState.binItems,
                    onRestore = { binItem -> viewModel.restoreBinItem(mediaCaptureManager, binItem) },
                    onPermanentDelete = { binItem -> viewModel.permanentlyDeleteBinItem(binItem) },
                    onClearBin = { viewModel.clearRecycleBin() },
                    onDismiss = { viewModel.closeRecycleBin() }
                )
            }

            // Camera Capability Diagnostic Report Dialog (Developer Menu)
            if (uiState.isDiagnosticsDialogOpen) {
                CameraDiagnosticsDialog(
                    reportText = uiState.diagnosticReportText,
                    onDismiss = { viewModel.closeDiagnostics() }
                )
            }
        }

        // Front Camera Screen Flash illumination surface (display-only, never captured into photo)
        AnimatedVisibility(
            visible = uiState.isScreenFlashOverlayVisible,
            enter = fadeIn(animationSpec = tween(durationMillis = 65)),
            exit = fadeOut(animationSpec = tween(durationMillis = 150)),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(50f)
        ) {
            val overlayCd = stringResource(R.string.screen_flash_overlay_cd)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFFFF8EE))
                    .semantics { contentDescription = overlayCd }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent()
                            }
                        }
                    }
                    .testTag("screen_flash_illumination_overlay")
            )
        }

        // Minimalist translucent Liquid Glass snackbar
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 64.dp)
        ) { data ->
            Box(
                modifier = Modifier
                    .liquidGlassSurface(
                        shape = RoundedCornerShape(20.dp),
                        cornerRadius = 20.dp
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = data.visuals.message,
                    color = StuDOWhite,
                    fontSize = 13.sp
                )
            }
        }
    }
}
