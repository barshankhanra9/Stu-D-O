package com.example.ui.components

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.WatermarkFont
import com.example.model.WatermarkLogoAlignment
import com.example.model.WatermarkLogoType
import com.example.model.WatermarkPosition
import com.example.model.WatermarkPreset
import com.example.model.WatermarkStyle
import com.example.model.WatermarkTextAlignment
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDOFocusYellow
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite
import com.example.util.RealCameraMetadata
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class StudioTab(val label: String, val icon: ImageVector) {
    TEMPLATES("Templates", Icons.Default.AutoAwesome),
    TEXT("Text", Icons.Default.Title),
    LOGO("Logo", Icons.Default.Badge),
    APPEARANCE("Style & Position", Icons.Default.Palette),
    METADATA("Info", Icons.Default.Info)
}

internal val STUDIO_TABS = StudioTab.entries.toList()
private val WATERMARK_STYLES = WatermarkStyle.entries.toList()
private val WATERMARK_FONTS = WatermarkFont.entries.toList()

private val TEXT_COLOR_SWATCHES: List<Pair<String, Color>> = listOf(
    "#FFFFFF" to Color(0xFFFFFFFF),
    "#FFB74D" to Color(0xFFFFB74D),
    "#FFE57F" to Color(0xFFFFE57F),
    "#00E5FF" to Color(0xFF00E5FF),
    "#1A1A1A" to Color(0xFF1A1A1A),
    "#FF5252" to Color(0xFFFF5252)
)

private val BG_COLOR_SWATCHES: List<Pair<String, Color>> = listOf(
    "#000000" to Color(0xFF000000),
    "#14151B" to Color(0xFF14151B),
    "#FFFFFF" to Color(0xFFFFFFFF),
    "#1E1E24" to Color(0xFF1E1E24),
    "#101115" to Color(0xFF101115),
    "#263238" to Color(0xFF263238)
)

internal val LocalStudioSwitchColors = staticCompositionLocalOf<SwitchColors?> { null }
internal val LocalStudioSliderColors = staticCompositionLocalOf<SliderColors?> { null }

@Immutable
private data class StudioMetadataFieldSpec(
    val key: String,
    val title: String,
    val subtitle: String,
    val checked: Boolean,
    val testTag: String,
    val onToggle: (Boolean) -> Unit
)

