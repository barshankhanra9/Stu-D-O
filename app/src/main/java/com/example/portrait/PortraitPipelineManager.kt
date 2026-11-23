package com.example.portrait

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import com.example.model.PortraitCaptureTier
import com.example.model.PortraitDepthSource
import com.example.model.PortraitPreviewStrategy
import com.example.model.PortraitSubjectModelStatus
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.Feature
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.OptionalModuleApi
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.sdkinternal.OptionalModuleUtils
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic FPS watchdog for live OpenGL portrait preview.
 * If effective live preview FPS falls below [lowFpsThreshold] continuously for [sustainDurationMs],
 * pauses the real-time shader blur so the viewfinder stays crisp and responsive, displaying
 * the UI badge "Portrait blur applied on capture" instead of lagging on budget devices.
 */
class PortraitFpsWatchdog(
    private val lowFpsThreshold: Float = 15f,
    private val sustainDurationMs: Long = 2000L,
    private val evaluationWindowMs: Long = 500L,
    private val timeMsProvider: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
    private var windowStartMs: Long = 0L
    private var framesInWindow: Int = 0
    private var lowFpsStartMs: Long = 0L

    @Volatile
    var isPaused: Boolean = false
        private set

    @Volatile
    var lastMeasuredFps: Float = 0f
        private set

    fun reset() {
        windowStartMs = 0L
        framesInWindow = 0
        lowFpsStartMs = 0L
        isPaused = false
        lastMeasuredFps = 0f
    }

    /**
     * Records a rendered live frame timestamp and returns `true` when the watchdog triggers pause.
     */
    fun onFrameRendered(nowMs: Long = timeMsProvider()): Boolean {
        if (isPaused) return true
        if (windowStartMs == 0L) {
            windowStartMs = nowMs
            framesInWindow = 0
            return false
        }
        framesInWindow++
        val windowElapsed = nowMs - windowStartMs
        if (windowElapsed < evaluationWindowMs) {
            return false
        }

        val fps = if (windowElapsed > 0L) {
            (framesInWindow * 1000f) / windowElapsed.toFloat()
        } else {
            60f
        }
        lastMeasuredFps = fps
        windowStartMs = nowMs
        framesInWindow = 0

        if (fps < lowFpsThreshold) {
            if (lowFpsStartMs == 0L) {
                lowFpsStartMs = nowMs
            } else if (nowMs - lowFpsStartMs >= sustainDurationMs) {
                isPaused = true
                return true
            }
        } else {
            lowFpsStartMs = 0L
        }
        return false
    }
}

data class PortraitLiveCapability(
    val strategy: PortraitPreviewStrategy,
    val effectiveSelector: CameraSelector
)

/**
 * Self-contained Dual-Stage Portrait Architecture Manager:
 *
 * Stage 1 (Live Viewfinder):
 * - Checks CameraX ExtensionsManager.isExtensionAvailable(selector, ExtensionMode.BOKEH).
 *   If available, provides the extension-enabled CameraSelector for zero-overhead native preview & capture.
 * - Otherwise falls back to a universal OpenGL ES 2.0 Shader (`PortraitLiveBlurGLSurfaceView`) fed by
 *   downscaled `ImageAnalysis` + lazily initialized `ML Kit SelfieSegmentation` (`STREAM_MODE`, raw size mask),
 *   guarded by an automatic `<15 FPS for >2s` watchdog.
 *
 * Stage 2 (Final Capture Waterfall, capped <= 4000px ~12MP to avoid OOM on 50MP+ sensors):
 * 1. CameraX `ExtensionMode.BOKEH`
 * 2. Hardware `DEPTH16` + morphological hole-filling (`HardwareDepthProcessor`)
 * 3. `ML Kit Subject Segmentation` (with async `ModuleInstallClient` download on Portrait entry for GitHub APKs)
 *    + Guided Filter edge refinement
 * 4. Bundled offline `ML Kit Selfie Segmentation` (`SINGLE_IMAGE_MODE`) + Guided Filter (+ deterministic software fallback)
 */
class PortraitPipelineManager(context: Context) {

    private val appContext = context.applicationContext

    private val _status = MutableStateFlow(PortraitPipelineStatus())
    val status: StateFlow<PortraitPipelineStatus> = _status.asStateFlow()

    val fpsWatchdog = PortraitFpsWatchdog()

    @Volatile
    private var isPortraitSessionActive = false

    @Volatile
    private var cachedExtensionsManager: ExtensionsManager? = null

