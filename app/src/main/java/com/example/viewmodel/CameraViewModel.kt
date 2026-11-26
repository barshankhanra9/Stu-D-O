package com.example.viewmodel

import android.net.Uri
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.MeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import android.media.MediaActionSound
import androidx.camera.video.VideoRecordEvent
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.MediaCaptureManager
import com.example.model.CameraFilter
import com.example.util.VideoThumbnailUtil
import com.example.model.CameraMode
import com.example.model.CameraUiState
import com.example.model.CaptureAspectRatio
import com.example.model.CapturePriority
import com.example.model.CaptureTimer
import com.example.model.FlashMode
import com.example.model.GridOption
import com.example.model.MediaItem
import com.example.model.PhotoDetails
import com.example.model.RawCaptureMode
import com.example.model.ScreenFlashMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import com.example.model.SettingsUiState
import com.example.model.WatermarkPreset
import com.example.model.toSettingsUiState
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import com.example.data.CameraPreferencesRepository
import com.example.model.PhotoResolution
import com.example.model.VideoCodecOption
import com.example.model.VideoFpsOption
import com.example.model.VideoQualityOption
import com.example.model.VolumeKeyAction
import com.example.model.WatermarkConfig
import com.example.model.WatermarkPosition
import com.example.model.WatermarkStyle

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val preferencesRepo = CameraPreferencesRepository(appContext)
    private val capabilityDetector = com.example.data.CameraCapabilityDetector(appContext)
    val mediaCaptureManager = MediaCaptureManager(appContext)
    val privateOriginalStorage = com.example.data.PrivateOriginalStorageManager(appContext)
    val portraitSidecarStorage = com.example.portrait.PortraitSidecarStorage(appContext)
    val recycleBinManager = com.example.data.RecycleBinManager(appContext)
    private val ultraResCaptureManager = com.example.data.UltraResCaptureManager(appContext)
    private val oemRegistry = com.example.oem.OemAdapterRegistry()
    val portraitPipelineManager = com.example.portrait.PortraitPipelineManager(appContext)
    val nightPipelineManager = com.example.night.NightPipelineManager(appContext)
    @Volatile
    private var isOemBokehExtensionActive = false
    @Volatile
    private var nightExtensionLatencyMs: Long? = null
    @Volatile
    private var lastExposureTimeNs: Long = 0L
    @Volatile
    private var lastIso: Int = 0

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _orientationState = MutableStateFlow(com.example.model.DeviceOrientationState())
    val orientationState: StateFlow<com.example.model.DeviceOrientationState> = _orientationState.asStateFlow()

    private val _histogramBins = MutableStateFlow<List<Float>>(emptyList())
    val histogramBins: StateFlow<List<Float>> = _histogramBins.asStateFlow()

    private val _histogramData = MutableStateFlow(com.example.model.HistogramData.Empty)
    val histogramData: StateFlow<com.example.model.HistogramData> = _histogramData.asStateFlow()

    private var activeCamera: Camera? = null
    private var activeCameraControl: CameraControl? = null
    private var activeCameraInfo: CameraInfo? = null
    private var activeCameraProvider: ProcessCameraProvider? = null
    private var activeImageCapture: ImageCapture? = null
    private var activeVideoCapture: VideoCapture<*>? = null
    private var activeRecording: Recording? = null
    private var isRecordingStarted: Boolean = false
    private var currentTargetRotation: Int = android.view.Surface.ROTATION_0
    @Volatile
    private var liveCaptureFocalLengthMm: Float? = null
    @Volatile
    private var boundCameraCharacteristicFocalLengthMm: Float? = null

    private var focusResetJob: Job? = null
    private var recordingTimerJob: Job? = null
    private var countdownJob: Job? = null
    private var galleryRefreshJob: Job? = null
    private var screenFlashTimeoutJob: Job? = null
    private var nightCaptureJob: Job? = null
    private val pendingScreenFlashListener = java.util.concurrent.atomic.AtomicReference<ImageCapture.ScreenFlashListener?>(null)
    private var mediaSound: MediaActionSound? = null
    private var developerTapCount = 0

    init {
        val savedOemMode = preferencesRepo.getOemEnhancementMode()
        val activeOemAdapter = oemRegistry.resolveActiveAdapter(appContext, savedOemMode)
        val savedCacheLimit = preferencesRepo.getWatermarkCacheLimit()
        val initialPrivateStats = com.example.model.PrivateOriginalStorageStats(
            maxBytes = savedCacheLimit.limitBytes,
            usedBytes = privateOriginalStorage.getUsedStorageBytes(),
            originalCount = privateOriginalStorage.getRetainedOriginalsCount()
        )

        val dm = appContext.resources.displayMetrics
        val initialFullAspect = if (dm.widthPixels > 0 && dm.heightPixels > 0) {
            dm.widthPixels.toFloat() / dm.heightPixels.toFloat()
        } else {
            9f / 20f
        }

        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val isLowRam = try { activityManager?.isLowRamDevice == true } catch (_: Throwable) { false }
        val initialPortraitTier = if (isLowRam) {
            com.example.model.PortraitQualityTier.EFFICIENT
        } else {
            com.example.model.PortraitQualityTier.BALANCED
        }

        val savedNightMode = preferencesRepo.getNightExposureMode()
        val initialNightDuration = nightPipelineManager.estimateCaptureDurationSeconds(
            exposureMode = savedNightMode,
            isExtensionSupported = false,
            extensionLatencyMs = null,
            isLowLightDetected = false,
            lastExposureNs = 0L,
            lastIso = 0
        )

        // Restore all persistent user settings from disk
        _uiState.update { current ->
            current.copy(
                aspectRatio = preferencesRepo.getAspectRatio(),
                videoAspectRatio = preferencesRepo.getVideoAspectRatio(),
                fullScreenAspectRatio = initialFullAspect,
                selectedTimer = preferencesRepo.getTimer(),
                customTimerSeconds = preferencesRepo.getCustomTimerSeconds(),
                selectedGrid = preferencesRepo.getGrid(),
                selectedFilter = preferencesRepo.getFilter(),
                screenFlashMode = preferencesRepo.getScreenFlashMode(),
                isLevelIndicatorEnabled = preferencesRepo.isLevelEnabled(),
                isHistogramEnabled = preferencesRepo.isHistogramEnabled(),
                histogramMode = preferencesRepo.getHistogramMode(),
                isShutterSoundEnabled = preferencesRepo.isShutterSoundEnabled(),
                isHapticFeedbackEnabled = preferencesRepo.isHapticFeedbackEnabled(),
                isKeepScreenAwakeEnabled = preferencesRepo.isKeepScreenAwakeEnabled(),
                volumeKeyAction = preferencesRepo.getVolumeKeyAction(),
                photoResolution = preferencesRepo.getPhotoResolution(),
                capturePriority = preferencesRepo.getCapturePriority(),
                rawCaptureMode = preferencesRepo.getRawCaptureMode(),
                isHdrEnabled = preferencesRepo.isHdrEnabled(),
                watermarkConfig = preferencesRepo.getWatermarkConfig(),
                isPostCaptureWatermarkEditingEnabled = preferencesRepo.isPostCaptureWatermarkEditingEnabled(),
                isWatermarkCacheLimitConfigured = preferencesRepo.isWatermarkCacheLimitConfigured(),
                watermarkCacheLimit = savedCacheLimit,
                privateOriginalStorageStats = initialPrivateStats,
                watermarkPresets = preferencesRepo.getWatermarkPresets(),
                videoQuality = preferencesRepo.getVideoQuality(),
                videoFps = preferencesRepo.getVideoFps(),
                isAudioEnabled = preferencesRepo.isVideoAudioEnabled(),
                isVideoStabilizationEnabled = preferencesRepo.isVideoStabilizationEnabled(),
                videoCodec = preferencesRepo.getVideoCodec(),
                isSelfieMirrorEnabled = preferencesRepo.isSelfieMirrorEnabled(),
                isSaveLocationEnabled = preferencesRepo.isSaveLocationEnabled(),
                oemEnhancementMode = savedOemMode,
                activeOemAdapterName = activeOemAdapter.adapterName,
                oemEnhancementSummary = "${activeOemAdapter.adapterName} (${if (savedOemMode == com.example.model.OemEnhancementMode.AUTOMATIC) "Auto" else "Universal"})",
                portraitBlurIntensity = preferencesRepo.getPortraitBlurIntensity(),
                portraitQualityTier = initialPortraitTier,
                nightExposureMode = savedNightMode,
                nightEstimatedDurationSeconds = initialNightDuration
            )
        }
        refreshLensCapabilities()
        refreshRecycleBinState()
        viewModelScope.launch(Dispatchers.IO) {
            cleanupOrphanedExportTempFiles()
        }
        viewModelScope.launch {
            portraitPipelineManager.status.collect { pStatus ->
                _uiState.update { cur ->
                    val inPortrait = cur.cameraMode == CameraMode.PORTRAIT
                    cur.copy(
                        portraitPreviewStrategy = pStatus.previewStrategy,
                        portraitCaptureTier = pStatus.activeCaptureTier,
                        portraitSubjectModelStatus = pStatus.subjectModelStatus,
                        portraitSubjectModelProgress = pStatus.subjectModelProgress,
                        isPortraitLiveBlurPausedByWatchdog = if (inPortrait) pStatus.isLiveBlurPausedByWatchdog else false,
                        portraitLiveFps = if (inPortrait) pStatus.livePreviewFps else 0f,
                        isPortraitSubjectDetected = if (inPortrait) (pStatus.isSubjectDetected || cur.isPortraitSubjectDetected) else false,
                        portraitSubjectCount = if (inPortrait) maxOf(pStatus.subjectCount, cur.portraitSubjectCount) else 0
                    )
                }
            }
        }
    }

    // Isolated UI state flows prevent high-frequency viewfinder/sensor updates from recomposing overlays
    val isSettingsOpenState: StateFlow<Boolean> = _uiState
        .map { it.isSettingsOpen }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value.isSettingsOpen)

    val settingsUiState: StateFlow<SettingsUiState> = _uiState
        .map { it.toSettingsUiState() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value.toSettingsUiState())

    val isWatermarkStudioOpenState: StateFlow<Boolean> = _uiState
        .map { it.isWatermarkStudioOpen }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value.isWatermarkStudioOpen)

    val watermarkStudioConfigState: StateFlow<WatermarkConfig> = _uiState
        .map { it.watermarkConfig }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value.watermarkConfig)

    val watermarkStudioPresetsState: StateFlow<List<WatermarkPreset>> = _uiState
        .map { it.watermarkPresets }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value.watermarkPresets)

    val activeFocalLengthTextState: StateFlow<String?> = _uiState
        .map { it.activeFocalLengthText }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value.activeFocalLengthText)

    val postCaptureWatermarkTargetState: StateFlow<com.example.model.PostCaptureWatermarkTarget?> = _uiState
        .map { it.postCaptureWatermarkTarget }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value.postCaptureWatermarkTarget)

    val editorWatermarkTargetState: StateFlow<com.example.model.EditorWatermarkTarget?> = _uiState
        .map { it.editorWatermarkTarget }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value.editorWatermarkTarget)

    val isPostCaptureSavingState: StateFlow<Boolean> = _uiState
        .map { it.isPostCaptureSaving }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value.isPostCaptureSaving)

    fun onPermissionResult(isGranted: Boolean, permanentlyDenied: Boolean = false) {
        _uiState.update {
            it.copy(
                hasPermission = isGranted,
                permissionPermanentlyDenied = permanentlyDenied
            )
        }
    }

    fun onCameraBound(
        camera: Camera,
        cameraProvider: ProcessCameraProvider,
        imageCapture: ImageCapture?,
        videoCapture: VideoCapture<*>?
    ) {
        activeCamera = camera
        activeCameraControl = camera.cameraControl
        activeCameraInfo = camera.cameraInfo
        activeCameraProvider = cameraProvider
        activeImageCapture = imageCapture
        activeVideoCapture = videoCapture

        val hasFront = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        val hasBack = cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        val hasFlash = camera.cameraInfo.hasFlashUnit()

        val zoomState = camera.cameraInfo.zoomState.value
        val minZoom = zoomState?.minZoomRatio ?: 1.0f
        val maxZoom = zoomState?.maxZoomRatio ?: 8.0f
        val currentZoom = zoomState?.zoomRatio ?: 1.0f

        // Exposure compensation state
        val expState = camera.cameraInfo.exposureState
        val isExpSupported = expState.isExposureCompensationSupported
        val expRange = expState.exposureCompensationRange
        val expStep = expState.exposureCompensationStep.toFloat()
        val currentExpIndex = expState.exposureCompensationIndex

        boundCameraCharacteristicFocalLengthMm = extractCharacteristicFocalLengthMm(camera.cameraInfo)

        val updatedState = _uiState.updateAndGet {
            it.copy(
                isCameraReady = true,
                hasFrontCamera = hasFront,
                hasBackCamera = hasBack,
                minZoomRatio = minZoom,
                maxZoomRatio = maxZoom,
                zoomRatio = currentZoom,
                isExposureSupported = isExpSupported,
                minExposureIndex = expRange.lower,
                maxExposureIndex = expRange.upper,
                exposureStep = expStep,
                exposureIndex = currentExpIndex,
                isVideoRecordingSupported = if (_uiState.value.cameraMode == CameraMode.VIDEO) {
                    (videoCapture != null)
                } else {
                    it.isVideoRecordingSupported
                }
            )
        }
        val isFront = (updatedState.lensFacing == CameraSelector.LENS_FACING_FRONT)
        applyBoundFlashCapabilities(isFrontLens = isFront, hasHardwareFlash = hasFlash, imageCapture = imageCapture)
        refreshLensCapabilities()
        if (_uiState.value.cameraMode == CameraMode.PRO) {
            applyProSettingsToPreview()
        } else if (_uiState.value.cameraMode == CameraMode.VIDEO) {
            applyVideoSettingsToCamera()
        }
    }

    internal fun applyBoundFlashCapabilities(
        isFrontLens: Boolean,
        hasHardwareFlash: Boolean,
        imageCapture: ImageCapture? = activeImageCapture
    ) {
        val screenFlashAvailable = isFrontLens && !hasHardwareFlash
        if (!screenFlashAvailable) {
            dismissScreenFlash()
        }
        val updatedState = _uiState.updateAndGet {
            it.copy(
                isFlashAvailable = hasHardwareFlash,
                isScreenFlashAvailable = screenFlashAvailable,
                isTorchActive = if (hasHardwareFlash) it.isTorchActive else false
            )
        }
        // Apply current effective flash mode (hardware flash or Screen Flash) to imageCapture
        syncImageCaptureFlashMode(imageCapture, updatedState)
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun extractCharacteristicFocalLengthMm(cameraInfo: CameraInfo): Float? {
        return try {
            val c2Info = androidx.camera.camera2.interop.Camera2CameraInfo.from(cameraInfo)
            c2Info.getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            )?.firstOrNull { it.isFinite() && it > 0f }
        } catch (_: Throwable) {
            null
        }
    }

    fun refreshLensCapabilities() {
        try {
            val isFront = (_uiState.value.lensFacing == CameraSelector.LENS_FACING_FRONT)
            val cap = capabilityDetector.getCapabilityForLens(isFront)
            if (cap != null) {
                val options = cap.getDynamicResolutionOptions()
                val currentPref = _uiState.value.photoResolution
                val activeOpt = options.firstOrNull { it.resolution == currentPref } ?: options.firstOrNull()
                val genuineMax = cap.highestGenuineJpegSize.takeIf { it.width > 0 && it.height > 0 }
                val targetSize = activeOpt?.size ?: genuineMax ?: cap.recommendedUhrSize ?: cap.standardJpegSizes.firstOrNull()
                val standardResText = if (genuineMax != null) {
                    "${genuineMax.width} × ${genuineMax.height} (${String.format(java.util.Locale.US, "%.1f", cap.maxGenuineJpegMegapixels)} MP)"
                } else ""

                // Fall back RAW capture to OFF if switching to a lens (e.g. front) that doesn't support RAW
                val currentRawMode = _uiState.value.rawCaptureMode
                val effectiveRawMode = if (!cap.isRawSupported && currentRawMode != RawCaptureMode.OFF) {
                    RawCaptureMode.OFF
                } else {
                    currentRawMode
                }

                val currentQuality = _uiState.value.videoQuality
                val effectiveQuality = if (cap.supportedVideoQualities.contains(currentQuality)) currentQuality else (cap.supportedVideoQualities.firstOrNull() ?: VideoQualityOption.QUALITY_1080P)

                val validFps = cap.getSupportedFpsForQuality(effectiveQuality)
                val currentFps = _uiState.value.videoFps
                val effectiveFps = if (validFps.contains(currentFps)) currentFps else (validFps.firstOrNull() ?: VideoFpsOption.FPS_AUTO)
                val supportedCodecs = cap.supportedVideoCodecs.ifEmpty {
                    com.example.util.VideoEncoderUtil.getSupportedVideoCodecs().ifEmpty { listOf(VideoCodecOption.H264) }
                }
                val currentCodec = _uiState.value.videoCodec
                val effectiveCodec = if (supportedCodecs.contains(currentCodec)) currentCodec else supportedCodecs.first()
                if (effectiveCodec != currentCodec) {
                    preferencesRepo.saveVideoCodec(effectiveCodec)
                }
                val supportedVideoRatios = cap.supportedVideoAspectRatios.ifEmpty {
                    listOf(CaptureAspectRatio.RATIO_16_9)
                }
                val currentVideoRatio = _uiState.value.videoAspectRatio
                val effectiveVideoRatio = if (supportedVideoRatios.contains(currentVideoRatio)) {
                    currentVideoRatio
                } else {
                    supportedVideoRatios.first()
                }
                val effectiveFocalMm = resolveEffectiveFocalLengthMm(cap.primaryFocalLengthMm)
                val effectiveFocalText = com.example.util.CameraMetadataUtil.formatFocalLengthMm(effectiveFocalMm)

                val isVideoActiveWithCapture = _uiState.value.cameraMode == CameraMode.VIDEO &&
                    activeCamera != null &&
                    activeVideoCapture != null
                val isVideoBoundWithoutCapture = _uiState.value.cameraMode == CameraMode.VIDEO &&
                    activeCamera != null &&
                    activeVideoCapture == null

                val effectiveSupportedQualities = if (cap.supportedVideoQualities.isNotEmpty()) {
                    cap.supportedVideoQualities
                } else if (isVideoActiveWithCapture) {
                    listOf(VideoQualityOption.QUALITY_1080P, VideoQualityOption.QUALITY_720P, VideoQualityOption.QUALITY_480P)
                } else {
                    emptyList()
                }
                val effectiveSupportedFps = if (validFps.isNotEmpty()) {
                    validFps
                } else if (isVideoActiveWithCapture) {
                    listOf(VideoFpsOption.FPS_AUTO, VideoFpsOption.FPS_30)
                } else {
                    emptyList()
                }

                val isHwDepthSupported = cap.isDepthOutputSupported || cap.depth16Sizes.isNotEmpty()
                val effectiveDepthSource = if (isHwDepthSupported) {
                    com.example.model.PortraitDepthSource.HYBRID
                } else {
                    com.example.model.PortraitDepthSource.AI_SEGMENTATION
                }

                _uiState.update {
                    it.copy(
                        isUhrSupportedOnCurrentLens = cap.isUltraHighResolutionSupported,
                        uhrSize = cap.recommendedUhrSize,
                        uhrMegapixels = cap.maxJpegMegapixels,
                        isRawSupportedOnCurrentLens = cap.isRawSupported,
                        rawCaptureMode = effectiveRawMode,
                        proCapabilities = cap.proCapabilities,
                        standardSensorResolutionText = standardResText,
                        standardMaxResolutionSize = genuineMax,
                        availableResolutionOptions = options,
                        activeResolutionOption = activeOpt,
                        targetCaptureSize = targetSize,
                        supportedVideoQualities = effectiveSupportedQualities,
                        supportedVideoFps = effectiveSupportedFps,
                        supportedFpsPerQuality = cap.supportedFpsPerQuality,
                        supportedVideoCodecs = supportedCodecs,
                        supportedVideoAspectRatios = supportedVideoRatios,
                        videoAspectRatio = effectiveVideoRatio,
                        videoCodec = effectiveCodec,
                        isVideoRecordingSupported = when {
                            isVideoActiveWithCapture -> true
                            isVideoBoundWithoutCapture -> false
                            else -> cap.isVideoRecordingSupported
                        },
                        isVideoStabilizationSupported = cap.isVideoStabilizationSupported,
                        videoQuality = effectiveQuality,
                        videoFps = effectiveFps,
                        activeFocalLengthMm = effectiveFocalMm,
                        activeFocalLengthText = effectiveFocalText,
                        isPortraitHardwareDepthSupported = isHwDepthSupported,
                        portraitDepthSource = effectiveDepthSource
                    )
                }
            } else {
                val effectiveFocalMm = resolveEffectiveFocalLengthMm(null)
                val effectiveFocalText = com.example.util.CameraMetadataUtil.formatFocalLengthMm(effectiveFocalMm)
                _uiState.update {
                    it.copy(
                        isUhrSupportedOnCurrentLens = false,
                        uhrSize = null,
                        uhrMegapixels = 0.0,
                        isRawSupportedOnCurrentLens = false,
                        standardSensorResolutionText = "",
                        standardMaxResolutionSize = null,
                        availableResolutionOptions = emptyList(),
                        activeResolutionOption = null,
                        targetCaptureSize = null,
                        activeFocalLengthMm = effectiveFocalMm,
                        activeFocalLengthText = effectiveFocalText,
                        isPortraitHardwareDepthSupported = false,
                        portraitDepthSource = com.example.model.PortraitDepthSource.AI_SEGMENTATION
                    )
                }
            }
        } catch (_: Exception) {}
    }

    private fun resolveEffectiveFocalLengthMm(capabilityFocalMm: Float?): Float? {
        return liveCaptureFocalLengthMm?.takeIf { it.isFinite() && it > 0f }
            ?: boundCameraCharacteristicFocalLengthMm?.takeIf { it.isFinite() && it > 0f }
            ?: capabilityFocalMm?.takeIf { it.isFinite() && it > 0f }
    }

    fun onActiveLensFocalLengthChanged(focalLengthMm: Float?, physicalCameraId: String?) {
        val reportedMm = focalLengthMm?.takeIf { it.isFinite() && it > 0f }
            ?: physicalCameraId?.let { capabilityDetector.getFocalLengthForCameraId(it) }
        liveCaptureFocalLengthMm = reportedMm
        val isFront = (_uiState.value.lensFacing == CameraSelector.LENS_FACING_FRONT)
        val effectiveMm = reportedMm
            ?: boundCameraCharacteristicFocalLengthMm?.takeIf { it.isFinite() && it > 0f }
            ?: capabilityDetector.getCapabilityForLens(isFront)?.primaryFocalLengthMm
        val formatted = com.example.util.CameraMetadataUtil.formatFocalLengthMm(effectiveMm)
        _uiState.update { current ->
            if (current.activeFocalLengthMm == effectiveMm && current.activeFocalLengthText == formatted) {
                current
            } else {
                current.copy(
                    activeFocalLengthMm = effectiveMm,
                    activeFocalLengthText = formatted
                )
            }
        }
    }

    fun onResolutionConfigured(size: android.util.Size) {
        _uiState.update { it.copy(actualConfiguredResolution = size) }
    }

    fun setCapturePriority(priority: CapturePriority) {
        if (_uiState.value.capturePriority != priority) {
            preferencesRepo.saveCapturePriority(priority)
            _uiState.update { it.copy(capturePriority = priority) }
        }
    }

    fun onStreamStateChanged(isStreaming: Boolean) {
        _uiState.update { it.copy(isStreaming = isStreaming) }
    }

    fun onFullScreenAspectRatioChanged(aspectRatio: Float) {
        if (aspectRatio.isFinite() && aspectRatio > 0f &&
            abs(_uiState.value.fullScreenAspectRatio - aspectRatio) > 0.005f
        ) {
            _uiState.update { it.copy(fullScreenAspectRatio = aspectRatio) }
        }
    }

    fun setAspectRatio(ratio: CaptureAspectRatio) {
        if (_uiState.value.aspectRatio != ratio) {
            preferencesRepo.saveAspectRatio(ratio)
            _uiState.update { it.copy(aspectRatio = ratio, isCameraReady = false) }
        }
    }

    fun cycleAspectRatio() {
        val nextRatio = when (_uiState.value.aspectRatio) {
            CaptureAspectRatio.RATIO_4_3 -> CaptureAspectRatio.RATIO_16_9
            CaptureAspectRatio.RATIO_16_9 -> CaptureAspectRatio.RATIO_1_1
            CaptureAspectRatio.RATIO_1_1 -> CaptureAspectRatio.RATIO_FULL
            CaptureAspectRatio.RATIO_FULL -> CaptureAspectRatio.RATIO_4_3
        }
        setAspectRatio(nextRatio)
    }

    fun setVideoAspectRatio(ratio: CaptureAspectRatio) {
        if (_uiState.value.isRecordingVideo) return
        val supported = _uiState.value.supportedVideoAspectRatios
        val clampedRatio = if (supported.isEmpty() || supported.contains(ratio)) ratio else supported.first()
        if (_uiState.value.videoAspectRatio != clampedRatio) {
            preferencesRepo.saveVideoAspectRatio(clampedRatio)
            _uiState.update { it.copy(videoAspectRatio = clampedRatio, isCameraReady = false) }
        }
    }

    fun cycleVideoAspectRatio() {
        if (_uiState.value.isRecordingVideo) return
        val supported = _uiState.value.supportedVideoAspectRatios
        if (supported.size <= 1) return
        val currentIdx = supported.indexOf(_uiState.value.videoAspectRatio)
        val nextRatio = supported[(currentIdx + 1) % supported.size]
        setVideoAspectRatio(nextRatio)
    }

    fun setCameraMode(mode: CameraMode) {
        if (_uiState.value.isNightCapturing) return
        val currentMode = _uiState.value.cameraMode
        if (currentMode != mode) {
            dismissScreenFlash()
            if (_uiState.value.isRecordingVideo) {
                stopVideoRecording()
            }
            val wasPortrait = (currentMode == CameraMode.PORTRAIT)
            val enteringPortrait = (mode == CameraMode.PORTRAIT)
            val wasNight = (currentMode == CameraMode.NIGHT)
            val enteringNight = (mode == CameraMode.NIGHT)
            val isFront = (_uiState.value.lensFacing == CameraSelector.LENS_FACING_FRONT)
            val cap = capabilityDetector.getCapabilityForLens(isFront)
            val videoSupportedForLens = cap?.isVideoRecordingSupported ?: true

            if (wasPortrait && !enteringPortrait) {
                isOemBokehExtensionActive = false
                portraitPipelineManager.onExitPortraitMode()
            } else if (!wasPortrait && enteringPortrait) {
                isOemBokehExtensionActive = false
                portraitPipelineManager.onEnterPortraitMode()
            }
            if (!enteringNight) {
                nightExtensionLatencyMs = null
            }

            val updatedState = _uiState.updateAndGet {
                val switchingVideo = (it.cameraMode == CameraMode.VIDEO) != (mode == CameraMode.VIDEO)
                val switchingPortrait = wasPortrait != enteringPortrait
                val switchingNight = wasNight != enteringNight
                it.copy(
                    cameraMode = mode,
                    isCameraReady = if (switchingVideo || switchingPortrait || switchingNight) false else it.isCameraReady,
                    activeCameraPanel = com.example.model.ActiveCameraPanel.NONE,
                    isFilterSelectorOpen = false,
                    isQuickSettingsOpen = false,
                    isVideoSettingsPanelOpen = false,
                    activeProTab = com.example.model.ProControlTab.NONE,
                    showExposureSlider = false,
                    isVideoRecordingSupported = if (mode == CameraMode.VIDEO) videoSupportedForLens else it.isVideoRecordingSupported,
                    isPortraitSubjectDetected = if (enteringPortrait) it.isPortraitSubjectDetected else false,
                    portraitSubjectCount = if (enteringPortrait) it.portraitSubjectCount else 0,
                    isPortraitLiveBlurPausedByWatchdog = if (enteringPortrait) it.isPortraitLiveBlurPausedByWatchdog else false,
                    portraitLiveFps = if (enteringPortrait) it.portraitLiveFps else 0f,
                    portraitPreviewStrategy = if (enteringPortrait) com.example.model.PortraitPreviewStrategy.CHECKING else it.portraitPreviewStrategy,
                    isNightExtensionSupported = if (enteringNight) it.isNightExtensionSupported else false,
                    nightCaptureEngine = if (enteringNight) it.nightCaptureEngine else com.example.model.NightCaptureEngine.MULTI_FRAME_STACK
                )
            }
            syncImageCaptureFlashMode(state = updatedState)
            if (enteringNight) {
                recomputeNightDurationEstimate()
            }
            if (mode == CameraMode.VIDEO) {
                _histogramBins.value = emptyList()
                _histogramData.value = com.example.model.HistogramData.Empty
            }
            if (mode == CameraMode.PRO) {
                applyProSettingsToPreview()
            } else {
                clearProSettingsFromPreview()
            }
        }
    }

    fun onPortraitExtensionBindingChanged(isOemBokehActive: Boolean) {
        isOemBokehExtensionActive = isOemBokehActive && (_uiState.value.cameraMode == CameraMode.PORTRAIT)
    }

    fun setNightExposureMode(mode: com.example.model.NightExposureMode) {
        if (_uiState.value.isNightCapturing) return
        preferencesRepo.saveNightExposureMode(mode)
        _uiState.update { it.copy(nightExposureMode = mode) }
        recomputeNightDurationEstimate()
    }

    fun toggleNightExposureMode() {
        val next = if (_uiState.value.nightExposureMode == com.example.model.NightExposureMode.AUTO) {
            com.example.model.NightExposureMode.MAX
        } else {
            com.example.model.NightExposureMode.AUTO
        }
        setNightExposureMode(next)
    }

    fun onNightBindingChanged(isExtensionActive: Boolean, estimatedLatencyMs: Long?) {
        val inNight = _uiState.value.cameraMode == CameraMode.NIGHT
        val activeExt = inNight && isExtensionActive
        nightExtensionLatencyMs = if (activeExt) estimatedLatencyMs else null
        val engine = if (activeExt) {
            com.example.model.NightCaptureEngine.HARDWARE_EXTENSION
        } else {
            com.example.model.NightCaptureEngine.MULTI_FRAME_STACK
        }
        _uiState.update { cur ->
            if (cur.isNightExtensionSupported == activeExt && cur.nightCaptureEngine == engine) {
                cur
            } else {
                cur.copy(
                    isNightExtensionSupported = activeExt,
                    nightCaptureEngine = engine
                )
            }
        }
        recomputeNightDurationEstimate()
    }

    fun onExposureTelemetry(exposureNs: Long, iso: Int) {
        lastExposureTimeNs = exposureNs
        lastIso = iso
        if (_uiState.value.cameraMode == CameraMode.NIGHT && !_uiState.value.isNightCapturing) {
            recomputeNightDurationEstimate()
        }
    }

    private fun recomputeNightDurationEstimate() {
        val cur = _uiState.value
        val estimated = nightPipelineManager.estimateCaptureDurationSeconds(
            exposureMode = cur.nightExposureMode,
            isExtensionSupported = cur.isNightExtensionSupported,
            extensionLatencyMs = nightExtensionLatencyMs,
            isLowLightDetected = cur.isScreenFlashLowLightDetected,
            lastExposureNs = lastExposureTimeNs,
            lastIso = lastIso
        )
        if (abs(cur.nightEstimatedDurationSeconds - estimated) >= 0.05f) {
            _uiState.update { it.copy(nightEstimatedDurationSeconds = estimated) }
        }
    }

    fun toggleLens() {
        if (_uiState.value.isRecordingVideo) return

        val currentFacing = _uiState.value.lensFacing
        val nextFacing = if (currentFacing == CameraSelector.LENS_FACING_BACK) {
            if (_uiState.value.hasFrontCamera) CameraSelector.LENS_FACING_FRONT else currentFacing
        } else {
            if (_uiState.value.hasBackCamera) CameraSelector.LENS_FACING_BACK else currentFacing
        }

        if (nextFacing != currentFacing) {
            dismissScreenFlash()
            liveCaptureFocalLengthMm = null
            boundCameraCharacteristicFocalLengthMm = null
            _histogramBins.value = emptyList()
            _histogramData.value = com.example.model.HistogramData.Empty
            _uiState.update {
                it.copy(
                    lensFacing = nextFacing,
                    isTorchActive = false,
                    isFlashAvailable = false,
                    isScreenFlashAvailable = false,
                    isScreenFlashLowLightDetected = false,
                    isCameraReady = false
                )
            }
            refreshLensCapabilities()
        }
    }

    fun shouldUseScreenFlashForCapture(state: CameraUiState = _uiState.value): Boolean {
        return !state.isFlashAvailable &&
            state.isScreenFlashAvailable &&
            state.cameraMode != CameraMode.VIDEO &&
            (state.screenFlashMode == ScreenFlashMode.ON ||
                (state.screenFlashMode == ScreenFlashMode.AUTO && state.isScreenFlashLowLightDetected))
    }

    fun resolveEffectiveFlashMode(state: CameraUiState = _uiState.value): Int {
        return when {
            state.isFlashAvailable -> state.flashMode.toCameraXFlashMode()
            shouldUseScreenFlashForCapture(state) -> ImageCapture.FLASH_MODE_SCREEN
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    private fun syncImageCaptureFlashMode(
        imageCapture: ImageCapture? = activeImageCapture,
        state: CameraUiState = _uiState.value
    ) {
        val capture = imageCapture ?: return
        val targetMode = resolveEffectiveFlashMode(state)
        try {
            capture.flashMode = targetMode
        } catch (_: IllegalArgumentException) {
            if (targetMode == ImageCapture.FLASH_MODE_SCREEN) {
                try {
                    capture.flashMode = ImageCapture.FLASH_MODE_OFF
                } catch (_: Throwable) {}
            }
        }
    }

    fun setScreenFlashMode(mode: ScreenFlashMode) {
        preferencesRepo.saveScreenFlashMode(mode)
        val updatedState = _uiState.updateAndGet {
            it.copy(screenFlashMode = mode)
        }
        syncImageCaptureFlashMode(state = updatedState)
    }

    fun onLowLightConditionChanged(isLowLight: Boolean) {
        if (_uiState.value.isScreenFlashLowLightDetected == isLowLight) return
        val updatedState = _uiState.updateAndGet {
            it.copy(isScreenFlashLowLightDetected = isLowLight)
        }
        if (updatedState.isScreenFlashAvailable && updatedState.screenFlashMode == ScreenFlashMode.AUTO) {
            syncImageCaptureFlashMode(state = updatedState)
        }
        if (updatedState.cameraMode == CameraMode.NIGHT && !updatedState.isNightCapturing) {
            recomputeNightDurationEstimate()
        }
    }

    fun onScreenFlashApplyRequested(
        expirationTimeMillis: Long,
        listener: ImageCapture.ScreenFlashListener
    ) {
        if (!_uiState.value.isScreenFlashAvailable ||
            _uiState.value.isFlashAvailable ||
            _uiState.value.cameraMode == CameraMode.VIDEO
        ) {
            try {
                listener.onCompleted()
            } catch (_: Throwable) {}
            return
        }
        pendingScreenFlashListener.getAndSet(listener)?.let { prev ->
            try {
                prev.onCompleted()
            } catch (_: Throwable) {}
        }
        screenFlashTimeoutJob?.cancel()
        val remainingMs = (expirationTimeMillis - System.currentTimeMillis()).coerceIn(250L, 4500L)
        _uiState.update { it.copy(isScreenFlashOverlayVisible = true) }
        screenFlashTimeoutJob = viewModelScope.launch {
            delay(remainingMs)
            pendingScreenFlashListener.getAndSet(null)?.let { pending ->
                try {
                    pending.onCompleted()
                } catch (_: Throwable) {}
            }
            // Failsafe dismiss in case CameraX fails to invoke ScreenFlash.clear() after timeout
            delay(3000L)
            _uiState.update { it.copy(isScreenFlashOverlayVisible = false) }
        }
    }

    fun onScreenFlashSurfaceReady() {
        pendingScreenFlashListener.getAndSet(null)?.let { listener ->
            try {
                listener.onCompleted()
            } catch (_: Throwable) {}
        }
    }

    fun dismissScreenFlash() {
        screenFlashTimeoutJob?.cancel()
        screenFlashTimeoutJob = null
        pendingScreenFlashListener.getAndSet(null)?.let { listener ->
            try {
                listener.onCompleted()
            } catch (_: Throwable) {}
        }
        if (_uiState.value.isScreenFlashOverlayVisible) {
            _uiState.update { it.copy(isScreenFlashOverlayVisible = false) }
        }
    }

    fun cycleFlashMode() {
        val current = _uiState.value
        if (current.isFlashAvailable) {
            val nextMode = when (current.flashMode) {
                FlashMode.OFF -> FlashMode.AUTO
                FlashMode.AUTO -> FlashMode.ON
                FlashMode.ON -> FlashMode.TORCH
                FlashMode.TORCH -> FlashMode.OFF
            }

            val enableTorch = (nextMode == FlashMode.TORCH)
            activeCameraControl?.enableTorch(enableTorch)
            activeImageCapture?.flashMode = nextMode.toCameraXFlashMode()

            _uiState.update {
                it.copy(
                    flashMode = nextMode,
                    isTorchActive = enableTorch
                )
            }
            return
        }

        if (current.isScreenFlashAvailable && current.cameraMode != CameraMode.VIDEO) {
            val nextScreenMode = when (current.screenFlashMode) {
                ScreenFlashMode.OFF -> ScreenFlashMode.AUTO
                ScreenFlashMode.AUTO -> ScreenFlashMode.ON
                ScreenFlashMode.ON -> ScreenFlashMode.OFF
            }
            setScreenFlashMode(nextScreenMode)
            return
        }

        _uiState.update { it.copy(userMessage = "Flash unavailable on this sensor") }
    }

    fun setZoomRatio(ratio: Float) {
        val clamped = ratio.coerceIn(_uiState.value.minZoomRatio, _uiState.value.maxZoomRatio)
        activeCameraControl?.setZoomRatio(clamped)
        _uiState.update { it.copy(zoomRatio = clamped) }
    }

    fun onPinchZoom(scaleFactor: Float) {
        val newRatio = (_uiState.value.zoomRatio * scaleFactor)
            .coerceIn(_uiState.value.minZoomRatio, _uiState.value.maxZoomRatio)
        setZoomRatio(newRatio)
    }

    fun triggerTapToFocus(
        pointFactory: MeteringPointFactory,
        screenX: Float,
        screenY: Float,
        normalizedX: Float? = null,
        normalizedY: Float? = null
    ) {
        val point = pointFactory.createPoint(screenX, screenY)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()

        activeCameraControl?.startFocusAndMetering(action)

        _uiState.update {
            val normFocus = if (normalizedX != null && normalizedY != null) {
                Offset(normalizedX.coerceIn(0f, 1f), normalizedY.coerceIn(0f, 1f))
            } else {
                it.portraitFocusPointNormalized
            }
            it.copy(
                focusPoint = Offset(screenX, screenY),
                showFocusRing = true,
                portraitFocusPointNormalized = normFocus
            )
        }

        focusResetJob?.cancel()
        focusResetJob = viewModelScope.launch {
            delay(3500)
            _uiState.update { it.copy(showFocusRing = false) }
        }
    }

    fun keepFocusRingActive() {
        focusResetJob?.cancel()
        focusResetJob = viewModelScope.launch {
            delay(3000)
            _uiState.update { it.copy(showFocusRing = false) }
        }
    }

    fun toggleSettings() {
        if (!_uiState.value.isSettingsOpen) {
            dismissScreenFlash()
        }
        _uiState.update {
            val opening = !it.isSettingsOpen
            it.copy(
                isSettingsOpen = opening,
                activeCameraPanel = if (opening) com.example.model.ActiveCameraPanel.NONE else it.activeCameraPanel,
                isFilterSelectorOpen = if (opening) false else it.isFilterSelectorOpen,
                isQuickSettingsOpen = if (opening) false else it.isQuickSettingsOpen,
                isVideoSettingsPanelOpen = if (opening) false else it.isVideoSettingsPanelOpen,
                activeProTab = if (opening) com.example.model.ProControlTab.NONE else it.activeProTab
            )
        }
    }

    fun closeSettings() {
        _uiState.update { it.copy(isSettingsOpen = false) }
    }

    fun onShutterTrigger(mediaCaptureManager: MediaCaptureManager) {
        if (_uiState.value.cameraMode == CameraMode.VIDEO) {
            if (!_uiState.value.isVideoRecordingSupported) {
                _uiState.update { it.copy(userMessage = "Video recording is unavailable on this sensor") }
                return
            }
            if (_uiState.value.isRecordingVideo) {
                stopVideoRecording()
            } else {
                startVideoRecording(mediaCaptureManager)
            }
            return
        }

        // Photo Mode: check timer
        val timer = _uiState.value.selectedTimer
        val timerSeconds = timer.effectiveSeconds(_uiState.value.customTimerSeconds)
        if (timer != CaptureTimer.OFF && timerSeconds > 0) {
            startCountdownCapture(timerSeconds, mediaCaptureManager)
        } else {
            dispatchPhotoCapture(mediaCaptureManager)
        }
    }

    fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        dismissScreenFlash()
        _uiState.update { it.copy(countdownSecondsLeft = null) }
    }

    private fun startCountdownCapture(seconds: Int, mediaCaptureManager: MediaCaptureManager) {
        cancelCountdown()
        countdownJob = viewModelScope.launch {
            for (s in seconds downTo 1) {
                _uiState.update { it.copy(countdownSecondsLeft = s) }
                if (_uiState.value.isShutterSoundEnabled) {
                    try {
                        if (mediaSound == null) mediaSound = MediaActionSound()
                        mediaSound?.play(MediaActionSound.FOCUS_COMPLETE)
                    } catch (_: Exception) {}
                }
                delay(1000)
            }
            _uiState.update { it.copy(countdownSecondsLeft = null) }
            dispatchPhotoCapture(mediaCaptureManager)
        }
    }

    private fun dispatchPhotoCapture(mediaCaptureManager: MediaCaptureManager) {
        if (_uiState.value.isCapturing || _uiState.value.isNightCapturing || _uiState.value.isRecordingVideo) return

        // Portrait Mode always routes through CameraX still capture + Portrait bokeh pipeline
        if (_uiState.value.cameraMode == CameraMode.PORTRAIT) {
            capturePhoto(mediaCaptureManager)
            return
        }

        // Night Mode routes through Hybrid Night Pipeline (ExtensionMode.NIGHT or Multi-Frame Stack)
        if (_uiState.value.cameraMode == CameraMode.NIGHT) {
            captureNightPhoto(mediaCaptureManager)
            return
        }

        val isUhrRequested = _uiState.value.photoResolution == PhotoResolution.ULTRA_RES
        val isRawRequested = _uiState.value.rawCaptureMode != com.example.model.RawCaptureMode.OFF
        val isProMode = _uiState.value.cameraMode == CameraMode.PRO
        val isProManual = isProMode && !_uiState.value.proSettings.isAuto
        val isFront = (_uiState.value.lensFacing == CameraSelector.LENS_FACING_FRONT)
        val cap = capabilityDetector.getCapabilityForLens(isFront)

        if (cap != null) {
            if ((isUhrRequested && _uiState.value.isUhrSupportedOnCurrentLens) ||
                (isRawRequested && _uiState.value.isRawSupportedOnCurrentLens) ||
                isProManual ||
                (isProMode && isRawRequested)) {
                captureUltraHighResolutionPhoto(mediaCaptureManager, cap)
                return
            }
        }
        capturePhoto(mediaCaptureManager)
    }

    private fun captureNightPhoto(mediaCaptureManager: MediaCaptureManager) {
        val imageCapture = activeImageCapture
        if (imageCapture == null) {
            _uiState.update { it.copy(isCapturing = false, isNightCapturing = false, userMessage = "Camera not ready yet") }
            return
        }
        if (_uiState.value.isCapturing || _uiState.value.isNightCapturing || _uiState.value.isRecordingVideo) return

        val holdSteadyText = appContext.getString(com.example.R.string.night_hold_steady)
        val processingText = appContext.getString(com.example.R.string.night_processing)
        val snapshot = _uiState.value
        val isFront = snapshot.lensFacing == CameraSelector.LENS_FACING_FRONT

        triggerHapticFeedback()
        syncImageCaptureFlashMode(imageCapture)

        if (snapshot.isNightExtensionSupported) {
            if (snapshot.isShutterSoundEnabled) {
                try {
                    if (mediaSound == null) mediaSound = MediaActionSound()
                    mediaSound?.play(MediaActionSound.SHUTTER_CLICK)
                } catch (_: Exception) {}
            }

            _uiState.update {
                it.copy(
                    isCapturing = true,
                    isNightCapturing = true,
                    nightCaptureProgress = 0.08f,
                    nightCaptureStatusText = holdSteadyText
                )
            }

            val totalAnimMs = (snapshot.nightEstimatedDurationSeconds * 1000f).toLong().coerceIn(1200L, 6000L)
            nightCaptureJob?.cancel()
            nightCaptureJob = viewModelScope.launch {
                val steps = 20
                val stepMs = (totalAnimMs / steps).coerceAtLeast(45L)
                for (i in 1..steps) {
                    delay(stepMs)
                    if (!_uiState.value.isNightCapturing) break
                    val frac = 0.08f + (i.toFloat() / steps.toFloat()) * 0.84f
                    _uiState.update {
                        it.copy(
                            nightCaptureProgress = frac,
                            nightCaptureStatusText = if (frac >= 0.65f) processingText else holdSteadyText
                        )
                    }
                }
            }

            var captureStartedCounted = false
            mediaCaptureManager.capturePhoto(
                imageCapture = imageCapture,
                aspectRatio = snapshot.aspectRatio,
                fullScreenAspectRatio = snapshot.fullScreenAspectRatio,
                filter = snapshot.selectedFilter,
                isFrontCamera = isFront,
                mirrorSelfie = snapshot.isSelfieMirrorEnabled,
                watermarkConfig = snapshot.watermarkConfig,
                isSaveLocation = snapshot.isSaveLocationEnabled,
                activeFocalLengthMm = snapshot.activeFocalLengthMm,
                privateOriginalStorage = privateOriginalStorage,
                retainCleanOriginal = snapshot.isPostCaptureWatermarkEditingEnabled,
                watermarkCacheLimitBytes = snapshot.watermarkCacheLimit.limitBytes,
                onEvictionNotice = { eviction -> handlePrivateStorageEvictionNotice(eviction) },
                onCaptureStarted = {
                    captureStartedCounted = true
                    _uiState.update {
                        it.copy(
                            inFlightCapturesCount = it.inFlightCapturesCount + 1,
                            isSavingMedia = true,
                            shutterFlashTrigger = System.currentTimeMillis()
                        )
                    }
                },
                onShutterReady = {
                    _uiState.update {
                        it.copy(
                            nightCaptureStatusText = processingText,
                            nightCaptureProgress = maxOf(it.nightCaptureProgress, 0.72f)
                        )
                    }
                },
                onPhotoSaved = { uri, width, height, bytes ->
                    nightCaptureJob?.cancel()
                    dismissScreenFlash()
                    val cap = capabilityDetector.getCapabilityForLens(isFront)
                    val actualSize = if (width > 0 && height > 0) {
                        android.util.Size(width, height)
                    } else {
                        _uiState.value.actualConfiguredResolution ?: android.util.Size(0, 0)
                    }
                    val actualMp = (actualSize.width.toDouble() * actualSize.height.toDouble()) / 1_000_000.0
                    if (cap != null) {
                        capabilityDetector.recordResolutionCapture(
                            cameraId = cap.cameraId,
                            lensFacing = if (isFront) "FRONT" else "BACK",
                            requestedResolution = _uiState.value.photoResolution,
                            targetDimensions = _uiState.value.targetCaptureSize,
                            actualDimensions = actualSize,
                            fileSizeBytes = bytes,
                            captureEngine = "CameraX ExtensionMode.NIGHT",
                            isFallback = false
                        )
                    }
                    _uiState.update { cur ->
                        val remaining = if (captureStartedCounted) maxOf(0, cur.inFlightCapturesCount - 1) else cur.inFlightCapturesCount
                        cur.copy(
                            isCapturing = false,
                            isNightCapturing = false,
                            nightCaptureProgress = 0f,
                            nightCaptureStatusText = "",
                            lastCapturedUri = uri,
                            inFlightCapturesCount = remaining,
                            isSavingMedia = remaining > 0
                        )
                    }
                    refreshPrivateStorageStats()
                    scheduleGalleryRefresh(mediaCaptureManager, actualSize, actualMp)
                },
                onError = { exc ->
                    nightCaptureJob?.cancel()
                    dismissScreenFlash()
                    _uiState.update { cur ->
                        val remaining = if (captureStartedCounted) maxOf(0, cur.inFlightCapturesCount - 1) else cur.inFlightCapturesCount
                        cur.copy(
                            isCapturing = false,
                            isNightCapturing = false,
                            nightCaptureProgress = 0f,
                            nightCaptureStatusText = "",
                            inFlightCapturesCount = remaining,
                            isSavingMedia = remaining > 0,
                            userMessage = "Night capture failed: ${exc.localizedMessage ?: "Unknown error"}"
                        )
                    }
                }
            )
            return
        }

        // Fallback: Custom Multi-Frame Burst Stacking Pipeline
        _uiState.update {
            it.copy(
                isCapturing = true,
                isNightCapturing = true,
                inFlightCapturesCount = it.inFlightCapturesCount + 1,
                isSavingMedia = true,
                nightCaptureProgress = 0.04f,
                nightCaptureStatusText = holdSteadyText
            )
        }

        nightCaptureJob?.cancel()
        nightCaptureJob = viewModelScope.launch {
            var countCleared = false
            try {
                val saveResult = nightPipelineManager.captureAndStackNightBurst(
                    imageCapture = imageCapture,
                    cameraControl = activeCameraControl,
                    cameraInfo = activeCameraInfo,
                    exposureMode = snapshot.nightExposureMode,
                    aspectRatio = snapshot.aspectRatio,
                    fullScreenAspectRatio = snapshot.fullScreenAspectRatio,
                    filter = snapshot.selectedFilter,
                    isFrontCamera = isFront,
                    mirrorSelfie = snapshot.isSelfieMirrorEnabled,
                    watermarkConfig = snapshot.watermarkConfig,
                    isSaveLocation = snapshot.isSaveLocationEnabled,
                    activeFocalLengthMm = snapshot.activeFocalLengthMm,
                    privateOriginalStorage = privateOriginalStorage,
                    retainCleanOriginal = snapshot.isPostCaptureWatermarkEditingEnabled,
                    watermarkCacheLimitBytes = snapshot.watermarkCacheLimit.limitBytes,
                    onEvictionNotice = { eviction -> handlePrivateStorageEvictionNotice(eviction) },
                    onBurstShutterTick = {
                        if (_uiState.value.isShutterSoundEnabled) {
                            try {
                                if (mediaSound == null) mediaSound = MediaActionSound()
                                mediaSound?.play(MediaActionSound.SHUTTER_CLICK)
                            } catch (_: Exception) {}
                        }
                        _uiState.update { it.copy(shutterFlashTrigger = System.currentTimeMillis()) }
                    },
                    onProgress = { progress, isProcessingStage ->
                        _uiState.update {
                            it.copy(
                                nightCaptureProgress = progress.coerceIn(0f, 1f),
                                nightCaptureStatusText = if (isProcessingStage) processingText else holdSteadyText
                            )
                        }
                    }
                )

                dismissScreenFlash()
                val actualSize = android.util.Size(saveResult.width, saveResult.height)
                val actualMp = (saveResult.width.toDouble() * saveResult.height.toDouble()) / 1_000_000.0
                val cap = capabilityDetector.getCapabilityForLens(isFront)
                if (cap != null) {
                    capabilityDetector.recordResolutionCapture(
                        cameraId = cap.cameraId,
                        lensFacing = if (isFront) "FRONT" else "BACK",
                        requestedResolution = _uiState.value.photoResolution,
                        targetDimensions = _uiState.value.targetCaptureSize,
                        actualDimensions = actualSize,
                        fileSizeBytes = saveResult.sizeBytes,
                        captureEngine = "Multi-Frame Night Stack (${saveResult.frameCount} frames)",
                        isFallback = false
                    )
                }

                countCleared = true
                _uiState.update { cur ->
                    val remaining = maxOf(0, cur.inFlightCapturesCount - 1)
                    cur.copy(
                        isCapturing = false,
                        isNightCapturing = false,
                        nightCaptureProgress = 0f,
                        nightCaptureStatusText = "",
                        lastCapturedUri = saveResult.uri,
                        inFlightCapturesCount = remaining,
                        isSavingMedia = remaining > 0
                    )
                }
                refreshPrivateStorageStats()
                scheduleGalleryRefresh(mediaCaptureManager, actualSize, actualMp)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                dismissScreenFlash()
                countCleared = true
                _uiState.update { cur ->
                    val remaining = maxOf(0, cur.inFlightCapturesCount - 1)
                    cur.copy(
                        isCapturing = false,
                        isNightCapturing = false,
                        nightCaptureProgress = 0f,
                        nightCaptureStatusText = "",
                        inFlightCapturesCount = remaining,
                        isSavingMedia = remaining > 0,
                        userMessage = "Night capture failed: ${t.localizedMessage ?: "Unknown error"}"
                    )
                }
            } finally {
                if (!countCleared) {
                    _uiState.update { cur ->
                        val remaining = maxOf(0, cur.inFlightCapturesCount - 1)
                        cur.copy(
                            isCapturing = false,
                            isNightCapturing = false,
                            nightCaptureProgress = 0f,
                            nightCaptureStatusText = "",
                            inFlightCapturesCount = remaining,
                            isSavingMedia = remaining > 0
                        )
                    }
                }
            }
        }
    }

    private fun captureUltraHighResolutionPhoto(
        mediaCaptureManager: MediaCaptureManager,
        cap: com.example.data.CameraSensorCapability
    ) {
        if (_uiState.value.isCapturing || _uiState.value.isRecordingVideo) return

        val isUhrMode = _uiState.value.photoResolution == PhotoResolution.ULTRA_RES && _uiState.value.isUhrSupportedOnCurrentLens
        val rawSize = if (isUhrMode) {
            cap.maxResolutionRawSizes.firstOrNull() ?: cap.standardRawSizes.firstOrNull()
        } else {
            cap.standardRawSizes.firstOrNull() ?: cap.maxResolutionRawSizes.firstOrNull()
        }
        val targetSize = _uiState.value.targetCaptureSize?.takeIf { it.width > 0 && it.height > 0 }
            ?: cap.recommendedUhrSize
            ?: cap.highestGenuineJpegSize.takeIf { it.width > 0 && it.height > 0 }
            ?: cap.standardJpegSizes.firstOrNull()
            ?: if (_uiState.value.rawCaptureMode == RawCaptureMode.DNG_ONLY) rawSize else null

        if (targetSize == null) {
            _uiState.update {
                it.copy(
                    isCapturing = false,
                    userMessage = "No valid capture resolution available on this sensor"
                )
            }
            return
        }

        // Atomically reserve capture slot before unbind/Camera2 open to prevent double-tap races
        _uiState.update { it.copy(isCapturing = true) }
        triggerHapticFeedback()

        if (_uiState.value.isShutterSoundEnabled) {
            try {
                if (mediaSound == null) mediaSound = MediaActionSound()
                mediaSound?.play(MediaActionSound.SHUTTER_CLICK)
            } catch (_: Exception) {}
        }

        val isFront = (_uiState.value.lensFacing == CameraSelector.LENS_FACING_FRONT)
        val oemAdapter = oemRegistry.resolveActiveAdapter(appContext, _uiState.value.oemEnhancementMode)
        val currentProSettings = if (_uiState.value.cameraMode == CameraMode.PRO) _uiState.value.proSettings else null
        val boundCamInfo = activeCameraInfo
        val provider = activeCameraProvider
        activeCamera = null
        activeCameraControl = null
        activeCameraInfo = null
        activeImageCapture = null
        activeVideoCapture = null

        // Cleanly unbind CameraX session and wait for CameraDevice closure so Camera2 does not
        // preempt CameraX while it is still in CLOSING state (avoids ERROR_CAMERA_IN_USE & stopRepeating failures)
        try {
            provider?.unbindAll()
        } catch (_: Exception) {}

        var captureStartedCounted = false
        val hasReboundViewfinder = java.util.concurrent.atomic.AtomicBoolean(false)
        fun triggerViewfinderRebindOnce(clearCapturing: Boolean) {
            if (hasReboundViewfinder.compareAndSet(false, true)) {
                _uiState.update {
                    it.copy(
                        isCapturing = if (clearCapturing) false else it.isCapturing,
                        viewfinderRebindKey = it.viewfinderRebindKey + 1
                    )
                }
            }
        }

        val useScreenFlash = shouldUseScreenFlashForCapture()

        viewModelScope.launch {
            if (useScreenFlash) {
                _uiState.update { it.copy(isScreenFlashOverlayVisible = true) }
                delay(260L)
            }
            if (boundCamInfo != null) {
                awaitCameraInfoClosed(boundCamInfo)
            }
            ultraResCaptureManager.captureUltraHighResolution(
                cameraId = cap.cameraId,
                targetSize = targetSize,
                rawSize = rawSize,
                rawMode = _uiState.value.rawCaptureMode,
                filter = _uiState.value.selectedFilter,
                mirrorSelfie = _uiState.value.isSelfieMirrorEnabled && isFront,
                watermarkConfig = _uiState.value.watermarkConfig,
                oemAdapter = oemAdapter,
                isHdrEnabled = _uiState.value.isHdrEnabled,
                proSettings = currentProSettings,
                isSaveLocation = _uiState.value.isSaveLocationEnabled,
                targetRotation = currentTargetRotation,
                activeFocalLengthMm = _uiState.value.activeFocalLengthMm,
                privateOriginalStorage = privateOriginalStorage,
                retainCleanOriginal = _uiState.value.isPostCaptureWatermarkEditingEnabled,
                watermarkCacheLimitBytes = _uiState.value.watermarkCacheLimit.limitBytes,
                aspectRatio = _uiState.value.aspectRatio,
                fullScreenAspectRatio = _uiState.value.fullScreenAspectRatio,
                onEvictionNotice = { eviction -> handlePrivateStorageEvictionNotice(eviction) },
                onCaptureStarted = {
                    captureStartedCounted = true
                    _uiState.update {
                        it.copy(
                            isCapturing = true,
                            inFlightCapturesCount = it.inFlightCapturesCount + 1,
                            isSavingMedia = true,
                            shutterFlashTrigger = System.currentTimeMillis()
                        )
                    }
                },
                onShutterReady = {
                    dismissScreenFlash()
                    // Sensor closed and released! Rebind viewfinder cleanly and unblock shutter
                    triggerViewfinderRebindOnce(clearCapturing = true)
                },
                onSuccess = { mainUri, rawUri ->
                    dismissScreenFlash()
                    val (actualW, actualH, actualBytes) = mediaCaptureManager.getMediaDimensionsAndSize(mainUri)
                    val actualSize = if (actualW > 0 && actualH > 0) android.util.Size(actualW, actualH) else targetSize
                    val actualMp = (actualSize.width.toDouble() * actualSize.height.toDouble()) / 1_000_000.0
                    val isFallback = targetSize.width != actualSize.width || targetSize.height != actualSize.height

                    capabilityDetector.recordResolutionCapture(
                        cameraId = cap.cameraId,
                        lensFacing = if (isFront) "FRONT" else "BACK",
                        requestedResolution = _uiState.value.photoResolution,
                        targetDimensions = targetSize,
                        actualDimensions = actualSize,
                        fileSizeBytes = actualBytes,
                        captureEngine = "Camera2 High-Res Stall / UHR",
                        isFallback = isFallback
                    )
                    capabilityDetector.recordCaptureTestResult(
                        cameraId = cap.cameraId,
                        isSuccess = true,
                        details = "Verified capture: ${actualSize.width} × ${actualSize.height} (${String.format(java.util.Locale.US, "%.1f", actualMp)} MP)" + if (rawUri != null) " + RAW DNG" else ""
                    )

                    _uiState.update { cur ->
                        val remaining = if (captureStartedCounted) maxOf(0, cur.inFlightCapturesCount - 1) else cur.inFlightCapturesCount
                        cur.copy(
                            isCapturing = false,
                            lastCapturedUri = mainUri,
                            inFlightCapturesCount = remaining,
                            isSavingMedia = remaining > 0
                        )
                    }
                    refreshPrivateStorageStats()
                    scheduleGalleryRefresh(mediaCaptureManager, actualSize, actualMp, rawUri)
                },
                onError = { errorMsg ->
                    dismissScreenFlash()
                    capabilityDetector.recordCaptureTestResult(
                        cameraId = cap.cameraId,
                        isSuccess = false,
                        details = errorMsg
                    )
                    android.util.Log.w("CameraViewModel", "Capture error: $errorMsg")
                    val shouldRebind = hasReboundViewfinder.compareAndSet(false, true)
                    _uiState.update { cur ->
                        val remaining = if (captureStartedCounted) maxOf(0, cur.inFlightCapturesCount - 1) else cur.inFlightCapturesCount
                        cur.copy(
                            isCapturing = false,
                            inFlightCapturesCount = remaining,
                            isSavingMedia = remaining > 0,
                            userMessage = "Capture error: $errorMsg",
                            viewfinderRebindKey = if (shouldRebind) cur.viewfinderRebindKey + 1 else cur.viewfinderRebindKey
                        )
                    }
                }
            )
        }
    }

    private suspend fun awaitCameraInfoClosed(cameraInfo: CameraInfo) {
        com.example.ui.components.awaitCameraStateClosed(cameraInfo.cameraState, timeoutMs = 750L)
    }

    fun capturePhoto(mediaCaptureManager: MediaCaptureManager) {
        val imageCapture = activeImageCapture
        if (imageCapture == null) {
            _uiState.update { it.copy(isCapturing = false, userMessage = "Camera not ready yet") }
            return
        }
        if (_uiState.value.isCapturing || _uiState.value.isRecordingVideo) return

        // Atomically reserve capture slot before dispatching async capture
        _uiState.update { it.copy(isCapturing = true) }

        val targetAspectRatio = _uiState.value.aspectRatio
        val fullScreenAspectRatio = _uiState.value.fullScreenAspectRatio
        val currentFilter = _uiState.value.selectedFilter
        val isFront = (_uiState.value.lensFacing == CameraSelector.LENS_FACING_FRONT)
        val mirrorSelfie = _uiState.value.isSelfieMirrorEnabled
        val watermarkConfig = _uiState.value.watermarkConfig
        val isSaveLocation = _uiState.value.isSaveLocationEnabled

        triggerHapticFeedback()

        if (_uiState.value.isShutterSoundEnabled) {
            try {
                if (mediaSound == null) mediaSound = MediaActionSound()
                mediaSound?.play(MediaActionSound.SHUTTER_CLICK)
            } catch (_: Exception) {}
        }

        // Ensure ImageCapture carries latest hardware or Screen Flash mode before capture begins
        syncImageCaptureFlashMode(imageCapture)

        val isPortraitMode = _uiState.value.cameraMode == CameraMode.PORTRAIT
        val isOemBokeh = isPortraitMode && isOemBokehExtensionActive
        val portraitBlur = _uiState.value.portraitBlurIntensity
        val portraitFocus = _uiState.value.portraitFocusPointNormalized
        val portraitCap = if (isPortraitMode && !isOemBokeh) capabilityDetector.getCapabilityForLens(isFront) else null
        val hwDepthProvider: (() -> com.example.portrait.HardwareDepthFrame?)? =
            if (isPortraitMode && !isOemBokeh && portraitCap != null && portraitCap.isDepthOutputSupported && portraitCap.depth16Sizes.isNotEmpty()) {
                {
                    captureHardwareDepthWithTemporaryUnbind(
                        cap = portraitCap,
                        targetRotation = currentTargetRotation,
                        mirrorHorizontal = isFront && mirrorSelfie
                    )
                }
            } else {
                null
            }

        var captureStartedCounted = false

        mediaCaptureManager.capturePhoto(
            imageCapture = imageCapture,
            aspectRatio = targetAspectRatio,
            fullScreenAspectRatio = fullScreenAspectRatio,
            filter = currentFilter,
            isFrontCamera = isFront,
            mirrorSelfie = mirrorSelfie,
            watermarkConfig = watermarkConfig,
            isSaveLocation = isSaveLocation,
            activeFocalLengthMm = _uiState.value.activeFocalLengthMm,
            privateOriginalStorage = privateOriginalStorage,
            retainCleanOriginal = _uiState.value.isPostCaptureWatermarkEditingEnabled,
            watermarkCacheLimitBytes = _uiState.value.watermarkCacheLimit.limitBytes,
            onEvictionNotice = { eviction -> handlePrivateStorageEvictionNotice(eviction) },
            isPortraitMode = isPortraitMode,
            portraitBlurIntensity = portraitBlur,
            portraitFocusX = portraitFocus?.x,
            portraitFocusY = portraitFocus?.y,
            portraitDepthFrameProvider = hwDepthProvider,
            portraitSidecarStorage = if (isPortraitMode) portraitSidecarStorage else null,
            portraitPipelineManager = if (isPortraitMode) portraitPipelineManager else null,
            isPortraitOemBokehCapture = isOemBokeh,
            onPortraitProcessed = { source, subjectCount, captureTier ->
                _uiState.update {
                    it.copy(
                        portraitDepthSource = source,
                        portraitSubjectCount = subjectCount,
                        isPortraitSubjectDetected = subjectCount > 0,
                        portraitCaptureTier = captureTier,
                        portraitSidecarRevision = it.portraitSidecarRevision + 1L
                    )
                }
            },
            onCaptureStarted = {
                captureStartedCounted = true
                _uiState.update {
                    it.copy(
                        isCapturing = true,
                        inFlightCapturesCount = it.inFlightCapturesCount + 1,
                        isSavingMedia = true,
                        shutterFlashTrigger = System.currentTimeMillis()
                    )
                }
            },
            onShutterReady = {
                // Asynchronous Capture Pipeline: Camera sensor & capture pipeline is ready for another capture!
                _uiState.update {
                    it.copy(
                        isCapturing = false
                    )
                }
            },
            onPhotoSaved = { uri, width, height, bytes ->
                dismissScreenFlash()
                val isFrontLens = (_uiState.value.lensFacing == CameraSelector.LENS_FACING_FRONT)
                val cap = capabilityDetector.getCapabilityForLens(isFrontLens)
                val targetSize = _uiState.value.targetCaptureSize?.takeIf { it.width > 0 && it.height > 0 }
                val actualSize = if (width > 0 && height > 0) android.util.Size(width, height) else (targetSize ?: _uiState.value.actualConfiguredResolution ?: android.util.Size(0, 0))
                val isFallback = targetSize != null && actualSize.width > 0 && (targetSize.width != actualSize.width || targetSize.height != actualSize.height)
                val actualMp = (actualSize.width.toDouble() * actualSize.height.toDouble()) / 1_000_000.0

                if (cap != null) {
                    capabilityDetector.recordResolutionCapture(
                        cameraId = cap.cameraId,
                        lensFacing = if (isFrontLens) "FRONT" else "BACK",
                        requestedResolution = _uiState.value.photoResolution,
                        targetDimensions = targetSize,
                        actualDimensions = actualSize,
                        fileSizeBytes = bytes,
                        captureEngine = if (isPortraitMode) "CameraX Portrait" else "CameraX ImageCapture",
                        isFallback = isFallback
                    )
                }

                _uiState.update { cur ->
                    val remaining = if (captureStartedCounted) maxOf(0, cur.inFlightCapturesCount - 1) else cur.inFlightCapturesCount
                    cur.copy(
                        isCapturing = false,
                        lastCapturedUri = uri,
                        inFlightCapturesCount = remaining,
                        isSavingMedia = remaining > 0,
                        portraitSidecarRevision = if (isPortraitMode) cur.portraitSidecarRevision + 1L else cur.portraitSidecarRevision
                    )
                }
                refreshPrivateStorageStats()
                scheduleGalleryRefresh(mediaCaptureManager, actualSize, actualMp)
            },
            onError = { exc ->
                dismissScreenFlash()
                _uiState.update { cur ->
                    val remaining = if (captureStartedCounted) maxOf(0, cur.inFlightCapturesCount - 1) else cur.inFlightCapturesCount
                    cur.copy(
                        isCapturing = false,
                        inFlightCapturesCount = remaining,
                        isSavingMedia = remaining > 0,
                        userMessage = "Capture failed: ${exc.localizedMessage ?: "Unknown error"}"
                    )
                }
            }
        )
    }

    private fun captureHardwareDepthWithTemporaryUnbind(
        cap: com.example.data.CameraSensorCapability,
        targetRotation: Int,
        mirrorHorizontal: Boolean
    ): com.example.portrait.HardwareDepthFrame? {
        val provider = activeCameraProvider ?: return null
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val unbindLatch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            try {
                provider.unbindAll()
            } catch (_: Throwable) {
            } finally {
                unbindLatch.countDown()
            }
        }
        try {
            unbindLatch.await(400L, java.util.concurrent.TimeUnit.MILLISECONDS)
            Thread.sleep(160L)
        } catch (_: Throwable) {}

        val rotationDeg = when (targetRotation) {
            android.view.Surface.ROTATION_90 -> 270
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 90
            else -> 0
        }
        val frame = try {
            com.example.portrait.HardwareDepthProcessor.captureHardwareDepthSnapshot(
                context = appContext,
                capability = cap,
                targetRotationDegrees = rotationDeg,
                mirrorHorizontal = mirrorHorizontal,
                timeoutMs = 950L
            )
        } catch (_: Throwable) {
            null
        }
        mainHandler.post {
            _uiState.update { it.copy(viewfinderRebindKey = it.viewfinderRebindKey + 1L) }
        }
        return frame
    }

    private fun scheduleGalleryRefresh(
        mediaCaptureManager: MediaCaptureManager,
        actualSize: android.util.Size,
        actualMp: Double,
        rawUri: Uri? = null
    ) {
        galleryRefreshJob?.cancel()
        galleryRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            delay(120)
            val allMedia = mediaCaptureManager.getAllMedia()
            val resInfo = "${actualSize.width} × ${actualSize.height} (${String.format(java.util.Locale.US, "%.1f", actualMp)} MP)"
            val isDngOnly = _uiState.value.rawCaptureMode == RawCaptureMode.DNG_ONLY &&
                _uiState.value.cameraMode != CameraMode.PORTRAIT
            val msg = when {
                isDngOnly -> "Captured RAW DNG ($resInfo)"
                rawUri != null -> "Captured $resInfo + RAW DNG"
                else -> "Captured $resInfo"
            }
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        galleryItems = allMedia,
                        userMessage = msg
                    )
                }
            }
        }
    }

    fun startVideoRecording(mediaCaptureManager: MediaCaptureManager) {
        val videoCapture = activeVideoCapture
        if (videoCapture == null) {
            _uiState.update { it.copy(userMessage = "Video recording unavailable on this camera or emulator") }
            return
        }
        if (_uiState.value.isRecordingVideo) return
        if (!mediaCaptureManager.hasSufficientStorage()) {
            _uiState.update { it.copy(userMessage = "Insufficient storage space for video recording") }
            return
        }
        dismissActivePanel()
        triggerHapticFeedback()

        try {
            isRecordingStarted = false
            activeRecording = mediaCaptureManager.startVideoRecording(
                videoCapture = videoCapture,
                isAudioEnabled = _uiState.value.isAudioEnabled,
                onEvent = { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            isRecordingStarted = true
                            _uiState.update {
                                it.copy(
                                    isRecordingVideo = true,
                                    isRecordingPaused = false,
                                    recordingDurationSeconds = 0L
                                )
                            }
                            recordingTimerJob?.cancel()
                            recordingTimerJob = viewModelScope.launch {
                                while (true) {
                                    delay(1000)
                                    if (!_uiState.value.isRecordingPaused) {
                                        _uiState.update { it.copy(recordingDurationSeconds = it.recordingDurationSeconds + 1) }
                                    }
                                }
                            }
                        }
                        is VideoRecordEvent.Pause -> {
                            _uiState.update { it.copy(isRecordingPaused = true) }
                        }
                        is VideoRecordEvent.Resume -> {
                            _uiState.update { it.copy(isRecordingPaused = false) }
                        }
                        is VideoRecordEvent.Finalize -> {
                            isRecordingStarted = false
                            recordingTimerJob?.cancel()
                            _uiState.update {
                                it.copy(
                                    isRecordingVideo = false,
                                    isRecordingPaused = false,
                                    recordingDurationSeconds = 0L
                                )
                            }
                            if (!event.hasError()) {
                                val uri = event.outputResults.outputUri
                                viewModelScope.launch(Dispatchers.IO) {
                                    // 1. Immediately decode and pre-warm thumbnail cache from the saved video URI
                                    try {
                                        VideoThumbnailUtil.loadThumbnail(appContext, uri)
                                    } catch (_: Throwable) {}

                                    // 2. Query MediaStore, retry if delayed indexing
                                    var allMedia = mediaCaptureManager.getAllMedia()
                                    val matchesUri = { item: MediaItem ->
                                        item.uri == uri ||
                                        (item.uri.lastPathSegment != null && item.uri.lastPathSegment == uri.lastPathSegment)
                                    }
                                    if (allMedia.none(matchesUri)) {
                                        delay(150)
                                        allMedia = mediaCaptureManager.getAllMedia()
                                    }

                                    val finalMedia = if (allMedia.none(matchesUri) && uri != Uri.EMPTY) {
                                        listOf(
                                            MediaItem(
                                                id = uri.lastPathSegment?.toLongOrNull() ?: System.currentTimeMillis(),
                                                uri = uri,
                                                isVideo = true,
                                                displayName = "VID_${System.currentTimeMillis()}.mp4",
                                                dateAdded = System.currentTimeMillis() / 1000,
                                                mimeType = "video/mp4",
                                                sizeBytes = 0L,
                                                width = 0,
                                                height = 0,
                                                durationMs = 0L
                                            )
                                        ) + allMedia
                                    } else {
                                        allMedia
                                    }

                                    withContext(Dispatchers.Main) {
                                        _uiState.update {
                                            it.copy(
                                                lastCapturedUri = uri,
                                                galleryItems = finalMedia,
                                                userMessage = "Video saved to gallery"
                                            )
                                        }
                                    }
                                }
                            } else {
                                val errorDetail = when (event.error) {
                                    VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> "Storage limit reached"
                                    VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "Insufficient device storage"
                                    VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "Encoding failed"
                                    VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "No video data encoded"
                                    VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "Camera stream inactive"
                                    else -> event.cause?.localizedMessage ?: "Encoder issue (${event.error})"
                                }
                                _uiState.update {
                                    it.copy(userMessage = "Video stopped: $errorDetail")
                                }
                            }
                            activeRecording = null
                        }
                    }
                }
            )
        } catch (e: Exception) {
            isRecordingStarted = false
            recordingTimerJob?.cancel()
            _uiState.update {
                it.copy(
                    isRecordingVideo = false,
                    isRecordingPaused = false,
                    recordingDurationSeconds = 0L,
                    userMessage = "Recording failed: ${e.localizedMessage ?: "Hardware encoder unavailable"}"
                )
            }
            activeRecording = null
        }
    }

    fun pauseVideoRecording() {
        val recording = activeRecording ?: return
        try {
            recording.pause()
            _uiState.update { it.copy(isRecordingPaused = true) }
        } catch (e: Exception) {
            android.util.Log.w("CameraViewModel", "Error pausing video recording: ${e.message}")
        }
    }

    fun resumeVideoRecording() {
        val recording = activeRecording ?: return
        try {
            recording.resume()
            _uiState.update { it.copy(isRecordingPaused = false) }
        } catch (e: Exception) {
            android.util.Log.w("CameraViewModel", "Error resuming video recording: ${e.message}")
        }
    }

    fun togglePauseResumeVideoRecording() {
        if (!_uiState.value.isRecordingVideo) return
        triggerHapticFeedback()
        if (_uiState.value.isRecordingPaused) {
            resumeVideoRecording()
        } else {
            pauseVideoRecording()
        }
    }

    fun stopVideoRecording() {
        val recording = activeRecording
        recordingTimerJob?.cancel()
        triggerHapticFeedback()
        if (recording != null) {
            try {
                if (isRecordingStarted) {
                    recording.stop()
                } else {
                    recording.close()
                }
            } catch (_: Throwable) {
                try { recording.close() } catch (_: Throwable) {}
            }
        }
        activeRecording = null
        isRecordingStarted = false
        _uiState.update {
            it.copy(
                isRecordingVideo = false,
                isRecordingPaused = false,
                recordingDurationSeconds = 0L
            )
        }
    }

    fun toggleAudioEnabled() {
        val next = !_uiState.value.isAudioEnabled
        preferencesRepo.saveVideoAudioEnabled(next)
        triggerHapticFeedback()
        _uiState.update {
            it.copy(
                isAudioEnabled = next,
                userMessage = if (next) "Microphone ON" else "Microphone MUTED"
            )
        }
    }

    fun setAudioEnabled(enabled: Boolean) {
        preferencesRepo.saveVideoAudioEnabled(enabled)
        _uiState.update { it.copy(isAudioEnabled = enabled) }
    }

    fun loadGallery(mediaCaptureManager: MediaCaptureManager) {
        viewModelScope.launch(Dispatchers.IO) {
            val items = mediaCaptureManager.getAllMedia()
            // Pre-warm thumbnail for the most recent video if present
            items.firstOrNull { it.isVideo }?.let { vid ->
                try {
                    VideoThumbnailUtil.loadThumbnail(appContext, vid.uri)
                } catch (_: Throwable) {}
            }
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        galleryItems = items,
                        lastCapturedUri = items.firstOrNull()?.uri ?: it.lastCapturedUri
                    )
                }
            }
        }
    }

    fun openGallery(mediaCaptureManager: MediaCaptureManager, targetUri: Uri? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val items = mediaCaptureManager.getAllMedia()
            val initialIndex = if (targetUri != null) {
                val idx = items.indexOfFirst {
                    it.uri == targetUri ||
                    (it.uri.lastPathSegment != null && it.uri.lastPathSegment == targetUri.lastPathSegment)
                }
                if (idx >= 0) idx else 0
            } else {
                0
            }
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        galleryItems = items,
                        currentGalleryIndex = initialIndex,
                        isGalleryOpen = items.isNotEmpty(),
                        selectedQuickPhotoUri = if (items.isNotEmpty()) items[initialIndex].uri else targetUri
                    )
                }
            }
        }
    }

    fun closeGallery() {
        _uiState.update {
            it.copy(
                isGalleryOpen = false,
                selectedQuickPhotoUri = null
            )
        }
    }

    fun openPhotoEditor(uri: Uri, isImported: Boolean = false) {
        dismissScreenFlash()
        cleanupEditorWatermarkTempFiles()
        _uiState.update {
            it.copy(
                isPhotoEditorOpen = true,
                editingPhotoUri = uri,
                isEditingPhotoImported = isImported,
                editorWatermarkTarget = null,
                pendingEditorWatermarkResult = null
            )
        }
    }

    fun closePhotoEditor() {
        cleanupEditorWatermarkTempFiles()
        _uiState.update {
            it.copy(
                isPhotoEditorOpen = false,
                editingPhotoUri = null,
                isEditingPhotoImported = false,
                editorWatermarkTarget = null,
                pendingEditorWatermarkResult = null
            )
        }
    }

    fun onPhotoEdited(mediaCaptureManager: MediaCaptureManager, savedCopyUri: Uri) {
        cleanupEditorWatermarkTempFiles()
        viewModelScope.launch(Dispatchers.IO) {
            val items = mediaCaptureManager.getAllMedia()
            val newIdx = items.indexOfFirst { it.uri == savedCopyUri }.coerceAtLeast(0)
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        galleryItems = items,
                        currentGalleryIndex = newIdx,
                        lastCapturedUri = savedCopyUri,
                        isPhotoEditorOpen = false,
                        editingPhotoUri = null,
                        isEditingPhotoImported = false,
                        editorWatermarkTarget = null,
                        pendingEditorWatermarkResult = null,
                        userMessage = "Photo saved as copy"
                    )
                }
            }
        }
    }

    fun selectGalleryIndex(index: Int) {
        val items = _uiState.value.galleryItems
        if (index in items.indices) {
            _uiState.update {
                it.copy(
                    currentGalleryIndex = index,
                    selectedQuickPhotoUri = items[index].uri
                )
            }
        }
    }

    fun nextGalleryItem() {
        val current = _uiState.value.currentGalleryIndex
        val total = _uiState.value.galleryItems.size
        if (total > 0 && current < total - 1) {
            selectGalleryIndex(current + 1)
        }
    }

    fun previousGalleryItem() {
        val current = _uiState.value.currentGalleryIndex
        if (current > 0) {
            selectGalleryIndex(current - 1)
        }
    }

    fun deleteGalleryItem(mediaCaptureManager: MediaCaptureManager, item: MediaItem) {
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = recycleBinManager.moveToBin(mediaCaptureManager, item)) {
                is com.example.data.RecycleResult.MovedToBin -> {
                    privateOriginalStorage.unlinkGalleryUri(item.uri)
                    portraitSidecarStorage.unlinkGalleryUri(item.uri)
                    val updatedItems = mediaCaptureManager.getAllMedia()
                    val updatedBin = recycleBinManager.getBinItems()
                    val nextIndex = if (updatedItems.isEmpty()) {
                        0
                    } else {
                        _uiState.value.currentGalleryIndex.coerceAtMost(updatedItems.size - 1)
                    }
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                galleryItems = updatedItems,
                                currentGalleryIndex = nextIndex,
                                isGalleryOpen = updatedItems.isNotEmpty(),
                                selectedQuickPhotoUri = if (updatedItems.isNotEmpty()) updatedItems[nextIndex].uri else null,
                                lastCapturedUri = updatedItems.firstOrNull()?.uri,
                                binItems = updatedBin,
                                binCount = updatedBin.size,
                                galleryMediaRevision = it.galleryMediaRevision + 1L,
                                userMessage = if (item.isVideo) "Video moved to StuDO Bin" else "Photo moved to StuDO Bin"
                            )
                        }
                    }
                }
                is com.example.data.RecycleResult.Error -> {
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(userMessage = result.message)
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    fun getMediaDetails(mediaCaptureManager: MediaCaptureManager, item: MediaItem): PhotoDetails {
        return mediaCaptureManager.getMediaDetails(item)
    }

    fun deleteCurrentPhoto(mediaCaptureManager: MediaCaptureManager, uri: Uri) {
        val matchingItem = _uiState.value.galleryItems.find { it.uri == uri }
        if (matchingItem != null) {
            deleteGalleryItem(mediaCaptureManager, matchingItem)
        } else {
            val fallbackItem = MediaItem(
                id = uri.lastPathSegment?.toLongOrNull() ?: System.currentTimeMillis(),
                uri = uri,
                isVideo = false,
                displayName = uri.lastPathSegment ?: "Media",
                dateAdded = System.currentTimeMillis() / 1000L,
                mimeType = appContext.contentResolver.getType(uri) ?: "image/jpeg",
                sizeBytes = 0L,
                width = 0,
                height = 0
            )
            deleteGalleryItem(mediaCaptureManager, fallbackItem)
        }
    }

    fun refreshRecycleBinState() {
        viewModelScope.launch(Dispatchers.IO) {
            val binList = recycleBinManager.getBinItems()
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(binItems = binList, binCount = binList.size)
                }
            }
        }
    }

    fun openRecycleBin() {
        refreshRecycleBinState()
        _uiState.update {
            it.copy(
                isRecycleBinOpen = true,
                isSettingsOpen = false
            )
        }
    }

    fun closeRecycleBin() {
        _uiState.update { it.copy(isRecycleBinOpen = false) }
    }

    fun restoreBinItem(mediaCaptureManager: MediaCaptureManager, binItem: com.example.model.BinMediaItem) {
        viewModelScope.launch(Dispatchers.IO) {
            when (val res = recycleBinManager.restoreFromBin(binItem.id)) {
                is com.example.data.RecycleResult.Restored -> {
                    val refreshedMedia = mediaCaptureManager.getAllMedia()
                    val refreshedBin = recycleBinManager.getBinItems()
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                galleryItems = refreshedMedia,
                                lastCapturedUri = refreshedMedia.firstOrNull()?.uri ?: it.lastCapturedUri,
                                binItems = refreshedBin,
                                binCount = refreshedBin.size,
                                galleryMediaRevision = it.galleryMediaRevision + 1L,
                                userMessage = if (binItem.isVideo) "Video restored to Gallery" else "Photo restored to Gallery"
                            )
                        }
                    }
                }
                is com.example.data.RecycleResult.Error -> {
                    val refreshedBin = recycleBinManager.getBinItems()
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                binItems = refreshedBin,
                                binCount = refreshedBin.size,
                                userMessage = res.message
                            )
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    fun permanentlyDeleteBinItem(binItem: com.example.model.BinMediaItem) {
        viewModelScope.launch(Dispatchers.IO) {
            recycleBinManager.permanentlyDelete(binItem.id)
            val refreshedBin = recycleBinManager.getBinItems()
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        binItems = refreshedBin,
                        binCount = refreshedBin.size,
                        userMessage = "Permanently deleted"
                    )
                }
            }
        }
    }

    fun clearRecycleBin() {
        viewModelScope.launch(Dispatchers.IO) {
            val count = recycleBinManager.clearBin()
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        binItems = emptyList(),
                        binCount = 0,
                        userMessage = if (count > 0) "StuDO Bin cleared" else "StuDO Bin is already empty"
                    )
                }
            }
        }
    }

    fun setInitialThumbnail(uri: Uri?) {
        if (_uiState.value.lastCapturedUri == null && uri != null) {
            _uiState.update { it.copy(lastCapturedUri = uri) }
        }
    }

    fun openQuickPreview(uri: Uri?) {
        _uiState.update { it.copy(selectedQuickPhotoUri = uri, isGalleryOpen = true) }
    }

    fun dismissQuickPreview() {
        _uiState.update { it.copy(selectedQuickPhotoUri = null, isGalleryOpen = false) }
    }

    fun setExposureIndex(index: Int) {
        val min = _uiState.value.minExposureIndex
        val max = _uiState.value.maxExposureIndex
        val clamped = index.coerceIn(min, max)
        try {
            activeCameraControl?.setExposureCompensationIndex(clamped)
        } catch (_: Exception) {}
        _uiState.update { it.copy(exposureIndex = clamped) }
    }

    fun toggleExposureSlider() {
        _uiState.update { it.copy(showExposureSlider = !it.showExposureSlider) }
    }

    fun setFilter(filter: CameraFilter) {
        preferencesRepo.saveFilter(filter)
        _uiState.update { it.copy(selectedFilter = filter) }
    }

    fun setActiveCameraPanel(panel: com.example.model.ActiveCameraPanel) {
        _uiState.update { current ->
            val target = if (current.activeCameraPanel == panel) {
                com.example.model.ActiveCameraPanel.NONE
            } else {
                panel
            }
            current.copy(
                activeCameraPanel = target,
                isFilterSelectorOpen = (target == com.example.model.ActiveCameraPanel.FILTERS),
                isQuickSettingsOpen = (target == com.example.model.ActiveCameraPanel.QUICK_SETTINGS),
                isVideoSettingsPanelOpen = (target == com.example.model.ActiveCameraPanel.VIDEO_SETTINGS),
                activeProTab = if (target != com.example.model.ActiveCameraPanel.NONE) {
                    com.example.model.ProControlTab.NONE
                } else current.activeProTab,
                showExposureSlider = if (target != com.example.model.ActiveCameraPanel.NONE) {
                    false
                } else current.showExposureSlider
            )
        }
    }

    fun dismissActivePanel() {
        _uiState.update { current ->
            if (current.activeCameraPanel == com.example.model.ActiveCameraPanel.NONE &&
                !current.isFilterSelectorOpen &&
                !current.isQuickSettingsOpen &&
                !current.isVideoSettingsPanelOpen &&
                current.activeProTab == com.example.model.ProControlTab.NONE &&
                !current.showExposureSlider
            ) {
                current
            } else {
                current.copy(
                    activeCameraPanel = com.example.model.ActiveCameraPanel.NONE,
                    isFilterSelectorOpen = false,
                    isQuickSettingsOpen = false,
                    isVideoSettingsPanelOpen = false,
                    activeProTab = com.example.model.ProControlTab.NONE,
                    showExposureSlider = false
                )
            }
        }
    }

    fun toggleFilterSelector() {
        setActiveCameraPanel(com.example.model.ActiveCameraPanel.FILTERS)
    }

    fun setGrid(grid: GridOption) {
        preferencesRepo.saveGrid(grid)
        _uiState.update { it.copy(selectedGrid = grid) }
    }

    fun setTimer(timer: CaptureTimer) {
        preferencesRepo.saveTimer(timer)
        _uiState.update { it.copy(selectedTimer = timer) }
    }

    fun setCustomTimerSeconds(seconds: Int, selectCustomTimer: Boolean = true) {
        val clamped = seconds.coerceIn(CaptureTimer.MIN_CUSTOM_SECONDS, CaptureTimer.MAX_CUSTOM_SECONDS)
        preferencesRepo.saveCustomTimerSeconds(clamped)
        if (selectCustomTimer) {
            preferencesRepo.saveTimer(CaptureTimer.CUSTOM)
        }
        _uiState.update { current ->
            current.copy(
                customTimerSeconds = clamped,
                selectedTimer = if (selectCustomTimer) CaptureTimer.CUSTOM else current.selectedTimer
            )
        }
    }

    fun toggleLevelIndicator() {
        val next = !_uiState.value.isLevelIndicatorEnabled
        preferencesRepo.saveLevelEnabled(next)
        _uiState.update { it.copy(isLevelIndicatorEnabled = next) }
    }

    fun onOrientationChanged(roll: Float, pitch: Float) {
        val current = _orientationState.value
        val isLevel = abs(roll) <= 1.0f
        // Only trigger orientation update if roll or pitch changed by at least 0.25 degree or level state flipped
        if (abs(current.rollAngle - roll) >= 0.25f ||
            abs(current.pitchAngle - pitch) >= 0.25f ||
            current.isLevel != isLevel
        ) {
            _orientationState.value = com.example.model.DeviceOrientationState(
                rollAngle = roll,
                pitchAngle = pitch,
                isLevel = isLevel
            )
        }
        if (_uiState.value.isDeviceLevel != isLevel || abs(_uiState.value.deviceRollAngle - roll) >= 0.25f) {
            _uiState.update {
                it.copy(
                    isDeviceLevel = isLevel,
                    deviceRollAngle = roll,
                    devicePitchAngle = pitch
                )
            }
        }
    }

    fun toggleHistogram() {
        val next = !_uiState.value.isHistogramEnabled
        preferencesRepo.saveHistogramEnabled(next)
        if (!next) {
            _histogramBins.value = emptyList()
            _histogramData.value = com.example.model.HistogramData.Empty
        }
        _uiState.update { it.copy(isHistogramEnabled = next) }
    }

    fun toggleHistogramMode() {
        val next = if (_uiState.value.histogramMode == com.example.model.HistogramDisplayMode.LUMA) {
            com.example.model.HistogramDisplayMode.RGB
        } else {
            com.example.model.HistogramDisplayMode.LUMA
        }
        setHistogramMode(next)
    }

    fun setHistogramMode(mode: com.example.model.HistogramDisplayMode) {
        if (_uiState.value.histogramMode != mode) {
            preferencesRepo.saveHistogramMode(mode)
            _uiState.update { it.copy(histogramMode = mode) }
        }
    }

    fun toggleHistogramCollapsed() {
        _uiState.update { it.copy(isHistogramCollapsed = !it.isHistogramCollapsed) }
    }

    fun toggleShutterSound() {
        val next = !_uiState.value.isShutterSoundEnabled
        preferencesRepo.saveShutterSoundEnabled(next)
        _uiState.update { it.copy(isShutterSoundEnabled = next) }
    }

    // Photo Pro Settings
    fun setPhotoResolution(resolution: PhotoResolution) {
        preferencesRepo.savePhotoResolution(resolution)
        val activeOpt = _uiState.value.availableResolutionOptions.firstOrNull { it.resolution == resolution }
        val targetSize = activeOpt?.size ?: _uiState.value.targetCaptureSize
        _uiState.update {
            it.copy(
                photoResolution = resolution,
                activeResolutionOption = activeOpt,
                targetCaptureSize = targetSize,
                viewfinderRebindKey = if (it.cameraMode != CameraMode.VIDEO) it.viewfinderRebindKey + 1 else it.viewfinderRebindKey
            )
        }
    }

    fun toggleHdr() {
        val next = !_uiState.value.isHdrEnabled
        preferencesRepo.saveHdrEnabled(next)
        _uiState.update { it.copy(isHdrEnabled = next) }
    }

    fun toggleWatermark() {
        val current = _uiState.value.watermarkConfig
        val next = current.copy(isEnabled = !current.isEnabled)
        preferencesRepo.saveWatermarkConfig(next)
        _uiState.update { it.copy(watermarkConfig = next) }
    }

    fun setWatermarkStyle(style: WatermarkStyle) {
        val current = _uiState.value.watermarkConfig
        val next = current.copy(style = style)
        preferencesRepo.saveWatermarkConfig(next)
        _uiState.update { it.copy(watermarkConfig = next) }
    }

    fun setWatermarkCustomText(text: String) {
        val current = _uiState.value.watermarkConfig
        val next = current.copy(customText = text)
        preferencesRepo.saveWatermarkConfig(next)
        _uiState.update { it.copy(watermarkConfig = next) }
    }

    fun setWatermarkPosition(position: WatermarkPosition) {
        val current = _uiState.value.watermarkConfig
        val next = current.copy(position = position)
        preferencesRepo.saveWatermarkConfig(next)
        _uiState.update { it.copy(watermarkConfig = next) }
    }

    fun openWatermarkStudio() {
        dismissScreenFlash()
        val presets = preferencesRepo.getWatermarkPresets()
        _uiState.update {
            it.copy(
                isWatermarkStudioOpen = true,
                isSettingsOpen = false,
                postCaptureWatermarkTarget = null,
                editorWatermarkTarget = null,
                watermarkPresets = presets
            )
        }
    }

    private fun cleanupEditorWatermarkTempFiles() {
        try {
            val dir = appContext.cacheDir ?: return
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith("studo_editor_watermark_") && f.name.endsWith(".jpg")) {
                    f.delete()
                }
            }
        } catch (_: Throwable) {}
    }

    private fun cleanupOrphanedExportTempFiles() {
        try {
            val dir = appContext.cacheDir ?: return
            val staleCutoffMs = System.currentTimeMillis() - 5L * 60L * 1000L
            dir.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                val name = f.name
                val isEditorWm = name.startsWith("studo_editor_watermark_") && name.endsWith(".jpg")
                val isStaleExport = (
                    name.startsWith("studo_clean_export_") ||
                        name.startsWith("studo_wm_export_") ||
                        name.startsWith("studo_portrait_export_") ||
                        name.startsWith("studo_proc_") ||
                        name.startsWith("mmap_in_") ||
                        name.startsWith("mmap_out_") ||
                        name.startsWith("zoom_src_")
                    ) && f.lastModified() < staleCutoffMs
                if (isEditorWm || isStaleExport) {
                    f.delete()
                }
            }
        } catch (_: Throwable) {}
    }

    fun openWatermarkStudioFromEditor(snapshot: com.example.editor.PhotoEditSnapshot) {
        val currentUri = _uiState.value.editingPhotoUri ?: return
        val isImported = _uiState.value.isEditingPhotoImported
        viewModelScope.launch(Dispatchers.IO) {
            val res = com.example.editor.PhotoProcessingEngine.renderEditedBitmapToTempFile(
                context = appContext,
                sourceUri = currentUri,
                snapshot = snapshot,
                isImportedPhoto = isImported
            )
            if (res != null && res.file.exists()) {
                val meta = com.example.util.CameraMetadataUtil.fromUri(
                    context = appContext,
                    uri = currentUri,
                    width = res.width,
                    height = res.height,
                    allowDeviceFallback = !isImported
                )
                val baseConfig = snapshot.watermarkConfig ?: _uiState.value.watermarkConfig.let {
                    if (it.isEnabled) it else it.copy(isEnabled = true)
                }
                val target = com.example.model.EditorWatermarkTarget(
                    sessionId = "editor_wm_${System.nanoTime()}",
                    sourceUri = currentUri,
                    previewFilePath = res.file.absolutePath,
                    width = res.width,
                    height = res.height,
                    initialConfig = baseConfig,
                    isImportedPhoto = isImported,
                    metadata = meta
                )
                val presets = preferencesRepo.getWatermarkPresets()
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            postCaptureWatermarkTarget = null,
                            editorWatermarkTarget = target,
                            watermarkPresets = presets,
                            isWatermarkStudioOpen = true,
                            isSettingsOpen = false
                        )
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(userMessage = "Unable to prepare photo for Watermark Studio")
                    }
                }
            }
        }
    }

    fun saveWatermarkFromEditorSession(config: WatermarkConfig) {
        cleanupEditorWatermarkTempFiles()
        _uiState.update {
            it.copy(
                isWatermarkStudioOpen = false,
                editorWatermarkTarget = null,
                pendingEditorWatermarkResult = com.example.model.PendingEditorWatermarkResult(
                    eventId = System.nanoTime(),
                    config = config
                )
            )
        }
    }

    fun consumePendingEditorWatermarkResult(eventId: Long) {
        _uiState.update { cur ->
            if (cur.pendingEditorWatermarkResult?.eventId == eventId) {
                cur.copy(pendingEditorWatermarkResult = null)
            } else {
                cur
            }
        }
    }

    fun closeWatermarkStudio() {
        val target = _uiState.value.postCaptureWatermarkTarget
        if (target != null) {
            privateOriginalStorage.unmarkOriginalActive(target.originalId)
        }
        if (_uiState.value.editorWatermarkTarget != null) {
            cleanupEditorWatermarkTempFiles()
        }
        _uiState.update {
            it.copy(
                isWatermarkStudioOpen = false,
                postCaptureWatermarkTarget = null,
                editorWatermarkTarget = null,
                isPostCaptureSaving = false
            )
        }
    }

    private fun handlePrivateStorageEvictionNotice(eviction: com.example.data.CacheEvictionResult) {
        refreshPrivateStorageStats()
        if (eviction.evictedCount > 0) {
            val freedStr = com.example.data.PrivateOriginalStorageManager.formatStorageSize(eviction.freedBytes)
            _uiState.update {
                it.copy(
                    userMessage = "Storage cache limit reached: automatically deleted ${eviction.evictedCount} oldest private original(s) ($freedStr). Gallery photos remain safe."
                )
            }
        }
    }

    fun refreshPrivateStorageStats() {
        val limitBytes = _uiState.value.watermarkCacheLimit.limitBytes
        val stats = com.example.model.PrivateOriginalStorageStats(
            maxBytes = limitBytes,
            usedBytes = privateOriginalStorage.getUsedStorageBytes(),
            originalCount = privateOriginalStorage.getRetainedOriginalsCount()
        )
        _uiState.update { it.copy(privateOriginalStorageStats = stats) }
    }

    fun setPostCaptureWatermarkEditing(
        enabled: Boolean,
        selectedLimit: com.example.model.WatermarkCacheLimit? = null
    ) {
        preferencesRepo.savePostCaptureWatermarkEditingEnabled(enabled)
        if (selectedLimit != null) {
            preferencesRepo.saveWatermarkCacheLimit(selectedLimit)
            preferencesRepo.markWatermarkCacheLimitConfigured()
        }
        val activeLimit = selectedLimit ?: _uiState.value.watermarkCacheLimit
        val isConfigured = if (selectedLimit != null) true else preferencesRepo.isWatermarkCacheLimitConfigured()

        _uiState.update {
            it.copy(
                isPostCaptureWatermarkEditingEnabled = enabled,
                isWatermarkCacheLimitConfigured = isConfigured,
                watermarkCacheLimit = activeLimit,
                privateOriginalStorageStats = it.privateOriginalStorageStats.copy(maxBytes = activeLimit.limitBytes)
            )
        }

        if (selectedLimit != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val eviction = privateOriginalStorage.enforceCacheLimit(selectedLimit)
                withContext(Dispatchers.Main) {
                    refreshPrivateStorageStats()
                    if (eviction.evictedCount > 0) {
                        val freedStr = com.example.data.PrivateOriginalStorageManager.formatStorageSize(eviction.freedBytes)
                        _uiState.update {
                            it.copy(
                                userMessage = "Post-capture watermark editing enabled (${selectedLimit.displayName} cache). Automatically cleared ${eviction.evictedCount} oldest private original(s) ($freedStr)."
                            )
                        }
                    } else if (enabled) {
                        _uiState.update {
                            it.copy(
                                userMessage = "Post-capture watermark editing ON (${selectedLimit.displayName} private cache limit)"
                            )
                        }
                    }
                }
            }
        }
    }

    fun setWatermarkCacheLimit(limit: com.example.model.WatermarkCacheLimit) {
        preferencesRepo.saveWatermarkCacheLimit(limit)
        preferencesRepo.markWatermarkCacheLimitConfigured()
        _uiState.update {
            it.copy(
                watermarkCacheLimit = limit,
                isWatermarkCacheLimitConfigured = true,
                privateOriginalStorageStats = it.privateOriginalStorageStats.copy(maxBytes = limit.limitBytes)
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val eviction = privateOriginalStorage.enforceCacheLimit(limit)
            withContext(Dispatchers.Main) {
                refreshPrivateStorageStats()
                if (eviction.evictedCount > 0) {
                    val freedStr = com.example.data.PrivateOriginalStorageManager.formatStorageSize(eviction.freedBytes)
                    _uiState.update {
                        it.copy(
                            userMessage = "Cache limit set to ${limit.displayName}. Automatically deleted ${eviction.evictedCount} oldest private original(s) ($freedStr). Gallery photos were kept safe."
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(userMessage = "Private original cache limit updated to ${limit.displayName}")
                    }
                }
            }
        }
    }

    fun clearAllPrivateOriginals() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = privateOriginalStorage.clearAllOriginals()
            withContext(Dispatchers.Main) {
                refreshPrivateStorageStats()
                val freedStr = com.example.data.PrivateOriginalStorageManager.formatStorageSize(result.freedBytes)
                val msg = if (result.evictedCount > 0) {
                    "Cleared ${result.evictedCount} private original(s) ($freedStr). Gallery photos remain safe."
                } else {
                    "No private originals to clear."
                }
                _uiState.update { it.copy(userMessage = msg) }
            }
        }
    }

    fun requestEditWatermark(item: com.example.model.MediaItem) {
        if (item.isVideo) return
        viewModelScope.launch(Dispatchers.IO) {
            when (val resolved = privateOriginalStorage.resolveForGalleryUri(item.uri)) {
                is com.example.data.ResolvedOriginalResult.Available -> {
                    privateOriginalStorage.markOriginalActive(resolved.entry.originalId)
                    val target = com.example.model.PostCaptureWatermarkTarget(
                        galleryUri = item.uri,
                        originalId = resolved.entry.originalId,
                        originalFilePath = resolved.file.absolutePath,
                        width = resolved.entry.width,
                        height = resolved.entry.height,
                        associatedConfig = resolved.watermarkConfig,
                        iso = resolved.metadata.iso,
                        shutterSpeed = resolved.metadata.shutterSpeed,
                        aperture = resolved.metadata.aperture,
                        focalLength = resolved.metadata.focalLength,
                        dateTimeFormatted = resolved.metadata.dateTimeFormatted,
                        deviceModel = resolved.metadata.deviceModel,
                        lensInfo = resolved.metadata.lensInfo,
                        resolutionText = resolved.metadata.resolutionText,
                        manufacturer = resolved.metadata.manufacturer,
                        modelOnly = resolved.metadata.modelOnly
                    )
                    val presets = preferencesRepo.getWatermarkPresets()
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                postCaptureWatermarkTarget = target,
                                editorWatermarkTarget = null,
                                watermarkOriginalUnavailableMessage = null,
                                watermarkOriginalUnavailableUri = null,
                                watermarkPresets = presets,
                                isWatermarkStudioOpen = true,
                                isSettingsOpen = false
                            )
                        }
                    }
                }
                is com.example.data.ResolvedOriginalResult.Unavailable -> {
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                watermarkOriginalUnavailableMessage = resolved.reason,
                                watermarkOriginalUnavailableUri = item.uri
                            )
                        }
                    }
                }
            }
        }
    }

    fun dismissWatermarkOriginalUnavailableDialog() {
        _uiState.update {
            it.copy(
                watermarkOriginalUnavailableMessage = null,
                watermarkOriginalUnavailableUri = null
            )
        }
    }

    fun savePostCaptureUpdateExisting(config: WatermarkConfig) {
        val target = _uiState.value.postCaptureWatermarkTarget ?: return
        if (_uiState.value.isPostCaptureSaving) return
        _uiState.update { it.copy(isPostCaptureSaving = true) }

        viewModelScope.launch(Dispatchers.IO) {
            val exportRes = privateOriginalStorage.updateExistingGalleryPhoto(
                galleryUri = target.galleryUri,
                originalId = target.originalId,
                config = config,
                isSaveLocation = _uiState.value.isSaveLocationEnabled
            )
            when (exportRes) {
                is com.example.data.PostCaptureExportResult.Success -> {
                    try {
                        coil.Coil.imageLoader(appContext).memoryCache?.clear()
                    } catch (_: Throwable) {}
                    val refreshedMedia = mediaCaptureManager.getAllMedia()
                    privateOriginalStorage.unmarkOriginalActive(target.originalId)
                    withContext(Dispatchers.Main) {
                        refreshPrivateStorageStats()
                        _uiState.update { cur ->
                            val newIdx = refreshedMedia.indexOfFirst {
                                it.uri == target.galleryUri ||
                                    (it.uri.lastPathSegment != null && it.uri.lastPathSegment == target.galleryUri.lastPathSegment)
                            }.takeIf { it >= 0 } ?: cur.currentGalleryIndex.coerceIn(0, (refreshedMedia.size - 1).coerceAtLeast(0))
                            cur.copy(
                                galleryItems = refreshedMedia,
                                currentGalleryIndex = newIdx,
                                galleryMediaRevision = cur.galleryMediaRevision + 1L,
                                isWatermarkStudioOpen = false,
                                postCaptureWatermarkTarget = null,
                                isPostCaptureSaving = false,
                                userMessage = if (config.isEnabled) {
                                    "Updated photo watermark cleanly from original"
                                } else {
                                    "Removed watermark cleanly from existing photo"
                                }
                            )
                        }
                    }
                }
                is com.example.data.PostCaptureExportResult.Failure -> {
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                isPostCaptureSaving = false,
                                userMessage = exportRes.reason
                            )
                        }
                    }
                }
            }
        }
    }

    fun savePostCaptureNewCopy(config: WatermarkConfig) {
        val target = _uiState.value.postCaptureWatermarkTarget ?: return
        if (_uiState.value.isPostCaptureSaving) return
        _uiState.update { it.copy(isPostCaptureSaving = true) }

        viewModelScope.launch(Dispatchers.IO) {
            val exportRes = privateOriginalStorage.saveNewGalleryCopyFromOriginal(
                originalId = target.originalId,
                config = config,
                isSaveLocation = _uiState.value.isSaveLocationEnabled
            )
            when (exportRes) {
                is com.example.data.PostCaptureExportResult.Success -> {
                    val refreshedMedia = mediaCaptureManager.getAllMedia()
                    privateOriginalStorage.unmarkOriginalActive(target.originalId)
                    withContext(Dispatchers.Main) {
                        refreshPrivateStorageStats()
                        _uiState.update { cur ->
                            val newIdx = refreshedMedia.indexOfFirst { it.uri == exportRes.galleryUri }.coerceAtLeast(0)
                            cur.copy(
                                galleryItems = refreshedMedia,
                                currentGalleryIndex = if (refreshedMedia.isNotEmpty()) newIdx else 0,
                                lastCapturedUri = exportRes.galleryUri,
                                selectedQuickPhotoUri = exportRes.galleryUri,
                                galleryMediaRevision = cur.galleryMediaRevision + 1L,
                                isWatermarkStudioOpen = false,
                                postCaptureWatermarkTarget = null,
                                isPostCaptureSaving = false,
                                userMessage = if (config.isEnabled) {
                                    "Saved new watermarked copy to gallery"
                                } else {
                                    "Saved clean unwatermarked copy to gallery"
                                }
                            )
                        }
                    }
                }
                is com.example.data.PostCaptureExportResult.Failure -> {
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                isPostCaptureSaving = false,
                                userMessage = exportRes.reason
                            )
                        }
                    }
                }
            }
        }
    }

    fun savePostCaptureUnwatermarkedCopy() {
        val target = _uiState.value.postCaptureWatermarkTarget ?: return
        savePostCaptureNewCopy(target.associatedConfig.copy(isEnabled = false))
    }

    fun saveWatermarkConfig(config: WatermarkConfig) {
        preferencesRepo.saveWatermarkConfig(config)
        _uiState.update {
            it.copy(
                watermarkConfig = config,
                isWatermarkStudioOpen = false,
                editorWatermarkTarget = null,
                userMessage = "Watermark settings saved"
            )
        }
    }

    fun saveWatermarkPreset(name: String, config: WatermarkConfig) {
        val newPreset = com.example.model.WatermarkPreset(
            id = java.util.UUID.randomUUID().toString(),
            name = name.ifBlank { "Custom Preset" },
            config = config
        )
        val updated = _uiState.value.watermarkPresets + newPreset
        preferencesRepo.saveWatermarkPresets(updated)
        _uiState.update {
            it.copy(
                watermarkPresets = updated,
                userMessage = "Preset \"${newPreset.name}\" saved"
            )
        }
    }

    fun deleteWatermarkPreset(presetId: String) {
        val updated = _uiState.value.watermarkPresets.filterNot { it.id == presetId }
        preferencesRepo.saveWatermarkPresets(updated)
        _uiState.update {
            it.copy(
                watermarkPresets = updated,
                userMessage = "Preset deleted"
            )
        }
    }

    // Video Pro Settings
    fun setVideoQuality(quality: VideoQualityOption) {
        preferencesRepo.saveVideoQuality(quality)
        val isFront = (_uiState.value.lensFacing == CameraSelector.LENS_FACING_FRONT)
        val cap = capabilityDetector.getCapabilityForLens(isFront)
        val validFps = cap?.getSupportedFpsForQuality(quality) ?: _uiState.value.supportedVideoFps
        val currentFps = _uiState.value.videoFps
        val effectiveFps = if (validFps.contains(currentFps)) currentFps else (validFps.firstOrNull() ?: VideoFpsOption.FPS_AUTO)
        preferencesRepo.saveVideoFps(effectiveFps)
        _uiState.update {
            it.copy(
                videoQuality = quality,
                supportedVideoFps = validFps,
                videoFps = effectiveFps,
                viewfinderRebindKey = if (it.cameraMode == CameraMode.VIDEO) it.viewfinderRebindKey + 1 else it.viewfinderRebindKey
            )
        }
        applyVideoSettingsToCamera()
    }

    fun setVideoFps(fps: VideoFpsOption) {
        val isFront = (_uiState.value.lensFacing == CameraSelector.LENS_FACING_FRONT)
        val cap = capabilityDetector.getCapabilityForLens(isFront)
        if (cap != null) {
            val supported = cap.getSupportedFpsForQuality(_uiState.value.videoQuality)
            if (supported.isNotEmpty() && !supported.contains(fps)) return
        }
        preferencesRepo.saveVideoFps(fps)
        _uiState.update {
            val changed = it.videoFps != fps
            it.copy(
                videoFps = fps,
                viewfinderRebindKey = if (changed && it.cameraMode == CameraMode.VIDEO) it.viewfinderRebindKey + 1 else it.viewfinderRebindKey
            )
        }
        applyVideoSettingsToCamera()
    }

    fun cycleVideoQualityAndFps() {
        val qualities = _uiState.value.supportedVideoQualities
        if (qualities.isEmpty()) return
        triggerHapticFeedback()
        val currentQ = _uiState.value.videoQuality
        val nextQIndex = (qualities.indexOf(currentQ) + 1) % qualities.size
        val nextQ = qualities[nextQIndex]
        setVideoQuality(nextQ)
    }

    fun cycleVideoFps() {
        val fpsList = _uiState.value.supportedVideoFps
        if (fpsList.isEmpty()) return
        triggerHapticFeedback()
        val currentFps = _uiState.value.videoFps
        val nextIndex = (fpsList.indexOf(currentFps) + 1) % fpsList.size
        setVideoFps(fpsList[nextIndex])
    }

    fun toggleVideoStabilization() {
        val next = !_uiState.value.isVideoStabilizationEnabled
        preferencesRepo.saveVideoStabilizationEnabled(next)
        triggerHapticFeedback()
        _uiState.update {
            it.copy(
                isVideoStabilizationEnabled = next,
                viewfinderRebindKey = if (it.cameraMode == CameraMode.VIDEO) it.viewfinderRebindKey + 1 else it.viewfinderRebindKey,
                userMessage = if (next) "Video Stabilization ON" else "Video Stabilization OFF"
            )
        }
        applyVideoSettingsToCamera()
    }

    fun setVideoCodec(codec: VideoCodecOption) {
        val support = com.example.util.VideoEncoderUtil.detectVideoEncoderSupport()
        if (support.canEncodeVideo && !com.example.util.VideoEncoderUtil.supportsStandardMime(support, codec.mimeType)) {
            _uiState.update { it.copy(userMessage = "${codec.displayName} encoder unavailable on this device") }
            return
        }
        preferencesRepo.saveVideoCodec(codec)
        _uiState.update {
            val changed = it.videoCodec != codec
            it.copy(
                videoCodec = codec,
                viewfinderRebindKey = if (changed && it.cameraMode == CameraMode.VIDEO) it.viewfinderRebindKey + 1 else it.viewfinderRebindKey
            )
        }
    }

    fun toggleVideoSettingsPanel() {
        if (_uiState.value.cameraMode != CameraMode.VIDEO) return
        setActiveCameraPanel(com.example.model.ActiveCameraPanel.VIDEO_SETTINGS)
    }

    fun dismissVideoSettingsPanel() {
        if (_uiState.value.activeCameraPanel == com.example.model.ActiveCameraPanel.VIDEO_SETTINGS) {
            setActiveCameraPanel(com.example.model.ActiveCameraPanel.NONE)
        }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    fun applyVideoSettingsToCamera() {
        val control = activeCameraControl ?: return
        if (_uiState.value.cameraMode != CameraMode.VIDEO) return

        try {
            val c2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(control)
            val builder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()

            // 1. Video Stabilization (EIS)
            if (_uiState.value.isVideoStabilizationEnabled && _uiState.value.isVideoStabilizationSupported) {
                builder.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                )
            } else {
                builder.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
            }

            // 2. Optical Image Stabilization (OIS)
            val isFront = (_uiState.value.lensFacing == CameraSelector.LENS_FACING_FRONT)
            val cap = capabilityDetector.getCapabilityForLens(isFront)
            if (cap?.isOisSupported == true) {
                builder.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                )
            }

            // 3. Target Frame Rate Range
            val fpsRanges = cap?.targetFpsRanges ?: emptyList()
            when (_uiState.value.videoFps) {
                VideoFpsOption.FPS_120 -> {
                    val targetRange = fpsRanges.firstOrNull { it.lower >= 120 && it.upper >= 120 }
                        ?: fpsRanges.firstOrNull { it.upper >= 120 }
                        ?: android.util.Range(120, 120)
                    builder.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        targetRange
                    )
                }
                VideoFpsOption.FPS_60 -> {
                    val targetRange = fpsRanges.firstOrNull { it.lower >= 60 && it.upper >= 60 }
                        ?: fpsRanges.firstOrNull { it.upper >= 60 }
                        ?: android.util.Range(30, 60)
                    builder.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        targetRange
                    )
                }
                VideoFpsOption.FPS_30 -> {
                    val targetRange = fpsRanges.firstOrNull { it.lower == 30 && it.upper == 30 }
                        ?: fpsRanges.firstOrNull { it.upper == 30 }
                        ?: android.util.Range(30, 30)
                    builder.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        targetRange
                    )
                }
                VideoFpsOption.FPS_24 -> {
                    val targetRange = fpsRanges.firstOrNull { it.lower == 24 && it.upper == 24 }
                        ?: fpsRanges.firstOrNull { it.upper == 24 }
                        ?: fpsRanges.firstOrNull { it.lower <= 24 && it.upper >= 24 }
                    if (targetRange != null) {
                        builder.setCaptureRequestOption(
                            android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            targetRange
                        )
                    }
                }
                VideoFpsOption.FPS_AUTO -> {
                    // Default AE range
                }
            }

            c2Control.setCaptureRequestOptions(builder.build())
        } catch (e: Exception) {
            android.util.Log.w("CameraViewModel", "Error applying video settings to preview: ${e.message}")
        }
    }

    // General & Hardware Settings
    fun toggleSelfieMirror() {
        val next = !_uiState.value.isSelfieMirrorEnabled
        preferencesRepo.saveSelfieMirrorEnabled(next)
        _uiState.update { it.copy(isSelfieMirrorEnabled = next) }
    }

    fun syncLocationPermission(isGranted: Boolean) {
        val effectiveEnabled = _uiState.value.isSaveLocationEnabled && isGranted
        if (_uiState.value.hasLocationPermission != isGranted || _uiState.value.isSaveLocationEnabled != effectiveEnabled) {
            if (!isGranted && _uiState.value.isSaveLocationEnabled) {
                preferencesRepo.saveSaveLocationEnabled(false)
            }
            _uiState.update {
                it.copy(
                    hasLocationPermission = isGranted,
                    isSaveLocationEnabled = effectiveEnabled
                )
            }
        }
    }

    fun toggleSaveLocation(enable: Boolean) {
        preferencesRepo.saveSaveLocationEnabled(enable)
        _uiState.update { it.copy(isSaveLocationEnabled = enable, hasLocationPermission = enable || it.hasLocationPermission) }
    }

    fun toggleHapticFeedback() {
        val next = !_uiState.value.isHapticFeedbackEnabled
        preferencesRepo.saveHapticFeedbackEnabled(next)
        _uiState.update { it.copy(isHapticFeedbackEnabled = next) }
    }

    fun toggleKeepScreenAwake() {
        val next = !_uiState.value.isKeepScreenAwakeEnabled
        preferencesRepo.saveKeepScreenAwakeEnabled(next)
        _uiState.update { it.copy(isKeepScreenAwakeEnabled = next) }
    }

    fun setVolumeKeyAction(action: VolumeKeyAction) {
        preferencesRepo.saveVolumeKeyAction(action)
        _uiState.update { it.copy(volumeKeyAction = action) }
    }

    fun triggerHapticFeedback() {
        if (!_uiState.value.isHapticFeedbackEnabled) return
        try {
            val app = getApplication<Application>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(25)
                }
            }
        } catch (_: Throwable) {}
    }

    fun handleVolumeKey(isVolumeUp: Boolean, mediaCaptureManager: MediaCaptureManager): Boolean {
        return when (_uiState.value.volumeKeyAction) {
            VolumeKeyAction.SHUTTER -> {
                onShutterTrigger(mediaCaptureManager)
                triggerHapticFeedback()
                true
            }
            VolumeKeyAction.ZOOM -> {
                val current = _uiState.value.zoomRatio
                val step = 0.5f
                val target = if (isVolumeUp) current + step else current - step
                setZoomRatio(target)
                triggerHapticFeedback()
                true
            }
            VolumeKeyAction.SYSTEM -> false
        }
    }

    fun setRawCaptureMode(mode: com.example.model.RawCaptureMode) {
        preferencesRepo.saveRawCaptureMode(mode)
        _uiState.update { it.copy(rawCaptureMode = mode) }
    }

    fun cycleRawModeFromTopBar() {
        if (_uiState.value.cameraMode == CameraMode.PORTRAIT ||
            _uiState.value.cameraMode == CameraMode.NIGHT
        ) {
            return
        }
        if (!_uiState.value.isRawSupportedOnCurrentLens) {
            _uiState.update { it.copy(userMessage = "RAW capture is not supported by this camera sensor") }
            return
        }
        val nextMode = when (_uiState.value.rawCaptureMode) {
            com.example.model.RawCaptureMode.OFF -> com.example.model.RawCaptureMode.DNG_ONLY
            com.example.model.RawCaptureMode.DNG_ONLY -> com.example.model.RawCaptureMode.JPEG_AND_DNG
            com.example.model.RawCaptureMode.JPEG_AND_DNG -> com.example.model.RawCaptureMode.OFF
        }
        setRawCaptureMode(nextMode)
        val modeLabel = when (nextMode) {
            com.example.model.RawCaptureMode.OFF -> "JPEG Only"
            com.example.model.RawCaptureMode.DNG_ONLY -> "RAW (DNG Only)"
            com.example.model.RawCaptureMode.JPEG_AND_DNG -> "RAW + JPEG"
        }
        _uiState.update { it.copy(userMessage = "Capture Format: $modeLabel") }
    }

    fun setActiveProTab(tab: com.example.model.ProControlTab) {
        _uiState.update { current ->
            val nextTab = if (current.activeProTab == tab) com.example.model.ProControlTab.NONE else tab
            val opening = nextTab != com.example.model.ProControlTab.NONE
            current.copy(
                activeProTab = nextTab,
                activeCameraPanel = if (opening) com.example.model.ActiveCameraPanel.NONE else current.activeCameraPanel,
                isFilterSelectorOpen = if (opening) false else current.isFilterSelectorOpen,
                isQuickSettingsOpen = if (opening) false else current.isQuickSettingsOpen,
                isVideoSettingsPanelOpen = if (opening) false else current.isVideoSettingsPanelOpen
            )
        }
    }

    fun setProIso(iso: Int?) {
        _uiState.update { current ->
            current.copy(proSettings = current.proSettings.copy(iso = iso))
        }
        applyProSettingsToPreview()
    }

    fun setProShutter(nanos: Long?) {
        _uiState.update { current ->
            current.copy(proSettings = current.proSettings.copy(shutterSpeedNanos = nanos))
        }
        applyProSettingsToPreview()
    }

    fun setProEv(ev: Int) {
        _uiState.update { current ->
            current.copy(proSettings = current.proSettings.copy(evCompensation = ev))
        }
        try {
            activeCameraControl?.setExposureCompensationIndex(ev)
        } catch (_: Exception) {}
        applyProSettingsToPreview()
    }

    fun setProFocus(distance: Float?, isManual: Boolean) {
        _uiState.update { current ->
            current.copy(proSettings = current.proSettings.copy(isManualFocus = isManual, focusDistance = distance))
        }
        applyProSettingsToPreview()
    }

    fun setProWhiteBalance(wb: com.example.model.ProWhitebalanceMode) {
        _uiState.update { current ->
            current.copy(proSettings = current.proSettings.copy(whiteBalance = wb))
        }
        applyProSettingsToPreview()
    }

    fun resetProSettings() {
        _uiState.update { current ->
            current.copy(
                proSettings = com.example.model.ProCameraSettings(),
                activeProTab = com.example.model.ProControlTab.NONE
            )
        }
        clearProSettingsFromPreview()
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    fun applyProSettingsToPreview() {
        val control = activeCameraControl ?: return
        if (_uiState.value.cameraMode != CameraMode.PRO) return

        try {
            val c2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(control)
            val builder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
            val pro = _uiState.value.proSettings

            // Manual Exposure / ISO / Shutter
            if (pro.iso != null || pro.shutterSpeedNanos != null) {
                builder.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_OFF
                )
                if (pro.iso != null) {
                    builder.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY,
                        pro.iso
                    )
                }
                if (pro.shutterSpeedNanos != null) {
                    builder.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME,
                        pro.shutterSpeedNanos
                    )
                }
            } else {
                builder.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON
                )
                try {
                    control.setExposureCompensationIndex(pro.evCompensation)
                } catch (_: Exception) {}
            }

            // Manual Focus
            if (pro.isManualFocus && pro.focusDistance != null) {
                builder.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_OFF
                )
                builder.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE,
                    pro.focusDistance
                )
            } else {
                builder.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            }

            // White Balance
            builder.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE,
                pro.whiteBalance.camera2Mode
            )

            c2Control.setCaptureRequestOptions(builder.build())
        } catch (e: Exception) {
            android.util.Log.w("CameraViewModel", "Error applying Pro settings to preview: ${e.message}")
        }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    fun clearProSettingsFromPreview() {
        val control = activeCameraControl ?: return
        try {
            val c2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(control)
            c2Control.clearCaptureRequestOptions()
        } catch (_: Exception) {}
    }

    fun setOemEnhancementMode(mode: com.example.model.OemEnhancementMode) {
        preferencesRepo.saveOemEnhancementMode(mode)
        val activeAdapter = oemRegistry.resolveActiveAdapter(appContext, mode)
        _uiState.update {
            it.copy(
                oemEnhancementMode = mode,
                activeOemAdapterName = activeAdapter.adapterName,
                oemEnhancementSummary = "${activeAdapter.adapterName} (${if (mode == com.example.model.OemEnhancementMode.AUTOMATIC) "Auto" else "Universal"})"
            )
        }
    }

    fun onVersionTapped() {
        developerTapCount++
        if (developerTapCount >= 5) {
            developerTapCount = 0
            triggerHapticFeedback()
            openDiagnostics()
        }
    }

    fun openDiagnostics() {
        val report = capabilityDetector.generateDiagnosticReport(_uiState.value.oemEnhancementMode)
        _uiState.update {
            it.copy(
                isDiagnosticsDialogOpen = true,
                diagnosticReportText = report
            )
        }
    }

    fun closeDiagnostics() {
        _uiState.update { it.copy(isDiagnosticsDialogOpen = false) }
    }

    fun toggleQuickSettings() {
        setActiveCameraPanel(com.example.model.ActiveCameraPanel.QUICK_SETTINGS)
    }

    fun dismissQuickSettings() {
        if (_uiState.value.activeCameraPanel == com.example.model.ActiveCameraPanel.QUICK_SETTINGS ||
            _uiState.value.isQuickSettingsOpen
        ) {
            setActiveCameraPanel(com.example.model.ActiveCameraPanel.NONE)
        }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun updateHistogramBins(bins: List<Float>) {
        _histogramBins.value = bins
        _histogramData.value = if (bins.isEmpty()) {
            com.example.model.HistogramData.Empty
        } else {
            com.example.model.HistogramData(
                lumaBins = bins,
                redBins = bins,
                greenBins = bins,
                blueBins = bins
            )
        }
    }

    fun updateHistogramData(data: com.example.model.HistogramData) {
        _histogramData.value = data
        _histogramBins.value = data.lumaBins
    }

    fun onTargetRotationChanged(rotation: Int) {
        currentTargetRotation = rotation
    }

    fun onCameraHardwareError(message: String, isFatal: Boolean = false) {
        dismissScreenFlash()
        if (isFatal && (_uiState.value.isRecordingVideo || activeRecording != null)) {
            stopVideoRecording()
        }
        _uiState.update {
            it.copy(
                isCameraReady = false,
                isStreaming = false,
                isCapturing = false,
                userMessage = message
            )
        }
    }

    // ============================================================================================
    // Dedicated Portrait Mode Controls & Non-Destructive Post-Capture Blur Editor
    // ============================================================================================

    fun setPortraitFocusPoint(normalizedX: Float, normalizedY: Float) {
        val norm = Offset(normalizedX.coerceIn(0f, 1f), normalizedY.coerceIn(0f, 1f))
        _uiState.update { it.copy(portraitFocusPointNormalized = norm) }
    }

    fun setPortraitBlurIntensity(intensity: Float) {
        val clamped = intensity.coerceIn(0f, 1f)
        preferencesRepo.savePortraitBlurIntensity(clamped)
        _uiState.update { it.copy(portraitBlurIntensity = clamped) }
    }

    fun onPortraitPreviewUpdate(
        hasSubject: Boolean,
        subjectCount: Int,
        depthSource: com.example.model.PortraitDepthSource,
        qualityTier: com.example.model.PortraitQualityTier
    ) {
        _uiState.update { cur ->
            val effectiveSource = if (cur.isPortraitHardwareDepthSupported &&
                depthSource == com.example.model.PortraitDepthSource.AI_SEGMENTATION
            ) {
                com.example.model.PortraitDepthSource.HYBRID
            } else {
                depthSource
            }
            if (cur.isPortraitSubjectDetected == hasSubject &&
                cur.portraitSubjectCount == subjectCount &&
                cur.portraitDepthSource == effectiveSource &&
                cur.portraitQualityTier == qualityTier
            ) {
                cur
            } else {
                cur.copy(
                    isPortraitSubjectDetected = hasSubject,
                    portraitSubjectCount = subjectCount,
                    portraitDepthSource = effectiveSource,
                    portraitQualityTier = qualityTier
                )
            }
        }
    }

    fun hasPortraitSidecar(item: MediaItem): Boolean {
        return !item.isVideo && portraitSidecarStorage.hasPortraitSidecar(item.uri)
    }

    fun openPortraitEditor(item: MediaItem) {
        if (item.isVideo) return
        val target = portraitSidecarStorage.resolveForGalleryUri(item.uri)
        if (target == null) {
            _uiState.update { it.copy(userMessage = "Portrait depth data is unavailable for this photo") }
            return
        }
        dismissScreenFlash()
        _uiState.update {
            it.copy(
                postCapturePortraitTarget = target,
                isPortraitEditorOpen = true,
                isPortraitEditorSaving = false
            )
        }
    }

    fun closePortraitEditor() {
        _uiState.update {
            it.copy(
                isPortraitEditorOpen = false,
                postCapturePortraitTarget = null,
                isPortraitEditorSaving = false
            )
        }
    }

    fun savePortraitUpdateExisting(blurIntensity: Float, focusX: Float, focusY: Float) {
        val target = _uiState.value.postCapturePortraitTarget ?: return
        if (_uiState.value.isPortraitEditorSaving) return
        _uiState.update { it.copy(isPortraitEditorSaving = true) }
        viewModelScope.launch {
            val res = portraitSidecarStorage.reRenderAndUpdateExisting(
                target = target,
                newBlurIntensity = blurIntensity,
                newFocusX = focusX,
                newFocusY = focusY,
                isSaveLocation = _uiState.value.isSaveLocationEnabled
            )
            val refreshed = withContext(Dispatchers.IO) { mediaCaptureManager.getAllMedia() }
            withContext(Dispatchers.Main) {
                when (res) {
                    is com.example.data.PostCaptureExportResult.Success -> {
                        _uiState.update { cur ->
                            cur.copy(
                                isPortraitEditorSaving = false,
                                isPortraitEditorOpen = false,
                                postCapturePortraitTarget = null,
                                galleryItems = refreshed,
                                galleryMediaRevision = cur.galleryMediaRevision + 1L,
                                portraitSidecarRevision = cur.portraitSidecarRevision + 1L,
                                userMessage = "Portrait blur updated (${com.example.model.PortraitApertureFormatter.formatFStop(blurIntensity)})"
                            )
                        }
                    }
                    is com.example.data.PostCaptureExportResult.Failure -> {
                        _uiState.update { cur ->
                            cur.copy(
                                isPortraitEditorSaving = false,
                                userMessage = res.reason
                            )
                        }
                    }
                }
            }
        }
    }

    fun savePortraitSaveCopy(blurIntensity: Float, focusX: Float, focusY: Float) {
        val target = _uiState.value.postCapturePortraitTarget ?: return
        if (_uiState.value.isPortraitEditorSaving) return
        _uiState.update { it.copy(isPortraitEditorSaving = true) }
        viewModelScope.launch {
            val res = portraitSidecarStorage.reRenderAndSaveCopy(
                target = target,
                newBlurIntensity = blurIntensity,
                newFocusX = focusX,
                newFocusY = focusY,
                isSaveLocation = _uiState.value.isSaveLocationEnabled
            )
            val refreshed = withContext(Dispatchers.IO) { mediaCaptureManager.getAllMedia() }
            withContext(Dispatchers.Main) {
                when (res) {
                    is com.example.data.PostCaptureExportResult.Success -> {
                        val copyIdx = refreshed.indexOfFirst { it.uri == res.galleryUri }
                            .takeIf { it >= 0 } ?: 0
                        _uiState.update { cur ->
                            cur.copy(
                                isPortraitEditorSaving = false,
                                isPortraitEditorOpen = false,
                                postCapturePortraitTarget = null,
                                galleryItems = refreshed,
                                currentGalleryIndex = if (cur.isGalleryOpen) copyIdx else cur.currentGalleryIndex,
                                lastCapturedUri = res.galleryUri,
                                galleryMediaRevision = cur.galleryMediaRevision + 1L,
                                portraitSidecarRevision = cur.portraitSidecarRevision + 1L,
                                userMessage = "Saved Portrait copy (${com.example.model.PortraitApertureFormatter.formatFStop(blurIntensity)})"
                            )
                        }
                    }
                    is com.example.data.PostCaptureExportResult.Failure -> {
                        _uiState.update { cur ->
                            cur.copy(
                                isPortraitEditorSaving = false,
                                userMessage = res.reason
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        dismissScreenFlash()
        focusResetJob?.cancel()
        recordingTimerJob?.cancel()
        countdownJob?.cancel()
        galleryRefreshJob?.cancel()
        nightCaptureJob?.cancel()
        if (_uiState.value.isRecordingVideo || activeRecording != null) {
            try {
                if (isRecordingStarted) {
                    activeRecording?.stop()
                } else {
                    activeRecording?.close()
                }
            } catch (_: Throwable) {
                try { activeRecording?.close() } catch (_: Throwable) {}
            }
            activeRecording = null
            isRecordingStarted = false
        }
        try { mediaSound?.release() } catch (_: Throwable) {}
        mediaSound = null
        cleanupEditorWatermarkTempFiles()
        portraitPipelineManager.onExitPortraitMode()
        nightPipelineManager.shutdown()
        mediaCaptureManager.shutdown()
        ultraResCaptureManager.shutdown()
    }
}
