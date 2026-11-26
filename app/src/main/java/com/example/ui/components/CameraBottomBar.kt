package com.example.ui.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CameraMode
import com.example.model.CameraUiState
import com.example.model.MediaItem
import com.example.ui.theme.GlassCoolBlueSpecular
import com.example.ui.theme.GlassNightCyanGlow
import com.example.ui.theme.GlassNightMoonAccent
import com.example.ui.theme.GlassObsidianBottom
import com.example.ui.theme.GlassObsidianTop
import com.example.ui.theme.GlassWarmAmberSpecular
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDOBlack
import com.example.ui.theme.StuDOGrey
import com.example.ui.theme.StuDORed
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun CameraBottomBar(
    uiState: CameraUiState,
    onCaptureClick: () -> Unit,
    onModeSelect: (CameraMode) -> Unit,
    onToggleLens: () -> Unit,
    onZoomChange: (Float) -> Unit,
    onThumbnailClick: (Uri) -> Unit,
    onTogglePauseResume: (() -> Unit)? = null,
    onToggleAudio: (() -> Unit)? = null,
    onSelectLensPreset: ((com.example.model.CameraLensOption) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptic = rememberStuDOHaptic(uiState.isHapticFeedbackEnabled)
    var isPressed by remember { mutableStateOf(false) }

    val shutterScale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1.0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
        label = "shutter_press_scale"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            AnimatedVisibility(
                visible = uiState.isNightCapturing,
                enter = fadeIn(tween(180)) + expandVertically(),
                exit = fadeOut(tween(180)) + shrinkVertically()
            ) {
                NightHoldSteadyPill(
                    statusText = uiState.nightCaptureStatusText,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }

            // Dynamic Liquid Glass Zoom Pill -> Curved Glass Zoom Arc on touch-and-hold / drag.
            // Collapses into a single compact curved-zoom-only pill when Portrait or Pro panels are open,
            // and auto-collapses after inactivity in Photo / Video modes.
            val isDenseSecondaryPanelOpen = uiState.cameraMode == CameraMode.PORTRAIT ||
                uiState.cameraMode == CameraMode.PRO
            val isBackCameraActive = uiState.lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_BACK
            CurvedZoomControl(
                zoomRatio = uiState.zoomRatio,
                minZoomRatio = uiState.minZoomRatio,
                maxZoomRatio = uiState.maxZoomRatio,
                hapticsEnabled = uiState.isHapticFeedbackEnabled,
                onZoomChange = onZoomChange,
                forceCompactPill = isDenseSecondaryPanelOpen,
                modeKey = uiState.cameraMode,
                lenses = if (isBackCameraActive) uiState.availableRearLenses else emptyList(),
                activeLensId = if (isBackCameraActive) uiState.activeLensId else null,
                activeLensType = if (isBackCameraActive) uiState.activeLensType else com.example.model.HardwareLensType.MAIN,
                onLensSelected = onSelectLensPreset
            )

            Spacer(modifier = Modifier.height(10.dp))

            // PHOTO / PORTRAIT / NIGHT / PRO / VIDEO Mode Selector with Elastic Glass Droplet Physics
            AnimatedVisibility(
                visible = !uiState.isRecordingVideo,
                enter = fadeIn(tween(200)) + expandVertically(),
                exit = fadeOut(tween(180)) + shrinkVertically()
            ) {
                ElasticModeSelector(
                    selectedMode = uiState.cameraMode,
                    enabled = !uiState.isRecordingVideo && !uiState.isCapturing && !uiState.isNightCapturing,
                    onModeSelected = { targetMode ->
                        if (uiState.cameraMode != targetMode) {
                            haptic.tick()
                            onModeSelect(targetMode)
                        }
                    },
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            if (uiState.cameraMode == CameraMode.VIDEO && uiState.isCameraReady && !uiState.isVideoRecordingSupported) {
                Text(
                    text = "Video recording unavailable on this sensor",
                    color = StuDOSilverMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            // Main Controls: [Gallery / Pause] ---- [Liquid Glass Shutter] ---- [Lens Switch / Mic]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isRecording = uiState.isRecordingVideo
                val isVideoMode = uiState.cameraMode == CameraMode.VIDEO

                if (isRecording) {
                    // Left control during recording: Pause / Resume Button
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .liquidGlassSurface(
                                shape = RoundedCornerShape(18.dp),
                                cornerRadius = 18.dp,
                                topColor = if (uiState.isRecordingPaused) StuDOAmber.copy(alpha = 0.24f) else GlassObsidianTop.copy(alpha = 0.56f),
                                bottomColor = GlassObsidianBottom.copy(alpha = 0.74f),
                                borderWidth = 0.85.dp,
                                coolHighlightAlpha = if (uiState.isRecordingPaused) 0.48f else 0.36f,
                                warmHighlightAlpha = 0.24f,
                                specularSheenAlpha = 0.16f
                            )
                            .clickable {
                                haptic.confirm()
                                onTogglePauseResume?.invoke()
                            }
                            .testTag("video_pause_resume_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (uiState.isRecordingPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (uiState.isRecordingPaused) "Resume Video" else "Pause Video",
                            tint = if (uiState.isRecordingPaused) StuDOAmber else StuDOWhite.copy(alpha = 0.96f),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                } else {
                    // Left: Recent capture thumbnail in Liquid Glass container
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .liquidGlassSurface(
                                shape = RoundedCornerShape(18.dp),
                                cornerRadius = 18.dp,
                                topColor = GlassObsidianTop.copy(alpha = 0.56f),
                                bottomColor = GlassObsidianBottom.copy(alpha = 0.74f),
                                borderWidth = 0.85.dp,
                                coolHighlightAlpha = 0.36f,
                                warmHighlightAlpha = 0.24f,
                                specularSheenAlpha = 0.16f
                            )
                            .clickable(
                                enabled = (uiState.lastCapturedUri != null || uiState.galleryItems.isNotEmpty()) &&
                                    !uiState.isRecordingVideo &&
                                    !uiState.isNightCapturing
                            ) {
                                val targetUri = uiState.lastCapturedUri ?: uiState.galleryItems.firstOrNull()?.uri
                                targetUri?.let { uri -> onThumbnailClick(uri) }
                            }
                            .testTag("gallery_thumbnail_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        val displayItem = uiState.galleryItems.firstOrNull {
                            it.uri == uiState.lastCapturedUri ||
                                (uiState.lastCapturedUri != null && it.uri.lastPathSegment != null && it.uri.lastPathSegment == uiState.lastCapturedUri.lastPathSegment)
                        } ?: uiState.galleryItems.firstOrNull()
                            ?: uiState.lastCapturedUri?.let { uri ->
                                MediaItem(
                                    id = uri.lastPathSegment?.toLongOrNull() ?: System.currentTimeMillis(),
                                    uri = uri,
                                    isVideo = uiState.cameraMode == CameraMode.VIDEO || uri.toString().contains("video"),
                                    displayName = if (uiState.cameraMode == CameraMode.VIDEO || uri.toString().contains("video")) "Video" else "Photo",
                                    dateAdded = System.currentTimeMillis() / 1000,
                                    mimeType = if (uiState.cameraMode == CameraMode.VIDEO || uri.toString().contains("video")) "video/mp4" else "image/jpeg",
                                    sizeBytes = 0L,
                                    width = 0,
                                    height = 0,
                                    durationMs = 0L
                                )
                            }

                        if (displayItem != null) {
                            MediaThumbnailView(
                                item = displayItem,
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(RoundedCornerShape(15.dp))
                                    .border(
                                        width = 0.6.dp,
                                        color = Color.White.copy(alpha = 0.14f),
                                        shape = RoundedCornerShape(15.dp)
                                    ),
                                mediaRevision = uiState.galleryMediaRevision
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = "Gallery",
                                tint = StuDOSilver,
                                modifier = Modifier.size(23.dp)
                            )
                        }

                        if (uiState.isSavingMedia) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color.Black.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = StuDOWhite,
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                }

                val isVideoRecordingUnavailable = isVideoMode && !uiState.isVideoRecordingSupported
                val isNightMode = uiState.cameraMode == CameraMode.NIGHT
                val isNightCapturing = uiState.isNightCapturing
                val isCaptureLocked = uiState.isCapturing || isNightCapturing
                val isShutterDisabled = isVideoMode && (!uiState.isVideoRecordingSupported || !uiState.isCameraReady)

                // Center: Physical Optical Liquid Glass Shutter / Video Stop Button
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .scale(shutterScale)
                        .semantics {
                            contentDescription = when {
                                isVideoRecordingUnavailable -> "Video recording unavailable on this sensor"
                                isRecording -> "Stop Recording"
                                isVideoMode -> "Record Video"
                                isNightCapturing -> "Night capture in progress"
                                isNightMode -> "Capture Night Photo"
                                else -> "Take Photo"
                            }
                        }
                        .pointerInput(isCaptureLocked, isRecording, isVideoMode, isShutterDisabled) {
                            detectTapGestures(
                                onPress = {
                                    if (!isCaptureLocked && !isShutterDisabled) {
                                        isPressed = true
                                        tryAwaitRelease()
                                        isPressed = false
                                    }
                                },
                                onTap = {
                                    if (!isCaptureLocked && !isShutterDisabled) {
                                        haptic.confirm()
                                        onCaptureClick()
                                    }
                                }
                            )
                        }
                        .testTag("camera_shutter_button"),
                    contentAlignment = Alignment.Center
                ) {
                    val ringAccent by animateColorAsState(
                        targetValue = when {
                            isRecording -> StuDORed
                            isNightCapturing || isNightMode -> GlassNightMoonAccent
                            isVideoRecordingUnavailable -> StuDOSilverMuted.copy(alpha = 0.35f)
                            else -> Color.White
                        },
                        animationSpec = tween(220),
                        label = "shutter_ring_accent"
                    )
                    val animatedNightProgress by animateFloatAsState(
                        targetValue = if (isNightCapturing) uiState.nightCaptureProgress.coerceIn(0f, 1f) else 0f,
                        animationSpec = tween(durationMillis = 160),
                        label = "night_shutter_progress"
                    )

                    Canvas(modifier = Modifier.size(86.dp)) {
                        val radius = size.minDimension / 2f
                        val ringStroke = 5.2.dp.toPx()
                        val ringRadius = radius - ringStroke / 2f

                        // Soft dark ambient contrast backing for bright-scene legibility
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF121620).copy(alpha = 0.44f),
                                    Color(0xFF080A0F).copy(alpha = 0.68f)
                                )
                            ),
                            radius = radius
                        )

                        // Outer translucent glass ring with subtle directional light refraction
                        drawCircle(
                            brush = Brush.linearGradient(
                                colorStops = arrayOf(
                                    0.0f to ringAccent.copy(alpha = if (isRecording) 0.58f else 0.44f),
                                    0.45f to Color.White.copy(alpha = 0.14f),
                                    1.0f to ringAccent.copy(alpha = if (isRecording) 0.46f else 0.28f)
                                ),
                                start = Offset(0f, 0f),
                                end = Offset(size.width, size.height)
                            ),
                            radius = ringRadius,
                            style = Stroke(width = ringStroke)
                        )

                        // Fine translucent outer optical lip (cool-blue upper-left -> warm amber lower-right)
                        drawCircle(
                            brush = Brush.linearGradient(
                                colorStops = arrayOf(
                                    0.0f to (if (isRecording) StuDORed else if (isNightMode) GlassNightCyanGlow else GlassCoolBlueSpecular).copy(alpha = 0.56f),
                                    0.32f to Color.White.copy(alpha = 0.34f),
                                    0.64f to Color.White.copy(alpha = 0.08f),
                                    1.0f to (if (isNightMode) GlassNightMoonAccent else GlassWarmAmberSpecular).copy(alpha = 0.32f)
                                ),
                                start = Offset(0f, 0f),
                                end = Offset(size.width, size.height)
                            ),
                            radius = radius - 0.45.dp.toPx(),
                            style = Stroke(width = 0.85.dp.toPx())
                        )

                        // Subtle recessed dark separation ring between outer glass housing and inner trigger
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.38f),
                            radius = radius - ringStroke - 0.5.dp.toPx(),
                            style = Stroke(width = 1.1.dp.toPx())
                        )

                        // Crisp inner specular rim inside the glass collar
                        drawCircle(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.28f),
                                    Color.White.copy(alpha = 0.06f)
                                )
                            ),
                            radius = radius - ringStroke,
                            style = Stroke(width = 0.7.dp.toPx())
                        )

                        // Glowing Liquid Glass progress arc during Night Mode capture & stacking
                        if (isNightCapturing) {
                            val arcTopLeft = Offset(ringStroke / 2f, ringStroke / 2f)
                            val arcSize = Size(size.width - ringStroke, size.height - ringStroke)
                            val sweep = (360f * animatedNightProgress.coerceAtLeast(0.06f)).coerceIn(22f, 360f)

                            // Soft outer cyan-indigo bloom
                            drawArc(
                                brush = Brush.sweepGradient(
                                    colors = listOf(
                                        GlassNightMoonAccent.copy(alpha = 0.32f),
                                        GlassNightCyanGlow.copy(alpha = 0.44f),
                                        GlassNightMoonAccent.copy(alpha = 0.32f)
                                    )
                                ),
                                startAngle = -90f,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = arcTopLeft,
                                size = arcSize,
                                style = Stroke(width = ringStroke + 4.dp.toPx(), cap = StrokeCap.Round)
                            )

                            // Crisp luminous glass progress ring
                            drawArc(
                                brush = Brush.sweepGradient(
                                    colors = listOf(
                                        GlassNightMoonAccent,
                                        GlassNightCyanGlow,
                                        StuDOWhite,
                                        GlassNightMoonAccent
                                    )
                                ),
                                startAngle = -90f,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = arcTopLeft,
                                size = arcSize,
                                style = Stroke(width = ringStroke * 0.92f, cap = StrokeCap.Round)
                            )
                        }
                    }

                    if (isNightCapturing) {
                        Box(
                            modifier = Modifier
                                .size(86.dp)
                                .testTag("night_shutter_progress_ring")
                        )
                    }

                    // Glossy inner trigger element (morphs cleanly into rounded-square Stop during recording)
                    val triggerCornerRadius by animateDpAsState(
                        targetValue = if (isRecording) 9.dp else 34.dp,
                        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium),
                        label = "trigger_corner_radius"
                    )
                    val triggerSize by animateDpAsState(
                        targetValue = if (isRecording) 32.dp else 65.dp,
                        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium),
                        label = "trigger_size"
                    )
                    val triggerTopColor by animateColorAsState(
                        targetValue = when {
                            isVideoRecordingUnavailable -> StuDOSilverMuted.copy(alpha = 0.35f)
                            isVideoMode -> Color(0xFFFF5555)
                            isNightCapturing -> Color(0xFFC7D2FE)
                            uiState.isCapturing -> StuDOSilverMuted
                            isNightMode -> Color(0xFFEEF2FF)
                            else -> Color(0xFFFFFFFF)
                        },
                        label = "trigger_top_color"
                    )
                    val triggerBottomColor by animateColorAsState(
                        targetValue = when {
                            isVideoRecordingUnavailable -> StuDOSilverMuted.copy(alpha = 0.28f)
                            isVideoMode -> Color(0xFFD92626)
                            isNightCapturing -> Color(0xFF818CF8)
                            uiState.isCapturing -> StuDOSilverMuted.copy(alpha = 0.85f)
                            isNightMode -> Color(0xFFC7D2FE)
                            else -> Color(0xFFD0D3DC)
                        },
                        label = "trigger_bottom_color"
                    )

                    val triggerShape = RoundedCornerShape(triggerCornerRadius)
                    Box(
                        modifier = Modifier
                            .size(triggerSize)
                            .clip(triggerShape)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(triggerTopColor, triggerBottomColor)
                                )
                            )
                            .drawWithCache {
                                val cr = CornerRadius(triggerCornerRadius.toPx(), triggerCornerRadius.toPx())
                                val innerSpecularRim = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = if (isVideoMode) 0.34f else 0.65f),
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.16f)
                                    )
                                )
                                onDrawWithContent {
                                    drawContent()
                                    drawRoundRect(
                                        brush = innerSpecularRim,
                                        cornerRadius = cr,
                                        style = Stroke(width = 1.dp.toPx())
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            isNightCapturing -> {
                                Icon(
                                    imageVector = Icons.Default.NightsStay,
                                    contentDescription = null,
                                    tint = Color(0xFF0F172A),
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            uiState.isCapturing -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    color = StuDOBlack,
                                    strokeWidth = 2.5.dp
                                )
                            }
                            isNightMode -> {
                                Icon(
                                    imageVector = Icons.Default.NightsStay,
                                    contentDescription = null,
                                    tint = Color(0xFF1E1B4B).copy(alpha = 0.85f),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }

                if (isRecording) {
                    // Right control during recording: Microphone On/Off Mute Toggle
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .liquidGlassSurface(
                                shape = CircleShape,
                                cornerRadius = 28.dp,
                                topColor = if (!uiState.isAudioEnabled) StuDORed.copy(alpha = 0.26f) else GlassObsidianTop.copy(alpha = 0.56f),
                                bottomColor = GlassObsidianBottom.copy(alpha = 0.74f),
                                borderWidth = 0.85.dp,
                                coolHighlightAlpha = if (!uiState.isAudioEnabled) 0.48f else 0.36f,
                                warmHighlightAlpha = 0.24f,
                                specularSheenAlpha = 0.16f
                            )
                            .clickable {
                                haptic.confirm()
                                onToggleAudio?.invoke()
                            }
                            .testTag("video_recording_mic_toggle_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (uiState.isAudioEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = if (uiState.isAudioEnabled) "Mute Microphone" else "Enable Microphone",
                            tint = if (uiState.isAudioEnabled) StuDOWhite.copy(alpha = 0.96f) else StuDORed,
                            modifier = Modifier.size(25.dp)
                        )
                    }
                } else {
                    // Right: Flip Lens Switch Button in Liquid Glass circular capsule
                    val lensSwitchEnabled = uiState.hasFrontCamera &&
                        uiState.hasBackCamera &&
                        !uiState.isCapturing &&
                        !uiState.isNightCapturing &&
                        !uiState.isRecordingVideo
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .liquidGlassSurface(
                                shape = CircleShape,
                                cornerRadius = 28.dp,
                                topColor = GlassObsidianTop.copy(alpha = 0.56f),
                                bottomColor = GlassObsidianBottom.copy(alpha = 0.74f),
                                borderWidth = 0.85.dp,
                                coolHighlightAlpha = 0.36f,
                                warmHighlightAlpha = 0.24f,
                                specularSheenAlpha = 0.16f
                            )
                            .clickable(
                                enabled = lensSwitchEnabled,
                                onClick = {
                                    haptic.tick()
                                    onToggleLens()
                                }
                            )
                            .testTag("lens_switch_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Switch Camera Lens",
                            tint = if (lensSwitchEnabled) StuDOWhite.copy(alpha = 0.94f) else StuDOSilverMuted.copy(alpha = 0.40f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Recessed Liquid Glass mode selector with elastic spring-physics droplet indicator
 * and faint luminous transition trail. Mode switches fire immediately on tap.
 */
@Composable
private fun ElasticModeSelector(
    selectedMode: CameraMode,
    enabled: Boolean,
    onModeSelected: (CameraMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val tabBounds = remember { mutableStateMapOf<CameraMode, Pair<Float, Float>>() }
    var previousMode by remember { mutableStateOf(selectedMode) }
    var lastTapDirection by remember { mutableFloatStateOf(0f) }

    if (previousMode != selectedMode) {
        lastTapDirection = if (selectedMode.ordinal > previousMode.ordinal) 1f else -1f
        previousMode = selectedMode
    }

    val targetBounds = tabBounds[selectedMode]
    val targetStart = targetBounds?.first ?: 0f
    val targetWidth = targetBounds?.second ?: 0f
    val targetEnd = targetStart + targetWidth

    // Asymmetric spring stiffness: leading edge leads ahead while trailing edge follows,
    // producing an authentic elastic droplet stretch during mode transitions.
    val movingRight = lastTapDirection >= 0f
    val animatedStart by animateFloatAsState(
        targetValue = targetStart,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = if (movingRight) 280f else 480f
        ),
        label = "elastic_mode_start"
    )
    val animatedEnd by animateFloatAsState(
        targetValue = targetEnd,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = if (movingRight) 480f else 280f
        ),
        label = "elastic_mode_end"
    )

    val isPro = selectedMode == CameraMode.PRO
    val isPortrait = selectedMode == CameraMode.PORTRAIT
    val isNight = selectedMode == CameraMode.NIGHT
    val beadTintTop by animateColorAsState(
        targetValue = when {
            isPro -> StuDOAmber.copy(alpha = 0.28f)
            isPortrait -> GlassCoolBlueSpecular.copy(alpha = 0.28f)
            isNight -> GlassNightMoonAccent.copy(alpha = 0.28f)
            else -> Color.White.copy(alpha = 0.22f)
        },
        animationSpec = tween(200),
        label = "mode_bead_top"
    )
    val beadTintBottom by animateColorAsState(
        targetValue = when {
            isPro -> Color(0xFF3A2206).copy(alpha = 0.74f)
            isPortrait -> Color(0xFF102438).copy(alpha = 0.78f)
            isNight -> Color(0xFF141836).copy(alpha = 0.80f)
            else -> GlassObsidianBottom.copy(alpha = 0.82f)
        },
        animationSpec = tween(200),
        label = "mode_bead_bottom"
    )

    Box(
        modifier = modifier
            .liquidGlassRecessedTrack(
                shape = RoundedCornerShape(22.dp),
                cornerRadius = 22.dp,
                coolHighlightAlpha = 0.28f,
                warmHighlightAlpha = 0.20f
            )
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        // Elastic droplet + faint luminous trail layer
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    val left = minOf(animatedStart, animatedEnd)
                    val right = maxOf(animatedStart, animatedEnd)
                    val width = (right - left).coerceAtLeast(0f)
                    if (width <= 1f || targetWidth <= 1f) return@drawBehind

                    // Faint luminous trail while stretched
                    val stretchRatio = ((width - targetWidth) / targetWidth).coerceIn(0f, 1f)
                    if (stretchRatio > 0.04f) {
                        val trailColor = when {
                            isPro -> StuDOAmber
                            isNight -> GlassNightMoonAccent
                            else -> GlassCoolBlueSpecular
                        }
                        val trailBrush = if (movingRight) {
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    trailColor.copy(alpha = 0.20f * stretchRatio),
                                    trailColor.copy(alpha = 0.30f * stretchRatio)
                                ),
                                startX = left,
                                endX = right
                            )
                        } else {
                            Brush.horizontalGradient(
                                colors = listOf(
                                    trailColor.copy(alpha = 0.30f * stretchRatio),
                                    trailColor.copy(alpha = 0.20f * stretchRatio),
                                    Color.Transparent
                                ),
                                startX = left,
                                endX = right
                            )
                        }
                        drawRoundRect(
                            brush = trailBrush,
                            topLeft = Offset(left, 0f),
                            size = Size(width, size.height),
                            cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx())
                        )
                    }
                }
        ) {
            if (targetWidth > 1f) {
                val leftPx = minOf(animatedStart, animatedEnd)
                val widthPx = (maxOf(animatedStart, animatedEnd) - leftPx).coerceAtLeast(targetWidth * 0.7f)
                val widthDp = with(density) { widthPx.toDp() }
                Box(
                    modifier = Modifier
                        .offset { IntOffset(leftPx.roundToInt(), 0) }
                        .width(widthDp)
                        .height(27.dp)
                        .liquidGlassSurface(
                            shape = RoundedCornerShape(18.dp),
                            cornerRadius = 18.dp,
                            topColor = beadTintTop,
                            bottomColor = beadTintBottom,
                            borderWidth = 0.8.dp,
                            coolHighlightAlpha = if (isPro) 0.36f else if (isPortrait || isNight) 0.56f else 0.44f,
                            warmHighlightAlpha = if (isPro) 0.36f else 0.28f,
                            specularSheenAlpha = 0.20f,
                            internalShadowAlpha = 0.26f
                        )
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CameraMode.values().forEach { mode ->
                val isSelected = selectedMode == mode
                val tag = when (mode) {
                    CameraMode.PHOTO -> "mode_photo_button"
                    CameraMode.PORTRAIT -> "mode_portrait_button"
                    CameraMode.NIGHT -> "mode_night_button"
                    CameraMode.PRO -> "mode_pro_button"
                    CameraMode.VIDEO -> "mode_video_button"
                }
                val textColor by animateColorAsState(
                    targetValue = when {
                        isSelected && mode == CameraMode.PRO -> StuDOAmber
                        isSelected && mode == CameraMode.PORTRAIT -> GlassCoolBlueSpecular
                        isSelected && mode == CameraMode.NIGHT -> GlassNightMoonAccent
                        isSelected -> StuDOWhite.copy(alpha = 0.98f)
                        else -> StuDOSilverMuted.copy(alpha = 0.78f)
                    },
                    animationSpec = tween(160),
                    label = "mode_text_color_${mode.name}"
                )

                Box(
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            tabBounds[mode] = coords.positionInParent().x to coords.size.width.toFloat()
                        }
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(enabled = enabled) {
                            onModeSelected(mode)
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                        .testTag(tag),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = mode.displayName,
                        color = textColor,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 10.sp,
                        letterSpacing = 0.7.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}
