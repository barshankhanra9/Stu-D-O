package com.example.ui.components

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.model.WatermarkConfig
import com.example.model.WatermarkFont
import com.example.model.WatermarkLogoAlignment
import com.example.model.WatermarkLogoType
import com.example.model.WatermarkPosition
import com.example.model.WatermarkStyle
import com.example.model.WatermarkTextAlignment
import com.example.ui.theme.GlassObsidianBottom
import com.example.ui.theme.GlassObsidianTop

@Immutable
internal data class StudioVisualTier(
    val isLowEndDevice: Boolean
)

@Composable
internal fun rememberStudioVisualTier(): StudioVisualTier {
    val context = LocalContext.current
    return remember(context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLowRam = try {
            am?.isLowRamDevice == true
        } catch (_: Throwable) {
            false
        }
        // Automatically tone down multi-layer specular gradients on low-RAM or legacy (< Android 12) devices
        val isLowEnd = isLowRam || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        StudioVisualTier(isLowEndDevice = isLowEnd)
    }
}

/**
 * Adaptive Liquid Glass surface modifier for Watermark Studio.
 * Uses StuDO's cached drawWithCache liquid glass shader on modern hardware and automatically simplifies
 * to a single-pass translucent surface with clean specular border on lower-end devices.
 */
internal fun Modifier.studioGlassPanel(
    visualTier: StudioVisualTier,
    shape: Shape = RoundedCornerShape(24.dp),
    cornerRadius: Dp = 24.dp,
    topColor: Color = GlassObsidianTop.copy(alpha = 0.78f),
    bottomColor: Color = GlassObsidianBottom.copy(alpha = 0.90f),
    borderWidth: Dp = 1.dp,
    coolHighlightAlpha: Float = 0.28f,
    warmHighlightAlpha: Float = 0.20f,
    specularSheenAlpha: Float = 0.12f
): Modifier {
    return if (visualTier.isLowEndDevice) {
        this
            .clip(shape)
            .background(bottomColor)
            .border(borderWidth, Color.White.copy(alpha = 0.16f), shape)
    } else {
        this.liquidGlassSurface(
            shape = shape,
            cornerRadius = cornerRadius,
            topColor = topColor,
            bottomColor = bottomColor,
            borderWidth = borderWidth,
            coolHighlightAlpha = coolHighlightAlpha,
            warmHighlightAlpha = warmHighlightAlpha,
            specularSheenAlpha = specularSheenAlpha
        )
    }
}

/**
 * Declarative capability descriptor for each WatermarkStyle template.
 * Keeps all 5 editing sections strictly aligned with what the selected template actually renders,
 * hiding controls that do not apply to that template.
 */
@Immutable
internal data class WatermarkTemplateCapabilities(
    // Text section
    val supportsPrimaryText: Boolean = true,
    val primaryTextLabel: String = "Primary Signature Text",
    val primaryTextPlaceholder: String = "e.g. Shot on STU•D•O",
    val canTogglePrimaryText: Boolean = false,
    val supportsSecondaryText: Boolean = false,
    val secondaryTextLabel: String = "Secondary Text / Subtitle",
    val secondaryTextPlaceholder: String = "e.g. ULTRA HD OPTICAL SYSTEM",
    val supportsFontFamily: Boolean = true,
    val supportsBoldToggle: Boolean = true,
    val supportsFontSizeScale: Boolean = true,
    val supportsTextColor: Boolean = true,
    val supportsLetterSpacing: Boolean = true,
    val supportsLineSpacing: Boolean = false,
    val supportsTextOpacity: Boolean = true,
    val supportedTextAlignments: List<WatermarkTextAlignment> = emptyList(),
    val supportsTextShadow: Boolean = true,
    val textSectionHint: String? = null,
    // Logo section
    val supportedLogoTypes: List<WatermarkLogoType> = WatermarkLogoType.entries,
    val supportedLogoAlignments: List<WatermarkLogoAlignment> = emptyList(),
    val supportsLogoScale: Boolean = true,
    val supportsLogoOpacity: Boolean = true,
    val supportsLogoSpacing: Boolean = true,
    val logoEmptyHint: String? = null,
    // Style & Position section
    val supportedPositions: List<WatermarkPosition> = WatermarkPosition.entries,
    val fixedLayoutDescription: String? = null,
    val supportsBackgroundColor: Boolean = true,
    val supportsBackgroundOpacity: Boolean = false,
    val supportsBorderThickness: Boolean = false,
    val supportsCornerRadius: Boolean = false,
    val supportsPadding: Boolean = false,
    val supportsMarginScale: Boolean = false,
    val supportsRotation: Boolean = false,
    val supportsHudAccentColor: Boolean = false,
    // Info (Metadata) section
    val supportsDateTime: Boolean = true,
    val supportsManufacturer: Boolean = false,
    val supportsDeviceModel: Boolean = false,
    val supportsLensInfo: Boolean = false,
    val supportsExifSpecs: Boolean = false,
    val supportsResolution: Boolean = false
) {
    val supportsAnyLogo: Boolean get() = supportedLogoTypes.any { it != WatermarkLogoType.NONE }
}

