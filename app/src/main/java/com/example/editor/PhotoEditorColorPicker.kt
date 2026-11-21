package com.example.editor

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.liquidGlassChipSurface
import com.example.ui.components.liquidGlassRecessedTrack
import com.example.ui.theme.StuDOFocusYellow
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite
import kotlin.math.roundToInt

val EDITOR_PRESET_COLORS: List<Color> = listOf(
    Color.White,
    Color(0xFFE4E4E7),
    Color(0xFFFACC15),
    Color(0xFFF59E0B),
    Color(0xFFFF8A65),
    Color(0xFFEF4444),
    Color(0xFFF48FB1),
    Color(0xFFCE93D8),
    Color(0xFF88C8FF),
    Color(0xFF3B82F6),
    Color(0xFF10B981),
    Color(0xFF090B10)
)

/**
 * Reusable Liquid Glass color picker with preset swatches, recently used custom colors,
 * expandable Hue/Saturation/Lightness sliders, and validated HEX input.
 */
@Composable
fun EditorColorPickerRow(
    selectedColor: Color,
    recentColors: List<Color>,
    onColorChanged: (Color) -> Unit,
    onColorCommitted: (Color) -> Unit = onColorChanged,
    label: String? = null,
    testTagPrefix: String = "color_picker"
) {
    var showCustomSliders by remember { mutableStateOf(false) }

    val hsl = remember { FloatArray(3) }
    var hue by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(1f) }
    var light by remember { mutableFloatStateOf(0.5f) }
    var hexText by remember { mutableStateOf("#FFFFFF") }

    fun syncFromColor(color: Color) {
        androidx.core.graphics.ColorUtils.colorToHSL(color.toArgb(), hsl)
        hue = hsl[0]
        sat = hsl[1]
        light = hsl[2]
        hexText = String.format("#%06X", 0xFFFFFF and color.toArgb())
    }

    LaunchedEffect(selectedColor) {
        syncFromColor(selectedColor)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (label != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    color = StuDOSilverMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = hexText,
                    color = StuDOSilver,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Custom color studio trigger pill
            Row(
                modifier = Modifier
                    .height(32.dp)
                    .liquidGlassChipSurface(
                        shape = RoundedCornerShape(16.dp),
                        cornerRadius = 16.dp,
                        isActive = showCustomSliders,
                        accentColor = StuDOFocusYellow
                    )
                    .clickable { showCustomSliders = !showCustomSliders }
                    .padding(horizontal = 10.dp)
                    .testTag("${testTagPrefix}_custom_toggle"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Colorize,
                    contentDescription = "Custom Color",
                    tint = if (showCustomSliders) StuDOFocusYellow else StuDOSilver,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Custom",
                    color = if (showCustomSliders) StuDOFocusYellow else StuDOSilver,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Recent custom colors first
            recentColors.forEachIndexed { idx, recent ->
                ColorSwatchCircle(
                    color = recent,
                    isSelected = recent.toArgb() == selectedColor.toArgb(),
                    onClick = {
                        onColorChanged(recent)
                        onColorCommitted(recent)
                    },
                    testTag = "${testTagPrefix}_recent_$idx"
                )
            }

            // Standard palette swatches
            EDITOR_PRESET_COLORS.forEachIndexed { idx, preset ->
                ColorSwatchCircle(
                    color = preset,
                    isSelected = preset.toArgb() == selectedColor.toArgb(),
                    onClick = {
                        onColorChanged(preset)
                        onColorCommitted(preset)
                    },
                    testTag = "${testTagPrefix}_swatch_$idx"
                )
            }
        }

        AnimatedVisibility(visible = showCustomSliders) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlassRecessedTrack(shape = RoundedCornerShape(16.dp), cornerRadius = 16.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(selectedColor)
                                .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                        )
                        Text("HEX", color = StuDOSilverMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    BasicTextField(
                        value = hexText,
                        onValueChange = { raw ->
                            val clean = raw.uppercase().take(7)
                            hexText = clean
                            if (clean.matches(Regex("^#[0-9A-F]{6}$"))) {
                                try {
                                    val parsed = Color(android.graphics.Color.parseColor(clean))
                                    onColorChanged(parsed)
                                    onColorCommitted(parsed)
                                } catch (_: Throwable) {}
                            }
                        },
                        singleLine = true,
                        cursorBrush = SolidColor(StuDOFocusYellow),
                        textStyle = TextStyle(
                            color = StuDOWhite,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier
                            .width(96.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .border(0.8.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                            .testTag("${testTagPrefix}_hex_input")
                    )
                }

                // Hue spectrum bar
                HslSliderRow(
                    label = "Hue",
                    value = hue,
                    range = 0f..360f,
                    valueLabel = "${hue.roundToInt()}°",
                    onValueChange = { h ->
                        hue = h
                        val c = Color(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hue, sat, light)))
                        onColorChanged(c)
                    },
                    onValueChangeFinished = {
                        val c = Color(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hue, sat, light)))
                        onColorCommitted(c)
                    },
                    trackBrush = Brush.horizontalGradient(
                        listOf(
                            Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                        )
                    )
                )

                // Saturation slider
                HslSliderRow(
                    label = "Sat",
                    value = sat,
                    range = 0f..1f,
                    valueLabel = "${(sat * 100f).roundToInt()}%",
                    onValueChange = { s ->
                        sat = s
                        val c = Color(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hue, sat, light)))
                        onColorChanged(c)
                    },
                    onValueChangeFinished = {
                        val c = Color(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hue, sat, light)))
                        onColorCommitted(c)
                    }
                )

                // Lightness slider
                HslSliderRow(
                    label = "Light",
                    value = light,
                    range = 0.05f..0.98f,
                    valueLabel = "${(light * 100f).roundToInt()}%",
                    onValueChange = { l ->
                        light = l
                        val c = Color(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hue, sat, light)))
                        onColorChanged(c)
                    },
                    onValueChangeFinished = {
                        val c = Color(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hue, sat, light)))
                        onColorCommitted(c)
                    }
                )
            }
        }
    }
}

@Composable
private fun ColorSwatchCircle(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (isSelected) 2.2.dp else 0.9.dp,
                color = if (isSelected) StuDOFocusYellow else Color.White.copy(alpha = 0.28f),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
            .testTag(testTag)
    )
}

@Composable
private fun HslSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    trackBrush: Brush? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            color = StuDOSilverMuted,
            fontSize = 10.sp,
            modifier = Modifier.width(34.dp)
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            if (trackBrush != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(trackBrush)
                )
            }
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = range,
                colors = SliderDefaults.colors(
                    thumbColor = StuDOFocusYellow,
                    activeTrackColor = if (trackBrush != null) Color.Transparent else StuDOFocusYellow,
                    inactiveTrackColor = if (trackBrush != null) Color.Transparent else Color.White.copy(alpha = 0.16f)
                ),
                modifier = Modifier.height(26.dp)
            )
        }
        Text(
            text = valueLabel,
            color = StuDOSilver,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(34.dp)
        )
    }
}
