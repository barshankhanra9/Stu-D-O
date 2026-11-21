package com.example.editor

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.liquidGlassChipSurface
import com.example.ui.components.liquidGlassRecessedTrack
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDOFocusYellow
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Lightroom-class 17-parameter Tune panel with category grouping, individual tool reset,
 * center zero-point visual guides, and adaptive expanded inspection list.
 */
@Composable
fun TuneEditorPanel(
    adjustments: PhotoAdjustmentState,
    selectedTool: AdjustmentTool,
    isExpandedHeight: Boolean,
    onSelectTool: (AdjustmentTool) -> Unit,
    onPreviewAdjustments: (PhotoAdjustmentState) -> Unit,
    onCommitAdjustments: (PhotoAdjustmentState) -> Unit,
    onResetSingleTool: (AdjustmentTool) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf(AdjustmentCategory.ALL) }
    val filteredTools = remember(selectedCategory) {
        if (selectedCategory == AdjustmentCategory.ALL) {
            AdjustmentTool.entries
        } else {
            AdjustmentTool.entries.filter { it.category == selectedCategory }
        }
    }

    val currentTool = if (selectedTool in filteredTools) selectedTool else filteredTools.first()
    val currentVal = adjustments.getValue(currentTool)
    val isModified = abs(currentVal - currentTool.defaultVal) > 0.004f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Category Filter Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AdjustmentCategory.entries.forEach { cat ->
                val isActive = cat == selectedCategory
                val hasModifiedInCat = if (cat == AdjustmentCategory.ALL) {
                    !adjustments.isDefault()
                } else {
                    AdjustmentTool.entries.any { it.category == cat && abs(adjustments.getValue(it) - it.defaultVal) > 0.004f }
                }
                Row(
                    modifier = Modifier
                        .height(30.dp)
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(14.dp),
                            cornerRadius = 14.dp,
                            isActive = isActive,
                            accentColor = StuDOFocusYellow
                        )
                        .clickable {
                            selectedCategory = cat
                            val nextFirst = AdjustmentTool.entries.firstOrNull { cat == AdjustmentCategory.ALL || it.category == cat }
                            if (nextFirst != null && selectedTool.category != cat && cat != AdjustmentCategory.ALL) {
                                onSelectTool(nextFirst)
                            }
                        }
                        .padding(horizontal = 10.dp)
                        .testTag("tune_category_${cat.name.lowercase()}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = cat.title,
                        color = if (isActive) StuDOFocusYellow else StuDOSilver,
                        fontSize = 11.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
                    )
                    if (hasModifiedInCat) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(StuDOFocusYellow)
                        )
                    }
                }
            }
        }

        // 2. Focused Precision Slider Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassRecessedTrack(shape = RoundedCornerShape(18.dp), cornerRadius = 18.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
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
                        imageVector = currentTool.icon,
                        contentDescription = null,
                        tint = if (isModified) StuDOFocusYellow else StuDOSilver,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = currentTool.title,
                        color = StuDOWhite,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isModified) StuDOFocusYellow.copy(alpha = 0.16f)
                                else Color.White.copy(alpha = 0.07f)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = currentTool.formatValue(currentVal),
                            color = if (isModified) StuDOFocusYellow else StuDOSilverMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("tune_active_value")
                        )
                    }

                    if (isModified) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable { onResetSingleTool(currentTool) }
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                                .testTag("tune_reset_single_button"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = "Reset ${currentTool.title}",
                                tint = StuDOAmber,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Reset",
                                color = StuDOAmber,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // Zero-origin visual center marker for bipolar sliders
                if (currentTool.minVal < 0f && currentTool.maxVal > 0f) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(14.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.32f))
                    )
                }

                Slider(
                    value = currentVal,
                    onValueChange = { v ->
                        val snapped = if (abs(v - currentTool.defaultVal) < (currentTool.maxVal - currentTool.minVal) * 0.015f) {
                            currentTool.defaultVal
                        } else {
                            v
                        }
                        onPreviewAdjustments(adjustments.withValue(currentTool, snapped))
                    },
                    onValueChangeFinished = {
                        onCommitAdjustments(adjustments)
                    },
                    valueRange = currentTool.minVal..currentTool.maxVal,
                    colors = SliderDefaults.colors(
                        thumbColor = StuDOFocusYellow,
                        activeTrackColor = StuDOFocusYellow,
                        inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .testTag("tune_primary_slider")
                )
            }
        }

        // 3. Tool Parameter Selector Carousel
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            items(filteredTools, key = { it.name }) { tool ->
                val valFloat = adjustments.getValue(tool)
                val toolModified = abs(valFloat - tool.defaultVal) > 0.004f
                val isSelected = tool == currentTool

                Column(
                    modifier = Modifier
                        .width(68.dp)
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(14.dp),
                            cornerRadius = 14.dp,
                            isActive = isSelected,
                            accentColor = StuDOFocusYellow
                        )
                        .clickable { onSelectTool(tool) }
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                        .testTag("tune_tool_${tool.name.lowercase()}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        Icon(
                            imageVector = tool.icon,
                            contentDescription = tool.title,
                            tint = when {
                                isSelected -> StuDOFocusYellow
                                toolModified -> StuDOAmber
                                else -> StuDOSilverMuted
                            },
                            modifier = Modifier.size(18.dp)
                        )
                        if (toolModified) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(StuDOFocusYellow)
                            )
                        }
                    }
                    Text(
                        text = tool.title,
                        color = if (isSelected) StuDOFocusYellow else StuDOSilver,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // 4. In Expanded Mode: Quick Multi-Parameter List for fast multi-slider tuning
        if (isExpandedHeight) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "FINE-TUNE OVERVIEW (${selectedCategory.title.uppercase()})",
                    color = StuDOSilverMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                filteredTools.forEach { tool ->
                    val toolVal = adjustments.getValue(tool)
                    val dirty = abs(toolVal - tool.defaultVal) > 0.004f
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = if (tool == currentTool) 0.07f else 0.03f))
                            .clickable { onSelectTool(tool) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = tool.title,
                            color = if (dirty) StuDOFocusYellow else StuDOSilver,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(76.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Slider(
                            value = toolVal,
                            onValueChange = { v ->
                                onSelectTool(tool)
                                onPreviewAdjustments(adjustments.withValue(tool, v))
                            },
                            onValueChangeFinished = {
                                onCommitAdjustments(adjustments)
                            },
                            valueRange = tool.minVal..tool.maxVal,
                            colors = SliderDefaults.colors(
                                thumbColor = if (dirty) StuDOFocusYellow else StuDOSilver,
                                activeTrackColor = StuDOFocusYellow,
                                inactiveTrackColor = Color.White.copy(alpha = 0.14f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp)
                        )
                        Text(
                            text = tool.formatValue(toolVal),
                            color = if (dirty) StuDOFocusYellow else StuDOSilverMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(44.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Curated Photographic Filters panel spanning 15 categories with real photo thumbnail previews
 * and continuous preset intensity blending (0%..100%).
 */
@Composable
fun FiltersEditorPanel(
    selectedFilter: PhotoFilterType,
    filterIntensity: Float,
    filterThumbnails: Map<PhotoFilterType, Bitmap>,
    onSelectFilter: (PhotoFilterType) -> Unit,
    onIntensityChanged: (Float) -> Unit,
    onIntensityCommitted: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf(FilterCategory.ALL) }

    val displayedPresets = remember(selectedCategory) {
        if (selectedCategory == FilterCategory.ALL) {
            PhotoFilterType.entries
        } else {
            listOf(PhotoFilterType.ORIGINAL) + PhotoFilterType.entries.filter { it.category == selectedCategory }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. 15-Category Chip Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterCategory.entries.forEach { cat ->
                val isActive = cat == selectedCategory
                Row(
                    modifier = Modifier
                        .height(30.dp)
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(14.dp),
                            cornerRadius = 14.dp,
                            isActive = isActive,
                            accentColor = StuDOFocusYellow
                        )
                        .clickable { selectedCategory = cat }
                        .padding(horizontal = 10.dp)
                        .testTag("filter_category_${cat.name.lowercase()}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = cat.title,
                        color = if (isActive) StuDOFocusYellow else StuDOSilver,
                        fontSize = 11.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        // 2. Intensity Slider when a color grading preset is active
        AnimatedVisibility(visible = selectedFilter != PhotoFilterType.ORIGINAL) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlassRecessedTrack(shape = RoundedCornerShape(14.dp), cornerRadius = 14.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.width(82.dp)) {
                    Text(
                        text = selectedFilter.displayName,
                        color = StuDOWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Intensity ${(filterIntensity * 100f).roundToInt()}%",
                        color = StuDOFocusYellow,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Slider(
                    value = filterIntensity,
                    onValueChange = onIntensityChanged,
                    onValueChangeFinished = onIntensityCommitted,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = StuDOFocusYellow,
                        activeTrackColor = StuDOFocusYellow,
                        inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .testTag("filter_intensity_slider")
                )
            }
        }

        // 3. Real Filtered Photo Thumbnail Strip
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            items(displayedPresets, key = { it.name }) { preset ->
                val isSelected = preset == selectedFilter
                val thumb = filterThumbnails[preset]

                Column(
                    modifier = Modifier
                        .width(78.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isSelected) StuDOFocusYellow.copy(alpha = 0.16f)
                            else Color.White.copy(alpha = 0.04f)
                        )
                        .border(
                            width = if (isSelected) 1.5.dp else 0.8.dp,
                            color = if (isSelected) StuDOFocusYellow else Color.White.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onSelectFilter(preset) }
                        .padding(5.dp)
                        .testTag("filter_preset_${preset.name.lowercase()}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(66.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(preset.accentColor.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (thumb != null && !thumb.isRecycled) {
                            Image(
                                bitmap = thumb.asImageBitmap(),
                                contentDescription = preset.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(preset.accentColor)
                            )
                        }
                    }

                    Text(
                        text = preset.displayName,
                        color = if (isSelected) StuDOFocusYellow else StuDOSilver,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (selectedFilter != PhotoFilterType.ORIGINAL) {
            Text(
                text = "${selectedFilter.category.title} · ${selectedFilter.subtitle}",
                color = StuDOSilverMuted,
                fontSize = 10.5.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
