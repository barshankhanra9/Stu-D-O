package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CameraFilter
import com.example.model.CameraFilterCategory
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite

@Composable
fun CameraFilterSelector(
    selectedFilter: CameraFilter,
    onSelectFilter: (CameraFilter) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var selectedCategory by remember { mutableStateOf(CameraFilterCategory.ALL) }

    val visibleFilters = remember(selectedCategory) {
        if (selectedCategory == CameraFilterCategory.ALL) {
            CameraFilter.entries
        } else {
            listOf(CameraFilter.NATURAL) + CameraFilter.entries.filter { it.category == selectedCategory }
        }
    }

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
            .pointerInput(Unit) { detectTapGestures { /* absorb inside panel taps */ } }
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("camera_filter_selector")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header Row: Title, Active Preset & Close / Reset
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
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "Filters",
                        tint = StuDOAmber,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "PRO FILM LUTS",
                        color = StuDOWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp
                    )
                    Text(
                        text = "• ${selectedFilter.displayName.uppercase()}",
                        color = if (selectedFilter == CameraFilter.NATURAL) StuDOSilver else selectedFilter.tintColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (selectedFilter != CameraFilter.NATURAL) {
                        Box(
                            modifier = Modifier
                                .liquidGlassChipSurface(
                                    shape = RoundedCornerShape(10.dp),
                                    cornerRadius = 10.dp,
                                    accentColor = StuDOSilver
                                )
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelectFilter(CameraFilter.NATURAL)
                                }
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                                .testTag("filter_reset_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "RESET",
                                color = StuDOSilver,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .liquidGlassChipSurface(
                                shape = CircleShape,
                                cornerRadius = 11.dp
                            )
                            .clickable(onClick = onClose),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Filters",
                            tint = StuDOSilver,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }

            // Category Pills Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CameraFilterCategory.entries.forEach { category ->
                    val isSelected = category == selectedCategory
                    Box(
                        modifier = Modifier
                            .liquidGlassChipSurface(
                                shape = RoundedCornerShape(10.dp),
                                cornerRadius = 10.dp,
                                isActive = isSelected,
                                accentColor = StuDOAmber,
                                activeFillAlpha = 0.18f
                            )
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedCategory = category
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .testTag("filter_category_${category.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = category.title.uppercase(),
                            color = if (isSelected) StuDOAmber else StuDOSilverMuted,
                            fontSize = 9.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            letterSpacing = 0.7.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            // Horizontal Filter Chips with Dual-Tone Color Swatch Orbs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                visibleFilters.forEach { filter ->
                    val isSelected = filter == selectedFilter
                    val swatchBrush = remember(filter) {
                        Brush.linearGradient(listOf(filter.tintColor, filter.secondaryTintColor))
                    }

                    Box(
                        modifier = Modifier
                            .liquidGlassChipSurface(
                                shape = RoundedCornerShape(14.dp),
                                cornerRadius = 14.dp,
                                isActive = isSelected,
                                accentColor = filter.tintColor,
                                activeFillAlpha = 0.22f
                            )
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelectFilter(filter)
                            }
                            .padding(horizontal = 11.dp, vertical = 6.dp)
                            .testTag("filter_option_${filter.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(13.dp)
                                    .clip(CircleShape)
                                    .background(swatchBrush)
                                    .border(
                                        width = if (isSelected) 1.dp else 0.6.dp,
                                        color = if (isSelected) StuDOWhite else Color.White.copy(alpha = 0.45f),
                                        shape = CircleShape
                                    )
                            )
                            Column {
                                Text(
                                    text = filter.displayName,
                                    color = if (isSelected) StuDOWhite else StuDOSilver,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1
                                )
                                Text(
                                    text = filter.subtitle,
                                    color = if (isSelected) filter.tintColor else StuDOSilverMuted,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Normal,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
