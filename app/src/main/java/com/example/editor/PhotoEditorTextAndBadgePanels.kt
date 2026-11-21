package com.example.editor

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
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

private enum class TextPanelSection(val title: String) {
    TYPOGRAPHY("Font"),
    COLORS("Color"),
    BACKGROUND("Box"),
    EFFECTS("Effects")
}

private enum class BadgePanelSection(val title: String) {
    LIBRARY("Library"),
    DESIGN("Style"),
    COLORS("Colors"),
    TRANSFORM("Transform")
}

/**
 * Non-blocking inline Text editing panel. Every change updates the overlay directly on the
 * preview canvas with zero blocking modal dialogs.
 */
@Composable
fun TextEditorPanel(
    textItems: List<TextOverlayItem>,
    selectedTextId: String?,
    recentColors: List<Color>,
    onSelectText: (String) -> Unit,
    onAddNewText: () -> Unit,
    onUpdateSelectedText: (isContinuous: Boolean, transform: (TextOverlayItem) -> TextOverlayItem) -> Unit,
    onCommitHistory: () -> Unit,
    onDeleteText: (String) -> Unit,
    onRecentColorAdded: (Color) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeItem = textItems.firstOrNull { it.id == selectedTextId } ?: textItems.lastOrNull()
    var activeSection by remember { mutableStateOf(TextPanelSection.TYPOGRAPHY) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Add Text + Existing layer selector chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onAddNewText,
                colors = ButtonDefaults.buttonColors(containerColor = StuDOFocusYellow),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier
                    .height(34.dp)
                    .testTag("text_add_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Text", color = Color.Black, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            }

            textItems.forEachIndexed { idx, item ->
                val isSelected = activeItem?.id == item.id
                Row(
                    modifier = Modifier
                        .height(34.dp)
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(14.dp),
                            cornerRadius = 14.dp,
                            isActive = isSelected,
                            accentColor = StuDOFocusYellow
                        )
                        .clickable { onSelectText(item.id) }
                        .padding(start = 10.dp, end = 6.dp)
                        .testTag("text_layer_chip_$idx"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = item.text.ifBlank { "Empty" }.replace("\n", " "),
                        color = if (isSelected) StuDOFocusYellow else StuDOSilver,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(72.dp)
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Delete Text",
                        tint = StuDORed,
                        modifier = Modifier
                            .size(15.dp)
                            .clickable { onDeleteText(item.id) }
                            .testTag("text_delete_chip_$idx")
                    )
                }
            }
        }

        if (activeItem == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlassRecessedTrack(shape = RoundedCornerShape(16.dp), cornerRadius = 16.dp)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Tap Add Text to place custom typography directly on your photo.",
                    color = StuDOSilverMuted,
                    fontSize = 11.5.sp
                )
            }
            return@Column
        }

        // 2. Non-blocking Inline Text Input
        OutlinedTextField(
            value = activeItem.text,
            onValueChange = { updated ->
                onUpdateSelectedText(true) { it.copy(text = updated) }
            },
            placeholder = {
                Text("Type your caption...", color = StuDOSilverMuted, fontSize = 12.sp)
            },
            maxLines = 3,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = StuDOWhite,
                unfocusedTextColor = StuDOWhite,
                focusedBorderColor = StuDOFocusYellow,
                unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                cursorColor = StuDOFocusYellow
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("text_inline_input")
        )

        // 3. Sub-section switcher tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TextPanelSection.entries.forEach { sec ->
                val active = sec == activeSection
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(12.dp),
                            cornerRadius = 12.dp,
                            isActive = active,
                            accentColor = StuDOFocusYellow
                        )
                        .clickable { activeSection = sec }
                        .padding(horizontal = 12.dp)
                        .testTag("text_subtab_${sec.name.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = sec.title,
                        color = if (active) StuDOFocusYellow else StuDOSilver,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        when (activeSection) {
            TextPanelSection.TYPOGRAPHY -> {
                val previewSample = activeItem.text.ifBlank { "Studio Type" }.take(14).replace("\n", " ")

                // Live Bundled Font Cards using user's actual text
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(EditorFontFamily.entries, key = { it.name }) { font ->
                        val isSelected = activeItem.fontFamily == font
                        Column(
                            modifier = Modifier
                                .width(104.dp)
                                .liquidGlassChipSurface(
                                    shape = RoundedCornerShape(14.dp),
                                    cornerRadius = 14.dp,
                                    isActive = isSelected,
                                    accentColor = StuDOFocusYellow
                                )
                                .clickable {
                                    onUpdateSelectedText(false) { it.copy(fontFamily = font) }
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("text_font_${font.name.lowercase()}"),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = previewSample,
                                color = if (isSelected) StuDOFocusYellow else StuDOWhite,
                                fontFamily = font.toComposeFontFamily(),
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = font.displayName,
                                color = StuDOSilverMuted,
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Weight / Bold / Italic / Align controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToggleChipButton(
                        active = activeItem.isBold,
                        onClick = { onUpdateSelectedText(false) { it.copy(isBold = !it.isBold, fontWeight = if (!it.isBold) 700 else 400) } },
                        testTag = "text_bold_toggle"
                    ) {
                        Icon(Icons.Default.FormatBold, contentDescription = "Bold", tint = if (activeItem.isBold) StuDOFocusYellow else StuDOSilver, modifier = Modifier.size(16.dp))
                    }
                    ToggleChipButton(
                        active = activeItem.isItalic,
                        onClick = { onUpdateSelectedText(false) { it.copy(isItalic = !it.isItalic) } },
                        testTag = "text_italic_toggle"
                    ) {
                        Icon(Icons.Default.FormatItalic, contentDescription = "Italic", tint = if (activeItem.isItalic) StuDOFocusYellow else StuDOSilver, modifier = Modifier.size(16.dp))
                    }
                    EditorTextAlign.entries.forEach { align ->
                        val activeAlign = activeItem.textAlign == align
                        val icon = when (align) {
                            EditorTextAlign.LEFT -> Icons.AutoMirrored.Filled.FormatAlignLeft
                            EditorTextAlign.CENTER -> Icons.Default.FormatAlignCenter
                            EditorTextAlign.RIGHT -> Icons.AutoMirrored.Filled.FormatAlignRight
                        }
                        ToggleChipButton(
                            active = activeAlign,
                            onClick = { onUpdateSelectedText(false) { it.copy(textAlign = align) } },
                            testTag = "text_align_${align.name.lowercase()}"
                        ) {
                            Icon(icon, contentDescription = align.title, tint = if (activeAlign) StuDOFocusYellow else StuDOSilver, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                // Size, Letter Spacing & Line Spacing sliders
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlassRecessedTrack(shape = RoundedCornerShape(14.dp), cornerRadius = 14.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CompactLabeledSlider("Size", activeItem.fontSize, 12f..64f, "${activeItem.fontSize.roundToInt()}sp", { v ->
                        onUpdateSelectedText(true) { it.copy(fontSize = v) }
                    }, onCommitHistory, "text_size_slider")

                    CompactLabeledSlider("Track", activeItem.letterSpacing, -0.04f..0.35f, String.format("%.2f", activeItem.letterSpacing), { v ->
                        onUpdateSelectedText(true) { it.copy(letterSpacing = v) }
                    }, onCommitHistory, "text_letter_spacing_slider")

                    CompactLabeledSlider("Line", activeItem.lineSpacing, 0.85f..2.0f, String.format("%.2fx", activeItem.lineSpacing), { v ->
                        onUpdateSelectedText(true) { it.copy(lineSpacing = v) }
                    }, onCommitHistory, "text_line_spacing_slider")
                }
            }

            TextPanelSection.COLORS -> {
                EditorColorPickerRow(
                    label = "Text Color",
                    selectedColor = activeItem.color,
                    recentColors = recentColors,
                    onColorChanged = { c -> onUpdateSelectedText(true) { it.copy(color = c) } },
                    onColorCommitted = { c ->
                        onCommitHistory()
                        onRecentColorAdded(c)
                    },
                    testTagPrefix = "text_fg_color"
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlassRecessedTrack(shape = RoundedCornerShape(14.dp), cornerRadius = 14.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    CompactLabeledSlider("Opacity", activeItem.textOpacity, 0.1f..1f, "${(activeItem.textOpacity * 100f).roundToInt()}%", { v ->
                        onUpdateSelectedText(true) { it.copy(textOpacity = v) }
                    }, onCommitHistory, "text_opacity_slider")
                }
            }

            TextPanelSection.BACKGROUND -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TextBackgroundStyle.entries.forEach { bgStyle ->
                        val active = (if (!activeItem.hasBackground) TextBackgroundStyle.NONE else activeItem.backgroundStyle) == bgStyle
                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .liquidGlassChipSurface(
                                    shape = RoundedCornerShape(12.dp),
                                    cornerRadius = 12.dp,
                                    isActive = active,
                                    accentColor = StuDOFocusYellow
                                )
                                .clickable {
                                    onUpdateSelectedText(false) {
                                        it.copy(
                                            hasBackground = bgStyle != TextBackgroundStyle.NONE,
                                            backgroundStyle = bgStyle
                                        )
                                    }
                                }
                                .padding(horizontal = 12.dp)
                                .testTag("text_bg_style_${bgStyle.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(bgStyle.title, color = if (active) StuDOFocusYellow else StuDOSilver, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                if (activeItem.hasBackground && activeItem.backgroundStyle != TextBackgroundStyle.NONE) {
                    EditorColorPickerRow(
                        label = "Box Color",
                        selectedColor = activeItem.backgroundColor,
                        recentColors = recentColors,
                        onColorChanged = { c -> onUpdateSelectedText(true) { it.copy(backgroundColor = c) } },
                        onColorCommitted = { c ->
                            onCommitHistory()
                            onRecentColorAdded(c)
                        },
                        testTagPrefix = "text_bg_color"
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .liquidGlassRecessedTrack(shape = RoundedCornerShape(14.dp), cornerRadius = 14.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        CompactLabeledSlider("Opacity", activeItem.backgroundOpacity, 0.1f..1f, "${(activeItem.backgroundOpacity * 100f).roundToInt()}%", { v ->
                            onUpdateSelectedText(true) { it.copy(backgroundOpacity = v) }
                        }, onCommitHistory, "text_bg_opacity_slider")
                    }
                }
            }

            TextPanelSection.EFFECTS -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .liquidGlassChipSurface(
                                shape = RoundedCornerShape(12.dp),
                                cornerRadius = 12.dp,
                                isActive = activeItem.hasShadow,
                                accentColor = StuDOFocusYellow
                            )
                            .clickable { onUpdateSelectedText(false) { it.copy(hasShadow = !it.hasShadow) } }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("text_shadow_toggle"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Drop Shadow", color = StuDOWhite, fontSize = 11.sp)
                        Switch(
                            checked = activeItem.hasShadow,
                            onCheckedChange = { onUpdateSelectedText(false) { it.copy(hasShadow = !it.hasShadow) } },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = StuDOFocusYellow)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .liquidGlassChipSurface(
                                shape = RoundedCornerShape(12.dp),
                                cornerRadius = 12.dp,
                                isActive = activeItem.hasOutline,
                                accentColor = StuDOFocusYellow
                            )
                            .clickable { onUpdateSelectedText(false) { it.copy(hasOutline = !it.hasOutline) } }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("text_outline_toggle"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Outline", color = StuDOWhite, fontSize = 11.sp)
                        Switch(
                            checked = activeItem.hasOutline,
                            onCheckedChange = { onUpdateSelectedText(false) { it.copy(hasOutline = !it.hasOutline) } },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = StuDOFocusYellow)
                        )
                    }
                }

                if (activeItem.hasOutline) {
                    EditorColorPickerRow(
                        label = "Outline Color",
                        selectedColor = activeItem.outlineColor,
                        recentColors = recentColors,
                        onColorChanged = { c -> onUpdateSelectedText(true) { it.copy(outlineColor = c) } },
                        onColorCommitted = { c ->
                            onCommitHistory()
                            onRecentColorAdded(c)
                        },
                        testTagPrefix = "text_outline_color"
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlassRecessedTrack(shape = RoundedCornerShape(14.dp), cornerRadius = 14.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CompactLabeledSlider("Scale", activeItem.scale, 0.5f..2.5f, String.format("%.2fx", activeItem.scale), { v ->
                        onUpdateSelectedText(true) { it.copy(scale = v) }
                    }, onCommitHistory, "text_scale_slider")

                    CompactLabeledSlider("Rotate", activeItem.rotation, -180f..180f, "${activeItem.rotation.roundToInt()}°", { v ->
                        onUpdateSelectedText(true) { it.copy(rotation = v) }
                    }, onCommitHistory, "text_rotation_slider")
                }
            }
        }
    }
}

/**
 * Expanded Badges & Stickers studio panel with 13 categories, reusable favorites,
 * and complete shape/style/color/font/transform customization.
 */
@Composable
fun BadgesEditorPanel(
    stickerItems: List<StickerOverlayItem>,
    selectedBadgeId: String?,
    savedPresets: List<SavedBadgePreset>,
    recentColors: List<Color>,
    onSelectBadge: (String) -> Unit,
    onAddBadge: (StickerType) -> Unit,
    onAddSavedPreset: (SavedBadgePreset) -> Unit,
    onSaveSelectedAsPreset: (StickerOverlayItem) -> Unit,
    onUpdateSelectedBadge: (isContinuous: Boolean, transform: (StickerOverlayItem) -> StickerOverlayItem) -> Unit,
    onCommitHistory: () -> Unit,
    onDeleteBadge: (String) -> Unit,
    onRecentColorAdded: (Color) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf(BadgeCategory.ALL) }
    var activeSection by remember { mutableStateOf(BadgePanelSection.LIBRARY) }
    val activeBadge = stickerItems.firstOrNull { it.id == selectedBadgeId } ?: stickerItems.lastOrNull()

    val filteredTemplates = remember(selectedCategory) {
        when (selectedCategory) {
            BadgeCategory.ALL -> StickerType.entries
            BadgeCategory.FAVORITES -> emptyList()
            else -> StickerType.entries.filter { it.category == selectedCategory }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Active Placed Badges Chips + Panel Mode Switcher
        if (stickerItems.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                stickerItems.forEachIndexed { idx, badge ->
                    val isSel = badge.id == activeBadge?.id
                    Row(
                        modifier = Modifier
                            .height(30.dp)
                            .liquidGlassChipSurface(
                                shape = RoundedCornerShape(12.dp),
                                cornerRadius = 12.dp,
                                isActive = isSel,
                                accentColor = StuDOFocusYellow
                            )
                            .clickable {
                                onSelectBadge(badge.id)
                                if (activeSection == BadgePanelSection.LIBRARY) {
                                    activeSection = BadgePanelSection.DESIGN
                                }
                            }
                            .padding(start = 10.dp, end = 6.dp)
                            .testTag("badge_layer_chip_$idx"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = badge.customText?.ifBlank { null } ?: badge.type.title,
                            color = if (isSel) StuDOFocusYellow else StuDOSilver,
                            fontSize = 10.5.sp,
                            maxLines = 1
                        )
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove Badge",
                            tint = StuDORed,
                            modifier = Modifier
                                .size(14.dp)
                                .clickable { onDeleteBadge(badge.id) }
                                .testTag("badge_delete_chip_$idx")
                        )
                    }
                }
            }
        }

        // Sub-navigation: Library | Style | Colors | Transform
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BadgePanelSection.entries.forEach { sec ->
                val enabled = sec == BadgePanelSection.LIBRARY || activeBadge != null
                val active = activeSection == sec
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(12.dp),
                            cornerRadius = 12.dp,
                            isActive = active,
                            isEnabled = enabled,
                            accentColor = StuDOFocusYellow
                        )
                        .clickable(enabled = enabled) { activeSection = sec }
                        .padding(horizontal = 12.dp)
                        .testTag("badge_subtab_${sec.name.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = sec.title,
                        color = when {
                            !enabled -> StuDOSilverMuted.copy(alpha = 0.35f)
                            active -> StuDOFocusYellow
                            else -> StuDOSilver
                        },
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        when (activeSection) {
            BadgePanelSection.LIBRARY -> {
                // Category Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BadgeCategory.entries.forEach { cat ->
                        val active = cat == selectedCategory
                        Box(
                            modifier = Modifier
                                .height(28.dp)
                                .liquidGlassChipSurface(
                                    shape = RoundedCornerShape(12.dp),
                                    cornerRadius = 12.dp,
                                    isActive = active,
                                    accentColor = StuDOFocusYellow
                                )
                                .clickable { selectedCategory = cat }
                                .padding(horizontal = 10.dp)
                                .testTag("badge_category_${cat.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(cat.title, color = if (active) StuDOFocusYellow else StuDOSilver, fontSize = 10.5.sp)
                        }
                    }
                }

                if (selectedCategory == BadgeCategory.FAVORITES) {
                    if (savedPresets.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .liquidGlassRecessedTrack(shape = RoundedCornerShape(14.dp), cornerRadius = 14.dp)
                                .padding(14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Customize a badge and tap Save to Favorites to reuse it here.", color = StuDOSilverMuted, fontSize = 11.sp)
                        }
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(savedPresets, key = { it.id }) { preset ->
                                BadgeLibraryCard(
                                    title = preset.name,
                                    subtitle = preset.styleConfig.shape.title,
                                    previewStyle = preset.styleConfig,
                                    onClick = { onAddSavedPreset(preset) },
                                    testTag = "badge_saved_${preset.id}"
                                )
                            }
                        }
                    }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(filteredTemplates, key = { it.name }) { type ->
                            BadgeLibraryCard(
                                title = type.title,
                                subtitle = type.subtitle,
                                previewStyle = type.defaultStyle,
                                onClick = {
                                    onAddBadge(type)
                                    activeSection = BadgePanelSection.DESIGN
                                },
                                testTag = "badge_preset_${type.name.lowercase()}"
                            )
                        }
                    }
                }
            }

            BadgePanelSection.DESIGN -> if (activeBadge != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = activeBadge.customText ?: activeBadge.type.defaultText,
                        onValueChange = { txt ->
                            onUpdateSelectedBadge(true) { it.copy(customText = txt) }
                        },
                        label = { Text("Badge Label", color = StuDOSilverMuted, fontSize = 10.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = StuDOWhite,
                            unfocusedTextColor = StuDOWhite,
                            focusedBorderColor = StuDOFocusYellow,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            cursorColor = StuDOFocusYellow
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("badge_text_input")
                    )

                    Row(
                        modifier = Modifier
                            .height(42.dp)
                            .liquidGlassChipSurface(shape = RoundedCornerShape(12.dp), cornerRadius = 12.dp, isActive = false)
                            .clickable { onSaveSelectedAsPreset(activeBadge) }
                            .padding(horizontal = 10.dp)
                            .testTag("badge_save_favorite_button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = "Save Favorite", tint = StuDOAmber, modifier = Modifier.size(16.dp))
                        Text("Save", color = StuDOAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Shape Selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BadgeShape.entries.forEach { shape ->
                        val active = activeBadge.styleConfig.shape == shape
                        Box(
                            modifier = Modifier
                                .height(30.dp)
                                .liquidGlassChipSurface(
                                    shape = RoundedCornerShape(12.dp),
                                    cornerRadius = 12.dp,
                                    isActive = active,
                                    accentColor = StuDOFocusYellow
                                )
                                .clickable {
                                    onUpdateSelectedBadge(false) {
                                        it.copy(styleConfig = it.styleConfig.copy(shape = shape))
                                    }
                                }
                                .padding(horizontal = 10.dp)
                                .testTag("badge_shape_${shape.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(shape.title, color = if (active) StuDOFocusYellow else StuDOSilver, fontSize = 10.5.sp)
                        }
                    }
                }

                // Font selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    EditorFontFamily.entries.forEach { font ->
                        val active = activeBadge.styleConfig.fontFamily == font
                        Box(
                            modifier = Modifier
                                .height(30.dp)
                                .liquidGlassChipSurface(
                                    shape = RoundedCornerShape(12.dp),
                                    cornerRadius = 12.dp,
                                    isActive = active,
                                    accentColor = StuDOFocusYellow
                                )
                                .clickable {
                                    onUpdateSelectedBadge(false) {
                                        it.copy(styleConfig = it.styleConfig.copy(fontFamily = font))
                                    }
                                }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = font.displayName,
                                color = if (active) StuDOFocusYellow else StuDOSilver,
                                fontFamily = font.toComposeFontFamily(),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            BadgePanelSection.COLORS -> if (activeBadge != null) {
                EditorColorPickerRow(
                    label = "Background Color",
                    selectedColor = activeBadge.styleConfig.backgroundColor,
                    recentColors = recentColors,
                    onColorChanged = { c ->
                        onUpdateSelectedBadge(true) { it.copy(styleConfig = it.styleConfig.copy(backgroundColor = c)) }
                    },
                    onColorCommitted = { c ->
                        onCommitHistory()
                        onRecentColorAdded(c)
                    },
                    testTagPrefix = "badge_bg_color"
                )
                EditorColorPickerRow(
                    label = "Text Color",
                    selectedColor = activeBadge.styleConfig.textColor,
                    recentColors = recentColors,
                    onColorChanged = { c ->
                        onUpdateSelectedBadge(true) { it.copy(styleConfig = it.styleConfig.copy(textColor = c)) }
                    },
                    onColorCommitted = { c ->
                        onCommitHistory()
                        onRecentColorAdded(c)
                    },
                    testTagPrefix = "badge_fg_color"
                )
                EditorColorPickerRow(
                    label = "Border Color",
                    selectedColor = activeBadge.styleConfig.borderColor,
                    recentColors = recentColors,
                    onColorChanged = { c ->
                        onUpdateSelectedBadge(true) { it.copy(styleConfig = it.styleConfig.copy(borderColor = c)) }
                    },
                    onColorCommitted = { c ->
                        onCommitHistory()
                        onRecentColorAdded(c)
                    },
                    testTagPrefix = "badge_border_color"
                )
            }

            BadgePanelSection.TRANSFORM -> if (activeBadge != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlassRecessedTrack(shape = RoundedCornerShape(14.dp), cornerRadius = 14.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CompactLabeledSlider("Opacity", activeBadge.styleConfig.opacity, 0.15f..1f, "${(activeBadge.styleConfig.opacity * 100f).roundToInt()}%", { v ->
                        onUpdateSelectedBadge(true) { it.copy(styleConfig = it.styleConfig.copy(opacity = v)) }
                    }, onCommitHistory, "badge_opacity_slider")

                    CompactLabeledSlider("Border", activeBadge.styleConfig.borderThicknessDp, 0f..4f, String.format("%.1fdp", activeBadge.styleConfig.borderThicknessDp), { v ->
                        onUpdateSelectedBadge(true) { it.copy(styleConfig = it.styleConfig.copy(borderThicknessDp = v)) }
                    }, onCommitHistory, "badge_border_slider")

                    CompactLabeledSlider("Radius", activeBadge.styleConfig.cornerRadiusDp, 0f..28f, "${activeBadge.styleConfig.cornerRadiusDp.roundToInt()}dp", { v ->
                        onUpdateSelectedBadge(true) { it.copy(styleConfig = it.styleConfig.copy(cornerRadiusDp = v)) }
                    }, onCommitHistory, "badge_radius_slider")

                    CompactLabeledSlider("Scale", activeBadge.scale, 0.5f..2.5f, String.format("%.2fx", activeBadge.scale), { v ->
                        onUpdateSelectedBadge(true) { it.copy(scale = v) }
                    }, onCommitHistory, "badge_scale_slider")

                    CompactLabeledSlider("Rotate", activeBadge.rotation, -180f..180f, "${activeBadge.rotation.roundToInt()}°", { v ->
                        onUpdateSelectedBadge(true) { it.copy(rotation = v) }
                    }, onCommitHistory, "badge_rotation_slider")
                }
            }
        }
    }
}

@Composable
private fun BadgeLibraryCard(
    title: String,
    subtitle: String,
    previewStyle: BadgeStyleConfig,
    onClick: () -> Unit,
    testTag: String
) {
    Column(
        modifier = Modifier
            .width(126.dp)
            .liquidGlassChipSurface(
                shape = RoundedCornerShape(16.dp),
                cornerRadius = 16.dp,
                isActive = false
            )
            .clickable(onClick = onClick)
            .padding(10.dp)
            .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(previewStyle.cornerRadiusDp.coerceAtMost(14f).dp))
                .background(previewStyle.backgroundColor)
                .border(
                    width = previewStyle.borderThicknessDp.coerceIn(0.6f, 2f).dp,
                    color = previewStyle.borderColor,
                    shape = RoundedCornerShape(previewStyle.cornerRadiusDp.coerceAtMost(14f).dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                color = previewStyle.textColor,
                fontFamily = previewStyle.fontFamily.toComposeFontFamily(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
        }
        Text(
            text = subtitle,
            color = StuDOSilverMuted,
            fontSize = 9.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ToggleChipButton(
    active: Boolean,
    onClick: () -> Unit,
    testTag: String,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .liquidGlassChipSurface(
                shape = RoundedCornerShape(10.dp),
                cornerRadius = 10.dp,
                isActive = active,
                accentColor = StuDOFocusYellow
            )
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun CompactLabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            color = StuDOSilverMuted,
            fontSize = 10.5.sp,
            modifier = Modifier.width(46.dp)
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = StuDOFocusYellow,
                activeTrackColor = StuDOFocusYellow,
                inactiveTrackColor = Color.White.copy(alpha = 0.16f)
            ),
            modifier = Modifier
                .weight(1f)
                .height(26.dp)
                .testTag(testTag)
        )
        Text(
            text = valueLabel,
            color = StuDOWhite,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(42.dp)
        )
    }
}
