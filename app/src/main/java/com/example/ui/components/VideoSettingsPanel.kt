package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CameraUiState
import com.example.model.VideoCodecOption
import com.example.model.VideoFpsOption
import com.example.model.VideoQualityOption
import com.example.ui.theme.GlassCoolBlueSpecular
import com.example.ui.theme.GlassObsidianBottom
import com.example.ui.theme.GlassObsidianTop
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDORed
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite

@Composable
fun VideoSettingsPanel(
    uiState: CameraUiState,
    onSelectQuality: (VideoQualityOption) -> Unit,
    onSelectFps: (VideoFpsOption) -> Unit,
    onSelectCodec: (VideoCodecOption) -> Unit,
    onToggleStabilization: () -> Unit,
    onToggleAudio: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = rememberStuDOHaptic(uiState.isHapticFeedbackEnabled)
    val availableQualities = uiState.supportedVideoQualities.ifEmpty {
        listOf(VideoQualityOption.QUALITY_1080P, VideoQualityOption.QUALITY_720P)
    }
    val availableFps = (uiState.supportedFpsPerQuality[uiState.videoQuality] ?: uiState.supportedVideoFps)
        .ifEmpty { listOf(VideoFpsOption.FPS_AUTO, VideoFpsOption.FPS_30) }
    val availableCodecs = uiState.supportedVideoCodecs.ifEmpty {
        listOf(VideoCodecOption.H264)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlassSurface(
                shape = RoundedCornerShape(22.dp),
                cornerRadius = 22.dp,
                topColor = GlassObsidianTop.copy(alpha = 0.56f),
                bottomColor = GlassObsidianBottom.copy(alpha = 0.72f),
                borderWidth = 0.85.dp,
                coolHighlightAlpha = 0.36f,
                warmHighlightAlpha = 0.24f,
                specularSheenAlpha = 0.14f
            )
            .pointerInput(Unit) { detectTapGestures { /* absorb inside panel taps */ } }
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("video_settings_panel")
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Video Settings",
                        tint = StuDOAmber,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "VIDEO SETTINGS",
                        color = StuDOWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .liquidGlassChipSurface(
                            shape = CircleShape,
                            cornerRadius = 11.dp
                        )
                        .clickable(onClick = onClose)
                        .testTag("video_settings_close_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Video Settings",
                        tint = StuDOSilver,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            // Row 1: Supported Resolutions
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = "Resolution",
                        tint = StuDOSilver,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "RESOLUTION",
                        color = StuDOSilver,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.9.sp
                    )
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    availableQualities.forEach { quality ->
                        val isSelected = quality == uiState.videoQuality
                        val label = when (quality) {
                            VideoQualityOption.QUALITY_2160P -> "4K"
                            VideoQualityOption.QUALITY_1080P -> "1080P"
                            VideoQualityOption.QUALITY_720P -> "720P"
                            VideoQualityOption.QUALITY_480P -> "480P"
                        }
                        Box(
                            modifier = Modifier
                                .liquidGlassChipSurface(
                                    shape = RoundedCornerShape(12.dp),
                                    cornerRadius = 12.dp,
                                    isActive = isSelected,
                                    accentColor = StuDOAmber
                                )
                                .clickable {
                                    haptic.tick()
                                    onSelectQuality(quality)
                                }
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                                .testTag("video_panel_quality_${quality.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) StuDOAmber else StuDOWhite.copy(alpha = 0.90f),
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Row 2: Dynamically Verified Framerate (FPS) per selected resolution
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Framerate",
                        tint = StuDOSilver,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "FPS",
                        color = StuDOSilver,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.9.sp
                    )
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    availableFps.forEach { fpsOption ->
                        val isSelected = fpsOption == uiState.videoFps
                        Box(
                            modifier = Modifier
                                .liquidGlassChipSurface(
                                    shape = RoundedCornerShape(12.dp),
                                    cornerRadius = 12.dp,
                                    isActive = isSelected,
                                    accentColor = StuDOAmber
                                )
                                .clickable {
                                    haptic.tick()
                                    onSelectFps(fpsOption)
                                }
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                                .testTag("video_panel_fps_${fpsOption.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = fpsOption.shortLabel,
                                color = if (isSelected) StuDOAmber else StuDOWhite.copy(alpha = 0.90f),
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Row 3: Genuinely Supported MP4 Codecs
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Video Codec",
                        tint = StuDOSilver,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "CODEC",
                        color = StuDOSilver,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.9.sp
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    availableCodecs.forEach { codec ->
                        val isSelected = codec == uiState.videoCodec
                        Box(
                            modifier = Modifier
                                .liquidGlassChipSurface(
                                    shape = RoundedCornerShape(12.dp),
                                    cornerRadius = 12.dp,
                                    isActive = isSelected,
                                    accentColor = GlassCoolBlueSpecular
                                )
                                .clickable {
                                    haptic.tick()
                                    onSelectCodec(codec)
                                }
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                                .testTag("video_panel_codec_${codec.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = codec.shortLabel,
                                color = if (isSelected) GlassCoolBlueSpecular else StuDOWhite.copy(alpha = 0.90f),
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Row 4: Stabilization & Audio Quick Toggles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.isVideoStabilizationSupported) {
                    val eisOn = uiState.isVideoStabilizationEnabled
                    Box(
                        modifier = Modifier
                            .liquidGlassChipSurface(
                                shape = RoundedCornerShape(12.dp),
                                cornerRadius = 12.dp,
                                isActive = eisOn,
                                accentColor = StuDOAmber,
                                activeFillAlpha = 0.18f
                            )
                            .clickable {
                                haptic.tick()
                                onToggleStabilization()
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("video_panel_eis_toggle"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.StayCurrentPortrait,
                                contentDescription = "Electronic Video Stabilization",
                                tint = if (eisOn) StuDOAmber else StuDOSilver,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = if (eisOn) "EIS ON" else "EIS OFF",
                                color = if (eisOn) StuDOAmber else StuDOSilverMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.7.sp
                            )
                        }
                    }
                }

                val micOn = uiState.isAudioEnabled
                Box(
                    modifier = Modifier
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(12.dp),
                            cornerRadius = 12.dp,
                            isActive = !micOn,
                            accentColor = StuDORed,
                            activeFillAlpha = 0.18f
                        )
                        .clickable {
                            haptic.tick()
                            onToggleAudio()
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .testTag("video_panel_mic_toggle"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = if (micOn) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = if (micOn) "Microphone On" else "Microphone Muted",
                            tint = if (micOn) Color(0xFF00E676) else StuDORed,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = if (micOn) "MIC ON" else "MIC MUTED",
                            color = if (micOn) StuDOWhite else StuDORed,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.7.sp
                        )
                    }
                }
            }
        }
    }
}
