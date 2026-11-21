package com.example.ui.components

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.os.Build
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.util.CameraLiveFilterEngine
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.GlassCoolBlueSpecular
import com.example.ui.theme.GlassWarmAmberSpecular
import com.example.model.CameraFilter
import com.example.model.CameraMode
import com.example.model.CameraUiState
import com.example.model.CaptureAspectRatio
import com.example.model.CapturePriority
import com.example.model.GridOption
import com.example.model.VideoCodecOption
import com.example.model.VideoFpsOption
import com.example.model.VideoQualityOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private data class CameraCoreBindingConfig(
    val lensFacing: Int,
    val aspectRatio: CaptureAspectRatio,
    val isVideoMode: Boolean,
    val capturePriority: CapturePriority?,
    val videoQuality: VideoQualityOption?,
    val videoFps: VideoFpsOption?,
    val videoCodec: VideoCodecOption?,
    val isVideoStabilizationEnabled: Boolean?,
    val targetCaptureSize: android.util.Size?,
    val viewfinderRebindKey: Long
)

private data class CameraBindingRequest(
    val core: CameraCoreBindingConfig,
    val isAnalysisNeeded: Boolean
)

private class ViewfinderSessionHolder {
    var cameraProvider: ProcessCameraProvider? = null
    var boundCamera: androidx.camera.core.Camera? = null
    var boundPreview: Preview? = null
    var boundImageCapture: ImageCapture? = null
    var boundVideoCapture: VideoCapture<Recorder>? = null
    var boundImageAnalysis: ImageAnalysis? = null
    var boundConfig: CameraBindingRequest? = null
    var analysisExecutor: ExecutorService? = null
    var cameraStateObserver: Observer<CameraState>? = null
    var boundCameraStateLiveData: androidx.lifecycle.LiveData<CameraState>? = null
    var orientationEventListener: OrientationEventListener? = null
    val isDisposed = AtomicBoolean(false)
    val isAnalysisPaused = AtomicBoolean(false)
    val isHistogramActive = AtomicBoolean(false)
    val isBackdropActive = AtomicBoolean(false)
    val isFrontCamera = AtomicBoolean(false)
    val isGestureBlocked = AtomicBoolean(false)
    var isSurfaceProviderAttached: Boolean = false

    fun currentlyBoundUseCases(): List<androidx.camera.core.UseCase> {
        return listOfNotNull(boundPreview, boundImageCapture, boundVideoCapture, boundImageAnalysis)
    }

    fun clearCameraStateObserver() {
        cameraStateObserver?.let { obs ->
            try {
                boundCameraStateLiveData?.removeObserver(obs)
            } catch (_: Throwable) {}
        }
        cameraStateObserver = null
        boundCameraStateLiveData = null
    }

    fun clearAnalysis() {
        try {
            boundImageAnalysis?.clearAnalyzer()
        } catch (_: Throwable) {}
        try {
            analysisExecutor?.shutdownNow()
        } catch (_: Throwable) {}
        analysisExecutor = null
        boundImageAnalysis = null
    }
}

private suspend fun com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider>.awaitProvider(
    executor: java.util.concurrent.Executor
): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
    addListener(
        {
            try {
                if (cont.isActive) {
                    cont.resume(get())
                }
            } catch (e: Throwable) {
                if (cont.isActive) {
                    cont.resumeWithException(e)
                }
            }
        },
        executor
    )
}

private inline fun runOnMainThreadSafe(crossinline action: () -> Unit) {
    val mainLooper = android.os.Looper.getMainLooper()
    if (android.os.Looper.myLooper() == mainLooper) {
        try {
            action()
        } catch (_: Throwable) {}
    } else {
        android.os.Handler(mainLooper).post {
            try {
                action()
            } catch (_: Throwable) {}
        }
    }
}

private suspend fun awaitViewAttachedAndLaidOut(view: View) {
    if (view.isAttachedToWindow && view.width > 0 && view.height > 0) return
    withTimeoutOrNull(150L) {
        suspendCancellableCoroutine<Unit> { cont ->
            val finished = AtomicBoolean(false)
            lateinit var attachListener: View.OnAttachStateChangeListener
            lateinit var layoutListener: View.OnLayoutChangeListener
            fun cleanup() {
                runOnMainThreadSafe {
                    view.removeOnAttachStateChangeListener(attachListener)
                    view.removeOnLayoutChangeListener(layoutListener)
                }
            }
            fun tryResume() {
                if (view.isAttachedToWindow && view.width > 0 && view.height > 0 && finished.compareAndSet(false, true)) {
                    cleanup()
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            attachListener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = tryResume()
                override fun onViewDetachedFromWindow(v: View) {}
            }
            layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                tryResume()
            }
            runOnMainThreadSafe {
                if (!finished.get()) {
                    view.addOnAttachStateChangeListener(attachListener)
                    view.addOnLayoutChangeListener(layoutListener)
                    tryResume()
                }
            }
            cont.invokeOnCancellation {
                finished.set(true)
                cleanup()
            }
        }
    }
}

private suspend fun awaitCameraClosed(camera: androidx.camera.core.Camera) {
    awaitCameraStateClosed(camera.cameraInfo.cameraState, timeoutMs = 500L)
}

