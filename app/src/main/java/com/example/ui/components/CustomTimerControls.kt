package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QueryBuilder
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.CaptureTimer
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDOBlack
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOWhite
import kotlin.math.roundToInt

private val CUSTOM_TIMER_PRESETS = listOf(2, 15, 30, 60, 120)

@Composable
fun CustomTimerInlineAdjuster(
    customSeconds: Int,
    onCustomSecondsChanged: (Int) -> Unit,
    onOpenExactInputDialog: () -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String = "custom_timer"
) {
    val haptic = LocalHapticFeedback.current
    val clampedSeconds = customSeconds.coerceIn(
        CaptureTimer.MIN_CUSTOM_SECONDS,
        CaptureTimer.MAX_CUSTOM_SECONDS
    )
    var sliderValue by remember(clampedSeconds) {
        mutableFloatStateOf(clampedSeconds.coerceIn(1, 60).toFloat())
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(StuDOAmber.copy(alpha = 0.08f))
            .border(0.75.dp, StuDOAmber.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("${tagPrefix}_adjuster"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Step down (-1s)
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 34.dp, minHeight = 32.dp)
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(10.dp),
                            cornerRadius = 10.dp,
                            isActive = false,
                            isEnabled = clampedSeconds > CaptureTimer.MIN_CUSTOM_SECONDS,
                            accentColor = StuDOAmber
                        )
                        .clickable(enabled = clampedSeconds > CaptureTimer.MIN_CUSTOM_SECONDS) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onCustomSecondsChanged((clampedSeconds - 1).coerceAtLeast(CaptureTimer.MIN_CUSTOM_SECONDS))
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                        .testTag("${tagPrefix}_decrement_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "Decrease custom timer by 1 second",
                        tint = if (clampedSeconds > CaptureTimer.MIN_CUSTOM_SECONDS) StuDOWhite else StuDOSilver.copy(alpha = 0.4f),
                        modifier = Modifier.size(15.dp)
                    )
                }

                // Tappable exact duration display (opens numeric keypad dialog)
                Row(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 32.dp)
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(10.dp),
                            cornerRadius = 10.dp,
                            isActive = true,
                            accentColor = StuDOAmber
                        )
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onOpenExactInputDialog()
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .testTag("${tagPrefix}_edit_button"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = CaptureTimer.formatDuration(clampedSeconds),
                        color = StuDOAmber,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Set exact custom timer seconds",
                        tint = StuDOAmber,
                        modifier = Modifier.size(12.dp)
                    )
                }

                // Step up (+1s)
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 34.dp, minHeight = 32.dp)
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(10.dp),
                            cornerRadius = 10.dp,
                            isActive = false,
                            isEnabled = clampedSeconds < CaptureTimer.MAX_CUSTOM_SECONDS,
                            accentColor = StuDOAmber
                        )
                        .clickable(enabled = clampedSeconds < CaptureTimer.MAX_CUSTOM_SECONDS) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onCustomSecondsChanged((clampedSeconds + 1).coerceAtMost(CaptureTimer.MAX_CUSTOM_SECONDS))
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                        .testTag("${tagPrefix}_increment_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Increase custom timer by 1 second",
                        tint = if (clampedSeconds < CaptureTimer.MAX_CUSTOM_SECONDS) StuDOWhite else StuDOSilver.copy(alpha = 0.4f),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .padding(start = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CUSTOM_TIMER_PRESETS.forEach { presetSec ->
                    val isPresetActive = clampedSeconds == presetSec
                    Box(
                        modifier = Modifier
                            .liquidGlassChipSurface(
                                shape = RoundedCornerShape(10.dp),
                                cornerRadius = 10.dp,
                                isActive = isPresetActive,
                                accentColor = StuDOAmber
                            )
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onCustomSecondsChanged(presetSec)
                            }
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                            .testTag("${tagPrefix}_preset_${presetSec}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = CaptureTimer.formatDuration(presetSec),
                            color = if (isPresetActive) StuDOAmber else StuDOWhite.copy(alpha = 0.85f),
                            fontSize = 10.sp,
                            fontWeight = if (isPresetActive) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        Slider(
            value = sliderValue,
            onValueChange = { raw ->
                sliderValue = raw
                val rounded = raw.roundToInt().coerceIn(CaptureTimer.MIN_CUSTOM_SECONDS, 60)
                if (rounded != clampedSeconds) {
                    onCustomSecondsChanged(rounded)
                }
            },
            valueRange = 1f..60f,
            colors = SliderDefaults.colors(
                thumbColor = StuDOAmber,
                activeTrackColor = StuDOAmber,
                inactiveTrackColor = Color.White.copy(alpha = 0.22f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .testTag("${tagPrefix}_slider")
        )
    }
}

@Composable
fun CustomTimerPickerDialog(
    initialSeconds: Int,
    onConfirmSeconds: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val clampedInitial = initialSeconds.coerceIn(
        CaptureTimer.MIN_CUSTOM_SECONDS,
        CaptureTimer.MAX_CUSTOM_SECONDS
    )
    var inputText by remember(clampedInitial) { mutableStateOf(clampedInitial.toString()) }
    val parsedSeconds = inputText.toIntOrNull()
    val isValid = parsedSeconds != null &&
        parsedSeconds in CaptureTimer.MIN_CUSTOM_SECONDS..CaptureTimer.MAX_CUSTOM_SECONDS

    fun updateSeconds(newVal: Int) {
        val clamped = newVal.coerceIn(
            CaptureTimer.MIN_CUSTOM_SECONDS,
            CaptureTimer.MAX_CUSTOM_SECONDS
        )
        inputText = clamped.toString()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .widthIn(max = 380.dp)
                .liquidGlassSurface(
                    shape = RoundedCornerShape(24.dp),
                    cornerRadius = 24.dp,
                    topColor = com.example.ui.theme.GlassObsidianTop.copy(alpha = 0.92f),
                    bottomColor = com.example.ui.theme.GlassObsidianBottom.copy(alpha = 0.96f),
                    borderWidth = 1.dp,
                    coolHighlightAlpha = 0.42f,
                    warmHighlightAlpha = 0.30f,
                    specularSheenAlpha = 0.16f
                )
                .padding(20.dp)
                .testTag("custom_timer_picker_dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.QueryBuilder,
                    contentDescription = "Custom timer",
                    tint = StuDOAmber,
                    modifier = Modifier.size(22.dp)
                )
                Column {
                    Text(
                        text = "Custom Photo Timer",
                        color = StuDOWhite,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Choose any delay from ${CaptureTimer.MIN_CUSTOM_SECONDS}s to ${CaptureTimer.MAX_CUSTOM_SECONDS}s",
                        color = StuDOSilver,
                        fontSize = 12.sp
                    )
                }
            }

            OutlinedTextField(
                value = inputText,
                onValueChange = { raw ->
                    val digitsOnly = raw.filter { it.isDigit() }.take(3)
                    inputText = digitsOnly
                },
                label = { Text("Delay in seconds", color = StuDOSilver) },
                suffix = { Text("sec", color = StuDOAmber, fontWeight = FontWeight.Bold) },
                singleLine = true,
                isError = !isValid,
                supportingText = if (!isValid) {
                    {
                        Text(
                            text = "Enter a whole number between ${CaptureTimer.MIN_CUSTOM_SECONDS} and ${CaptureTimer.MAX_CUSTOM_SECONDS}",
                            color = com.example.ui.theme.StuDORed,
                            fontSize = 11.sp
                        )
                    }
                } else {
                    {
                        Text(
                            text = "Countdown: ${CaptureTimer.formatDuration(parsedSeconds!!)}",
                            color = StuDOSilver,
                            fontSize = 11.sp
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (isValid) {
                            onConfirmSeconds(parsedSeconds!!)
                        }
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = StuDOAmber,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                    focusedTextColor = StuDOWhite,
                    unfocusedTextColor = StuDOWhite,
                    cursorColor = StuDOAmber
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("custom_timer_seconds_input")
            )

            // Fine step buttons (-5s, -1s, +1s, +5s)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val base = parsedSeconds ?: clampedInitial
                listOf(-5 to "-5s", -1 to "-1s", 1 to "+1s", 5 to "+5s").forEach { (delta, label) ->
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 40.dp)
                            .liquidGlassChipSurface(
                                shape = RoundedCornerShape(12.dp),
                                cornerRadius = 12.dp,
                                isActive = false,
                                accentColor = StuDOAmber
                            )
                            .clickable { updateSeconds(base + delta) }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag("custom_timer_step_${label}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = StuDOWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Quick presets
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(2, 15, 20, 30, 45, 60, 120).forEach { sec ->
                    val selected = parsedSeconds == sec
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minHeight = 36.dp)
                            .liquidGlassChipSurface(
                                shape = RoundedCornerShape(12.dp),
                                cornerRadius = 12.dp,
                                isActive = selected,
                                accentColor = StuDOAmber
                            )
                            .clickable { updateSeconds(sec) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("custom_timer_dialog_preset_$sec"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = CaptureTimer.formatDuration(sec),
                            color = if (selected) StuDOAmber else StuDOWhite.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("custom_timer_dialog_cancel")
                ) {
                    Text("Cancel", color = StuDOSilver)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (isValid) {
                            onConfirmSeconds(parsedSeconds!!)
                        }
                    },
                    enabled = isValid,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StuDOAmber,
                        contentColor = StuDOBlack
                    ),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("custom_timer_dialog_confirm")
                ) {
                    Text("Set Timer", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