    @Volatile
    private var streamSelfieSegmenter: Segmenter? = null

    @Volatile
    private var singleImageSelfieSegmenter: Segmenter? = null

    @Volatile
    private var subjectSegmenter: SubjectSegmenter? = null

    @Volatile
    private var selfieMlKitUnavailable = false

    @Volatile
    private var subjectMlKitUnavailable = false

    private val isLiveSegmentationBusy = AtomicBoolean(false)
    private val moduleInstallRequested = AtomicBoolean(false)
    private var activeInstallListener: InstallStatusListener? = null
    private var moduleInstallClient: ModuleInstallClient? = null

    // Reusable buffers for low-churn Stage 1 YUV extraction
    private var yScratch = ByteArray(0)
    private var uScratch = ByteArray(0)
    private var vScratch = ByteArray(0)
    private var nv21Scratch = ByteArray(0)

    /**
     * Enters Portrait Mode: resets FPS watchdog, prepares lazy ML Kit session, and triggers async
     * `ModuleInstallClient` check/download for Play Services Subject Segmentation (critical for GitHub APK sideloads).
     */
    fun onEnterPortraitMode() {
        isPortraitSessionActive = true
        fpsWatchdog.reset()
        _status.update {
            it.copy(
                isLiveBlurPausedByWatchdog = false,
                livePreviewFps = 0f,
                isSubjectDetected = false,
                subjectCount = 0
            )
        }
        ensureSubjectSegmentationReadyAsync()
    }

    /**
     * Exits Portrait Mode immediately closing all ML Kit segmenters and clearing scratch buffers
     * to prevent memory bloat on 4GB RAM devices.
     */
    fun onExitPortraitMode() {
        isPortraitSessionActive = false
        fpsWatchdog.reset()
        isLiveSegmentationBusy.set(false)
        closeSegmenters()
        unregisterInstallListener()
        yScratch = ByteArray(0)
        uScratch = ByteArray(0)
        vScratch = ByteArray(0)
        nv21Scratch = ByteArray(0)
        _status.update {
            it.copy(
                previewStrategy = PortraitPreviewStrategy.CHECKING,
                isLiveBlurPausedByWatchdog = false,
                livePreviewFps = 0f,
                isSubjectDetected = false,
                subjectCount = 0
            )
        }
    }