internal suspend fun awaitCameraStateClosed(
    stateLiveData: androidx.lifecycle.LiveData<CameraState>,
    timeoutMs: Long = 500L
) {
    if (stateLiveData.value?.type == CameraState.Type.CLOSED) return
    withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<Unit> { cont ->
            val finished = AtomicBoolean(false)
            lateinit var observer: Observer<CameraState>
            observer = Observer { state ->
                if (state.type == CameraState.Type.CLOSED && finished.compareAndSet(false, true)) {
                    runOnMainThreadSafe {
                        stateLiveData.removeObserver(observer)
                    }
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            runOnMainThreadSafe {
                if (!finished.get()) {
                    if (stateLiveData.value?.type == CameraState.Type.CLOSED && finished.compareAndSet(false, true)) {
                        if (cont.isActive) cont.resume(Unit)
                    } else {
                        stateLiveData.observeForever(observer)
                    }
                }
            }
            cont.invokeOnCancellation {
                finished.set(true)
                runOnMainThreadSafe {
                    stateLiveData.removeObserver(observer)
                }
            }
        }
    }
}

@Composable
fun CameraViewfinder(
    uiState: CameraUiState,
    onCameraBound: (androidx.camera.core.Camera, ProcessCameraProvider, ImageCapture?, VideoCapture<Recorder>?) -> Unit,
    onStreamStateChanged: (Boolean) -> Unit,
    onPinchZoom: (Float) -> Unit,
    onTapToFocus: (MeteringPointFactory, Float, Float) -> Unit,
    onExposureChanged: (Int) -> Unit = {},
    onKeepFocusActive: () -> Unit = {},
    onResolutionConfigured: ((android.util.Size) -> Unit)? = null,
    onCameraError: (String, Boolean) -> Unit = { _, _ -> },
    onTargetRotationChanged: (Int) -> Unit = {},
    onHistogramData: (List<Float>) -> Unit = {},
    onActiveLensFocalLengthChanged: (Float?, String?) -> Unit = { _, _ -> },
    onFullScreenAspectRatioChanged: (Float) -> Unit = {},
    orientationState: kotlinx.coroutines.flow.StateFlow<com.example.model.DeviceOrientationState>? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var previewViewInstance by remember { mutableStateOf<PreviewView?>(null) }
    val session = remember { ViewfinderSessionHolder() }
    val shutterAlpha = remember { Animatable(0f) }

    val latestUiState by rememberUpdatedState(uiState)
    val latestOnCameraBound by rememberUpdatedState(onCameraBound)
    val latestOnStreamStateChanged by rememberUpdatedState(onStreamStateChanged)
    val latestOnResolutionConfigured by rememberUpdatedState(onResolutionConfigured)
    val latestOnCameraError by rememberUpdatedState(onCameraError)
    val latestOnTargetRotationChanged by rememberUpdatedState(onTargetRotationChanged)
    val latestOnHistogramData by rememberUpdatedState(onHistogramData)
    val latestOnActiveLensFocalLengthChanged by rememberUpdatedState(onActiveLensFocalLengthChanged)
    val latestOnFullScreenAspectRatioChanged by rememberUpdatedState(onFullScreenAspectRatioChanged)

    val isOverlayBlocking = uiState.isSettingsOpen ||
        uiState.isWatermarkStudioOpen ||
        uiState.isPhotoEditorOpen ||
        uiState.isRecycleBinOpen ||
        (uiState.isGalleryOpen && uiState.galleryItems.isNotEmpty())
    val shouldPausePreview = isOverlayBlocking && !uiState.isRecordingVideo
    val isVideoMode = uiState.cameraMode == CameraMode.VIDEO

    val isLowRamDevice = remember(context) {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
    }
    var blurredBackdropBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val isFramedBackdropWanted = !isVideoMode &&
        uiState.aspectRatio != CaptureAspectRatio.RATIO_FULL &&
        !shouldPausePreview &&
        uiState.isStreaming &&
        !isLowRamDevice

    LaunchedEffect(isFramedBackdropWanted) {
        if (!isFramedBackdropWanted) {
            blurredBackdropBitmap = null
        }
    }

    SideEffect {
        session.isAnalysisPaused.set(shouldPausePreview || (!uiState.isHistogramEnabled && !isFramedBackdropWanted) || isVideoMode)
        session.isHistogramActive.set(!shouldPausePreview && uiState.isHistogramEnabled && !isVideoMode)
        session.isBackdropActive.set(isFramedBackdropWanted)
        session.isFrontCamera.set(uiState.lensFacing == CameraSelector.LENS_FACING_FRONT)
        session.isGestureBlocked.set(isOverlayBlocking)
    }

    // Flash dip animation on capture
    LaunchedEffect(uiState.shutterFlashTrigger) {
        if (uiState.shutterFlashTrigger > 0L) {
            shutterAlpha.snapTo(0.85f)
            shutterAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 140)
            )
        }
    }

    val activeAspectRatio = if (isVideoMode) uiState.videoAspectRatio else uiState.aspectRatio

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val density = LocalDensity.current
        val isFullScreen = activeAspectRatio == CaptureAspectRatio.RATIO_FULL
        val isRoundedFrame = activeAspectRatio == CaptureAspectRatio.RATIO_4_3 ||
            activeAspectRatio == CaptureAspectRatio.RATIO_1_1 ||
            activeAspectRatio == CaptureAspectRatio.RATIO_16_9

        // Softly blurred downscaled live preview extension + cached atmospheric obsidian scrim behind framed previews (4:3, 1:1, 16:9)
        if (!isFullScreen) {
            val activeFilterTint = if (uiState.selectedFilter != CameraFilter.NATURAL) {
                uiState.selectedFilter.tintColor
            } else {
                GlassCoolBlueSpecular
            }
            val backdropBlurEffect = remember {
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        RenderEffect.createBlurEffect(26f, 26f, android.graphics.Shader.TileMode.CLAMP)
                            .asComposeRenderEffect()
                    } catch (_: Throwable) {
                        null
                    }
                } else {
                    null
                }
            }
            val backdropBmp = blurredBackdropBitmap
            if (backdropBmp != null && !backdropBmp.isRecycled) {
                Image(
                    bitmap = backdropBmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.36f)
                        .then(
                            if (backdropBlurEffect != null) {
                                Modifier.graphicsLayer { renderEffect = backdropBlurEffect }
                            } else {
                                Modifier
                            }
                        )
                        .testTag("viewfinder_live_blurred_backdrop")
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val w = size.width
                        val h = size.height
                        val center = Offset(w * 0.5f, h * 0.48f)
                        val maxDim = maxOf(w, h)

                        // Deep obsidian canvas base (translucent if live downscaled backdrop is active)
                        val baseAlpha = if (backdropBmp != null) 0.54f else 1.0f
                        val baseColor = Color(0xFF05070B).copy(alpha = baseAlpha)

                        // Soft atmospheric bloom behind the viewfinder boundaries (zero full-res blur lag)
                        val centerBloom = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.0f to activeFilterTint.copy(alpha = 0.10f),
                                0.35f to Color(0xFF161C27).copy(alpha = if (backdropBmp != null) 0.36f else 0.58f),
                                0.72f to Color(0xFF0A0D13).copy(alpha = if (backdropBmp != null) 0.62f else 0.88f),
                                1.0f to Color(0xFF05070B).copy(alpha = if (backdropBmp != null) 0.84f else 1.0f)
                            ),
                            center = center,
                            radius = maxDim * 0.56f
                        )

                        // Subtle upper-left cool ambient diffusion and lower-right warm diffusion
                        val coolUpperBloom = Brush.radialGradient(
                            colors = listOf(
                                GlassCoolBlueSpecular.copy(alpha = 0.07f),
                                Color.Transparent
                            ),
                            center = Offset(w * 0.18f, h * 0.18f),
                            radius = maxDim * 0.42f
                        )
                        val warmLowerBloom = Brush.radialGradient(
                            colors = listOf(
                                GlassWarmAmberSpecular.copy(alpha = 0.05f),
                                Color.Transparent
                            ),
                            center = Offset(w * 0.84f, h * 0.76f),
                            radius = maxDim * 0.42f
                        )

                        // Restrained dark vertical scrim to maintain crisp top/bottom glass control contrast
                        val controlContrastScrim = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Black.copy(alpha = 0.48f),
                                0.18f to Color.Black.copy(alpha = 0.18f),
                                0.50f to Color.Transparent,
                                0.78f to Color.Black.copy(alpha = 0.20f),
                                1.0f to Color.Black.copy(alpha = 0.52f)
                            )
                        )

                        onDrawBehind {
                            drawRect(color = baseColor)
                            drawRect(brush = centerBloom)
                            drawRect(brush = coolUpperBloom)
                            drawRect(brush = warmLowerBloom)
                            drawRect(brush = controlContrastScrim)
                        }
                    }
            )
        }

        val containerAspect = if (maxHeight > 0.dp) maxWidth / maxHeight else (9f / 16f)
        LaunchedEffect(containerAspect) {
            if (containerAspect.isFinite() && containerAspect > 0f) {
                latestOnFullScreenAspectRatioChanged(containerAspect)
            }
        }
        val targetAspect = when (activeAspectRatio) {
            CaptureAspectRatio.RATIO_4_3 -> 3f / 4f
            CaptureAspectRatio.RATIO_16_9 -> 9f / 16f
            CaptureAspectRatio.RATIO_1_1 -> 1f
            CaptureAspectRatio.RATIO_FULL -> containerAspect
        }
        val animatedAspect by animateFloatAsState(
            targetValue = targetAspect,
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
            label = "viewfinder_aspect_ratio"
        )

        // ~20-24px subtle rounded corners for framed previews (4:3, 16:9, and 1:1)
        val targetCornerRadius = if (isRoundedFrame) 12.dp else 0.dp
        val animatedCornerRadius by animateDpAsState(
            targetValue = targetCornerRadius,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
            label = "viewfinder_corner_radius"
        )
        val cornerRadiusPx = with(density) { animatedCornerRadius.toPx() }
        val frameShape = RoundedCornerShape(animatedCornerRadius)

        val baseSizeModifier = if (isFullScreen && kotlin.math.abs(animatedAspect - containerAspect) < 0.01f) {
            Modifier.fillMaxSize()
        } else {
            val hPad = if (isRoundedFrame) 10.dp else 0.dp
            val vPad = if (isRoundedFrame) 10.dp else 0.dp
            val availableW = (maxWidth - hPad * 2).coerceAtLeast(0.dp)
            val availableH = (maxHeight - vPad * 2).coerceAtLeast(0.dp)
            val matchHeightFirst = if (availableH > 0.dp && availableW > 0.dp && animatedAspect > 0f) {
                (availableW / animatedAspect) > availableH
            } else {
                animatedAspect < containerAspect
            }

            val fitModifier = if (matchHeightFirst) {
                Modifier
                    .fillMaxHeight()
                    .padding(vertical = vPad)
                    .aspectRatio(animatedAspect, matchHeightConstraintsFirst = true)
            } else {
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = hPad)
                    .aspectRatio(animatedAspect, matchHeightConstraintsFirst = false)
            }

            fitModifier
                .align(Alignment.Center)
                .clip(frameShape)
                .clipToBounds()
        }

        val framedBorderModifier = if (animatedCornerRadius > 0.5.dp) {
            Modifier
                .drawWithCache {
                    val cr = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                    // Subtle boundary gradient falloff around the framed preview edge
                    val boundaryGradient = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.22f),
                            0.10f to Color.Transparent,
                            0.90f to Color.Transparent,
                            1.0f to Color.Black.copy(alpha = 0.26f)
                        )
                    )
                    onDrawWithContent {
                        drawContent()
                        drawRoundRect(brush = boundaryGradient, cornerRadius = cr)
                    }
                }
                .border(
                    width = 0.75.dp,
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0.0f to GlassCoolBlueSpecular.copy(alpha = 0.30f),
                            0.30f to Color.White.copy(alpha = 0.18f),
                            0.62f to Color.White.copy(alpha = 0.06f),
                            1.0f to GlassWarmAmberSpecular.copy(alpha = 0.22f)
                        )
                    ),
                    shape = frameShape
                )
        } else {
            Modifier
        }

        var viewfinderSizePx by remember { mutableStateOf(IntSize.Zero) }

        Box(
            modifier = baseSizeModifier
                .then(framedBorderModifier)
                .onSizeChanged { viewfinderSizePx = it }
                .pointerInput(uiState.lensFacing, isVideoMode, activeAspectRatio) {
                    detectTransformGestures { _, _, zoomChange, _ ->
                        if (!session.isGestureBlocked.get() && zoomChange != 1.0f) {
                            onPinchZoom(zoomChange)
                        }
                    }
                }
                .pointerInput(uiState.lensFacing, isVideoMode, activeAspectRatio) {
                    detectTapGestures { offset ->
                        if (!session.isGestureBlocked.get()) {
                            previewViewInstance?.let { pv ->
                                onTapToFocus(pv.meteringPointFactory, offset.x, offset.y)
                            }
                        }
                    }
                }
                .testTag("camera_viewfinder_frame")
        ) {
            val liveFilterEffect = remember(uiState.selectedFilter, viewfinderSizePx) {
                CameraLiveFilterEngine.resolveLiveViewfinderEffect(
                    filter = uiState.selectedFilter,
                    widthPx = viewfinderSizePx.width.toFloat(),
                    heightPx = viewfinderSizePx.height.toFloat()
                )
            }
            val previewModifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    renderEffect = if (!shouldPausePreview) liveFilterEffect.composeRenderEffect else null
                }

            AndroidView(
                modifier = previewModifier,
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        // TextureView (COMPATIBLE) integrates cleanly with Compose clipping, rounded frames,
                        // RenderEffect filters, and splash transitions without SurfaceView surfaceDestroyed
                        // invalidating CameraX Preview mid-CaptureSession.
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        previewViewInstance = this
                    }
                },
                update = { previewView ->
                    if (previewViewInstance != previewView) {
                        previewViewInstance = previewView
                    }
                    // On API < 31 (where Compose RenderEffect is unavailable), apply ColorMatrixColorFilter
                    // directly on PreviewView's internal TextureView hardware layer so live filters still render.
                    val fallbackFilter = if (!shouldPausePreview && liveFilterEffect.composeRenderEffect == null) {
                        liveFilterEffect.colorMatrixFilter
                    } else {
                        null
                    }
                    for (i in 0 until previewView.childCount) {
                        val tv = previewView.getChildAt(i) as? android.view.TextureView ?: continue
                        if (fallbackFilter != null) {
                            val paint = android.graphics.Paint().apply {
                                colorFilter = fallbackFilter
                            }
                            if (tv.layerType != View.LAYER_TYPE_HARDWARE) {
                                tv.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
                            } else {
                                tv.setLayerPaint(paint)
                            }
                        } else if (tv.layerType != View.LAYER_TYPE_NONE) {
                            tv.setLayerType(View.LAYER_TYPE_NONE, null)
                        }
                    }
                }
            )

            // Skip GPU/Compose viewfinder HUD overlays while full-screen modal screens cover the preview
            if (!shouldPausePreview) {
                // Only draw calibrated Compose atmosphere overlay on fallback API < 33 paths;
                // API 33+ AGSL RuntimeShader renders split-toning & vignette directly per-pixel on GPU.
                if (uiState.selectedFilter != CameraFilter.NATURAL && !liveFilterEffect.isFullShaderActive) {
                    FilterAtmosphereOverlay(filter = uiState.selectedFilter)
                }

                // Framing Grid overlay
                CameraGridView(gridOption = uiState.selectedGrid)

                // Artificial Horizon Level Indicator (isolated state collection to avoid viewfinder recompositions)
                if (uiState.isLevelIndicatorEnabled) {
                    val orientation = if (orientationState != null) {
                        orientationState.collectAsState().value
                    } else {
                        com.example.model.DeviceOrientationState(
                            rollAngle = uiState.deviceRollAngle,
                            pitchAngle = uiState.devicePitchAngle,
                            isLevel = uiState.isDeviceLevel
                        )
                    }
                    LevelIndicatorView(
                        rollAngle = orientation.rollAngle,
                        pitchAngle = orientation.pitchAngle,
                        isLevel = orientation.isLevel,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                // Shutter feedback flash layer
                if (shutterAlpha.value > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = shutterAlpha.value))
                    )
                }

                // Animated precision focus reticle with integrated vertical exposure compensation slider
                if (uiState.showFocusRing && uiState.focusPoint != null) {
                    FocusReticle(
                        point = uiState.focusPoint,
                        visible = uiState.showFocusRing,
                        exposureIndex = uiState.exposureIndex,
                        minExposureIndex = uiState.minExposureIndex,
                        maxExposureIndex = uiState.maxExposureIndex,
                        exposureStep = uiState.exposureStep,
                        isExposureSupported = uiState.isExposureSupported,
                        onExposureChanged = onExposureChanged,
                        onInteraction = onKeepFocusActive
                    )
                }
            }
        }

        // Display-only soft dark gradient feather (~15% opacity) at top and bottom edges in Full-Screen mode
        if (isFullScreen && !shouldPausePreview) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.16f),
                                Color.Black.copy(alpha = 0.07f),
                                Color.Transparent
                            )
                        )
                    )
                    .testTag("fullscreen_top_feather")
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.08f),
                                Color.Black.copy(alpha = 0.16f)
                            )
                        )
                    )
                    .testTag("fullscreen_bottom_feather")
            )
        }

        // Scope binding config strictly to hardware-session parameters:
        // - Switching PHOTO <-> PRO reuses the identical still pipeline without camera restart
        // - Toggling HDR never unbinds CameraX
        // - Opening Settings/Overlays freezes camera rebinding so changes apply once upon close
        val liveRequest = CameraBindingRequest(
            core = CameraCoreBindingConfig(
                lensFacing = uiState.lensFacing,
                aspectRatio = activeAspectRatio,
                isVideoMode = isVideoMode,
                capturePriority = if (!isVideoMode) uiState.capturePriority else null,
                videoQuality = if (isVideoMode) uiState.videoQuality else null,
                videoFps = if (isVideoMode) uiState.videoFps else null,
                videoCodec = if (isVideoMode) uiState.videoCodec else null,
                isVideoStabilizationEnabled = if (isVideoMode) uiState.isVideoStabilizationEnabled else null,
                targetCaptureSize = if (!isVideoMode) uiState.targetCaptureSize else null,
                viewfinderRebindKey = uiState.viewfinderRebindKey
            ),
            isAnalysisNeeded = !isVideoMode && (uiState.isHistogramEnabled || (activeAspectRatio != CaptureAspectRatio.RATIO_FULL && !isLowRamDevice))
        )
        var targetBindingRequest by remember { mutableStateOf(liveRequest) }
        if (!shouldPausePreview || uiState.viewfinderRebindKey != targetBindingRequest.core.viewfinderRebindKey) {
            targetBindingRequest = liveRequest
        }

        // Single outer DisposableEffect for stream/orientation listeners and true exit cleanup
        DisposableEffect(previewViewInstance, lifecycleOwner) {
            val pv = previewViewInstance ?: return@DisposableEffect onDispose {}
            session.isDisposed.set(false)

            val streamObserver = Observer<PreviewView.StreamState> { streamState ->
                if (!session.isDisposed.get()) {
                    latestOnStreamStateChanged(streamState == PreviewView.StreamState.STREAMING)
                }
            }
            pv.previewStreamState.observe(lifecycleOwner, streamObserver)

            val initialRotation = pv.display?.rotation ?: Surface.ROTATION_0
            latestOnTargetRotationChanged(initialRotation)

            val orientationEventListener = object : OrientationEventListener(context) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == ORIENTATION_UNKNOWN) return
                    val rotation = when (orientation) {
                        in 45 until 135 -> Surface.ROTATION_270
                        in 135 until 225 -> Surface.ROTATION_180
                        in 225 until 315 -> Surface.ROTATION_90
                        else -> Surface.ROTATION_0
                    }
                    latestOnTargetRotationChanged(rotation)
                    try {
                        session.boundImageCapture?.targetRotation = rotation
                        session.boundVideoCapture?.targetRotation = rotation
                    } catch (_: Throwable) {}
                }
            }
            session.orientationEventListener = orientationEventListener
            if (!shouldPausePreview && orientationEventListener.canDetectOrientation()) {
                orientationEventListener.enable()
            }

            onDispose {
                session.isDisposed.set(true)
                session.clearCameraStateObserver()
                session.clearAnalysis()
                try {
                    orientationEventListener.disable()
                } catch (_: Throwable) {}
                session.orientationEventListener = null
                try {
                    pv.previewStreamState.removeObserver(streamObserver)
                } catch (_: Throwable) {}
                try {
                    val provider = session.cameraProvider
                    val bound = session.currentlyBoundUseCases()
                    if (provider != null && bound.isNotEmpty()) {
                        provider.unbind(*bound.toTypedArray())
                    } else {
                        provider?.unbindAll()
                    }
                } catch (_: Throwable) {}
                try {
                    session.boundPreview?.setSurfaceProvider(null)
                } catch (_: Throwable) {}
                session.isSurfaceProviderAttached = false
                session.boundCamera = null
                session.boundPreview = null
                session.boundImageCapture = null
                session.boundVideoCapture = null
                session.boundConfig = null
            }
        }

        // Pause / resume orientation sensor when full-screen overlays cover the viewfinder
        // without detaching Preview.setSurfaceProvider mid-session (prevents DeferrableSurface reset & CaptureSession mismatches)
        LaunchedEffect(shouldPausePreview, previewViewInstance) {
            val listener = session.orientationEventListener ?: return@LaunchedEffect
            if (shouldPausePreview) {
                try {
                    listener.disable()
                } catch (_: Throwable) {}
            } else {
                try {
                    if (listener.canDetectOrientation()) {
                        listener.enable()
                    }
                } catch (_: Throwable) {}
            }
        }

        // Atomic CameraX binding: eliminates double unbindAll() and avoids partial mid-session UseCase mutations
        LaunchedEffect(previewViewInstance, lifecycleOwner, targetBindingRequest) {
            val pv = previewViewInstance ?: return@LaunchedEffect
            val prevConfig = session.boundConfig
            if (prevConfig == targetBindingRequest && session.boundCamera != null) {
                if (!session.isSurfaceProviderAttached) {
                    session.boundPreview?.setSurfaceProvider(pv.surfaceProvider)
                    session.isSurfaceProviderAttached = true
                }
                return@LaunchedEffect
            }

            val mainExecutor = ContextCompat.getMainExecutor(context)
            try {
                val cameraProvider = session.cameraProvider
                    ?: ProcessCameraProvider.getInstance(context).awaitProvider(mainExecutor).also {
                        session.cameraProvider = it
                    }
                if (session.isDisposed.get()) return@LaunchedEffect
                awaitViewAttachedAndLaidOut(pv)
                if (session.isDisposed.get()) return@LaunchedEffect

                val selector = CameraSelector.Builder()
                    .requireLensFacing(targetBindingRequest.core.lensFacing)
                    .build()

                // Configuration changed: tear down stale use cases cleanly before rebinding
                val prevCamera = session.boundCamera
                val prevPreview = session.boundPreview
                session.clearCameraStateObserver()
                session.clearAnalysis()
                session.isSurfaceProviderAttached = false
                val staleUseCases = session.currentlyBoundUseCases()
                val sameRebindSession = staleUseCases.isNotEmpty() &&
                    prevConfig?.core?.viewfinderRebindKey == targetBindingRequest.core.viewfinderRebindKey &&
                    prevConfig.core.isVideoMode == targetBindingRequest.core.isVideoMode &&
                    prevConfig.core.lensFacing == targetBindingRequest.core.lensFacing
                if (sameRebindSession) {
                    try {
                        cameraProvider.unbind(*staleUseCases.toTypedArray())
                    } catch (_: Throwable) {
                        cameraProvider.unbindAll()
                    }
                } else {
                    cameraProvider.unbindAll()
                }
                try {
                    prevPreview?.setSurfaceProvider(null)
                } catch (_: Throwable) {}
                session.boundCamera = null
                session.boundPreview = null
                session.boundImageCapture = null
                session.boundVideoCapture = null

                if (prevCamera != null &&
                    prevConfig != null &&
                    (prevConfig.core.lensFacing != targetBindingRequest.core.lensFacing ||
                        prevConfig.core.viewfinderRebindKey != targetBindingRequest.core.viewfinderRebindKey ||
                        prevConfig.core.isVideoMode != targetBindingRequest.core.isVideoMode)
                ) {
                    awaitCameraClosed(prevCamera)
                    if (session.isDisposed.get()) return@LaunchedEffect
                }

                val snapshotState = latestUiState
                val is16_9 = (targetBindingRequest.core.aspectRatio == CaptureAspectRatio.RATIO_16_9 ||
                    targetBindingRequest.core.aspectRatio == CaptureAspectRatio.RATIO_FULL)
                val sharedAspectStrategy = if (is16_9) {
                    androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                } else {
                    androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                }
                val defaultAspectResolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setAspectRatioStrategy(sharedAspectStrategy)
                    .build()
                val displayRotation = pv.display?.rotation ?: Surface.ROTATION_0
                latestOnTargetRotationChanged(displayRotation)

                fun buildPreviewUseCase(): Preview {
                    val previewBuilder = Preview.Builder()
                        .setResolutionSelector(defaultAspectResolutionSelector)
                        .setTargetRotation(displayRotation)
                    attachActiveLensCaptureCallback(
                        previewBuilder = previewBuilder,
                        mainExecutor = mainExecutor,
                        onFocalChanged = { focalMm, physicalId ->
                            if (!session.isDisposed.get()) {
                                latestOnActiveLensFocalLengthChanged(focalMm, physicalId)
                            }
                        }
                    )
                    return previewBuilder.build()
                }

                fun attachBoundPreview(preview: Preview) {
                    session.boundPreview = preview
                    preview.setSurfaceProvider(pv.surfaceProvider)
                    session.isSurfaceProviderAttached = true
                }

                val boundCamera: androidx.camera.core.Camera? = if (!targetBindingRequest.core.isVideoMode) {
                    val captureMode = if (targetBindingRequest.core.capturePriority == CapturePriority.LATENCY && !snapshotState.isHdrEnabled) {
                        ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    } else {
                        ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                    }

                    val targetSize = targetBindingRequest.core.targetCaptureSize?.takeIf { it.width > 0 && it.height > 0 }
                    val jpegQuality = if (snapshotState.capturePriority == CapturePriority.QUALITY) 100 else 95
                    val imageCaptureBuilder = ImageCapture.Builder()
                        .setCaptureMode(captureMode)
                        .setJpegQuality(jpegQuality)
                        .setTargetRotation(displayRotation)
                        .setFlashMode(snapshotState.flashMode.toCameraXFlashMode())

                    if (targetSize != null) {
                        val is16_9Capture = targetBindingRequest.core.aspectRatio == CaptureAspectRatio.RATIO_16_9 ||
                            targetBindingRequest.core.aspectRatio == CaptureAspectRatio.RATIO_FULL
                        val effectiveTargetSize = if (is16_9Capture) {
                            android.util.Size(targetSize.width, (targetSize.width * 9) / 16)
                        } else {
                            targetSize
                        }
                        val aspectStrategy = if (is16_9Capture) {
                            androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                        } else {
                            androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                        }
                        val fallbackRule = if (is16_9Capture) {
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        } else {
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        }
                        val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                            .setAllowedResolutionMode(androidx.camera.core.resolutionselector.ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                            .setResolutionStrategy(
                                androidx.camera.core.resolutionselector.ResolutionStrategy(
                                    effectiveTargetSize,
                                    fallbackRule
                                )
                            )
                            .setAspectRatioStrategy(aspectStrategy)
                            .build()
                        imageCaptureBuilder.setResolutionSelector(resolutionSelector)
                    } else {
                        imageCaptureBuilder.setResolutionSelector(defaultAspectResolutionSelector)
                    }

                    val imageCapture = imageCaptureBuilder.build()

                    val camera: androidx.camera.core.Camera? = try {
                        if (targetBindingRequest.isAnalysisNeeded) {
                            val (analysis, exec) = buildCombinedImageAnalysis(
                                mainExecutor = mainExecutor,
                                session = session,
                                aspectRatio = targetBindingRequest.core.aspectRatio,
                                onHistogramData = { bins -> latestOnHistogramData(bins) },
                                onBackdropBitmap = { bmp -> blurredBackdropBitmap = bmp }
                            )
                            session.analysisExecutor = exec
                            session.boundImageAnalysis = analysis

                            try {
                                val primaryPreview = buildPreviewUseCase()
                                val bound = cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    selector,
                                    primaryPreview,
                                    imageCapture,
                                    analysis
                                )
                                attachBoundPreview(primaryPreview)
                                session.boundImageCapture = imageCapture
                                bound
                            } catch (e: Throwable) {
                                android.util.Log.w(
                                    "CameraViewfinder",
                                    "3-use-case binding unsupported or failed; disabling live histogram analysis",
                                    e
                                )
                                session.clearAnalysis()
                                try {
                                    cameraProvider.unbindAll()
                                } catch (_: Throwable) {}
                                val twoUseCasePreview = buildPreviewUseCase()
                                val bound = cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    selector,
                                    twoUseCasePreview,
                                    imageCapture
                                )
                                attachBoundPreview(twoUseCasePreview)
                                session.boundImageCapture = imageCapture
                                bound
                            }
                        } else {
                            val stillPreview = buildPreviewUseCase()
                            val bound = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                selector,
                                stillPreview,
                                imageCapture
                            )
                            attachBoundPreview(stillPreview)
                            session.boundImageCapture = imageCapture
                            bound
                        }
                    } catch (e: Throwable) {
                        android.util.Log.w(
                            "CameraViewfinder",
                            "Standard ImageCapture bind failed: ${e.message}; attempting aspect ratio fallback",
                            e
                        )
                        session.clearAnalysis()
                        try {
                            cameraProvider.unbindAll()
                        } catch (_: Throwable) {}
                        try {
                            val fallbackPreview = buildPreviewUseCase()
                            val fallbackCapture = ImageCapture.Builder()
                                .setCaptureMode(captureMode)
                                .setJpegQuality(jpegQuality)
                                .setTargetRotation(displayRotation)
                                .setFlashMode(snapshotState.flashMode.toCameraXFlashMode())
                                .setResolutionSelector(defaultAspectResolutionSelector)
                                .build()
                            val fallbackCam = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                selector,
                                fallbackPreview,
                                fallbackCapture
                            )
                            attachBoundPreview(fallbackPreview)
                            session.boundImageCapture = fallbackCapture
                            fallbackCam
                        } catch (e2: Throwable) {
                            android.util.Log.e("CameraViewfinder", "Fallback capture binding failed: ${e2.message}; falling back to preview-only", e2)
                            try {
                                cameraProvider.unbindAll()
                            } catch (_: Throwable) {}
                            try {
                                val previewOnlyUseCase = buildPreviewUseCase()
                                val previewOnlyCam = cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    selector,
                                    previewOnlyUseCase
                                )
                                attachBoundPreview(previewOnlyUseCase)
                                session.boundImageCapture = null
                                latestOnCameraError("Camera capture degraded: preview only", false)
                                previewOnlyCam
                            } catch (e3: Throwable) {
                                android.util.Log.e("CameraViewfinder", "Complete camera bind failure", e3)
                                session.boundImageCapture = null
                                latestOnCameraError("Camera failed to bind: ${e3.message}", true)
                                null
                            }
                        }
                    }

                    try {
                        val configuredRes = session.boundImageCapture?.resolutionInfo?.resolution
                        if (configuredRes != null) {
                            latestOnResolutionConfigured?.invoke(configuredRes)
                        }
                    } catch (_: Exception) {}
                    if (camera != null) {
                        latestOnCameraBound(camera, cameraProvider, session.boundImageCapture, null)
                    }
                    camera
                } else {
                    var videoCaptureInstance: VideoCapture<Recorder>? = null
                    val camera = try {
                        val cameraInfo = cameraProvider.getCameraInfo(selector)
                        val encoderSupport = com.example.util.VideoEncoderUtil.detectVideoEncoderSupport()
                        val videoProbe = if (encoderSupport.canEncodeVideo && snapshotState.isVideoRecordingSupported) {
                            probeCameraXVideoCapabilities(context, cameraInfo)
                        } else {
                            CameraXVideoProbeResult(
                                capabilitiesSource = Recorder.VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES,
                                videoCapabilities = null,
                                supportedQualities = emptyList()
                            )
                        }
                        val effectiveCapSource = videoProbe.capabilitiesSource
                        val videoCapabilities = videoProbe.videoCapabilities
                        val supportedQualities = videoProbe.supportedQualities

                        if (encoderSupport.canEncodeVideo && supportedQualities.isNotEmpty()) {
                            val preferredQuality = when (targetBindingRequest.core.videoQuality ?: snapshotState.videoQuality) {
                                VideoQualityOption.QUALITY_2160P -> Quality.UHD
                                VideoQualityOption.QUALITY_1080P -> Quality.FHD
                                VideoQualityOption.QUALITY_720P -> Quality.HD
                                VideoQualityOption.QUALITY_480P -> Quality.SD
                            }

                            val prioritizedQualities = (listOf(preferredQuality) + supportedQualities).distinct().filter {
                                supportedQualities.contains(it)
                            }.ifEmpty { supportedQualities }

                            val activeQuality = prioritizedQualities.first()
                            val (qW, qH) = when (activeQuality) {
                                Quality.UHD -> 3840 to 2160
                                Quality.FHD -> 1920 to 1080
                                Quality.HD -> 1280 to 720
                                else -> 720 to 480
                            }
                            val selectedFps = targetBindingRequest.core.videoFps ?: snapshotState.videoFps
                            val selectedCodec = targetBindingRequest.core.videoCodec ?: snapshotState.videoCodec
                            val selectedEis = targetBindingRequest.core.isVideoStabilizationEnabled
                                ?: snapshotState.isVideoStabilizationEnabled
                            val nominalFps = when (selectedFps) {
                                VideoFpsOption.FPS_120 -> 120
                                VideoFpsOption.FPS_60 -> 60
                                VideoFpsOption.FPS_30 -> 30
                                VideoFpsOption.FPS_24 -> 24
                                VideoFpsOption.FPS_AUTO -> 30
                            }
                            val baseBitrate = com.example.util.VideoEncoderUtil.calculateNominalBitrate(qW, qH, nominalFps)
                            val targetBitrate = if (selectedCodec == VideoCodecOption.HEVC) {
                                (baseBitrate * 0.72).toInt().coerceAtLeast(1_500_000)
                            } else {
                                baseBitrate
                            }
                            val stabilizationSupported = (videoCapabilities?.isStabilizationSupported == true) ||
                                snapshotState.isVideoStabilizationSupported

                            val fallbackQuality = prioritizedQualities.last()
                            val qualitySelector = QualitySelector.fromOrderedList(
                                prioritizedQualities,
                                FallbackStrategy.lowerQualityOrHigherThan(fallbackQuality)
                            )

                            val videoAspectRatio = when (targetBindingRequest.core.aspectRatio) {
                                CaptureAspectRatio.RATIO_4_3 -> AspectRatio.RATIO_4_3
                                CaptureAspectRatio.RATIO_16_9, CaptureAspectRatio.RATIO_FULL -> AspectRatio.RATIO_16_9
                                CaptureAspectRatio.RATIO_1_1 -> AspectRatio.RATIO_4_3
                            }

                            try {
                                val recorder = Recorder.Builder()
                                    .setVideoCapabilitiesSource(effectiveCapSource)
                                    .setQualitySelector(qualitySelector)
                                    .setAspectRatio(videoAspectRatio)
                                    .setTargetVideoEncodingBitRate(targetBitrate)
                                    .build()
                                val videoCaptureBuilder = VideoCapture.Builder(recorder)
                                    .setTargetRotation(displayRotation)
                                    .setVideoStabilizationEnabled(selectedEis && stabilizationSupported)
                                if (selectedFps != VideoFpsOption.FPS_AUTO) {
                                    videoCaptureBuilder.setTargetFrameRate(
                                        android.util.Range(selectedFps.fps, selectedFps.fps)
                                    )
                                }
                                val videoCapture = videoCaptureBuilder.build()

                                val videoPreview = buildPreviewUseCase()
                                val boundCam = cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    selector,
                                    videoPreview,
                                    videoCapture
                                )
                                attachBoundPreview(videoPreview)
                                videoCaptureInstance = videoCapture
                                boundCam
                            } catch (e: Exception) {
                                android.util.Log.w(
                                    "CameraViewfinder",
                                    "VideoCapture binding with preferred quality failed; attempting safe fallback to $fallbackQuality",
                                    e
                                )
                                try {
                                    cameraProvider.unbindAll()
                                } catch (_: Throwable) {}
                                try {
                                    val fallbackVideoPreview = buildPreviewUseCase()
                                    val fallbackRecorder = Recorder.Builder()
                                        .setVideoCapabilitiesSource(effectiveCapSource)
                                        .setQualitySelector(QualitySelector.from(fallbackQuality))
                                        .setAspectRatio(videoAspectRatio)
                                        .build()
                                    val fallbackCapture = VideoCapture.Builder(fallbackRecorder)
                                        .setTargetRotation(displayRotation)
                                        .build()

                                    val boundCam = cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        selector,
                                        fallbackVideoPreview,
                                        fallbackCapture
                                    )
                                    attachBoundPreview(fallbackVideoPreview)
                                    videoCaptureInstance = fallbackCapture
                                    boundCam
                                } catch (fallbackError: Exception) {
                                    android.util.Log.e(
                                        "CameraViewfinder",
                                        "VideoCapture binding failed completely. Video recording unavailable on this sensor.",
                                        fallbackError
                                    )
                                    try {
                                        cameraProvider.unbindAll()
                                    } catch (_: Throwable) {}
                                    latestOnCameraError("Video recording unavailable on this sensor", false)
                                    val previewOnly = buildPreviewUseCase()
                                    val boundCam = cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        selector,
                                        previewOnly
                                    )
                                    attachBoundPreview(previewOnly)
                                    boundCam
                                }
                            }
                        } else {
                            android.util.Log.i(
                                "CameraViewfinder",
                                if (!encoderSupport.canEncodeVideo) "No video encoders detected. Preview-only mode enabled."
                                else "Sensor does not advertise supported video profiles. Preview-only mode enabled."
                            )
                            val previewOnly = buildPreviewUseCase()
                            val boundCam = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                selector,
                                previewOnly
                            )
                            attachBoundPreview(previewOnly)
                            boundCam
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "CameraViewfinder",
                            "VideoCapture initialization error. Falling back to Preview only.",
                            e
                        )
                        try {
                            cameraProvider.unbindAll()
                        } catch (_: Throwable) {}
                        val previewOnly = buildPreviewUseCase()
                        val boundCam = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            selector,
                            previewOnly
                        )
                        attachBoundPreview(previewOnly)
                        boundCam
                    }

                    session.boundVideoCapture = videoCaptureInstance
                    latestOnCameraBound(camera, cameraProvider, null, videoCaptureInstance)
                    camera
                }

                session.boundCamera = boundCamera
                session.boundConfig = targetBindingRequest

                boundCamera?.let { cam ->
                    val stateObserver = Observer<CameraState> { camState ->
                        if (session.isDisposed.get()) return@Observer
                        val err = camState.error
                        if (err != null) {
                            val (msg, isFatal) = when (err.code) {
                                CameraState.ERROR_CAMERA_IN_USE -> "Camera is currently in use by another app" to false
                                CameraState.ERROR_MAX_CAMERAS_IN_USE -> "Maximum concurrent cameras in use" to false
                                CameraState.ERROR_CAMERA_DISABLED -> "Camera disabled by device policy" to true
                                CameraState.ERROR_CAMERA_FATAL_ERROR -> "Camera hardware error — please reopen camera" to true
                                CameraState.ERROR_STREAM_CONFIG -> "Camera stream configuration failed" to false
                                CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "Do Not Disturb is blocking camera" to false
                                else -> "Camera interrupted (${err.code})" to false
                            }
                            android.util.Log.w("CameraViewfinder", "CameraState error ${err.code}: $msg", err.cause)
                            latestOnCameraError(msg, isFatal)
                        }
                    }
                    session.cameraStateObserver = stateObserver
                    session.boundCameraStateLiveData = cam.cameraInfo.cameraState
                    cam.cameraInfo.cameraState.observe(lifecycleOwner, stateObserver)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                android.util.Log.e("CameraViewfinder", "CameraX bindToLifecycle failed", e)
                if (!session.isDisposed.get()) {
                    latestOnCameraError("Unable to open camera: ${e.localizedMessage ?: "hardware busy"}", false)
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
private fun attachActiveLensCaptureCallback(
    previewBuilder: Preview.Builder,
    mainExecutor: java.util.concurrent.Executor,
    onFocalChanged: (Float?, String?) -> Unit
) {
    var hasReportedInitial = false
    var lastFocalMm: Float? = null
    var lastPhysicalId: String? = null

    androidx.camera.camera2.interop.Camera2Interop.Extender(previewBuilder)
        .setSessionCaptureCallback(
            object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: android.hardware.camera2.CameraCaptureSession,
                    request: android.hardware.camera2.CaptureRequest,
                    result: android.hardware.camera2.TotalCaptureResult
                ) {
                    val focalMm = result.get(android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH)
                        ?.takeIf { it.isFinite() && it > 0f }
                    val activePhysicalId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        result.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                    } else {
                        null
                    }
                    val focalChanged = (focalMm == null) != (lastFocalMm == null) ||
                        (focalMm != null && lastFocalMm != null && kotlin.math.abs(focalMm - lastFocalMm!!) > 0.01f)
                    val physicalChanged = activePhysicalId != lastPhysicalId
                    if (!hasReportedInitial || focalChanged || physicalChanged) {
                        hasReportedInitial = true
                        lastFocalMm = focalMm
                        lastPhysicalId = activePhysicalId
                        mainExecutor.execute {
                            onFocalChanged(focalMm, activePhysicalId)
                        }
                    }
                }
            }
        )
}

