package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.model.CameraFilter
import com.example.model.CameraMode
import com.example.model.CameraUiState
import com.example.model.CapturePriority
import com.example.model.CaptureTimer
import com.example.model.FlashMode
import com.example.model.GridOption
import com.example.model.PhotoResolution
import com.example.model.RawCaptureMode
import com.example.model.ScreenFlashMode
import com.example.model.VideoQualityOption
import com.example.ui.theme.GlassCoolBlueSpecular
import com.example.ui.theme.GlassObsidianBottom
import com.example.ui.theme.GlassObsidianTop
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDOGreen
import com.example.ui.theme.StuDORed
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite
import java.util.Locale

@Composable
fun CameraTopBar(
    uiState: CameraUiState,
    onCycleFlash: () -> Unit,
    onOpenSettings: () -> Unit,
    onCycleAspectRatio: () -> Unit,
    onToggleQuickSettings: () -> Unit,
    onToggleFilterSelector: () -> Unit,
    onCycleRawMode: () -> Unit = {},
    onCycleVideoQualityAndFps: (() -> Unit)? = null,
    onToggleVideoSettingsPanel: (() -> Unit)? = null,
    onCycleVideoAspectRatio: (() -> Unit)? = null,
    onToggleAudio: (() -> Unit)? = null,
    onToggleVideoStabilization: (() -> Unit)? = null,
    onOpenGallery: () -> Unit = {},
    onOpenFiles: () -> Unit = {},
    onTriggerHaptic: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showSourceSelector by remember { mutableStateOf(false) }
    var isBrandPressed by remember { mutableStateOf(false) }
    val brandPressScale by animateFloatAsState(
        targetValue = if (isBrandPressed) 0.94f else 1.0f,
        animationSpec = tween(durationMillis = 120),
        label = "brand_capsule_press_scale"
    )

    if (showSourceSelector) {
        CameraSourceSelectorDialog(
            onOpenGallery = onOpenGallery,
            onOpenFiles = onOpenFiles,
            onDismiss = { showSourceSelector = false }
        )
    }

    val nonEssentialAlpha by animateFloatAsState(
        targetValue = if (uiState.isRecordingVideo) 0.30f else 1.0f,
        animationSpec = tween(durationMillis = 220),
        label = "top_non_essential_alpha"
    )

    val leftTopTint by animateColorAsState(
        targetValue = when {
            uiState.isRecordingVideo && uiState.isRecordingPaused -> LiquidGlassTints.pausedTop
            uiState.isRecordingVideo -> LiquidGlassTints.recordingTop
            else -> GlassObsidianTop.copy(alpha = 0.46f)
        },
        animationSpec = tween(durationMillis = 240),
        label = "left_capsule_top_tint"
    )
    val leftBottomTint by animateColorAsState(
        targetValue = when {
            uiState.isRecordingVideo && uiState.isRecordingPaused -> LiquidGlassTints.pausedBottom
            uiState.isRecordingVideo -> LiquidGlassTints.recordingBottom
            else -> GlassObsidianBottom.copy(alpha = 0.60f)
        },
        animationSpec = tween(durationMillis = 240),
        label = "left_capsule_bottom_tint"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Glass Capsule: STU•D•O Brand + Contextual Status / Recording Timer
        val canImportFromBrand = !uiState.isRecordingVideo && !uiState.isCapturing
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .graphicsLayer {
                    scaleX = brandPressScale
                    scaleY = brandPressScale
                }
                .liquidGlassSurface(
                    shape = RoundedCornerShape(24.dp),
                    cornerRadius = 24.dp,
                    topColor = leftTopTint,
                    bottomColor = leftBottomTint,
                    borderWidth = 0.8.dp,
                    coolHighlightAlpha = if (uiState.isRecordingVideo) 0.44f else 0.36f,
                    warmHighlightAlpha = 0.24f,
                    specularSheenAlpha = 0.14f
                )
                .drawWithCache {
                    val cr = CornerRadius(24.dp.toPx(), 24.dp.toPx())
                    // Subtle diagonal internal glass reflection sweep
                    val internalGlint = Brush.linearGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.22f to Color.White.copy(alpha = 0.06f),
                            0.36f to GlassCoolBlueSpecular.copy(alpha = 0.04f),
                            0.55f to Color.Transparent
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(size.width * 0.85f, size.height)
                    )
                    onDrawBehind {
                        drawRoundRect(brush = internalGlint, cornerRadius = cr)
                    }
                }
                .pointerInput(canImportFromBrand) {
                    detectTapGestures(
                        onPress = {
                            if (canImportFromBrand) {
                                isBrandPressed = true
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    isBrandPressed = false
                                }
                            }
                        },
                        onLongPress = {
                            if (canImportFromBrand) {
                                isBrandPressed = false
                                onTriggerHaptic()
                                showSourceSelector = true
                            }
                        }
                    )
                }
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 7.dp)
                .semantics {
                    contentDescription = "StuDO brand pill. Long press to open photo from Gallery or Files."
                }
                .testTag("top_left_brand_capsule"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (uiState.isRecordingVideo) {
                ActiveRecordingBadge(
                    isPaused = uiState.isRecordingPaused,
                    durationSeconds = uiState.recordingDurationSeconds
                )
                Text(
                    text = "STU•D•O",
                    color = StuDOWhite.copy(alpha = 0.78f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.6.sp,
                    modifier = Modifier.testTag("app_brand_text")
                )
            } else {
                Text(
                    text = "STU•D•O",
                    color = StuDOWhite.copy(alpha = 0.96f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.2.sp,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.testTag("app_brand_text")
                )

                // Still-photo badges (PORTRAIT aperture, UHR, RAW/JPEG, HDR, SPEED) only appear in still modes, never in Video mode
                if (uiState.cameraMode != CameraMode.VIDEO) {

                if (uiState.cameraMode == CameraMode.PORTRAIT) {
                    val fStopText = com.example.model.PortraitApertureFormatter.formatFStop(uiState.portraitBlurIntensity)
                    val apertureCd = stringResource(R.string.portrait_aperture_cd, fStopText)
                    Box(
                        modifier = Modifier
                            .liquidGlassChipSurface(
                                shape = RoundedCornerShape(8.dp),
                                cornerRadius = 8.dp,
                                isActive = true,
                                accentColor = GlassCoolBlueSpecular,
                                activeFillAlpha = 0.20f
                            )
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                            .semantics { contentDescription = apertureCd }
                            .testTag("top_portrait_aperture_badge")
                    ) {
                        Text(
                            text = fStopText,
                            color = GlassCoolBlueSpecular,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                if (uiState.photoResolution == PhotoResolution.ULTRA_RES) {
                    val uhrText = if (uiState.uhrMegapixels > 0.0) {
                        "UHR ${String.format(Locale.US, "%.0f", uiState.uhrMegapixels)}MP"
                    } else {
                        "UHR"
                    }
                    Box(
                        modifier = Modifier
                            .liquidGlassChipSurface(
                                shape = RoundedCornerShape(8.dp),
                                cornerRadius = 8.dp,
                                isActive = true,
                                accentColor = StuDOAmber,
                                activeFillAlpha = 0.22f
                            )
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = uhrText,
                            color = StuDOAmber,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // In PRO mode or when RAW is active, show interactive RAW / JPEG format selector
                val isPro = uiState.cameraMode == CameraMode.PRO
                if (isPro || uiState.rawCaptureMode != RawCaptureMode.OFF) {
                    val rawLabel = when (uiState.rawCaptureMode) {
                        RawCaptureMode.OFF -> "JPEG"
                        RawCaptureMode.DNG_ONLY -> "RAW"
                        RawCaptureMode.JPEG_AND_DNG -> "RAW+"
                    }
                    val rawColor = when (uiState.rawCaptureMode) {
                        RawCaptureMode.OFF -> StuDOWhite
                        RawCaptureMode.DNG_ONLY -> GlassCoolBlueSpecular
                        RawCaptureMode.JPEG_AND_DNG -> StuDOAmber
                    }
                    val isRawActive = uiState.rawCaptureMode != RawCaptureMode.OFF

                    Box(
                        modifier = Modifier
                            .liquidGlassChipSurface(
                                shape = RoundedCornerShape(8.dp),
                                cornerRadius = 8.dp,
                                isActive = isRawActive,
                                isEnabled = !uiState.isRecordingVideo && !uiState.isCapturing,
                                accentColor = rawColor,
                                activeFillAlpha = 0.20f
                            )
                            .clickable(enabled = !uiState.isRecordingVideo && !uiState.isCapturing) {
                                onCycleRawMode()
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("raw_format_toggle_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = rawLabel,
                            color = rawColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (uiState.isHdrEnabled) {
                    Box(
                        modifier = Modifier
                            .liquidGlassChipSurface(
                                shape = RoundedCornerShape(8.dp),
                                cornerRadius = 8.dp,
                                isActive = true,
                                accentColor = StuDOAmber,
                                activeFillAlpha = 0.20f
                            )
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "HDR",
                            color = StuDOAmber,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (uiState.capturePriority == CapturePriority.LATENCY) {
                    Box(
                        modifier = Modifier
                            .liquidGlassChipSurface(
                                shape = RoundedCornerShape(8.dp),
                                cornerRadius = 8.dp,
                                isActive = true,
                                accentColor = StuDOGreen,
                                activeFillAlpha = 0.18f
                            )
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "SPEED",
                            color = Color(0xFFA5D6A7),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                } // end Photo/Pro badges
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Right Glass Capsule: Existing Mode-Specific Camera Controls (order & visibility preserved)
        Row(
            modifier = Modifier
                .liquidGlassSurface(
                    shape = RoundedCornerShape(24.dp),
                    cornerRadius = 24.dp,
                    topColor = GlassObsidianTop.copy(alpha = 0.46f),
                    bottomColor = GlassObsidianBottom.copy(alpha = 0.60f),
                    borderWidth = 0.8.dp,
                    coolHighlightAlpha = 0.36f,
                    warmHighlightAlpha = 0.24f,
                    specularSheenAlpha = 0.14f
                )
                .padding(horizontal = 7.dp, vertical = 5.dp)
                .testTag("top_right_controls_capsule"),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (uiState.cameraMode == CameraMode.VIDEO) {
                // Video Quality & FPS Pill (e.g. "1080P · AUTO") - opens anchored Video Settings Panel
                val qualityLabel = when (uiState.videoQuality) {
                    VideoQualityOption.QUALITY_2160P -> "4K"
                    VideoQualityOption.QUALITY_1080P -> "1080P"
                    VideoQualityOption.QUALITY_720P -> "720P"
                    VideoQualityOption.QUALITY_480P -> "480P"
                }
                val resFpsLabel = "$qualityLabel · ${uiState.videoFps.shortLabel}"
                val isVideoPanelOpen = uiState.isVideoSettingsPanelOpen

                Box(
                    modifier = Modifier
                        .alpha(nonEssentialAlpha)
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(14.dp),
                            cornerRadius = 14.dp,
                            isActive = isVideoPanelOpen,
                            isEnabled = !uiState.isRecordingVideo,
                            accentColor = StuDOAmber,
                            activeFillAlpha = 0.22f
                        )
                        .clickable(
                            enabled = !uiState.isRecordingVideo,
                            onClick = {
                                if (onToggleVideoSettingsPanel != null) {
                                    onToggleVideoSettingsPanel()
                                } else {
                                    onCycleVideoQualityAndFps?.invoke()
                                }
                            }
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("video_quality_fps_toggle_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = resFpsLabel,
                        color = if (isVideoPanelOpen) StuDOAmber else StuDOWhite.copy(alpha = 0.94f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                // Video Aspect Ratio selector pill (shown when device supports >1 video aspect ratio)
                if (uiState.isVideoRecordingSupported && uiState.supportedVideoAspectRatios.size > 1) {
                    Box(
                        modifier = Modifier
                            .alpha(nonEssentialAlpha)
                            .liquidGlassChipSurface(
                                shape = RoundedCornerShape(14.dp),
                                cornerRadius = 14.dp,
                                isActive = false,
                                isEnabled = !uiState.isRecordingVideo
                            )
                            .clickable(
                                enabled = !uiState.isRecordingVideo,
                                onClick = { onCycleVideoAspectRatio?.invoke() }
                            )
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                            .testTag("video_aspect_ratio_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = uiState.videoAspectRatio.displayName,
                            color = if (!uiState.isRecordingVideo) StuDOWhite.copy(alpha = 0.94f) else StuDOSilverMuted.copy(alpha = 0.40f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                // Video Stabilization (EIS) toggle pill (strictly shown when hardware supports EIS)
                if (uiState.isVideoStabilizationSupported) {
                    val isEisEnabled = uiState.isVideoStabilizationEnabled
                    Box(
                        modifier = Modifier
                            .alpha(nonEssentialAlpha)
                            .liquidGlassChipSurface(
                                shape = RoundedCornerShape(14.dp),
                                cornerRadius = 14.dp,
                                isActive = isEisEnabled,
                                isEnabled = !uiState.isRecordingVideo,
                                accentColor = StuDOAmber
                            )
                            .clickable(
                                enabled = !uiState.isRecordingVideo,
                                onClick = { onToggleVideoStabilization?.invoke() }
                            )
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                            .testTag("video_stabilization_toggle_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "EIS",
                            color = if (isEisEnabled) StuDOAmber else StuDOSilverMuted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Microphone audio toggle button (essential during recording)
                Box(
                    modifier = Modifier
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(14.dp),
                            cornerRadius = 14.dp,
                            isActive = !uiState.isAudioEnabled,
                            accentColor = StuDORed
                        )
                        .clickable(
                            onClick = { onToggleAudio?.invoke() }
                        )
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                        .testTag("video_mic_top_toggle_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (uiState.isAudioEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = if (uiState.isAudioEnabled) "Microphone Enabled" else "Microphone Muted",
                        tint = if (uiState.isAudioEnabled) StuDOWhite.copy(alpha = 0.94f) else StuDORed,
                        modifier = Modifier.size(14.dp)
                    )
                }

                // Torch Flash button
                val torchActive = uiState.flashMode == FlashMode.TORCH
                Box(
                    modifier = Modifier
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(14.dp),
                            cornerRadius = 14.dp,
                            isActive = torchActive,
                            isEnabled = uiState.isFlashAvailable,
                            accentColor = StuDOAmber
                        )
                        .clickable(
                            enabled = uiState.isFlashAvailable,
                            onClick = onCycleFlash
                        )
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                        .testTag("video_torch_toggle_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Highlight,
                        contentDescription = "Torch: ${if (torchActive) "On" else "Off"}",
                        tint = if (torchActive) StuDOAmber else if (uiState.isFlashAvailable) StuDOSilver else StuDOSilverMuted.copy(alpha = 0.40f),
                        modifier = Modifier.size(14.dp)
                    )
                }

                // Settings button (fades to 30% during recording; compact Box avoids M3 48dp hitbox overlap)
                Box(
                    modifier = Modifier
                        .alpha(nonEssentialAlpha)
                        .size(26.dp)
                        .liquidGlassChipSurface(
                            shape = CircleShape,
                            cornerRadius = 13.dp,
                            isActive = false,
                            isEnabled = !uiState.isRecordingVideo
                        )
                        .clickable(
                            enabled = !uiState.isRecordingVideo,
                            onClick = onOpenSettings
                        )
                        .testTag("top_settings_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Camera Settings",
                        tint = StuDOSilver,
                        modifier = Modifier.size(14.dp)
                    )
                }
            } else {
                // Live Filter Selector Pill
                val isFilterActive = uiState.selectedFilter != CameraFilter.NATURAL
                val filterChipActive = isFilterActive || uiState.isFilterSelectorOpen
                val filterAccent = if (isFilterActive) uiState.selectedFilter.tintColor else StuDOAmber
                Box(
                    modifier = Modifier
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(14.dp),
                            cornerRadius = 14.dp,
                            isActive = filterChipActive,
                            isEnabled = !uiState.isRecordingVideo,
                            accentColor = filterAccent,
                            activeFillAlpha = 0.20f
                        )
                        .clickable(
                            enabled = !uiState.isRecordingVideo,
                            onClick = onToggleFilterSelector
                        )
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                        .testTag("top_filter_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Tone Filters",
                            tint = if (isFilterActive) uiState.selectedFilter.tintColor else StuDOSilver,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = uiState.selectedFilter.displayName.uppercase(),
                            color = if (isFilterActive) uiState.selectedFilter.tintColor else StuDOSilver,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Quick Settings / Pro Tools Drawer Button
                val isToolsActive = uiState.selectedTimer != CaptureTimer.OFF ||
                    uiState.selectedGrid != GridOption.OFF ||
                    uiState.isLevelIndicatorEnabled ||
                    uiState.isHistogramEnabled ||
                    uiState.isQuickSettingsOpen

                Box(
                    modifier = Modifier
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(14.dp),
                            cornerRadius = 14.dp,
                            isActive = isToolsActive,
                            isEnabled = !uiState.isRecordingVideo,
                            accentColor = StuDOAmber,
                            activeFillAlpha = 0.20f
                        )
                        .clickable(
                            enabled = !uiState.isRecordingVideo,
                            onClick = onToggleQuickSettings
                        )
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                        .testTag("top_tools_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Camera Pro Tools",
                            tint = if (isToolsActive) StuDOAmber else StuDOSilver,
                            modifier = Modifier.size(13.dp)
                        )
                        if (uiState.selectedTimer != CaptureTimer.OFF) {
                            Text(
                                text = uiState.activeTimerLabel,
                                color = StuDOAmber,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Aspect Ratio Selector
                Box(
                    modifier = Modifier
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(14.dp),
                            cornerRadius = 14.dp,
                            isActive = false,
                            isEnabled = !uiState.isRecordingVideo
                        )
                        .clickable(
                            enabled = !uiState.isRecordingVideo,
                            onClick = onCycleAspectRatio
                        )
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                        .testTag("aspect_ratio_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = uiState.aspectRatio.displayName,
                        color = if (!uiState.isRecordingVideo) StuDOWhite.copy(alpha = 0.94f) else StuDOSilverMuted.copy(alpha = 0.40f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Flash / Front Screen Flash button in liquid glass chip
                val isScreenFlashMode = !uiState.isFlashAvailable && uiState.isScreenFlashAvailable
                val canToggleFlash = (uiState.isFlashAvailable || uiState.isScreenFlashAvailable) && !uiState.isRecordingVideo
                val flashActive = when {
                    uiState.isFlashAvailable -> uiState.flashMode != FlashMode.OFF
                    uiState.isScreenFlashAvailable -> uiState.screenFlashMode != ScreenFlashMode.OFF
                    else -> false
                }
                val flashAccentColor = if (isScreenFlashMode) Color(0xFFFFE5B4) else StuDOAmber
                val flashIconColor by animateColorAsState(
                    targetValue = when {
                        !canToggleFlash && !(uiState.isFlashAvailable || uiState.isScreenFlashAvailable) -> StuDOSilverMuted.copy(alpha = 0.40f)
                        isScreenFlashMode && flashActive -> Color(0xFFFFF3D6)
                        flashActive -> StuDOAmber
                        uiState.isFlashAvailable || uiState.isScreenFlashAvailable -> StuDOSilver
                        else -> StuDOSilverMuted.copy(alpha = 0.40f)
                    },
                    label = "flash_icon"
                )
                val flashContentDescription = if (isScreenFlashMode) {
                    when (uiState.screenFlashMode) {
                        ScreenFlashMode.OFF -> stringResource(R.string.screen_flash_cd_off)
                        ScreenFlashMode.AUTO -> stringResource(R.string.screen_flash_cd_auto)
                        ScreenFlashMode.ON -> stringResource(R.string.screen_flash_cd_on)
                    }
                } else {
                    stringResource(R.string.flash_cd_mode, uiState.flashMode.displayName)
                }

                Box(
                    modifier = Modifier
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(14.dp),
                            cornerRadius = 14.dp,
                            isActive = flashActive,
                            isEnabled = canToggleFlash,
                            accentColor = flashAccentColor,
                            activeFillAlpha = if (isScreenFlashMode) 0.24f else 0.16f
                        )
                        .clickable(
                            enabled = canToggleFlash,
                            onClick = onCycleFlash
                        )
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                        .testTag("flash_toggle_button"),
                    contentAlignment = Alignment.Center
                ) {
                    val icon = if (isScreenFlashMode) {
                        when (uiState.screenFlashMode) {
                            ScreenFlashMode.AUTO -> Icons.Default.FlashAuto
                            ScreenFlashMode.ON -> Icons.Default.FlashOn
                            ScreenFlashMode.OFF -> Icons.Default.FlashOff
                        }
                    } else {
                        when (uiState.flashMode) {
                            FlashMode.AUTO -> Icons.Default.FlashAuto
                            FlashMode.ON -> Icons.Default.FlashOn
                            FlashMode.OFF -> Icons.Default.FlashOff
                            FlashMode.TORCH -> Icons.Default.Highlight
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = flashContentDescription,
                            tint = flashIconColor,
                            modifier = Modifier.size(14.dp)
                        )
                        if (isScreenFlashMode && flashActive) {
                            Text(
                                text = stringResource(R.string.screen_flash_badge),
                                color = flashIconColor,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("screen_flash_mode_badge")
                            )
                        }
                    }
                }

                // Settings button (compact Box avoids M3 48dp forced hitbox overlap with flash toggle)
                Box(
                    modifier = Modifier
                        .alpha(nonEssentialAlpha)
                        .size(26.dp)
                        .liquidGlassChipSurface(
                            shape = CircleShape,
                            cornerRadius = 13.dp,
                            isActive = false,
                            isEnabled = !uiState.isRecordingVideo
                        )
                        .clickable(
                            enabled = !uiState.isRecordingVideo,
                            onClick = onOpenSettings
                        )
                        .testTag("top_settings_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Camera Settings",
                        tint = StuDOSilver,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveRecordingBadge(
    isPaused: Boolean,
    durationSeconds: Long
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recording_pulse")
    val recordingAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rec_dot_alpha"
    )

    val dotColor = if (isPaused) StuDOAmber else StuDORed.copy(alpha = recordingAlpha)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.testTag("video_recording_timer_badge")
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        val mins = durationSeconds / 60
        val secs = durationSeconds % 60
        val timeString = String.format(Locale.US, "%02d:%02d", mins, secs)
        Text(
            text = if (isPaused) "PAUSED $timeString" else timeString,
            color = if (isPaused) StuDOAmber else StuDOWhite,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CameraSourceSelectorDialog(
    onOpenGallery: () -> Unit,
    onOpenFiles: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .widthIn(max = 380.dp)
                .liquidGlassSurface(
                    shape = RoundedCornerShape(26.dp),
                    cornerRadius = 26.dp,
                    topColor = GlassObsidianTop.copy(alpha = 0.90f),
                    bottomColor = GlassObsidianBottom.copy(alpha = 0.95f),
                    borderWidth = 1.dp,
                    coolHighlightAlpha = 0.36f,
                    warmHighlightAlpha = 0.24f,
                    specularSheenAlpha = 0.14f,
                    internalShadowAlpha = 0.28f
                )
                .padding(18.dp)
                .testTag("camera_source_selector_dialog")
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "IMPORT PHOTO",
                            color = StuDOAmber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.3.sp
                        )
                        Text(
                            text = "Open in Photo Editor",
                            color = StuDOWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("source_selector_close_button")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .liquidGlassChipSurface(
                                    shape = CircleShape,
                                    cornerRadius = 17.dp,
                                    isActive = false
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close import source selector",
                                tint = StuDOSilver,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Option 1: Gallery (Android Photo Picker)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(18.dp),
                            cornerRadius = 18.dp,
                            isActive = true,
                            accentColor = StuDOAmber,
                            activeFillAlpha = 0.14f
                        )
                        .clickable {
                            onDismiss()
                            onOpenGallery()
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .testTag("source_selector_gallery_option"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(StuDOAmber.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = "Gallery",
                            tint = StuDOAmber,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Gallery",
                            color = StuDOWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Choose from Albums & Media Library",
                            color = StuDOSilverMuted,
                            fontSize = 11.5.sp
                        )
                    }
                }

                // Option 2: Files (Storage Access Framework)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(18.dp),
                            cornerRadius = 18.dp,
                            isActive = true,
                            accentColor = GlassCoolBlueSpecular,
                            activeFillAlpha = 0.14f
                        )
                        .clickable {
                            onDismiss()
                            onOpenFiles()
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .testTag("source_selector_files_option"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(GlassCoolBlueSpecular.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Files",
                            tint = GlassCoolBlueSpecular,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Files",
                            color = StuDOWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Browse device storage, downloads & folders",
                            color = StuDOSilverMuted,
                            fontSize = 11.5.sp
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("source_selector_cancel_button")
                    ) {
                        Text("Cancel", color = StuDOSilver, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