private val FLOATING_OVERLAY_POSITIONS = listOf(
    WatermarkPosition.BOTTOM_RIGHT,
    WatermarkPosition.BOTTOM_LEFT,
    WatermarkPosition.BOTTOM_CENTER,
    WatermarkPosition.TOP_RIGHT,
    WatermarkPosition.TOP_LEFT,
    WatermarkPosition.CUSTOM_COORDS
)

private val GLASS_FRAME_POSITIONS = listOf(
    WatermarkPosition.FRAME_BORDER,
    WatermarkPosition.BOTTOM_CENTER,
    WatermarkPosition.BOTTOM_RIGHT,
    WatermarkPosition.BOTTOM_LEFT,
    WatermarkPosition.TOP_RIGHT,
    WatermarkPosition.TOP_LEFT,
    WatermarkPosition.CUSTOM_COORDS
)

private val STANDARD_LOGO_TYPES = listOf(
    WatermarkLogoType.APP_ICON,
    WatermarkLogoType.DEVICE_MANUFACTURER,
    WatermarkLogoType.CUSTOM,
    WatermarkLogoType.NONE
)

private val GLASS_LOGO_TYPES = listOf(
    WatermarkLogoType.DEVICE_MANUFACTURER,
    WatermarkLogoType.CUSTOM,
    WatermarkLogoType.NONE
)

internal object WatermarkTemplateDefaults {