private data class CameraXVideoProbeResult(
    val capabilitiesSource: Int,
    val videoCapabilities: androidx.camera.video.VideoCapabilities?,
    val supportedQualities: List<Quality>
)

private object CameraXVideoCapabilitiesCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CameraXVideoProbeResult>()

    fun get(cameraId: String): CameraXVideoProbeResult? = cache[cameraId]

    fun put(cameraId: String, result: CameraXVideoProbeResult) {
        cache[cameraId] = result
    }
}

private val CAMERAX_SUPPORTED_VIDEO_MIMES = setOf(
    "video/avc",
    "video/hevc",
    "video/x-vnd.on2.vp8",
    "video/x-vnd.on2.vp9",
    "video/av01"
)

private fun getCameraVideoStreamSizes(context: Context, cameraIdStr: String): Set<android.util.Size> {
    return try {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
            ?: return emptySet()
        val chars = cameraManager.getCameraCharacteristics(cameraIdStr)
        val map = chars.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptySet()
        val privateSizes = map.getOutputSizes(android.graphics.ImageFormat.PRIVATE)?.toSet() ?: emptySet()
        if (privateSizes.isNotEmpty()) {
            privateSizes
        } else {
            val surfaceSizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toSet() ?: emptySet()
            val recorderSizes = map.getOutputSizes(android.media.MediaRecorder::class.java)?.toSet() ?: emptySet()
            surfaceSizes + recorderSizes
        }
    } catch (_: Throwable) {
        emptySet()
    }
}

