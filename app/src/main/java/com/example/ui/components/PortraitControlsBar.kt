package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.CameraUiState
import com.example.model.PortraitApertureFormatter
import com.example.model.PortraitDepthSource
import com.example.ui.theme.GlassCoolBlueSpecular
import com.example.ui.theme.GlassObsidianBottom
import com.example.ui.theme.GlassObsidianTop
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDOGreen
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite
import kotlin.math.abs
import kotlin.math.roundToInt

private data class PortraitPresetOption(
    val label: String,
    val intensity: Float
)

private val PORTRAIT_PRESET_OPTIONS = listOf(
    PortraitPresetOption("f/1.4", 1.0f),
    PortraitPresetOption("f/2.0", 0.854f),
    PortraitPresetOption("f/4.0", 0.570f),
    PortraitPresetOption("f/8.0", 0.284f)
)

@Composable
fun PortraitControlsBar(
    uiState: CameraUiState,
    onBlurIntensityChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = rememberStuDOHaptic(uiState.isHapticFeedbackEnabled)
    val fStopText = PortraitApertureFormatter.formatFStop(uiState.portraitBlurIntensity)
    val blurPct = (uiState.portraitBlurIntensity * 100f).roundToInt().coerceIn(0, 100)

    val depthBadgeText = when (uiState.portraitDepthSource) {
        PortraitDepthSource.HARDWARE_DEPTH -> stringResource(R.string.portrait_depth_hardware)
        PortraitDepthSource.HYBRID -> stringResource(R.string.portrait_depth_hybrid)
        PortraitDepthSource.AI_SEGMENTATION -> stringResource(R.string.portrait_depth_ai)
    }

    val subjectText = when {
        uiState.portraitSubjectCount > 1 -> stringResource(R.string.portrait_subject_multiple, uiState.portraitSubjectCount)
        uiState.isPortraitSubjectDetected -> stringResource(R.string.portrait_subject_single)
        else -> stringResource(R.string.portrait_subject_searching)
    }
    val subjectAccent = if (uiState.isPortraitSubjectDetected) StuDOGreen else StuDOSilverMuted

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .liquidGlassSurface(
                shape = RoundedCornerShape(22.dp),
                cornerRadius = 22.dp,
                topColor = GlassObsidianTop.copy(alpha = 0.56f),
                bottomColor = GlassObsidianBottom.copy(alpha = 0.74f),
                borderWidth = 0.85.dp,
                coolHighlightAlpha = 0.42f,
                warmHighlightAlpha = 0.24f,
                specularSheenAlpha = 0.16f
            )
            .pointerInput(Unit) { detectTapGestures { /* absorb taps inside portrait panel */ } }
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("portrait_controls_bar")
    ) {
        // Header row: Aperture readout + Depth Source + Subject Lock Status
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
                    imageVector = Icons.Default.BlurOn,
                    contentDescription = stringResource(R.string.portrait_blur_label),
                    tint = GlassCoolBlueSpecular,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = fStopText,
                    color = StuDOWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("portrait_aperture_readout")
                )
                Text(
                    text = "$blurPct%",
                    color = GlassCoolBlueSpecular,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Depth source chip (HW Depth / Hybrid / AI Depth)
                Box(
                    modifier = Modifier
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(10.dp),
                            cornerRadius = 10.dp,
                            isActive = uiState.portraitDepthSource != PortraitDepthSource.AI_SEGMENTATION,
                            accentColor = StuDOAmber,
                            activeFillAlpha = 0.18f
                        )
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                        .testTag("portrait_depth_source_chip")
                ) {
                    Text(
                        text = depthBadgeText.uppercase(),
                        color = if (uiState.portraitDepthSource != PortraitDepthSource.AI_SEGMENTATION) {
                            StuDOAmber
                        } else {
                            StuDOSilver
                        },
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                // Multi-subject detection badge
                Box(
                    modifier = Modifier
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(10.dp),
                            cornerRadius = 10.dp,
                            isActive = uiState.isPortraitSubjectDetected,
                            accentColor = subjectAccent,
                            activeFillAlpha = 0.18f
                        )
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                        .testTag("portrait_subject_status_chip")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (uiState.isPortraitSubjectDetected) {
                                Icons.Default.CenterFocusStrong
                            } else {
                                Icons.Default.PersonSearch
                            },
                            contentDescription = subjectText,
                            tint = subjectAccent,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = subjectText,
                            color = if (uiState.isPortraitSubjectDetected) StuDOWhite else StuDOSilverMuted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        // Smooth blur intensity slider
        val sliderCd = stringResource(R.string.portrait_aperture_cd, fStopText)
        Slider(
            value = uiState.portraitBlurIntensity,
            onValueChange = { value ->
                onBlurIntensityChanged(value)
            },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = StuDOWhite,
                activeTrackColor = GlassCoolBlueSpecular,
                inactiveTrackColor = Color.White.copy(alpha = 0.18f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .semantics { contentDescription = sliderCd }
                .testTag("portrait_blur_slider")
        )

        Spacer(modifier = Modifier.height(2.dp))

        // Quick optical aperture presets
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PORTRAIT_PRESET_OPTIONS.forEach { preset ->
                val isSelected = abs(uiState.portraitBlurIntensity - preset.intensity) < 0.08f
                Box(
                    modifier = Modifier
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(12.dp),
                            cornerRadius = 12.dp,
                            isActive = isSelected,
                            accentColor = GlassCoolBlueSpecular,
                            activeFillAlpha = 0.22f
                        )
                        .clickable {
                            haptic.tick()
                            onBlurIntensityChanged(preset.intensity)
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .testTag("portrait_preset_${preset.label.replace("/", "_")}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = preset.label,
                        color = if (isSelected) GlassCoolBlueSpecular else StuDOSilverMuted,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}
