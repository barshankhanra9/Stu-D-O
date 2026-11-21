package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.QueryBuilder
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CameraUiState
import com.example.model.CapturePriority
import com.example.model.CaptureTimer
import com.example.model.GridOption
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDOFocusYellow
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOWhite

@Composable
fun CameraQuickSettingsDrawer(
    uiState: CameraUiState,
    onSelectTimer: (CaptureTimer) -> Unit,
    onSelectGrid: (GridOption) -> Unit,
    onToggleLevel: () -> Unit,
    onToggleShutterSound: () -> Unit,
    onSelectCapturePriority: (CapturePriority) -> Unit = {},
    onSetCustomTimerSeconds: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var showCustomTimerDialog by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlassSurface(
                shape = RoundedCornerShape(22.dp),
                cornerRadius = 22.dp,
                topColor = com.example.ui.theme.GlassObsidianTop.copy(alpha = 0.56f),
                bottomColor = com.example.ui.theme.GlassObsidianBottom.copy(alpha = 0.72f),
                borderWidth = 0.85.dp,
                coolHighlightAlpha = 0.36f,
                warmHighlightAlpha = 0.24f,
                specularSheenAlpha = 0.14f
            )
            .pointerInput(Unit) { detectTapGestures { /* absorb inside drawer taps */ } }
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("camera_quick_settings_drawer")
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Row 1: Timer Controls
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
                        imageVector = Icons.Default.QueryBuilder,
                        contentDescription = "Timer",
                        tint = if (uiState.selectedTimer != CaptureTimer.OFF) StuDOAmber else StuDOSilver,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "TIMER",
                        color = StuDOSilver,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CaptureTimer.entries.forEach { timer ->
                        val isSelected = timer == uiState.selectedTimer
                        val chipLabel = if (timer == CaptureTimer.CUSTOM) {
                            if (isSelected) CaptureTimer.formatDuration(uiState.customTimerSeconds) else timer.displayName
                        } else {
                            timer.displayName
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
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    if (timer == CaptureTimer.CUSTOM && isSelected) {
                                        showCustomTimerDialog = true
                                    } else {
                                        onSelectTimer(timer)
                                    }
                                }
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                                .testTag("timer_option_${timer.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = chipLabel,
                                    color = if (isSelected) StuDOAmber else StuDOWhite.copy(alpha = 0.90f),
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1
                                )
                                if (timer == CaptureTimer.CUSTOM) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edit custom timer",
                                        tint = if (isSelected) StuDOAmber else StuDOSilver,
                                        modifier = Modifier.size(10.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = uiState.selectedTimer == CaptureTimer.CUSTOM,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                CustomTimerInlineAdjuster(
                    customSeconds = uiState.customTimerSeconds,
                    onCustomSecondsChanged = onSetCustomTimerSeconds,
                    onOpenExactInputDialog = { showCustomTimerDialog = true },
                    tagPrefix = "drawer_custom_timer"
                )
            }

            // Row 2: Grid Framing Controls
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
                        imageVector = Icons.Default.GridOn,
                        contentDescription = "Grid framing",
                        tint = if (uiState.selectedGrid != GridOption.OFF) StuDOFocusYellow else StuDOSilver,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "GRID",
                        color = StuDOSilver,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GridOption.entries.forEach { grid ->
                        val isSelected = grid == uiState.selectedGrid
                        Box(
                            modifier = Modifier
                                .liquidGlassChipSurface(
                                    shape = RoundedCornerShape(12.dp),
                                    cornerRadius = 12.dp,
                                    isActive = isSelected,
                                    accentColor = StuDOFocusYellow
                                )
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelectGrid(grid)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("grid_option_${grid.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = grid.shortLabel,
                                color = if (isSelected) StuDOFocusYellow else StuDOWhite.copy(alpha = 0.90f),
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Row 3: Capture Strategy & Engine Priority
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
                        contentDescription = "Capture Priority",
                        tint = if (uiState.capturePriority == CapturePriority.QUALITY) StuDOAmber else StuDOFocusYellow,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "PRIORITY",
                        color = StuDOSilver,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CapturePriority.values().forEach { priority ->
                        val isSelected = priority == uiState.capturePriority
                        Box(
                            modifier = Modifier
                                .liquidGlassChipSurface(
                                    shape = RoundedCornerShape(12.dp),
                                    cornerRadius = 12.dp,
                                    isActive = isSelected,
                                    accentColor = StuDOAmber
                                )
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelectCapturePriority(priority)
                                }
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                                .testTag("priority_option_${priority.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = priority.shortLabel,
                                color = if (isSelected) StuDOAmber else StuDOWhite.copy(alpha = 0.90f),
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Row 4: Quick Toggles (Level Indicator & Shutter Sound)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Level Indicator Toggle
                QuickToggleChip(
                    icon = Icons.Default.ScreenRotation,
                    label = "LEVEL",
                    isActive = uiState.isLevelIndicatorEnabled,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleLevel()
                    },
                    testTag = "toggle_level_button"
                )

                // Shutter Sound Toggle
                QuickToggleChip(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    label = "SOUND",
                    isActive = uiState.isShutterSoundEnabled,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleShutterSound()
                    },
                    testTag = "toggle_sound_button"
                )
            }
        }
    }

    if (showCustomTimerDialog) {
        CustomTimerPickerDialog(
            initialSeconds = uiState.customTimerSeconds,
            onConfirmSeconds = { seconds ->
                onSetCustomTimerSeconds(seconds)
                showCustomTimerDialog = false
            },
            onDismiss = { showCustomTimerDialog = false }
        )
    }
}

@Composable
private fun QuickToggleChip(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val activeColor = Color(0xFF00E676)
    Box(
        modifier = Modifier
            .liquidGlassChipSurface(
                shape = RoundedCornerShape(12.dp),
                cornerRadius = 12.dp,
                isActive = isActive,
                accentColor = activeColor,
                activeFillAlpha = 0.18f
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) activeColor else StuDOSilver,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                color = if (isActive) Color.White else StuDOSilver,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
        }
    }
}