@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
private fun hasValidatedCamcorderEncoderProfiles(context: Context, cameraIdStr: String): Boolean {
    // Only probe the exact Quality tiers used by CameraX CapabilitiesByQuality, and always guard with
    // CamcorderProfile.hasProfile first so native MediaProfiles does not emit missing-quality log spam.
    val qualityIds = intArrayOf(
        android.media.CamcorderProfile.QUALITY_2160P,
        android.media.CamcorderProfile.QUALITY_1080P,
        android.media.CamcorderProfile.QUALITY_720P,
        android.media.CamcorderProfile.QUALITY_480P
    )
    val camIdInt = cameraIdStr.toIntOrNull()
    val supportedCameraSizes = getCameraVideoStreamSizes(context, cameraIdStr)

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        qualityIds.any { q ->
            if (camIdInt != null && !android.media.CamcorderProfile.hasProfile(camIdInt, q)) {
                return@any false
            }
            val profiles = try {
                android.media.CamcorderProfile.getAll(cameraIdStr, q)
            } catch (_: Throwable) {
                null
            }
            profiles?.videoProfiles?.any { vp ->
                val mime = vp.mediaType.lowercase(java.util.Locale.ROOT)
                val isSdrCompatible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    (vp.bitDepth == 0 || vp.bitDepth == 8) &&
                        vp.hdrFormat == android.media.EncoderProfiles.VideoProfile.HDR_NONE
                } else {
                    true
                }
                vp.width > 0 && vp.height > 0 && vp.frameRate > 0 && vp.bitrate > 0 &&
                    mime in CAMERAX_SUPPORTED_VIDEO_MIMES &&
                    isSdrCompatible &&
                    (supportedCameraSizes.isEmpty() || supportedCameraSizes.contains(android.util.Size(vp.width, vp.height))) &&
                    com.example.util.VideoEncoderUtil.isResolutionAndFpsSupported(
                        width = vp.width,
                        height = vp.height,
                        fps = vp.frameRate.coerceAtLeast(30),
                        preferredMime = mime,
                        targetBitrate = vp.bitrate
                    )
            } == true
        }
    } else {
        val camId = camIdInt ?: return false
        qualityIds.any { q ->
            if (!android.media.CamcorderProfile.hasProfile(camId, q)) return@any false
            val profile = try {
                @Suppress("DEPRECATION")
                android.media.CamcorderProfile.get(camId, q)
            } catch (_: Throwable) {
                null
            }
            val mime = when (profile?.videoCodec) {
                android.media.MediaRecorder.VideoEncoder.H264 -> "video/avc"
                android.media.MediaRecorder.VideoEncoder.HEVC -> "video/hevc"
                android.media.MediaRecorder.VideoEncoder.VP8 -> "video/x-vnd.on2.vp8"
                else -> null
            }
            profile != null && mime != null &&
                profile.videoFrameWidth > 0 && profile.videoFrameHeight > 0 &&
                (supportedCameraSizes.isEmpty() || supportedCameraSizes.contains(android.util.Size(profile.videoFrameWidth, profile.videoFrameHeight))) &&
                com.example.util.VideoEncoderUtil.isResolutionAndFpsSupported(
                    width = profile.videoFrameWidth,
                    height = profile.videoFrameHeight,
                    fps = profile.videoFrameRate.coerceAtLeast(30),
                    preferredMime = mime,
                    targetBitrate = profile.videoBitRate
                )
        }
    }
}

