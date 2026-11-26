package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.CameraUiState
import com.example.model.NightExposureMode
import com.example.ui.theme.GlassNightCyanGlow
import com.example.ui.theme.GlassNightMoonAccent
import com.example.ui.theme.GlassNightTintBottom
import com.example.ui.theme.GlassNightTintTop
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite

/**
 * Translucent Liquid Glass status pill displayed near the top of the viewfinder in Night Mode.
 * Shows estimated capture duration, active engine mode (OEM Night vs 4F/6F Stack),
 * and an interactive Auto vs Max exposure toggle.
 */
@Composable
fun NightControlsBar(
    uiState: CameraUiState,
    onSelectExposureMode: (NightExposureMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = rememberStuDOHaptic(uiState.isHapticFeedbackEnabled)
    val durationText = uiState.nightDurationBadgeText
    val pillCd = stringResource(R.string.night_status_pill_cd, durationText)
    val engineLabel = if (uiState.isNightExtensionSupported) {
        stringResource(R.string.night_engine_hw)
    } else {
        stringResource(R.string.night_engine_stack, uiState.nightExposureMode.burstFrameCount)
    }
    val controlsEnabled = !uiState.isNightCapturing && !uiState.isCapturing

    Row(
        modifier = modifier
            .widthIn(max = 360.dp)
            .liquidGlassSurface(
                shape = RoundedCornerShape(24.dp),
                cornerRadius = 24.dp,
                topColor = GlassNightTintTop.copy(alpha = 0.62f),
                bottomColor = GlassNightTintBottom.copy(alpha = 0.78f),
                borderWidth = 0.85.dp,
                coolHighlightAlpha = 0.46f,
                warmHighlightAlpha = 0.18f,
                specularSheenAlpha = 0.16f
            )
            .pointerInput(Unit) { detectTapGestures { /* absorb taps cleanly */ } }
            .padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 5.dp)
            .semantics { contentDescription = pillCd }
            .testTag("night_status_pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Moon glyph + estimated exposure duration + active engine badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.NightsStay,
                contentDescription = stringResource(R.string.night_mode_title),
                tint = GlassNightMoonAccent,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = durationText,
                color = StuDOWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.testTag("night_duration_badge")
            )
            Box(
                modifier = Modifier
                    .liquidGlassChipSurface(
                        shape = RoundedCornerShape(8.dp),
                        cornerRadius = 8.dp,
                        isActive = uiState.isNightExtensionSupported,
                        accentColor = GlassNightCyanGlow,
                        activeFillAlpha = 0.20f
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("night_engine_badge")
            ) {
                Text(
                    text = engineLabel,
                    color = if (uiState.isNightExtensionSupported) GlassNightCyanGlow else GlassNightMoonAccent,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        // Segmented Auto / Max exposure duration toggle
        Row(
            modifier = Modifier
                .liquidGlassRecessedTrack(
                    shape = RoundedCornerShape(18.dp),
                    cornerRadius = 18.dp,
                    coolHighlightAlpha = 0.30f,
                    warmHighlightAlpha = 0.14f
                )
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            NightExposureMode.values().forEach { mode ->
                val isSelected = uiState.nightExposureMode == mode
                val label = when (mode) {
                    NightExposureMode.AUTO -> stringResource(R.string.night_exposure_auto)
                    NightExposureMode.MAX -> stringResource(R.string.night_exposure_max)
                }
                val chipCd = when (mode) {
                    NightExposureMode.AUTO -> stringResource(R.string.night_auto_cd)
                    NightExposureMode.MAX -> stringResource(R.string.night_max_cd)
                }
                val chipTag = when (mode) {
                    NightExposureMode.AUTO -> "night_mode_auto_chip"
                    NightExposureMode.MAX -> "night_mode_max_chip"
                }
                val textColor by animateColorAsState(
                    targetValue = when {
                        isSelected -> StuDOWhite
                        controlsEnabled -> StuDOSilverMuted
                        else -> StuDOSilverMuted.copy(alpha = 0.45f)
                    },
                    animationSpec = tween(160),
                    label = "night_mode_chip_text_${mode.name}"
                )

                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 44.dp, minHeight = 32.dp)
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(15.dp),
                            cornerRadius = 15.dp,
                            isActive = isSelected,
                            isEnabled = controlsEnabled,
                            accentColor = GlassNightMoonAccent,
                            activeFillAlpha = 0.34f
                        )
                        .clickable(
                            enabled = controlsEnabled,
                            role = Role.RadioButton
                        ) {
                            if (uiState.nightExposureMode != mode) {
                                haptic.tick()
                                onSelectExposureMode(mode)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .semantics {
                            contentDescription = chipCd
                            selected = isSelected
                        }
                        .testTag(chipTag),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = textColor,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

/**
 * Non-intrusive translucent Liquid Glass indicator displayed during Night Mode acquisition
 * and multi-frame alignment/stacking.
 */
@Composable
fun NightHoldSteadyPill(
    statusText: String,
    modifier: Modifier = Modifier
) {
    val label = statusText.ifBlank { stringResource(R.string.night_hold_steady) }
    val infiniteTransition = rememberInfiniteTransition(label = "night_hold_steady_pulse")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "night_hold_steady_glow"
    )

    Row(
        modifier = modifier
            .liquidGlassSurface(
                shape = RoundedCornerShape(20.dp),
                cornerRadius = 20.dp,
                topColor = GlassNightTintTop.copy(alpha = 0.72f),
                bottomColor = GlassNightTintBottom.copy(alpha = 0.86f),
                borderWidth = 0.9.dp,
                coolHighlightAlpha = 0.54f,
                warmHighlightAlpha = 0.16f,
                specularSheenAlpha = 0.18f
            )
            .padding(horizontal = 14.dp, vertical = 7.dp)
            .semantics { contentDescription = label }
            .testTag("night_hold_steady_pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(GlassNightCyanGlow.copy(alpha = glowAlpha))
        )
        Icon(
            imageVector = Icons.Default.NightsStay,
            contentDescription = null,
            tint = GlassNightMoonAccent.copy(alpha = 0.92f),
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = label,
            color = StuDOWhite,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}