    /**
     * Returns an independent, complete default WatermarkConfig for the given template.
     * Never copies text, logo, layout, or styling from another template.
     */
    fun defaultConfig(style: WatermarkStyle, isEnabled: Boolean = true): WatermarkConfig {
        return when (style) {
            WatermarkStyle.LEICA_FRAME -> WatermarkConfig(
                isEnabled = isEnabled,
                style = WatermarkStyle.LEICA_FRAME,
                customText = "Shot on STU•D•O",
                secondaryText = "OPTICAL SYSTEM · ULTRA HD",
                position = WatermarkPosition.FRAME_BORDER,
                customOffsetX = 0.85f,
                customOffsetY = 0.92f,
                font = WatermarkFont.SANS_SERIF,
                isBold = true,
                fontSizeScale = 1.0f,
                textColorHex = "#1A1A1A",
                letterSpacing = 0.05f,
                hasShadow = false,
                logoType = WatermarkLogoType.APP_ICON,
                customLogoUri = null,
                logoScale = 1.0f,
                logoOpacity = 1.0f,
                logoSpacingDp = 12,
                backgroundOpacity = 1.0f,
                backgroundColorHex = "#FFFFFF",
                borderThicknessDp = 0,
                cornerRadiusDp = 0,
                paddingDp = 16,
                showManufacturer = false,
                showDateTime = true,
                showDeviceModel = true,
                showLensInfo = true,
                showExifSpecs = true,
                showResolution = false
            )
            WatermarkStyle.GLASS_FRAME -> WatermarkConfig(
                isEnabled = isEnabled,
                style = WatermarkStyle.GLASS_FRAME,
                customText = "google",
                secondaryText = "pixel 6 pro",
                position = WatermarkPosition.FRAME_BORDER,
                customOffsetX = 0.50f,
                customOffsetY = 0.92f,
                font = WatermarkFont.SANS_SERIF,
                isBold = true,
                fontSizeScale = 1.0f,
                textColorHex = "#FFFFFF",
                letterSpacing = 0.04f,
                hasShadow = false,
                logoType = WatermarkLogoType.DEVICE_MANUFACTURER,
                customLogoUri = null,
                logoScale = 1.0f,
                logoOpacity = 1.0f,
                logoSpacingDp = 12,
                backgroundOpacity = 1.0f,
                backgroundColorHex = "#FFFFFF",
                borderThicknessDp = 1,
                cornerRadiusDp = 24,
                paddingDp = 16,
                showManufacturer = true,
                showDateTime = true,
                showDeviceModel = true,
                showLensInfo = true,
                showExifSpecs = true,
                showResolution = false
            )
            WatermarkStyle.HASSELBLAD -> WatermarkConfig(
                isEnabled = isEnabled,
                style = WatermarkStyle.HASSELBLAD,
                customText = "STU•D•O MASTER",
                secondaryText = "MEDIUM FORMAT SIMULATION",
                position = WatermarkPosition.FRAME_BORDER,
                customOffsetX = 0.85f,
                customOffsetY = 0.92f,
                font = WatermarkFont.SANS_SERIF,
                isBold = true,
                fontSizeScale = 1.0f,
                textColorHex = "#F5F5F7",
                letterSpacing = 0.05f,
                hasShadow = false,
                logoType = WatermarkLogoType.APP_ICON,
                customLogoUri = null,
                logoScale = 1.0f,
                logoOpacity = 1.0f,
                logoSpacingDp = 12,
                backgroundOpacity = 1.0f,
                backgroundColorHex = "#101115",
                borderThicknessDp = 0,
                cornerRadiusDp = 0,
                paddingDp = 16,
                showManufacturer = false,
                showDateTime = true,
                showDeviceModel = true,
                showLensInfo = true,
                showExifSpecs = true,
                showResolution = false
            )
            WatermarkStyle.CYBERPUNK -> WatermarkConfig(
                isEnabled = isEnabled,
                style = WatermarkStyle.CYBERPUNK,
                customText = "SYSTEM // STU•D•O CORE",
                secondaryText = "",
                position = WatermarkPosition.BOTTOM_RIGHT,
                customOffsetX = 0.85f,
                customOffsetY = 0.92f,
                font = WatermarkFont.MONOSPACE,
                isBold = true,
                fontSizeScale = 1.0f,
                textColorHex = "#00E5FF",
                letterSpacing = 0.06f,
                hasShadow = true,
                logoType = WatermarkLogoType.NONE,
                customLogoUri = null,
                logoScale = 1.0f,
                logoOpacity = 1.0f,
                logoSpacingDp = 12,
                backgroundOpacity = 0.0f,
                backgroundColorHex = "#0A0E17",
                borderThicknessDp = 0,
                cornerRadiusDp = 0,
                paddingDp = 16,
                showManufacturer = false,
                showDateTime = true,
                showDeviceModel = false,
                showLensInfo = true,
                showExifSpecs = true,
                showResolution = false
            )
            WatermarkStyle.RETRO_90S -> WatermarkConfig(
                isEnabled = isEnabled,
                style = WatermarkStyle.RETRO_90S,
                customText = "'90s CAM",
                secondaryText = "",
                position = WatermarkPosition.BOTTOM_RIGHT,
                customOffsetX = 0.85f,
                customOffsetY = 0.92f,
                font = WatermarkFont.MONOSPACE,
                isBold = true,
                fontSizeScale = 1.1f,
                textColorHex = "#FF9100",
                letterSpacing = 0.05f,
                hasShadow = true,
                logoType = WatermarkLogoType.NONE,
                customLogoUri = null,
                logoScale = 1.0f,
                logoOpacity = 1.0f,
                logoSpacingDp = 12,
                backgroundOpacity = 0.0f,
                backgroundColorHex = "#000000",
                borderThicknessDp = 0,
                cornerRadiusDp = 0,
                paddingDp = 16,
                showManufacturer = false,
                showDateTime = true,
                showDeviceModel = false,
                showLensInfo = false,
                showExifSpecs = false,
                showResolution = false
            )
            WatermarkStyle.STUDIO_BRAND -> WatermarkConfig(
                isEnabled = isEnabled,
                style = WatermarkStyle.STUDIO_BRAND,
                customText = "STU•D•O PHOTOGRAPHY",
                secondaryText = "FINE ART EDITIONS",
                position = WatermarkPosition.BOTTOM_RIGHT,
                customOffsetX = 0.85f,
                customOffsetY = 0.92f,
                font = WatermarkFont.SERIF,
                isBold = true,
                fontSizeScale = 1.0f,
                textColorHex = "#FFFFFF",
                letterSpacing = 0.05f,
                hasShadow = true,
                logoType = WatermarkLogoType.APP_ICON,
                customLogoUri = null,
                logoScale = 1.0f,
                logoOpacity = 1.0f,
                logoSpacingDp = 12,
                backgroundOpacity = 0.75f,
                backgroundColorHex = "#14151B",
                borderThicknessDp = 1,
                cornerRadiusDp = 12,
                paddingDp = 14,
                showManufacturer = false,
                showDateTime = false,
                showDeviceModel = false,
                showLensInfo = false,
                showExifSpecs = false,
                showResolution = false
            )
            WatermarkStyle.MINIMAL_SIGNATURE -> WatermarkConfig(
                isEnabled = isEnabled,
                style = WatermarkStyle.MINIMAL_SIGNATURE,
                customText = "by StuDO",
                secondaryText = "Photographer Series",
                position = WatermarkPosition.BOTTOM_RIGHT,
                customOffsetX = 0.85f,
                customOffsetY = 0.92f,
                font = WatermarkFont.SERIF,
                isBold = false,
                fontSizeScale = 1.0f,
                textColorHex = "#E0E0E0",
                letterSpacing = 0.04f,
                hasShadow = true,
                logoType = WatermarkLogoType.NONE,
                customLogoUri = null,
                logoScale = 1.0f,
                logoOpacity = 1.0f,
                logoSpacingDp = 12,
                backgroundOpacity = 0.0f,
                backgroundColorHex = "#000000",
                borderThicknessDp = 0,
                cornerRadiusDp = 0,
                paddingDp = 16,
                showManufacturer = false,
                showDateTime = true,
                showDeviceModel = false,
                showLensInfo = false,
                showExifSpecs = false,
                showResolution = false
            )
            WatermarkStyle.METADATA_STRIP -> WatermarkConfig(
                isEnabled = isEnabled,
                style = WatermarkStyle.METADATA_STRIP,
                customText = "STU•D•O",
                secondaryText = "",
                position = WatermarkPosition.FRAME_BORDER,
                customOffsetX = 0.85f,
                customOffsetY = 0.92f,
                font = WatermarkFont.MONOSPACE,
                isBold = true,
                fontSizeScale = 1.0f,
                textColorHex = "#E0E0E0",
                letterSpacing = 0.05f,
                hasShadow = false,
                logoType = WatermarkLogoType.APP_ICON,
                customLogoUri = null,
                logoScale = 1.0f,
                logoOpacity = 1.0f,
                logoSpacingDp = 12,
                backgroundOpacity = 1.0f,
                backgroundColorHex = "#0A0B0E",
                borderThicknessDp = 0,
                cornerRadiusDp = 0,
                paddingDp = 12,
                showManufacturer = false,
                showDateTime = true,
                showDeviceModel = true,
                showLensInfo = true,
                showExifSpecs = true,
                showResolution = false
            )
            WatermarkStyle.CUSTOM -> WatermarkConfig(
                isEnabled = isEnabled,
                style = WatermarkStyle.CUSTOM,
                customText = "Captured with STU•D•O",
                secondaryText = "Custom Studio Layout",
                position = WatermarkPosition.BOTTOM_RIGHT,
                customOffsetX = 0.85f,
                customOffsetY = 0.92f,
                font = WatermarkFont.SANS_SERIF,
                isBold = true,
                fontSizeScale = 1.0f,
                textColorHex = "#FFFFFF",
                letterSpacing = 0.05f,
                hasShadow = true,
                logoType = WatermarkLogoType.APP_ICON,
                customLogoUri = null,
                logoScale = 1.0f,
                logoOpacity = 1.0f,
                logoSpacingDp = 12,
                backgroundOpacity = 0.65f,
                backgroundColorHex = "#14151B",
                borderThicknessDp = 1,
                cornerRadiusDp = 10,
                paddingDp = 14,
                showManufacturer = true,
                showDateTime = true,
                showDeviceModel = true,
                showLensInfo = true,
                showExifSpecs = true,
                showResolution = true
            )
        }
    }