@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
private fun probeCameraXVideoCapabilities(
    context: Context,
    cameraInfo: androidx.camera.core.CameraInfo
): CameraXVideoProbeResult {
    val cameraIdStr = try {
        androidx.camera.camera2.interop.Camera2CameraInfo.from(cameraInfo).cameraId
    } catch (_: Throwable) {
        ""
    }
    if (cameraIdStr.isNotEmpty()) {
        CameraXVideoCapabilitiesCache.get(cameraIdStr)?.let { return it }
    }

    val canTryCamcorderProfile = cameraIdStr.isNotEmpty() && hasValidatedCamcorderEncoderProfiles(context, cameraIdStr)
    if (canTryCamcorderProfile) {
        val capSource = Recorder.VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE
        val caps = try {
            Recorder.getVideoCapabilities(cameraInfo, capSource)
        } catch (_: Throwable) {
            null
        }
        val qualities = try {
            caps?.getSupportedQualities(DynamicRange.SDR) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        if (qualities.isNotEmpty()) {
            return CameraXVideoProbeResult(capSource, caps, qualities).also {
                if (cameraIdStr.isNotEmpty()) CameraXVideoCapabilitiesCache.put(cameraIdStr, it)
            }
        }
    }

    // Do not invoke Recorder.getVideoCapabilities with VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES
    // on sensors lacking valid CamcorderProfiles: CameraX 1.5.0 still constructs CapabilitiesByQuality
    // over the empty EncoderProfilesProvider (emitting E/CapabilitiesByQuality errors) and emulator
    // MediaMuxer/MPEG4Writer fails to encode frames cleanly without hardware-backed profiles.
    return CameraXVideoProbeResult(
        capabilitiesSource = Recorder.VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE,
        videoCapabilities = null,
        supportedQualities = emptyList()
    ).also {
        if (cameraIdStr.isNotEmpty()) CameraXVideoCapabilitiesCache.put(cameraIdStr, it)
    }
}

private fun buildCombinedImageAnalysis(
    mainExecutor: java.util.concurrent.Executor,
    session: ViewfinderSessionHolder,
    aspectRatio: CaptureAspectRatio,
    onHistogramData: (List<Float>) -> Unit,
    onBackdropBitmap: (Bitmap) -> Unit
): Pair<ImageAnalysis, ExecutorService> {
    val exec = Executors.newSingleThreadExecutor()
    val is16_9 = (aspectRatio == CaptureAspectRatio.RATIO_16_9 || aspectRatio == CaptureAspectRatio.RATIO_FULL)
    val analysisSize = if (is16_9) android.util.Size(640, 360) else android.util.Size(640, 480)
    val aspectStrategy = if (is16_9) {
        androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
    } else {
        androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
    }
    val analysisRes = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
        .setAspectRatioStrategy(aspectStrategy)
        .setResolutionStrategy(
            androidx.camera.core.resolutionselector.ResolutionStrategy(
                analysisSize,
                androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
            )
        )
        .build()
    val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(analysisRes)
        .build()
    var lastHistogramNs = 0L
    var lastBackdropNs = 0L
    val bins = IntArray(24)
    val backdropPixels = IntArray(36 * 48)
    analysis.setAnalyzer(exec) { imageProxy ->
        try {
            if (!session.isDisposed.get() && !session.isAnalysisPaused.get()) {
                val nowNs = System.nanoTime()
                val wantHistogram = session.isHistogramActive.get() && (nowNs - lastHistogramNs >= 100_000_000L)
                val wantBackdrop = session.isBackdropActive.get() && (nowNs - lastBackdropNs >= 260_000_000L)

                if (wantHistogram) {
                    lastHistogramNs = nowNs
                    val yPlane = imageProxy.planes.firstOrNull()
                    if (yPlane != null) {
                        val buf = yPlane.buffer
                        val rowStride = yPlane.rowStride
                        val pixelStride = yPlane.pixelStride
                        val w = imageProxy.width
                        val h = imageProxy.height
                        bins.fill(0)
                        val limit = buf.limit()
                        var row = 0
                        while (row < h) {
                            val rowOffset = row * rowStride
                            var col = 0
                            while (col < w) {
                                val idx = rowOffset + col * pixelStride
                                if (idx in 0 until limit) {
                                    val yVal = buf.get(idx).toInt() and 0xFF
                                    val binIdx = (yVal * 24 / 256).coerceIn(0, 23)
                                    bins[binIdx]++
                                }
                                col += 8
                            }
                            row += 8
                        }
                        val maxBin = bins.maxOrNull()?.coerceAtLeast(1) ?: 1
                        val normalized = List(24) { i -> bins[i].toFloat() / maxBin.toFloat() }
                        mainExecutor.execute {
                            if (!session.isDisposed.get() && session.isHistogramActive.get()) {
                                onHistogramData(normalized)
                            }
                        }
                    }
                }

                if (wantBackdrop) {
                    lastBackdropNs = nowNs
                    val planes = imageProxy.planes
                    val w = imageProxy.width
                    val h = imageProxy.height
                    if (planes.isNotEmpty() && w > 1 && h > 1) {
                        val dstW = 36
                        val dstH = 48
                        val yPlane = planes[0]
                        val yBuf = yPlane.buffer
                        val yRowStride = yPlane.rowStride
                        val yPixelStride = yPlane.pixelStride
                        val yLimit = yBuf.limit()

                        val hasUv = planes.size >= 3
                        val uPlane = if (hasUv) planes[1] else null
                        val vPlane = if (hasUv) planes[2] else null
                        val uBuf = uPlane?.buffer
                        val vBuf = vPlane?.buffer
                        val uRowStride = uPlane?.rowStride ?: 0
                        val uPixelStride = uPlane?.pixelStride ?: 0
                        val uLimit = uBuf?.limit() ?: 0
                        val vRowStride = vPlane?.rowStride ?: 0
                        val vPixelStride = vPlane?.pixelStride ?: 0
                        val vLimit = vBuf?.limit() ?: 0

                        val rotation = imageProxy.imageInfo.rotationDegrees
                        val isFront = session.isFrontCamera.get()
                        val wMax = w - 1
                        val hMax = h - 1

                        for (dy in 0 until dstH) {
                            val ny = dy.toFloat() / (dstH - 1f)
                            for (dx in 0 until dstW) {
                                val nx = dx.toFloat() / (dstW - 1f)
                                val mx = if (isFront) 1f - nx else nx
                                val uNorm: Float
                                val vNorm: Float
                                when (rotation) {
                                    90 -> {
                                        uNorm = ny
                                        vNorm = 1f - mx
                                    }
                                    180 -> {
                                        uNorm = 1f - mx
                                        vNorm = 1f - ny
                                    }
                                    270 -> {
                                        uNorm = 1f - ny
                                        vNorm = mx
                                    }
                                    else -> {
                                        uNorm = mx
                                        vNorm = ny
                                    }
                                }
                                val sx = (uNorm * wMax).toInt().coerceIn(0, wMax)
                                val sy = (vNorm * hMax).toInt().coerceIn(0, hMax)
                                val yIdx = sy * yRowStride + sx * yPixelStride
                                val yVal = if (yIdx in 0 until yLimit) (yBuf.get(yIdx).toInt() and 0xFF) else 0

                                val r: Int
                                val g: Int
                                val b: Int
                                if (hasUv && uBuf != null && vBuf != null) {
                                    val uvX = sx shr 1
                                    val uvY = sy shr 1
                                    val uIdx = uvY * uRowStride + uvX * uPixelStride
                                    val vIdx = uvY * vRowStride + uvX * vPixelStride
                                    val uVal = if (uIdx in 0 until uLimit) ((uBuf.get(uIdx).toInt() and 0xFF) - 128) else 0
                                    val vVal = if (vIdx in 0 until vLimit) ((vBuf.get(vIdx).toInt() and 0xFF) - 128) else 0
                                    r = (yVal + (1.370705f * vVal)).toInt().coerceIn(0, 255)
                                    g = (yVal - (0.337633f * uVal) - (0.698001f * vVal)).toInt().coerceIn(0, 255)
                                    b = (yVal + (1.732446f * uVal)).toInt().coerceIn(0, 255)
                                } else {
                                    r = yVal
                                    g = yVal
                                    b = yVal
                                }
                                backdropPixels[dy * dstW + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                        }
                        val bmp = Bitmap.createBitmap(backdropPixels, dstW, dstH, Bitmap.Config.ARGB_8888)
                        mainExecutor.execute {
                            if (!session.isDisposed.get() && session.isBackdropActive.get()) {
                                onBackdropBitmap(bmp)
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            imageProxy.close()
        }
    }
    return analysis to exec
}

@Composable
private fun FilterAtmosphereOverlay(filter: CameraFilter) {
    if (filter == CameraFilter.NATURAL) return
    val spec = filter.gradingSpec

    // Derive calibrated split-tone atmospheric gradient from the same gradingSpec used on export
    val highAlpha = (kotlin.math.max(
        kotlin.math.abs(spec.splitHighlightR),
        kotlin.math.max(kotlin.math.abs(spec.splitHighlightG), kotlin.math.abs(spec.splitHighlightB))
    ) * 0.45f).coerceIn(0f, 0.08f)
    val shadowAlpha = (kotlin.math.max(
        kotlin.math.abs(spec.splitShadowR),
        kotlin.math.max(kotlin.math.abs(spec.splitShadowG), kotlin.math.abs(spec.splitShadowB))
    ) * 0.45f).coerceIn(0f, 0.08f)

    if (highAlpha > 0.005f || shadowAlpha > 0.005f) {
        val highColor = Color(
            red = (0.5f + spec.splitHighlightR * 3.5f).coerceIn(0f, 1f),
            green = (0.5f + spec.splitHighlightG * 3.5f).coerceIn(0f, 1f),
            blue = (0.5f + spec.splitHighlightB * 3.5f).coerceIn(0f, 1f),
            alpha = highAlpha
        )
        val shadowColor = Color(
            red = (0.5f + spec.splitShadowR * 3.5f).coerceIn(0f, 1f),
            green = (0.5f + spec.splitShadowG * 3.5f).coerceIn(0f, 1f),
            blue = (0.5f + spec.splitShadowB * 3.5f).coerceIn(0f, 1f),
            alpha = shadowAlpha
        )
        val splitBrush = remember(filter) {
            Brush.verticalGradient(listOf(highColor, Color.Transparent, shadowColor))
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(splitBrush)
        )
    }

    if (spec.vignetteStrength > 0.01f) {
        val edgeAlpha = (spec.vignetteStrength * 0.85f).coerceIn(0f, 0.28f)
        val vignetteBrush = remember(filter) {
            Brush.radialGradient(
                listOf(
                    Color.Transparent,
                    Color.Black.copy(alpha = edgeAlpha)
                )
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(vignetteBrush)
        )
    }
}