@Composable
internal fun StudioEditingSectionHost(
    activeTab: StudioTab,
    stateHolder: WatermarkStudioStateHolder,
    availableMetadata: RealCameraMetadata,
    savedPresets: List<WatermarkPreset>,
    onOpenSavePreset: () -> Unit,
    onDeletePreset: (String) -> Unit,
    onPickCustomLogo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentConfig = stateHolder.activeConfig
    val capabilities = stateHolder.activeCapabilities
    val selectedStyle = stateHolder.selectedStyle

    // Prevent hiding optional text fields mid-typing when user clears characters manually
    var keepEmptyPrimaryFieldOpen by remember(selectedStyle) { mutableStateOf(false) }
    var keepEmptySecondaryFieldOpen by remember(selectedStyle) { mutableStateOf(false) }

    key(activeTab) {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .testTag("studio_panel_scroll_container"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (activeTab) {
                StudioTab.TEMPLATES -> {
                    item(key = "templates_header") {
                        StudioSectionHeader(title = "PRE-BUILT TEMPLATES")
                    }
                    item(key = "templates_row") {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("studio_templates_lazy_row"),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(items = WATERMARK_STYLES, key = { it.name }) { style ->
                                val isSelected = style == selectedStyle
                                val isModified = stateHolder.isTemplateModified(style)
                                Box(
                                    modifier = Modifier
                                        .width(142.dp)
                                        .heightIn(min = 82.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (isSelected) StuDOAmber.copy(alpha = 0.14f)
                                            else Color.White.copy(alpha = 0.05f)
                                        )
                                        .border(
                                            width = if (isSelected) 1.4.dp else 0.8.dp,
                                            color = if (isSelected) StuDOAmber else Color.White.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .clickable { stateHolder.selectTemplate(style) }
                                        .padding(11.dp)
                                        .testTag("studio_template_${style.name.lowercase()}")
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = style.tag,
                                                color = if (isSelected) StuDOFocusYellow else StuDOAmber,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (isModified) {
                                                Box(
                                                    modifier = Modifier
                                                        .testTag("studio_template_edited_badge_${style.name.lowercase()}")
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(StuDOAmber.copy(alpha = 0.22f))
                                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                                ) {
                                                    Text(
                                                        text = "EDITED",
                                                        color = StuDOFocusYellow,
                                                        fontSize = 7.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        letterSpacing = 0.6.sp,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = style.displayName,
                                            color = StuDOWhite,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = style.subtitle,
                                            color = StuDOSilverMuted,
                                            fontSize = 10.sp,
                                            lineHeight = 13.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item(key = "presets_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StudioSectionHeader(title = "USER PRESETS")
                            OutlinedButton(
                                onClick = onOpenSavePreset,
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = StuDOAmber),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .heightIn(min = 40.dp)
                                    .testTag("studio_save_preset_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Save Preset", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    if (savedPresets.isEmpty()) {
                        item(key = "presets_empty") {
                            StudioInfoCallout(
                                text = "No saved presets yet. Fine-tune any template and tap \"Save Preset\"."
                            )
                        }
                    } else {
                        items(items = savedPresets, key = { it.id }) { preset ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .border(0.8.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = preset.name,
                                        color = StuDOWhite,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${preset.config.style.displayName} · ${preset.config.position.displayName}",
                                        color = StuDOAmber,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Button(
                                        onClick = { stateHolder.applyPreset(preset) },
                                        colors = ButtonDefaults.buttonColors(containerColor = StuDOFocusYellow),
                                        shape = RoundedCornerShape(14.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier
                                            .heightIn(min = 36.dp)
                                            .testTag("studio_apply_preset_${preset.name}")
                                    ) {
                                        Text("Apply", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    IconButton(
                                        onClick = { onDeletePreset(preset.id) },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("studio_delete_preset_${preset.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Delete Preset",
                                            tint = StuDOSilverMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                StudioTab.TEXT -> {
                    item(key = "text_header") {
                        StudioSectionHeader(title = "${selectedStyle.tag} · TEXT & TYPOGRAPHY")
                    }

                    capabilities.textSectionHint?.let { hint ->
                        item(key = "text_hint") {
                            StudioInfoCallout(text = hint)
                        }
                    }

                    if (capabilities.supportsPrimaryText) {
                        item(key = "text_primary") {
                            val showPrimaryField = !capabilities.canTogglePrimaryText ||
                                currentConfig.customText.isNotEmpty() ||
                                keepEmptyPrimaryFieldOpen

                            if (showPrimaryField) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = capabilities.primaryTextLabel,
                                            color = StuDOSilver,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (capabilities.canTogglePrimaryText) {
                                            StudioInlineActionPill(
                                                label = "Remove",
                                                icon = Icons.Default.Close,
                                                onClick = {
                                                    keepEmptyPrimaryFieldOpen = false
                                                    stateHolder.removePrimaryText()
                                                },
                                                testTag = "studio_text_remove_primary"
                                            )
                                        }
                                    }
                                    OutlinedTextField(
                                        value = currentConfig.customText,
                                        onValueChange = { next ->
                                            keepEmptyPrimaryFieldOpen = next.isEmpty()
                                            stateHolder.setPrimaryText(next)
                                        },
                                        singleLine = true,
                                        placeholder = { Text(capabilities.primaryTextPlaceholder, color = StuDOSilverMuted) },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = studioTextFieldColors(),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("studio_text_primary_input")
                                    )
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        keepEmptyPrimaryFieldOpen = false
                                        stateHolder.addPrimaryText()
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StuDOAmber),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("studio_text_add_primary")
                                ) {
                                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Add ${capabilities.primaryTextLabel}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    if (capabilities.supportsSecondaryText) {
                        item(key = "text_secondary") {
                            val showSecondaryField = currentConfig.secondaryText.isNotEmpty() || keepEmptySecondaryFieldOpen
                            if (showSecondaryField) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = capabilities.secondaryTextLabel,
                                            color = StuDOSilver,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        StudioInlineActionPill(
                                            label = "Remove",
                                            icon = Icons.Default.Close,
                                            onClick = {
                                                keepEmptySecondaryFieldOpen = false
                                                stateHolder.removeSecondaryText()
                                            },
                                            testTag = "studio_text_remove_secondary"
                                        )
                                    }
                                    OutlinedTextField(
                                        value = currentConfig.secondaryText,
                                        onValueChange = { next ->
                                            keepEmptySecondaryFieldOpen = next.isEmpty()
                                            stateHolder.setSecondaryText(next)
                                        },
                                        singleLine = true,
                                        placeholder = { Text(capabilities.secondaryTextPlaceholder, color = StuDOSilverMuted) },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = studioTextFieldColors(),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("studio_text_secondary_input")
                                    )
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        keepEmptySecondaryFieldOpen = false
                                        stateHolder.addSecondaryText()
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StuDOAmber),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("studio_text_add_secondary")
                                ) {
                                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Add Subtitle Text", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    if (capabilities.supportsFontFamily) {
                        item(key = "text_font_family") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Font Family", color = StuDOSilver, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    WATERMARK_FONTS.forEach { font ->
                                        StudioChoiceChip(
                                            label = font.displayName,
                                            selected = font == currentConfig.font,
                                            onClick = { stateHolder.updateActiveConfig { it.copy(font = font) } },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("studio_font_${font.name.lowercase()}")
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (capabilities.supportedTextAlignments.isNotEmpty()) {
                        item(key = "text_alignment") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Text Alignment", color = StuDOSilver, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    capabilities.supportedTextAlignments.forEach { align ->
                                        StudioChoiceChip(
                                            label = align.displayName,
                                            selected = align == currentConfig.textAlignment,
                                            onClick = { stateHolder.updateActiveConfig { it.copy(textAlignment = align) } },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("studio_text_align_${align.name.lowercase()}")
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (capabilities.supportsBoldToggle || capabilities.supportsTextShadow) {
                        item(key = "text_toggles") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (capabilities.supportsBoldToggle) {
                                    StudioToggleCard(
                                        title = "Bold Typography",
                                        subtitle = "Emphasize signature text weight",
                                        checked = currentConfig.isBold,
                                        onCheckedChange = { bold ->
                                            stateHolder.updateActiveConfig { it.copy(isBold = bold) }
                                        },
                                        testTag = "studio_toggle_bold"
                                    )
                                }
                                if (capabilities.supportsTextShadow) {
                                    StudioToggleCard(
                                        title = "High-Contrast Text Shadow",
                                        subtitle = "Subtle depth shadow for bright scenes",
                                        checked = currentConfig.hasShadow,
                                        onCheckedChange = { shadow ->
                                            stateHolder.updateActiveConfig { it.copy(hasShadow = shadow) }
                                        },
                                        testTag = "studio_toggle_shadow"
                                    )
                                }
                            }
                        }
                    }

                    val hasTextSliders = capabilities.supportsFontSizeScale ||
                        capabilities.supportsLetterSpacing ||
                        capabilities.supportsLineSpacing ||
                        capabilities.supportsTextOpacity

                    if (hasTextSliders) {
                        item(key = "text_sliders") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (capabilities.supportsFontSizeScale) {
                                    StudioSliderControl(
                                        label = if (selectedStyle == WatermarkStyle.GLASS_FRAME) "Capsule & Frame Scale" else "Font Size Scale",
                                        valueText = String.format(Locale.US, "%.1fx", currentConfig.fontSizeScale),
                                        value = currentConfig.fontSizeScale,
                                        valueRange = 0.7f..1.8f,
                                        onValueChange = { scale ->
                                            stateHolder.updateActiveConfig { it.copy(fontSizeScale = scale) }
                                        },
                                        testTag = "studio_slider_font_size"
                                    )
                                }
                                if (capabilities.supportsLetterSpacing) {
                                    StudioSliderControl(
                                        label = "Letter Spacing",
                                        valueText = String.format(Locale.US, "%.2f", currentConfig.letterSpacing),
                                        value = currentConfig.letterSpacing,
                                        valueRange = 0.0f..0.20f,
                                        onValueChange = { spacing ->
                                            stateHolder.updateActiveConfig { it.copy(letterSpacing = spacing) }
                                        },
                                        testTag = "studio_slider_letter_spacing"
                                    )
                                }
                                if (capabilities.supportsLineSpacing) {
                                    StudioSliderControl(
                                        label = "Line Spacing",
                                        valueText = String.format(Locale.US, "%.2fx", currentConfig.lineSpacing),
                                        value = currentConfig.lineSpacing,
                                        valueRange = 0.90f..1.80f,
                                        onValueChange = { spacing ->
                                            stateHolder.updateActiveConfig { it.copy(lineSpacing = spacing) }
                                        },
                                        testTag = "studio_slider_line_spacing"
                                    )
                                }
                                if (capabilities.supportsTextOpacity) {
                                    StudioSliderControl(
                                        label = "Text Opacity",
                                        valueText = "${(currentConfig.textOpacity * 100).roundToInt()}%",
                                        value = currentConfig.textOpacity,
                                        valueRange = 0.20f..1.0f,
                                        onValueChange = { opacity ->
                                            stateHolder.updateActiveConfig { it.copy(textOpacity = opacity) }
                                        },
                                        testTag = "studio_slider_text_opacity"
                                    )
                                }
                            }
                        }
                    }

                    if (capabilities.supportsTextColor) {
                        item(key = "text_colors") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Text Color", color = StuDOSilver, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                ColorSwatchRow(
                                    swatches = TEXT_COLOR_SWATCHES,
                                    selectedHex = currentConfig.textColorHex,
                                    prefixTag = "studio_text_color_",
                                    onSelectHex = { hex ->
                                        stateHolder.updateActiveConfig { it.copy(textColorHex = hex) }
                                    }
                                )
                            }
                        }
                    }
                }

                StudioTab.LOGO -> {
                    item(key = "logo_header") {
                        StudioSectionHeader(title = "${selectedStyle.tag} · LOGO & EMBLEM")
                    }

                    if (!capabilities.supportsAnyLogo) {
                        item(key = "logo_unsupported") {
                            StudioInfoCallout(
                                text = capabilities.logoEmptyHint
                                    ?: "This template renders without a brand logo."
                            )
                        }
                    } else {
                        item(key = "logo_source") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Logo Source", color = StuDOSilver, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    capabilities.supportedLogoTypes.forEach { logo ->
                                        StudioChoiceChip(
                                            label = logo.displayName,
                                            selected = logo == currentConfig.logoType,
                                            onClick = { stateHolder.updateActiveConfig { it.copy(logoType = logo) } },
                                            modifier = Modifier.testTag("studio_logo_${logo.name.lowercase()}")
                                        )
                                    }
                                }
                            }
                        }
                        if (currentConfig.logoType == WatermarkLogoType.CUSTOM) {
                            item(key = "logo_custom_picker") {
                                OutlinedButton(
                                    onClick = onPickCustomLogo,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StuDOAmber),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("studio_pick_logo_button")
                                ) {
                                    Icon(imageVector = Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (currentConfig.customLogoUri != null) "Change Uploaded Image" else "Upload Custom Logo from Device",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                        if (currentConfig.logoType != WatermarkLogoType.NONE) {
                            if (capabilities.supportedLogoAlignments.isNotEmpty()) {
                                item(key = "logo_alignment") {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Logo Placement", color = StuDOSilver, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            capabilities.supportedLogoAlignments.forEach { align ->
                                                StudioChoiceChip(
                                                    label = align.displayName,
                                                    selected = align == currentConfig.logoAlignment,
                                                    onClick = { stateHolder.updateActiveConfig { it.copy(logoAlignment = align) } },
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .testTag("studio_logo_align_${align.name.lowercase()}")
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            item(key = "logo_sliders") {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (capabilities.supportsLogoScale) {
                                        StudioSliderControl(
                                            label = "Logo Size",
                                            valueText = String.format(Locale.US, "%.1fx", currentConfig.logoScale),
                                            value = currentConfig.logoScale,
                                            valueRange = 0.5f..2.5f,
                                            onValueChange = { scale ->
                                                stateHolder.updateActiveConfig { it.copy(logoScale = scale) }
                                            },
                                            testTag = "studio_slider_logo_scale"
                                        )
                                    }
                                    if (capabilities.supportsLogoOpacity) {
                                        StudioSliderControl(
                                            label = "Logo Opacity",
                                            valueText = "${(currentConfig.logoOpacity * 100).roundToInt()}%",
                                            value = currentConfig.logoOpacity,
                                            valueRange = 0.1f..1.0f,
                                            onValueChange = { opacity ->
                                                stateHolder.updateActiveConfig { it.copy(logoOpacity = opacity) }
                                            },
                                            testTag = "studio_slider_logo_opacity"
                                        )
                                    }
                                    if (capabilities.supportsLogoSpacing) {
                                        StudioSliderControl(
                                            label = "Logo Spacing",
                                            valueText = "${currentConfig.logoSpacingDp} dp",
                                            value = currentConfig.logoSpacingDp.toFloat(),
                                            valueRange = 4f..32f,
                                            onValueChange = { spacing ->
                                                stateHolder.updateActiveConfig { it.copy(logoSpacingDp = spacing.roundToInt()) }
                                            },
                                            testTag = "studio_slider_logo_spacing"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                StudioTab.APPEARANCE -> {
                    item(key = "style_pos_header") {
                        StudioSectionHeader(title = "${selectedStyle.tag} · PLACEMENT & STYLE")
                    }

                    if (capabilities.supportedPositions.isEmpty()) {
                        item(key = "style_fixed_layout") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.White.copy(alpha = 0.04f))
                                    .border(0.8.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 12.dp, vertical = 12.dp)
                                    .testTag("studio_fixed_layout_badge"),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = StuDOAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Column {
                                    Text(
                                        text = "Template-Anchored Placement",
                                        color = StuDOWhite,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = capabilities.fixedLayoutDescription ?: "Position is fixed by this template layout.",
                                        color = StuDOSilverMuted,
                                        fontSize = 10.5.sp
                                    )
                                }
                            }
                        }
                    } else {
                        item(key = "style_positions") {
                            val standardPositions = capabilities.supportedPositions.filter { it != WatermarkPosition.CUSTOM_COORDS }
                            val supportsCustom = WatermarkPosition.CUSTOM_COORDS in capabilities.supportedPositions
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Watermark Position", color = StuDOSilver, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                standardPositions.chunked(3).forEach { rowPositions ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        rowPositions.forEach { pos ->
                                            PositionPill(
                                                pos = pos,
                                                currentPos = currentConfig.position,
                                                onSelect = { stateHolder.updateActiveConfig { it.copy(position = pos) } },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        repeat(3 - rowPositions.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                                if (supportsCustom) {
                                    PositionPill(
                                        pos = WatermarkPosition.CUSTOM_COORDS,
                                        currentPos = currentConfig.position,
                                        onSelect = {
                                            stateHolder.updateActiveConfig { it.copy(position = WatermarkPosition.CUSTOM_COORDS) }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                        if (currentConfig.position == WatermarkPosition.CUSTOM_COORDS) {
                            item(key = "style_custom_coords") {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    StudioSliderControl(
                                        label = "Horizontal X",
                                        valueText = "${(currentConfig.customOffsetX * 100).toInt()}%",
                                        value = currentConfig.customOffsetX,
                                        valueRange = 0.05f..0.95f,
                                        onValueChange = { x ->
                                            stateHolder.updateActiveConfig { it.copy(customOffsetX = x) }
                                        },
                                        testTag = "studio_slider_custom_x"
                                    )
                                    StudioSliderControl(
                                        label = "Vertical Y",
                                        valueText = "${(currentConfig.customOffsetY * 100).toInt()}%",
                                        value = currentConfig.customOffsetY,
                                        valueRange = 0.05f..0.95f,
                                        onValueChange = { y ->
                                            stateHolder.updateActiveConfig { it.copy(customOffsetY = y) }
                                        },
                                        testTag = "studio_slider_custom_y"
                                    )
                                }
                            }
                        }
                    }

                    val showRotationControl = capabilities.supportsRotation &&
                        currentConfig.position != WatermarkPosition.FRAME_BORDER
                    val showMarginControl = capabilities.supportsMarginScale &&
                        (currentConfig.position != WatermarkPosition.FRAME_BORDER || selectedStyle == WatermarkStyle.CYBERPUNK)

                    if (showMarginControl || showRotationControl) {
                        item(key = "style_transform_sliders") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (showMarginControl) {
                                    StudioSliderControl(
                                        label = "Edge Margin",
                                        valueText = String.format(Locale.US, "%.1fx", currentConfig.marginScale),
                                        value = currentConfig.marginScale,
                                        valueRange = 0.5f..2.0f,
                                        onValueChange = { scale ->
                                            stateHolder.updateActiveConfig { it.copy(marginScale = scale) }
                                        },
                                        testTag = "studio_slider_margin"
                                    )
                                }
                                if (showRotationControl) {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Rotation", color = StuDOSilver, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                if (abs(currentConfig.rotationDegrees) > 0.2f) {
                                                    StudioInlineActionPill(
                                                        label = "0° Reset",
                                                        icon = Icons.Default.Close,
                                                        onClick = {
                                                            stateHolder.updateActiveConfig { it.copy(rotationDegrees = 0f) }
                                                        },
                                                        testTag = "studio_rotation_reset_button"
                                                    )
                                                }
                                                Text(
                                                    text = "${currentConfig.rotationDegrees.roundToInt()}°",
                                                    color = StuDOFocusYellow,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        Slider(
                                            value = currentConfig.rotationDegrees,
                                            onValueChange = { deg ->
                                                val snapped = if (abs(deg) < 1.2f) 0f else deg
                                                stateHolder.updateActiveConfig { it.copy(rotationDegrees = snapped) }
                                            },
                                            valueRange = -30f..30f,
                                            colors = LocalStudioSliderColors.current
                                                ?: SliderDefaults.colors(thumbColor = StuDOFocusYellow, activeTrackColor = StuDOAmber),
                                            modifier = Modifier.testTag("studio_slider_rotation")
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val hasAnyContainerControls = capabilities.supportsBackgroundColor ||
                        capabilities.supportsBackgroundOpacity ||
                        capabilities.supportsBorderThickness ||
                        capabilities.supportsCornerRadius ||
                        capabilities.supportsPadding ||
                        capabilities.supportsHudAccentColor

                    if (hasAnyContainerControls) {
                        item(key = "style_container_header") {
                            StudioSectionHeader(
                                title = if (capabilities.supportsHudAccentColor) "HUD ACCENT COLOR" else "CONTAINER & FRAME"
                            )
                        }
                        if (capabilities.supportsHudAccentColor) {
                            item(key = "style_hud_accent") {
                                ColorSwatchRow(
                                    swatches = TEXT_COLOR_SWATCHES,
                                    selectedHex = currentConfig.textColorHex,
                                    prefixTag = "studio_hud_accent_",
                                    onSelectHex = { hex ->
                                        stateHolder.updateActiveConfig { it.copy(textColorHex = hex) }
                                    }
                                )
                            }
                        }
                        if (capabilities.supportsBackgroundColor) {
                            item(key = "style_bg_colors") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Background Color", color = StuDOSilver, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    ColorSwatchRow(
                                        swatches = BG_COLOR_SWATCHES,
                                        selectedHex = currentConfig.backgroundColorHex,
                                        prefixTag = "studio_bg_color_",
                                        onSelectHex = { hex ->
                                            stateHolder.updateActiveConfig { it.copy(backgroundColorHex = hex) }
                                        }
                                    )
                                }
                            }
                        }
                        val hasFrameSliders = capabilities.supportsBackgroundOpacity ||
                            capabilities.supportsBorderThickness ||
                            capabilities.supportsCornerRadius ||
                            capabilities.supportsPadding
                        if (hasFrameSliders) {
                            item(key = "style_frame_sliders") {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (capabilities.supportsBackgroundOpacity) {
                                        StudioSliderControl(
                                            label = if (selectedStyle == WatermarkStyle.GLASS_FRAME) "Glass Frost Opacity" else "Background Opacity",
                                            valueText = "${(currentConfig.backgroundOpacity * 100).roundToInt()}%",
                                            value = currentConfig.backgroundOpacity,
                                            valueRange = 0.0f..1.0f,
                                            onValueChange = { opacity ->
                                                stateHolder.updateActiveConfig { it.copy(backgroundOpacity = opacity) }
                                            },
                                            testTag = "studio_slider_bg_opacity"
                                        )
                                    }
                                    if (capabilities.supportsBorderThickness) {
                                        StudioSliderControl(
                                            label = "Border Thickness",
                                            valueText = "${currentConfig.borderThicknessDp} dp",
                                            value = currentConfig.borderThicknessDp.toFloat(),
                                            valueRange = 0f..6f,
                                            onValueChange = { thickness ->
                                                stateHolder.updateActiveConfig { it.copy(borderThicknessDp = thickness.roundToInt()) }
                                            },
                                            testTag = "studio_slider_border_thickness"
                                        )
                                    }
                                    if (capabilities.supportsCornerRadius) {
                                        StudioSliderControl(
                                            label = "Corner Radius",
                                            valueText = "${currentConfig.cornerRadiusDp} dp",
                                            value = currentConfig.cornerRadiusDp.toFloat(),
                                            valueRange = 0f..24f,
                                            onValueChange = { radius ->
                                                stateHolder.updateActiveConfig { it.copy(cornerRadiusDp = radius.roundToInt()) }
                                            },
                                            testTag = "studio_slider_corner_radius"
                                        )
                                    }
                                    if (capabilities.supportsPadding) {
                                        StudioSliderControl(
                                            label = "Internal Padding",
                                            valueText = "${currentConfig.paddingDp} dp",
                                            value = currentConfig.paddingDp.toFloat(),
                                            valueRange = 4f..32f,
                                            onValueChange = { pad ->
                                                stateHolder.updateActiveConfig { it.copy(paddingDp = pad.roundToInt()) }
                                            },
                                            testTag = "studio_slider_padding"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                StudioTab.METADATA -> {
                    val metadataFields = buildApplicableMetadataFields(
                        capabilities = capabilities,
                        config = currentConfig,
                        metadata = availableMetadata,
                        stateHolder = stateHolder
                    )
                    item(key = "info_header") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            StudioSectionHeader(title = "${selectedStyle.tag} · CAPTURE METADATA")
                            Text(
                                text = "Showing only genuine metadata fields supported by this template and available on this photo.",
                                color = StuDOSilverMuted,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                    if (metadataFields.isEmpty()) {
                        item(key = "info_empty") {
                            StudioInfoCallout(
                                text = "No supported EXIF metadata fields are available for this photo and template."
                            )
                        }
                    } else {
                        items(items = metadataFields, key = { it.key }) { spec ->
                            StudioToggleCard(
                                title = spec.title,
                                subtitle = spec.subtitle,
                                checked = spec.checked,
                                onCheckedChange = spec.onToggle,
                                testTag = spec.testTag
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildApplicableMetadataFields(
    capabilities: WatermarkTemplateCapabilities,
    config: com.example.model.WatermarkConfig,
    metadata: RealCameraMetadata,
    stateHolder: WatermarkStudioStateHolder
): List<StudioMetadataFieldSpec> {
    val fields = mutableListOf<StudioMetadataFieldSpec>()

    val dateValue = metadata.dateTimeFormatted?.takeIf { it.isNotBlank() }
    if (capabilities.supportsDateTime && dateValue != null) {
        fields.add(
            StudioMetadataFieldSpec(
                key = "info_date",
                title = "Date & Timestamp",
                subtitle = dateValue,
                checked = config.showDateTime,
                testTag = "studio_toggle_meta_date",
                onToggle = { checked -> stateHolder.updateActiveConfig { it.copy(showDateTime = checked) } }
            )
        )
    }

    val mfrValue = metadata.manufacturer?.takeIf { it.isNotBlank() }
    val showMfrSeparately = capabilities.supportsManufacturer && mfrValue != null
    if (showMfrSeparately) {
        fields.add(
            StudioMetadataFieldSpec(
                key = "info_manufacturer",
                title = "Device Manufacturer",
                subtitle = mfrValue!!,
                checked = config.showManufacturer,
                testTag = "studio_toggle_meta_manufacturer",
                onToggle = { checked -> stateHolder.updateActiveConfig { it.copy(showManufacturer = checked) } }
            )
        )
    }

    // Avoid duplicating manufacturer inside Device Model when Manufacturer toggle is already shown
    val modelValue = if (showMfrSeparately) {
        metadata.modelOnly?.takeIf { it.isNotBlank() }
            ?: metadata.deviceModel?.removePrefix(mfrValue!!)?.trim()?.takeIf { it.isNotBlank() }
    } else {
        metadata.deviceModel?.takeIf { it.isNotBlank() } ?: metadata.modelOnly?.takeIf { it.isNotBlank() }
    }
    if (capabilities.supportsDeviceModel && modelValue != null) {
        fields.add(
            StudioMetadataFieldSpec(
                key = "info_device",
                title = "Device Model",
                subtitle = modelValue,
                checked = config.showDeviceModel,
                testTag = "studio_toggle_meta_device",
                onToggle = { checked -> stateHolder.updateActiveConfig { it.copy(showDeviceModel = checked) } }
            )
        )
    }

    val focalValue = metadata.focalLength?.takeIf { it.isNotBlank() }
    if (capabilities.supportsLensInfo && focalValue != null) {
        fields.add(
            StudioMetadataFieldSpec(
                key = "info_lens",
                title = "Lens Focal Length",
                subtitle = focalValue,
                checked = config.showLensInfo,
                testTag = "studio_toggle_meta_lens",
                onToggle = { checked -> stateHolder.updateActiveConfig { it.copy(showLensInfo = checked) } }
            )
        )
    }

    val exposureParts = listOfNotNull(
        metadata.aperture?.takeIf { it.isNotBlank() },
        metadata.shutterSpeed?.takeIf { it.isNotBlank() },
        metadata.iso?.takeIf { it.isNotBlank() }
    )
    if (capabilities.supportsExifSpecs && exposureParts.isNotEmpty()) {
        fields.add(
            StudioMetadataFieldSpec(
                key = "info_exif",
                title = "Exposure Triangle",
                subtitle = exposureParts.joinToString("  ·  "),
                checked = config.showExifSpecs,
                testTag = "studio_toggle_meta_exif",
                onToggle = { checked -> stateHolder.updateActiveConfig { it.copy(showExifSpecs = checked) } }
            )
        )
    }

    val resolutionValue = metadata.resolutionText?.takeIf { it.isNotBlank() }
    if (capabilities.supportsResolution && resolutionValue != null) {
        fields.add(
            StudioMetadataFieldSpec(
                key = "info_resolution",
                title = "Photo Resolution",
                subtitle = resolutionValue,
                checked = config.showResolution,
                testTag = "studio_toggle_meta_resolution",
                onToggle = { checked -> stateHolder.updateActiveConfig { it.copy(showResolution = checked) } }
            )
        )
    }

    return fields
}

@Composable
private fun studioTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = StuDOWhite,
    unfocusedTextColor = StuDOWhite,
    focusedBorderColor = StuDOAmber,
    unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
    cursorColor = StuDOFocusYellow,
    focusedContainerColor = Color.White.copy(alpha = 0.04f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.03f)
)

@Composable
private fun StudioSectionHeader(title: String) {
    Text(
        text = title,
        color = StuDOAmber,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp
    )
}

@Composable
private fun StudioInfoCallout(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(0.8.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            color = StuDOSilverMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }
}

@Composable
private fun StudioInlineActionPill(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(0.8.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = StuDOSilver,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = label,
            color = StuDOSilver,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun StudioChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) StuDOAmber.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f))
            .border(
                width = if (selected) 1.2.dp else 0.8.dp,
                color = if (selected) StuDOAmber else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) StuDOFocusYellow else StuDOSilver,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PositionPill(
    pos: WatermarkPosition,
    currentPos: WatermarkPosition,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isSelected = pos == currentPos
    StudioChoiceChip(
        label = pos.displayName,
        selected = isSelected,
        onClick = onSelect,
        modifier = modifier.testTag("studio_pos_${pos.name.lowercase()}")
    )
}

@Composable
private fun ColorSwatchRow(
    swatches: List<Pair<String, Color>>,
    selectedHex: String,
    prefixTag: String,
    onSelectHex: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        swatches.forEach { (hex, swatchColor) ->
            val isSelected = selectedHex.equals(hex, ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable { onSelectHex(hex) }
                    .testTag("$prefixTag${hex.removePrefix("#").lowercase()}"),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(swatchColor)
                        .border(
                            width = if (isSelected) 2.5.dp else 1.dp,
                            color = if (isSelected) StuDOFocusYellow else Color.White.copy(alpha = 0.28f),
                            shape = CircleShape
                        )
                )
            }
        }
    }
}

@Composable
private fun StudioSliderControl(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    testTag: String
) {
    val sharedSliderColors = LocalStudioSliderColors.current
        ?: SliderDefaults.colors(thumbColor = StuDOFocusYellow, activeTrackColor = StuDOAmber)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, color = StuDOSilver, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(text = valueText, color = StuDOFocusYellow, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = sharedSliderColors,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun StudioToggleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    val sharedSwitchColors = LocalStudioSwitchColors.current
        ?: SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = StuDOFocusYellow)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(0.8.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = title, color = StuDOWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, color = StuDOSilverMuted, fontSize = 10.sp, lineHeight = 13.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = sharedSwitchColors,
            modifier = Modifier.testTag(testTag)
        )
    }
}