    /**
     * Stage 1 Check 1: Queries CameraX ExtensionsManager for `ExtensionMode.BOKEH`.
     * Returns either native extension-enabled CameraSelector or universal OpenGL ES shader strategy.
     */
    suspend fun resolveLivePreviewStrategy(
        cameraProvider: ProcessCameraProvider,
        baseSelector: CameraSelector,
        mainExecutor: Executor
    ): PortraitLiveCapability {
        return try {
            val extManager = cachedExtensionsManager ?: run {
                val future = ExtensionsManager.getInstanceAsync(appContext, cameraProvider)
                suspendCancellableCoroutine<ExtensionsManager?> { cont ->
                    future.addListener(
                        {
                            try {
                                if (cont.isActive) cont.resume(future.get())
                            } catch (_: Throwable) {
                                if (cont.isActive) cont.resume(null)
                            }
                        },
                        mainExecutor
                    )
                }?.also { cachedExtensionsManager = it }
            }
            if (extManager != null && extManager.isExtensionAvailable(baseSelector, ExtensionMode.BOKEH)) {
                val bokehSelector = extManager.getExtensionEnabledCameraSelector(baseSelector, ExtensionMode.BOKEH)
                _status.update {
                    it.copy(
                        previewStrategy = PortraitPreviewStrategy.NATIVE_OEM_BOKEH,
                        activeCaptureTier = PortraitCaptureTier.CAMERA_X_BOKEH,
                        isLiveBlurPausedByWatchdog = false
                    )
                }
                PortraitLiveCapability(
                    strategy = PortraitPreviewStrategy.NATIVE_OEM_BOKEH,
                    effectiveSelector = bokehSelector
                )
            } else {
                _status.update {
                    it.copy(
                        previewStrategy = PortraitPreviewStrategy.UNIVERSAL_GL_SHADER,
                        activeCaptureTier = resolveExpectedSoftwareTier()
                    )
                }
                PortraitLiveCapability(
                    strategy = PortraitPreviewStrategy.UNIVERSAL_GL_SHADER,
                    effectiveSelector = baseSelector
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.d(TAG, "ExtensionMode.BOKEH unavailable, using GL Bokeh shader: ${t.message}")
            _status.update {
                it.copy(
                    previewStrategy = PortraitPreviewStrategy.UNIVERSAL_GL_SHADER,
                    activeCaptureTier = resolveExpectedSoftwareTier()
                )
            }
            PortraitLiveCapability(
                strategy = PortraitPreviewStrategy.UNIVERSAL_GL_SHADER,
                effectiveSelector = baseSelector
            )
        }
    }

    private fun resolveExpectedSoftwareTier(): PortraitCaptureTier {
        return if (_status.value.subjectModelStatus == PortraitSubjectModelStatus.READY) {
            PortraitCaptureTier.MLKIT_SUBJECT_SEGMENTATION
        } else {
            PortraitCaptureTier.MLKIT_SELFIE_SEGMENTATION
        }
    }

    /**
     * Checks Google Play Services `SubjectSegmentation` availability via `ModuleInstallClient`
     * using `OptionalModuleApi` (WITHOUT calling `SubjectSegmentation.getClient(...)` prematurely,
     * avoiding background `MobileVisionBase` preload `MlKitException` when the module isn't downloaded yet).
     */
    fun ensureSubjectSegmentationReadyAsync() {
        if (subjectMlKitUnavailable) {
            _status.update { it.copy(subjectModelStatus = PortraitSubjectModelStatus.UNAVAILABLE) }
            return
        }
        val isGmsPresent = try {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS
        } catch (_: Throwable) {
            false
        }
        if (!isGmsPresent) {
            _status.update { it.copy(subjectModelStatus = PortraitSubjectModelStatus.UNAVAILABLE) }
            return
        }

        val optionalApi = resolveSubjectOptionalModuleApi()
        val client = try {
            moduleInstallClient ?: ModuleInstall.getClient(appContext).also { moduleInstallClient = it }
        } catch (_: Throwable) {
            _status.update { it.copy(subjectModelStatus = PortraitSubjectModelStatus.UNAVAILABLE) }
            return
        }

        _status.update { it.copy(subjectModelStatus = PortraitSubjectModelStatus.CHECKING) }

        try {
            client.areModulesAvailable(optionalApi)
                .addOnSuccessListener { availResp ->
                    if (availResp.areModulesAvailable()) {
                        _status.update {
                            it.copy(
                                subjectModelStatus = PortraitSubjectModelStatus.READY,
                                subjectModelProgress = 100
                            )
                        }
                    } else if (moduleInstallRequested.compareAndSet(false, true)) {
                        requestSubjectModuleInstall(client, optionalApi)
                    } else {
                        _status.update {
                            it.copy(subjectModelStatus = PortraitSubjectModelStatus.DOWNLOADING)
                        }
                    }
                }
                .addOnFailureListener {
                    _status.update { it.copy(subjectModelStatus = PortraitSubjectModelStatus.UNAVAILABLE) }
                }
        } catch (_: Throwable) {
            _status.update { it.copy(subjectModelStatus = PortraitSubjectModelStatus.UNAVAILABLE) }
        }
    }

    private fun requestSubjectModuleInstall(
        client: ModuleInstallClient,
        optionalApi: OptionalModuleApi
    ) {
        unregisterInstallListener()
        val listener = object : InstallStatusListener {
            override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                val pInfo = update.progressInfo
                val pct = if (pInfo != null && pInfo.totalBytesToDownload > 0L) {
                    ((pInfo.bytesDownloaded * 100L) / pInfo.totalBytesToDownload).toInt().coerceIn(0, 100)
                } else {
                    _status.value.subjectModelProgress
                }
                when (update.installState) {
                    ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> {
                        _status.update {
                            it.copy(
                                subjectModelStatus = PortraitSubjectModelStatus.READY,
                                subjectModelProgress = 100
                            )
                        }
                        unregisterInstallListener()
                    }
                    ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                    ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> {
                        _status.update {
                            it.copy(subjectModelStatus = PortraitSubjectModelStatus.UNAVAILABLE)
                        }
                        unregisterInstallListener()
                    }
                    else -> {
                        _status.update {
                            it.copy(
                                subjectModelStatus = PortraitSubjectModelStatus.DOWNLOADING,
                                subjectModelProgress = pct
                            )
                        }
                    }
                }
            }
        }
        activeInstallListener = listener

        val request = ModuleInstallRequest.newBuilder()
            .addApi(optionalApi)
            .setListener(listener)
            .build()

        client.installModules(request)
            .addOnSuccessListener { resp ->
                if (resp.areModulesAlreadyInstalled()) {
                    _status.update {
                        it.copy(
                            subjectModelStatus = PortraitSubjectModelStatus.READY,
                            subjectModelProgress = 100
                        )
                    }
                    unregisterInstallListener()
                } else {
                    _status.update { it.copy(subjectModelStatus = PortraitSubjectModelStatus.DOWNLOADING) }
                }
            }
            .addOnFailureListener {
                _status.update { it.copy(subjectModelStatus = PortraitSubjectModelStatus.UNAVAILABLE) }
                unregisterInstallListener()
            }
    }

    internal fun resolveSubjectOptionalModuleApi(): OptionalModuleApi {
        val feature = try {
            OptionalModuleUtils.FEATURE_SUBJECT_SEGMENTATION
        } catch (_: Throwable) {
            Feature("mlkit.segmentation.subject", 1L)
        }
        return OptionalModuleApi { arrayOf(feature) }
    }

    private fun unregisterInstallListener() {
        val listener = activeInstallListener ?: return
        activeInstallListener = null
        try {
            moduleInstallClient?.unregisterListener(listener)
        } catch (_: Throwable) {}
    }

    /**
     * Stage 1 Live Preview Frame Processor:
     * 1. Extracts Y, U, V planes cleanly and uploads to OpenGL ES `PortraitLiveBlurGLSurfaceView` (if live shader active).
     * 2. Copies an NV21 scratch byte array and closes `ImageProxy` IMMEDIATELY so CameraX never stalls.
     * 3. Runs lazily initialized `ML Kit SelfieSegmentation` (`STREAM_MODE`, `enableRawSizeMask()`) asynchronously
     *    when not already busy, updating the GPU mask texture and foreground lock telemetry.
     */
    fun processLivePreviewFrame(
        imageProxy: ImageProxy,
        isFrontCamera: Boolean,
        glView: PortraitLiveBlurGLSurfaceView?,
        onStatusTelemetry: (Boolean, Int, PortraitDepthSource, Float, Boolean) -> Unit
    ) {
        if (!isPortraitSessionActive) {
            imageProxy.close()
            return
        }

        val width = imageProxy.width
        val height = imageProxy.height
        val rotation = imageProxy.imageInfo.rotationDegrees
        val planes = imageProxy.planes
        if (width <= 2 || height <= 2 || planes.size < 3) {
            imageProxy.close()
            return
        }

        val ySize = width * height
        val uvSize = (width / 2) * (height / 2)
        val nv21Size = ySize + uvSize * 2

        try {
            if (yScratch.size < ySize) yScratch = ByteArray(ySize)
            if (uScratch.size < uvSize) uScratch = ByteArray(uvSize)
            if (vScratch.size < uvSize) vScratch = ByteArray(uvSize)
            if (nv21Scratch.size < nv21Size) nv21Scratch = ByteArray(nv21Size)

            extractYuvPlanesAndNv21(
                planes = planes,
                width = width,
                height = height,
                yOut = yScratch,
                uOut = uScratch,
                vOut = vScratch,
                nv21Out = nv21Scratch
            )
        } catch (_: Throwable) {
            imageProxy.close()
            return
        } finally {
            imageProxy.close()
        }

        // Upload live Y/U/V frame to OpenGL shader if watchdog hasn't paused live blur
        if (!fpsWatchdog.isPaused && glView != null) {
            glView.submitCameraYuvFrame(
                yBytes = yScratch,
                uBytes = uScratch,
                vBytes = vScratch,
                width = width,
                height = height,
                rotationDegrees = rotation,
                isFrontCamera = isFrontCamera
            )
        }

        // Run asynchronous SelfieSegmentation STREAM_MODE only if previous inference finished
        if (isLiveSegmentationBusy.compareAndSet(false, true)) {
            val seg = getOrCreateStreamSelfieSegmenter()
            if (seg == null) {
                isLiveSegmentationBusy.set(false)
                return
            }
            val nv21Copy = nv21Scratch.copyOf(nv21Size)
            try {
                val input = InputImage.fromByteArray(
                    nv21Copy,
                    width,
                    height,
                    rotation,
                    InputImage.IMAGE_FORMAT_NV21
                )
                seg.process(input)
                    .addOnSuccessListener { mask ->
                        if (!isPortraitSessionActive) return@addOnSuccessListener
                        val mw = mask.width
                        val mh = mask.height
                        if (mw > 0 && mh > 0) {
                            val buf = mask.buffer.order(ByteOrder.nativeOrder())
                            buf.rewind()
                            val fBuf = buf.asFloatBuffer()
                            val count = mw * mh
                            if (fBuf.remaining() >= count) {
                                val alphas = FloatArray(count)
                                fBuf.get(alphas)
                                val hasSubject = hasMeaningfulForeground(alphas)
                                if (!fpsWatchdog.isPaused) {
                                    glView?.submitSegmentationMask(alphas, mw, mh)
                                }
                                val subjectCount = if (hasSubject) 1 else 0
                                _status.update {
                                    it.copy(
                                        isSubjectDetected = hasSubject,
                                        subjectCount = subjectCount
                                    )
                                }
                                onStatusTelemetry(
                                    hasSubject,
                                    subjectCount,
                                    PortraitDepthSource.AI_SEGMENTATION,
                                    fpsWatchdog.lastMeasuredFps,
                                    fpsWatchdog.isPaused
                                )
                            }
                        }
                    }
                    .addOnCompleteListener {
                        isLiveSegmentationBusy.set(false)
                    }
            } catch (_: Throwable) {
                isLiveSegmentationBusy.set(false)
            }
        }
    }

    /**
     * Records one rendered GL frame for FPS watchdog tracking; notifies if live blur just tripped paused state.
     */
    fun notifyGlFrameRendered(
        onWatchdogTriggered: (fps: Float) -> Unit
    ) {
        if (!isPortraitSessionActive) return
        val wasPaused = fpsWatchdog.isPaused
        val nowPaused = fpsWatchdog.onFrameRendered()
        if (!wasPaused && nowPaused) {
            _status.update {
                it.copy(
                    isLiveBlurPausedByWatchdog = true,
                    livePreviewFps = fpsWatchdog.lastMeasuredFps
                )
            }
            onWatchdogTriggered(fpsWatchdog.lastMeasuredFps)
        }
    }

    /**
     * Stage 2: Runs the strict 4-tier capture waterfall on the ~12MP-capped upright bitmap:
     * 1. `ExtensionMode.BOKEH` -> handled natively by OEM HAL, skip double software blur
     * 2. Hardware `DEPTH16` -> millimeter decode + hole-filling fused with highest-quality available matte
     * 3. `ML Kit Subject Segmentation` (GMS) + Guided Filter (people, pets, food, objects)
     * 4. Bundled `ML Kit Selfie Segmentation` (`SINGLE_IMAGE_MODE`) + Guided Filter (+ deterministic software fallback)
     */
    fun executeCaptureWaterfall(
        uprightBitmap: Bitmap,
        isOemExtensionCapture: Boolean,
        focusX: Float?,
        focusY: Float?,
        hardwareDepthProvider: (() -> HardwareDepthFrame?)? = null,
        subjectMaskOverride: Pair<FloatArray, Pair<Int, Int>>? = null,
        selfieMaskOverride: Pair<FloatArray, Pair<Int, Int>>? = null
    ): PortraitCaptureWaterfallResult {
        // Tier 1: Native CameraX ExtensionMode.BOKEH already rendered DSLR bokeh in the JPEG
        if (isOemExtensionCapture) {
            _status.update { it.copy(activeCaptureTier = PortraitCaptureTier.CAMERA_X_BOKEH) }
            return PortraitCaptureWaterfallResult(
                captureTier = PortraitCaptureTier.CAMERA_X_BOKEH,
                segmentation = null,
                handledByOemExtension = true
            )
        }

        // Cap bitmap to ~12MP (max 4000px longest edge) before segmentation/bokeh to protect 4GB devices
        val cappedBitmap = capPortraitResolution(uprightBitmap, MAX_PORTRAIT_EDGE_PX)
        val shouldRecycleCapped = (cappedBitmap !== uprightBitmap)

        try {
            // Tier 2: Hardware DEPTH16 millimeter stream + hole-filling (when available)
            val hwDepthFrame = try {
                hardwareDepthProvider?.invoke()
            } catch (_: Throwable) {
                null
            }

            // Tier 3: Try ML Kit Subject Segmentation (GMS) for people, pets, food, and objects
            val subjectMask = subjectMaskOverride ?: trySubjectSegmentationForCapture(cappedBitmap)

            val segResult = if (subjectMask != null) {
                val (rawAlpha, dims) = subjectMask
                PortraitSegmentationEngine.finishSegmentationWithRawAlpha(
                    uprightBitmap = cappedBitmap,
                    isPreviewStream = false,
                    focusX = focusX,
                    focusY = focusY,
                    hardwareDepthFrame = hwDepthFrame,
                    precomputedRawAlpha = rawAlpha,
                    precomputedWidth = dims.first,
                    precomputedHeight = dims.second,
                    requestedTier = PortraitCaptureTier.MLKIT_SUBJECT_SEGMENTATION
                )
            } else if (selfieMaskOverride != null) {
                val (rawAlpha, dims) = selfieMaskOverride
                PortraitSegmentationEngine.finishSegmentationWithRawAlpha(
                    uprightBitmap = cappedBitmap,
                    isPreviewStream = false,
                    focusX = focusX,
                    focusY = focusY,
                    hardwareDepthFrame = hwDepthFrame,
                    precomputedRawAlpha = rawAlpha,
                    precomputedWidth = dims.first,
                    precomputedHeight = dims.second,
                    requestedTier = PortraitCaptureTier.MLKIT_SELFIE_SEGMENTATION
                )
            } else {
                // Tier 4: Bundled offline ML Kit SelfieSegmentation SINGLE_IMAGE_MODE -> software fallback
                val bundledSelfieMask = tryBundledSelfieSingleImage(cappedBitmap)
                if (bundledSelfieMask != null) {
                    val (rawAlpha, dims) = bundledSelfieMask
                    PortraitSegmentationEngine.finishSegmentationWithRawAlpha(
                        uprightBitmap = cappedBitmap,
                        isPreviewStream = false,
                        focusX = focusX,
                        focusY = focusY,
                        hardwareDepthFrame = hwDepthFrame,
                        precomputedRawAlpha = rawAlpha,
                        precomputedWidth = dims.first,
                        precomputedHeight = dims.second,
                        requestedTier = PortraitCaptureTier.MLKIT_SELFIE_SEGMENTATION
                    )
                } else {
                    PortraitSegmentationEngine.finishSegmentationWithRawAlpha(
                        uprightBitmap = cappedBitmap,
                        isPreviewStream = false,
                        focusX = focusX,
                        focusY = focusY,
                        hardwareDepthFrame = hwDepthFrame
                    )
                }
            }

            val resolvedTier = when {
                hwDepthFrame != null && hwDepthFrame.validFraction > 0.08f -> PortraitCaptureTier.HARDWARE_DEPTH16
                else -> segResult.captureTier
            }

            val finalSeg = segResult.copy(captureTier = resolvedTier)
            _status.update {
                it.copy(
                    activeCaptureTier = resolvedTier,
                    isSubjectDetected = finalSeg.hasSubject,
                    subjectCount = finalSeg.subjectCount
                )
            }
            return PortraitCaptureWaterfallResult(
                captureTier = resolvedTier,
                segmentation = finalSeg,
                handledByOemExtension = false
            )
        } finally {
            if (shouldRecycleCapped && !cappedBitmap.isRecycled) {
                cappedBitmap.recycle()
            }
        }
    }

    private fun trySubjectSegmentationForCapture(bitmap: Bitmap): Pair<FloatArray, Pair<Int, Int>>? {
        if (subjectMlKitUnavailable) return null
        val isGmsPresent = try {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS
        } catch (_: Throwable) {
            false
        }
        if (!isGmsPresent) return null

        val optionalApi = resolveSubjectOptionalModuleApi()
        val client = try {
            moduleInstallClient ?: ModuleInstall.getClient(appContext).also { moduleInstallClient = it }
        } catch (_: Throwable) {
            null
        }

        // Verify optional module is installed via OptionalModuleApi BEFORE instantiating SubjectSegmenter,
        // preventing MlKitException preload errors when offline or downloading.
        if (_status.value.subjectModelStatus != PortraitSubjectModelStatus.READY) {
            val isReadyNow = if (client != null) {
                try {
                    val avail = Tasks.await(client.areModulesAvailable(optionalApi), 300L, TimeUnit.MILLISECONDS)
                    avail.areModulesAvailable()
                } catch (_: Throwable) {
                    false
                }
            } else {
                false
            }
            if (!isReadyNow) {
                return null
            }
            _status.update {
                it.copy(
                    subjectModelStatus = PortraitSubjectModelStatus.READY,
                    subjectModelProgress = 100
                )
            }
        }

        val seg = getOrCreateSubjectSegmenter() ?: return null

        return try {
            // Downscale working copy slightly for fast, accurate inference
            val workScale = min(1f, 512f / max(bitmap.width, bitmap.height).coerceAtLeast(1))
            val workW = (bitmap.width * workScale).toInt().coerceAtLeast(32)
            val workH = (bitmap.height * workScale).toInt().coerceAtLeast(32)
            val workBmp = if (workW == bitmap.width && workH == bitmap.height) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, workW, workH, true)
            }
            try {
                val input = InputImage.fromBitmap(workBmp, 0)
                val result = Tasks.await(seg.process(input), 2600L, TimeUnit.MILLISECONDS)
                val maskBuf = result.foregroundConfidenceMask ?: return null
                maskBuf.rewind()
                val count = workW * workH
                if (maskBuf.remaining() < count) return null
                val out = FloatArray(count)
                maskBuf.get(out)
                if (!hasMeaningfulForeground(out)) return null
                out to (workW to workH)
            } finally {
                if (workBmp !== bitmap && !workBmp.isRecycled) workBmp.recycle()
            }
        } catch (t: Throwable) {
            if (t is UnsatisfiedLinkError || t.cause is UnsatisfiedLinkError) {
                subjectMlKitUnavailable = true
            }
            Log.d(TAG, "Tier 3 SubjectSegmentation fallback -> Tier 4 SelfieSegmentation: ${t.message}")
            null
        }
    }

    private fun tryBundledSelfieSingleImage(bitmap: Bitmap): Pair<FloatArray, Pair<Int, Int>>? {
        if (selfieMlKitUnavailable) return null
        val seg = getOrCreateSingleImageSelfieSegmenter() ?: return null
        return try {
            val workScale = min(1f, 384f / max(bitmap.width, bitmap.height).coerceAtLeast(1))
            val workW = (bitmap.width * workScale).toInt().coerceAtLeast(32)
            val workH = (bitmap.height * workScale).toInt().coerceAtLeast(32)
            val workBmp = if (workW == bitmap.width && workH == bitmap.height) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, workW, workH, true)
            }
            try {
                val input = InputImage.fromBitmap(workBmp, 0)
                val mask = Tasks.await(seg.process(input), 2200L, TimeUnit.MILLISECONDS)
                val mw = mask.width
                val mh = mask.height
                if (mw <= 0 || mh <= 0) return null
                val buf = mask.buffer.order(ByteOrder.nativeOrder())
                buf.rewind()
                val floatBuf = buf.asFloatBuffer()
                val count = mw * mh
                if (floatBuf.remaining() < count) return null
                val out = FloatArray(count)
                floatBuf.get(out)
                if (!hasMeaningfulForeground(out)) return null
                out to (mw to mh)
            } finally {
                if (workBmp !== bitmap && !workBmp.isRecycled) workBmp.recycle()
            }
        } catch (t: Throwable) {
            if (t is UnsatisfiedLinkError || t.cause is UnsatisfiedLinkError) {
                selfieMlKitUnavailable = true
            }
            null
        }
    }

    private fun getOrCreateStreamSelfieSegmenter(): Segmenter? {
        if (selfieMlKitUnavailable) return null
        return streamSelfieSegmenter ?: synchronized(this) {
            if (selfieMlKitUnavailable) return@synchronized null
            streamSelfieSegmenter ?: try {
                val options = SelfieSegmenterOptions.Builder()
                    .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                    .enableRawSizeMask()
                    .build()
                Segmentation.getClient(options).also { streamSelfieSegmenter = it }
            } catch (_: Throwable) {
                selfieMlKitUnavailable = true
                null
            }
        }
    }

    private fun getOrCreateSingleImageSelfieSegmenter(): Segmenter? {
        if (selfieMlKitUnavailable) return null
        return singleImageSelfieSegmenter ?: synchronized(this) {
            if (selfieMlKitUnavailable) return@synchronized null
            singleImageSelfieSegmenter ?: try {
                val options = SelfieSegmenterOptions.Builder()
                    .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                    .enableRawSizeMask()
                    .build()
                Segmentation.getClient(options).also { singleImageSelfieSegmenter = it }
            } catch (_: Throwable) {
                selfieMlKitUnavailable = true
                null
            }
        }
    }

    private fun getOrCreateSubjectSegmenter(): SubjectSegmenter? {
        if (subjectMlKitUnavailable) return null
        return subjectSegmenter ?: synchronized(this) {
            if (subjectMlKitUnavailable) return@synchronized null
            subjectSegmenter ?: try {
                val options = SubjectSegmenterOptions.Builder()
                    .enableForegroundConfidenceMask()
                    .build()
                SubjectSegmentation.getClient(options).also { subjectSegmenter = it }
            } catch (_: Throwable) {
                subjectMlKitUnavailable = true
                null
            }
        }
    }

    private fun closeSegmenters() {
        try { streamSelfieSegmenter?.close() } catch (_: Throwable) {}
        try { singleImageSelfieSegmenter?.close() } catch (_: Throwable) {}
        try { subjectSegmenter?.close() } catch (_: Throwable) {}
        streamSelfieSegmenter = null
        singleImageSelfieSegmenter = null
        subjectSegmenter = null
        PortraitSegmentationEngine.close()
    }

    private fun hasMeaningfulForeground(mask: FloatArray): Boolean {
        if (mask.isEmpty()) return false
        var fg = 0
        val step = max(1, mask.size / 1024)
        var total = 0
        var i = 0
        while (i < mask.size) {
            total++
            if (mask[i] > 0.45f) fg++
            i += step
        }
        return total > 0 && fg.toFloat() / total.toFloat() > 0.02f
    }

    private fun extractYuvPlanesAndNv21(
        planes: Array<ImageProxy.PlaneProxy>,
        width: Int,
        height: Int,
        yOut: ByteArray,
        uOut: ByteArray,
        vOut: ByteArray,
        nv21Out: ByteArray
    ) {
        val yPlane = planes[0]
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val yLimit = yBuf.limit()

        if (yPixelStride == 1 && yRowStride == width && yLimit >= width * height) {
            yBuf.rewind()
            yBuf.get(yOut, 0, width * height)
        } else {
            var dst = 0
            for (r in 0 until height) {
                val rowOff = r * yRowStride
                if (yPixelStride == 1 && rowOff + width <= yLimit) {
                    yBuf.position(rowOff)
                    yBuf.get(yOut, dst, width)
                    dst += width
                } else {
                    for (c in 0 until width) {
                        val idx = rowOff + c * yPixelStride
                        yOut[dst++] = if (idx in 0 until yLimit) yBuf.get(idx) else 0
                    }
                }
            }
        }
        System.arraycopy(yOut, 0, nv21Out, 0, width * height)

        val uPlane = planes[1]
        val vPlane = planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uLimit = uBuf.limit()
        val vLimit = vBuf.limit()

        val chromaW = width / 2
        val chromaH = height / 2
        var uvDst = 0
        var nvDst = width * height
        for (r in 0 until chromaH) {
            val uRowOff = r * uRowStride
            val vRowOff = r * vRowStride
            for (c in 0 until chromaW) {
                val uIdx = uRowOff + c * uPixelStride
                val vIdx = vRowOff + c * vPixelStride
                val uByte = if (uIdx in 0 until uLimit) uBuf.get(uIdx) else 128.toByte()
                val vByte = if (vIdx in 0 until vLimit) vBuf.get(vIdx) else 128.toByte()
                uOut[uvDst] = uByte
                vOut[uvDst] = vByte
                uvDst++
                nv21Out[nvDst++] = vByte
                nv21Out[nvDst++] = uByte
            }
        }
    }

    companion object {
        private const val TAG = "PortraitPipelineManager"
        const val MAX_PORTRAIT_EDGE_PX = 4000

        /**
         * Computes a safe power-of-two BitmapFactory `inSampleSize` so 50MP/108MP JPEGs never exhaust heap
         * while retaining at least ~12MP (`maxLongestEdge` = 4000px).
         */
        fun computePortraitDecodeSampleSize(
            rawWidth: Int,
            rawHeight: Int,
            maxLongestEdge: Int = MAX_PORTRAIT_EDGE_PX
        ): Int {
            val longest = max(rawWidth, rawHeight)
            if (longest <= maxLongestEdge || maxLongestEdge <= 0) return 1
            var sampleSize = 1
            while ((longest / (sampleSize * 2)) >= maxLongestEdge) {
                sampleSize *= 2
            }
            return sampleSize.coerceAtLeast(1)
        }

        /**
         * Caps a decoded upright bitmap to [maxLongestEdge] (default 4000px ~12MP) preserving aspect ratio
         * to prevent OutOfMemoryError on 50MP+ sensors during Portrait capture compositing.
         */
        fun capPortraitResolution(
            bitmap: Bitmap,
            maxLongestEdge: Int = MAX_PORTRAIT_EDGE_PX
        ): Bitmap {
            val w = bitmap.width
            val h = bitmap.height
            val longest = max(w, h)
            if (longest <= maxLongestEdge || longest <= 0 || maxLongestEdge <= 0) {
                return bitmap
            }
            val scale = maxLongestEdge.toFloat() / longest.toFloat()
            val targetW = (w * scale).toInt().coerceAtLeast(1)
            val targetH = (h * scale).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        }
    }
}
