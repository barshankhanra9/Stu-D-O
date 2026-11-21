package com.example.editor

import android.graphics.RectF
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.liquidGlassChipSurface
import com.example.ui.components.liquidGlassRecessedTrack
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDOFocusYellow
import com.example.ui.theme.StuDORed
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite
import kotlin.math.roundToInt

/**
 * Crop & Geometry panel supporting 90° CCW/CW rotation, Horizontal/Vertical flip,
 * mathematically exact pixel-space aspect ratio presets, reset, and Apply Crop action.
 */
@Composable
fun CropEditorPanel(
    cropState: CropState,
    orientedImageWidth: Float,
    orientedImageHeight: Float,
    onRotateCcw: () -> Unit,
    onRotateCw: () -> Unit,
    onFlipHorizontal: () -> Unit,
    onFlipVertical: () -> Unit,
    onSelectAspectRatio: (CropAspectRatio, RectF) -> Unit,
    onResetCrop: () -> Unit,
    onApplyCrop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Primary Rotate, Flip, Reset & Apply actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CropActionChip(
                label = "-90°",
                icon = Icons.Default.Rotate90DegreesCcw,
                isActive = false,
                onClick = onRotateCcw,
                testTag = "crop_rotate_ccw"
            )
            CropActionChip(
                label = "+90°",
                icon = Icons.Default.Rotate90DegreesCw,
                isActive = false,
                onClick = onRotateCw,
                testTag = "crop_rotate_cw"
            )
            CropActionChip(
                label = "Flip H",
                icon = Icons.Default.Flip,
                isActive = cropState.isFlippedHorizontal,
                onClick = onFlipHorizontal,
                testTag = "crop_flip_h"
            )
            CropActionChip(
                label = "Flip V",
                icon = Icons.Default.SwapVert,
                isActive = cropState.isFlippedVertical,
                onClick = onFlipVertical,
                testTag = "crop_flip_v"
            )
            CropActionChip(
                label = "Reset",
                icon = Icons.Default.RestartAlt,
                isActive = false,
                enabled = !cropState.isDefault(),
                onClick = onResetCrop,
                testTag = "crop_reset_button"
            )
        }

        // 2. Aspect Ratio Presets
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CropAspectRatio.entries.forEach { aspect ->
                val isSelected = aspect == cropState.aspectRatio
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(14.dp),
                            cornerRadius = 14.dp,
                            isActive = isSelected,
                            accentColor = StuDOFocusYellow
                        )
                        .clickable {
                            val newRect = computeAspectRatioCropRect(
                                aspectRatio = aspect,
                                imageWidth = orientedImageWidth,
                                imageHeight = orientedImageHeight
                            )
                            onSelectAspectRatio(aspect, newRect)
                        }
                        .padding(horizontal = 13.dp)
                        .testTag("crop_aspect_${aspect.name.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = aspect.displayName,
                        color = if (isSelected) StuDOFocusYellow else StuDOSilver,
                        fontSize = 11.5.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        // 3. Apply Crop confirmation bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Drag frame handles to compose cleanly",
                color = StuDOSilverMuted,
                fontSize = 11.sp
            )
            Button(
                onClick = onApplyCrop,
                colors = ButtonDefaults.buttonColors(containerColor = StuDOFocusYellow),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .height(36.dp)
                    .testTag("crop_apply_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Apply Crop",
                    color = Color.Black,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun CropActionChip(
    label: String,
    icon: ImageVector,
    isActive: Boolean,
    onClick: () -> Unit,
    testTag: String,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .height(36.dp)
            .liquidGlassChipSurface(
                shape = RoundedCornerShape(14.dp),
                cornerRadius = 14.dp,
                isActive = isActive,
                isEnabled = enabled,
                accentColor = StuDOFocusYellow
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = when {
                !enabled -> StuDOSilverMuted.copy(alpha = 0.35f)
                isActive -> StuDOFocusYellow
                else -> StuDOSilver
            },
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            color = when {
                !enabled -> StuDOSilverMuted.copy(alpha = 0.35f)
                isActive -> StuDOFocusYellow
                else -> StuDOSilver
            },
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/**
 * Vector Drawing tool controls: brush styles, stroke width, opacity, color picker, eraser & clear.
 */
@Composable
fun DrawEditorPanel(
    brushType: DrawBrushType,
    drawColor: Color,
    strokeWidth: Float,
    opacity: Float,
    isEraser: Boolean,
    hasPaths: Boolean,
    recentColors: List<Color>,
    onBrushTypeChanged: (DrawBrushType) -> Unit,
    onColorChanged: (Color) -> Unit,
    onColorCommitted: (Color) -> Unit,
    onStrokeWidthChanged: (Float) -> Unit,
    onOpacityChanged: (Float) -> Unit,
    onToggleEraser: (Boolean) -> Unit,
    onClearAllPaths: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Brush styles + Eraser + Clear row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DrawBrushType.entries.forEach { brush ->
                val active = !isEraser && brushType == brush
                Row(
                    modifier = Modifier
                        .height(32.dp)
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(14.dp),
                            cornerRadius = 14.dp,
                            isActive = active,
                            accentColor = StuDOFocusYellow
                        )
                        .clickable {
                            onToggleEraser(false)
                            onBrushTypeChanged(brush)
                        }
                        .padding(horizontal = 10.dp)
                        .testTag("draw_brush_${brush.name.lowercase()}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Brush,
                        contentDescription = null,
                        tint = if (active) StuDOFocusYellow else StuDOSilverMuted,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = brush.title,
                        color = if (active) StuDOFocusYellow else StuDOSilver,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }

            Row(
                modifier = Modifier
                    .height(32.dp)
                    .liquidGlassChipSurface(
                        shape = RoundedCornerShape(14.dp),
                        cornerRadius = 14.dp,
                        isActive = isEraser,
                        accentColor = StuDOAmber
                    )
                    .clickable { onToggleEraser(!isEraser) }
                    .padding(horizontal = 10.dp)
                    .testTag("draw_eraser_toggle"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoFixHigh,
                    contentDescription = "Eraser",
                    tint = if (isEraser) StuDOAmber else StuDOSilver,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "Eraser",
                    color = if (isEraser) StuDOAmber else StuDOSilver,
                    fontSize = 11.sp,
                    fontWeight = if (isEraser) FontWeight.Bold else FontWeight.Medium
                )
            }

            if (hasPaths) {
                Row(
                    modifier = Modifier
                        .height(32.dp)
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(14.dp),
                            cornerRadius = 14.dp,
                            isActive = false
                        )
                        .clickable(onClick = onClearAllPaths)
                        .padding(horizontal = 10.dp)
                        .testTag("draw_clear_button"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear Drawings",
                        tint = StuDORed,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "Clear",
                        color = StuDORed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // 2. Color picker (when not erasing)
        if (!isEraser) {
            EditorColorPickerRow(
                selectedColor = drawColor,
                recentColors = recentColors,
                onColorChanged = onColorChanged,
                onColorCommitted = onColorCommitted,
                testTagPrefix = "draw_color"
            )
        }

        // 3. Size & Opacity sliders
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassRecessedTrack(shape = RoundedCornerShape(16.dp), cornerRadius = 16.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Size", color = StuDOSilverMuted, fontSize = 11.sp, modifier = Modifier.width(50.dp))
                Slider(
                    value = strokeWidth,
                    onValueChange = onStrokeWidthChanged,
                    valueRange = 2f..42f,
                    colors = SliderDefaults.colors(
                        thumbColor = StuDOFocusYellow,
                        activeTrackColor = StuDOFocusYellow,
                        inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .testTag("draw_size_slider")
                )
                Text(
                    text = "${strokeWidth.roundToInt()}px",
                    color = StuDOWhite,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(38.dp)
                )
            }

            if (!isEraser) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Opacity", color = StuDOSilverMuted, fontSize = 11.sp, modifier = Modifier.width(50.dp))
                    Slider(
                        value = opacity,
                        onValueChange = onOpacityChanged,
                        valueRange = 0.15f..1.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = StuDOFocusYellow,
                            activeTrackColor = StuDOFocusYellow,
                            inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                            .testTag("draw_opacity_slider")
                    )
                    Text(
                        text = "${(opacity * 100f).roundToInt()}%",
                        color = StuDOWhite,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(38.dp)
                    )
                }
            }
        }
    }
}