    /**
     * Initializes an isolated configuration instance per template, injecting [initialConfig]
     * strictly into its matching style without contaminating any other template.
     */
    fun createInitialTemplateConfigs(initialConfig: WatermarkConfig): Map<WatermarkStyle, WatermarkConfig> {
        return WatermarkStyle.entries.associateWith { style ->
            if (style == initialConfig.style) {
                initialConfig
            } else {
                defaultConfig(style = style, isEnabled = initialConfig.isEnabled)
            }
        }
    }
}

internal fun capabilitiesForTemplate(style: WatermarkStyle): WatermarkTemplateCapabilities {
    return when (style) {
        WatermarkStyle.LEICA_FRAME -> WatermarkTemplateCapabilities(
            supportsPrimaryText = true,
            primaryTextLabel = "Primary Signature Text",
            primaryTextPlaceholder = "e.g. Shot on STU•D•O",
            canTogglePrimaryText = false,
            supportsSecondaryText = true,
            secondaryTextLabel = "Subtitle / Optical Line",
            secondaryTextPlaceholder = "Blank uses device model when enabled",
            supportsFontFamily = true,
            supportsBoldToggle = true,
            supportsFontSizeScale = true,
            supportsTextColor = true,
            supportsLetterSpacing = true,
            supportsLineSpacing = true,
            supportsTextOpacity = true,
            supportedTextAlignments = emptyList(),
            supportsTextShadow = true,
            supportedLogoTypes = STANDARD_LOGO_TYPES,
            supportedLogoAlignments = listOf(WatermarkLogoAlignment.START, WatermarkLogoAlignment.END),
            supportsLogoScale = true,
            supportsLogoOpacity = true,
            supportsLogoSpacing = true,
            supportedPositions = emptyList(),
            fixedLayoutDescription = "Anchored to Bottom White Frame Border",
            supportsBackgroundColor = true,
            supportsBackgroundOpacity = false,
            supportsBorderThickness = false,
            supportsCornerRadius = false,
            supportsPadding = true,
            supportsMarginScale = false,
            supportsRotation = false,
            supportsDateTime = true,
            supportsManufacturer = false,
            supportsDeviceModel = true,
            supportsLensInfo = true,
            supportsExifSpecs = true,
            supportsResolution = true
        )
        WatermarkStyle.GLASS_FRAME -> WatermarkTemplateCapabilities(
            supportsPrimaryText = false,
            canTogglePrimaryText = false,
            supportsSecondaryText = false,
            supportsFontFamily = false,
            supportsBoldToggle = false,
            supportsFontSizeScale = true,
            supportsTextColor = false,
            supportsLetterSpacing = false,
            supportsLineSpacing = false,
            supportsTextOpacity = true,
            supportedTextAlignments = emptyList(),
            supportsTextShadow = false,
            textSectionHint = "Glass Frame automatically displays genuine Device Manufacturer & Model from capture metadata. Adjust badge scale or opacity below, or toggle metadata fields in Info.",
            supportedLogoTypes = GLASS_LOGO_TYPES,
            supportedLogoAlignments = emptyList(),
            supportsLogoScale = true,
            supportsLogoOpacity = true,
            supportsLogoSpacing = false,
            supportedPositions = GLASS_FRAME_POSITIONS,
            fixedLayoutDescription = null,
            supportsBackgroundColor = false,
            supportsBackgroundOpacity = true,
            supportsBorderThickness = false,
            supportsCornerRadius = false,
            supportsPadding = false,
            supportsMarginScale = true,
            supportsRotation = true,
            supportsDateTime = true,
            supportsManufacturer = true,
            supportsDeviceModel = true,
            supportsLensInfo = true,
            supportsExifSpecs = true,
            supportsResolution = false
        )
        WatermarkStyle.HASSELBLAD -> WatermarkTemplateCapabilities(
            supportsPrimaryText = true,
            primaryTextLabel = "Primary Brand Title",
            primaryTextPlaceholder = "e.g. STU•D•O MASTER",
            canTogglePrimaryText = false,
            supportsSecondaryText = true,
            secondaryTextLabel = "Secondary Film Caption",
            secondaryTextPlaceholder = "e.g. MEDIUM FORMAT SIMULATION",
            supportsFontFamily = true,
            supportsBoldToggle = true,
            supportsFontSizeScale = true,
            supportsTextColor = true,
            supportsLetterSpacing = true,
            supportsLineSpacing = true,
            supportsTextOpacity = true,
            supportedTextAlignments = emptyList(),
            supportsTextShadow = true,
            supportedLogoTypes = STANDARD_LOGO_TYPES,
            supportedLogoAlignments = listOf(WatermarkLogoAlignment.START, WatermarkLogoAlignment.END),
            supportsLogoScale = true,
            supportsLogoOpacity = true,
            supportsLogoSpacing = true,
            supportedPositions = emptyList(),
            fixedLayoutDescription = "Anchored to Bottom Film Border Bar",
            supportsBackgroundColor = true,
            supportsBackgroundOpacity = false,
            supportsBorderThickness = false,
            supportsCornerRadius = false,
            supportsPadding = true,
            supportsMarginScale = false,
            supportsRotation = false,
            supportsDateTime = true,
            supportsManufacturer = false,
            supportsDeviceModel = true,
            supportsLensInfo = true,
            supportsExifSpecs = true,
            supportsResolution = false
        )
        WatermarkStyle.CYBERPUNK -> WatermarkTemplateCapabilities(
            supportsPrimaryText = true,
            primaryTextLabel = "HUD Callsign Text",
            primaryTextPlaceholder = "e.g. SYSTEM // STU•D•O CORE",
            canTogglePrimaryText = false,
            supportsSecondaryText = false,
            supportsFontFamily = true,
            supportsBoldToggle = true,
            supportsFontSizeScale = true,
            supportsTextColor = true,
            supportsLetterSpacing = true,
            supportsLineSpacing = true,
            supportsTextOpacity = true,
            supportedTextAlignments = emptyList(),
            supportsTextShadow = true,
            supportedLogoTypes = emptyList(),
            supportedLogoAlignments = emptyList(),
            supportsLogoScale = false,
            supportsLogoOpacity = false,
            supportsLogoSpacing = false,
            logoEmptyHint = "Cyberpunk HUD uses vector corner brackets and telemetry lines without a brand logo.",
            supportedPositions = emptyList(),
            fixedLayoutDescription = "Anchored Bottom HUD Telemetry Corners",
            supportsBackgroundColor = false,
            supportsBackgroundOpacity = false,
            supportsBorderThickness = false,
            supportsCornerRadius = false,
            supportsPadding = false,
            supportsMarginScale = true,
            supportsRotation = false,
            supportsHudAccentColor = true,
            supportsDateTime = true,
            supportsManufacturer = false,
            supportsDeviceModel = true,
            supportsLensInfo = true,
            supportsExifSpecs = true,
            supportsResolution = false
        )
        WatermarkStyle.RETRO_90S -> WatermarkTemplateCapabilities(
            supportsPrimaryText = true,
            primaryTextLabel = "Optional Stamp Label",
            primaryTextPlaceholder = "e.g. '90s CAM",
            canTogglePrimaryText = true,
            supportsSecondaryText = false,
            supportsFontFamily = true,
            supportsBoldToggle = false,
            supportsFontSizeScale = true,
            supportsTextColor = true,
            supportsLetterSpacing = true,
            supportsLineSpacing = true,
            supportsTextOpacity = true,
            supportedTextAlignments = WatermarkTextAlignment.entries,
            supportsTextShadow = true,
            supportedLogoTypes = emptyList(),
            supportedLogoAlignments = emptyList(),
            supportsLogoScale = false,
            supportsLogoOpacity = false,
            supportsLogoSpacing = false,
            logoEmptyHint = "Retro '90s Stamp renders a classic glowing digital clock stamp without a logo.",
            supportedPositions = FLOATING_OVERLAY_POSITIONS,
            fixedLayoutDescription = null,
            supportsBackgroundColor = false,
            supportsBackgroundOpacity = false,
            supportsBorderThickness = false,
            supportsCornerRadius = false,
            supportsPadding = false,
            supportsMarginScale = true,
            supportsRotation = true,
            supportsDateTime = true,
            supportsManufacturer = false,
            supportsDeviceModel = false,
            supportsLensInfo = false,
            supportsExifSpecs = false,
            supportsResolution = false
        )
        WatermarkStyle.STUDIO_BRAND -> WatermarkTemplateCapabilities(
            supportsPrimaryText = true,
            primaryTextLabel = "Monogram Headline",
            primaryTextPlaceholder = "e.g. STU•D•O PHOTOGRAPHY",
            canTogglePrimaryText = false,
            supportsSecondaryText = true,
            secondaryTextLabel = "Edition Subtitle",
            secondaryTextPlaceholder = "e.g. FINE ART EDITIONS",
            supportsFontFamily = true,
            supportsBoldToggle = true,
            supportsFontSizeScale = true,
            supportsTextColor = true,
            supportsLetterSpacing = true,
            supportsLineSpacing = true,
            supportsTextOpacity = true,
            supportedTextAlignments = WatermarkTextAlignment.entries,
            supportsTextShadow = true,
            supportedLogoTypes = STANDARD_LOGO_TYPES,
            supportedLogoAlignments = WatermarkLogoAlignment.entries,
            supportsLogoScale = true,
            supportsLogoOpacity = true,
            supportsLogoSpacing = true,
            supportedPositions = FLOATING_OVERLAY_POSITIONS,
            fixedLayoutDescription = null,
            supportsBackgroundColor = true,
            supportsBackgroundOpacity = true,
            supportsBorderThickness = true,
            supportsCornerRadius = true,
            supportsPadding = true,
            supportsMarginScale = true,
            supportsRotation = true,
            supportsDateTime = true,
            supportsManufacturer = true,
            supportsDeviceModel = true,
            supportsLensInfo = true,
            supportsExifSpecs = true,
            supportsResolution = true
        )
        WatermarkStyle.MINIMAL_SIGNATURE -> WatermarkTemplateCapabilities(
            supportsPrimaryText = true,
            primaryTextLabel = "Signature Line",
            primaryTextPlaceholder = "e.g. by StuDO",
            canTogglePrimaryText = false,
            supportsSecondaryText = true,
            secondaryTextLabel = "Series Subtitle",
            secondaryTextPlaceholder = "e.g. Photographer Series",
            supportsFontFamily = true,
            supportsBoldToggle = true,
            supportsFontSizeScale = true,
            supportsTextColor = true,
            supportsLetterSpacing = true,
            supportsLineSpacing = true,
            supportsTextOpacity = true,
            supportedTextAlignments = WatermarkTextAlignment.entries,
            supportsTextShadow = true,
            supportedLogoTypes = emptyList(),
            supportedLogoAlignments = emptyList(),
            supportsLogoScale = false,
            supportsLogoOpacity = false,
            supportsLogoSpacing = false,
            logoEmptyHint = "Minimal Signature focuses cleanly on typography and capture year without a logo.",
            supportedPositions = FLOATING_OVERLAY_POSITIONS,
            fixedLayoutDescription = null,
            supportsBackgroundColor = false,
            supportsBackgroundOpacity = false,
            supportsBorderThickness = false,
            supportsCornerRadius = false,
            supportsPadding = false,
            supportsMarginScale = true,
            supportsRotation = true,
            supportsDateTime = true,
            supportsManufacturer = false,
            supportsDeviceModel = false,
            supportsLensInfo = false,
            supportsExifSpecs = false,
            supportsResolution = false
        )
        WatermarkStyle.METADATA_STRIP -> WatermarkTemplateCapabilities(
            supportsPrimaryText = true,
            primaryTextLabel = "Strip Brand Label",
            primaryTextPlaceholder = "e.g. STU•D•O",
            canTogglePrimaryText = false,
            supportsSecondaryText = false,
            supportsFontFamily = true,
            supportsBoldToggle = true,
            supportsFontSizeScale = true,
            supportsTextColor = true,
            supportsLetterSpacing = true,
            supportsLineSpacing = false,
            supportsTextOpacity = true,
            supportedTextAlignments = emptyList(),
            supportsTextShadow = false,
            supportedLogoTypes = STANDARD_LOGO_TYPES,
            supportedLogoAlignments = listOf(WatermarkLogoAlignment.START, WatermarkLogoAlignment.END),
            supportsLogoScale = true,
            supportsLogoOpacity = true,
            supportsLogoSpacing = true,
            supportedPositions = emptyList(),
            fixedLayoutDescription = "Anchored to Bottom Compact Metadata Strip",
            supportsBackgroundColor = true,
            supportsBackgroundOpacity = false,
            supportsBorderThickness = false,
            supportsCornerRadius = false,
            supportsPadding = false,
            supportsMarginScale = false,
            supportsRotation = false,
            supportsDateTime = true,
            supportsManufacturer = false,
            supportsDeviceModel = true,
            supportsLensInfo = true,
            supportsExifSpecs = true,
            supportsResolution = false
        )
        WatermarkStyle.CUSTOM -> WatermarkTemplateCapabilities(
            supportsPrimaryText = true,
            primaryTextLabel = "Primary Signature Text",
            primaryTextPlaceholder = "e.g. Captured with STU•D•O",
            canTogglePrimaryText = false,
            supportsSecondaryText = true,
            secondaryTextLabel = "Secondary Text / Subtitle",
            secondaryTextPlaceholder = "e.g. Custom Studio Layout",
            supportsFontFamily = true,
            supportsBoldToggle = true,
            supportsFontSizeScale = true,
            supportsTextColor = true,
            supportsLetterSpacing = true,
            supportsLineSpacing = true,
            supportsTextOpacity = true,
            supportedTextAlignments = WatermarkTextAlignment.entries,
            supportsTextShadow = true,
            supportedLogoTypes = STANDARD_LOGO_TYPES,
            supportedLogoAlignments = WatermarkLogoAlignment.entries,
            supportsLogoScale = true,
            supportsLogoOpacity = true,
            supportsLogoSpacing = true,
            supportedPositions = FLOATING_OVERLAY_POSITIONS,
            fixedLayoutDescription = null,
            supportsBackgroundColor = true,
            supportsBackgroundOpacity = true,
            supportsBorderThickness = true,
            supportsCornerRadius = true,
            supportsPadding = true,
            supportsMarginScale = true,
            supportsRotation = true,
            supportsDateTime = true,
            supportsManufacturer = true,
            supportsDeviceModel = true,
            supportsLensInfo = true,
            supportsExifSpecs = true,
            supportsResolution = true
        )
    }
}
