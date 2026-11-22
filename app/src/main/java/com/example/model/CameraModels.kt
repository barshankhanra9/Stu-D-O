package com.example.model

import android.graphics.ColorMatrix
import android.net.Uri
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

enum class CameraMode(val displayName: String) {
    PHOTO("PHOTO"),
    PORTRAIT("PORTRAIT"),
    PRO("PRO"),
    VIDEO("VIDEO")
}

enum class PortraitDepthSource(val displayName: String, val badgeLabel: String) {
    HARDWARE_DEPTH("Hardware Depth", "HW DEPTH"),
    AI_SEGMENTATION("AI Portrait Depth", "AI DEPTH"),
    HYBRID("Hybrid HW + AI", "HYBRID")
}

enum class PortraitQualityTier(
    val displayName: String,
    val previewMaxDim: Int,
    val previewMinIntervalNs: Long,
    val enableLivePreviewBokeh: Boolean
) {
    HIGH("High", 480, 75_000_000L, true),
    BALANCED("Balanced", 360, 125_000_000L, true),
    EFFICIENT("Capture Only", 320, 220_000_000L, false)
}

object PortraitApertureFormatter {
    fun blurIntensityToFStop(blurIntensity: Float): Float {
        val clamped = blurIntensity.coerceIn(0f, 1f)
        // Smooth logarithmic aperture scale from f/16.0 (0% blur) down to f/1.4 (100% blur)
        return (16.0 * Math.pow((1.4 / 16.0), clamped.toDouble())).toFloat()
    }

    fun formatFStop(blurIntensity: Float): String {
        val f = blurIntensityToFStop(blurIntensity)
        return if (f >= 10f) {
            String.format(java.util.Locale.US, "f/%.0f", f)
        } else {
            String.format(java.util.Locale.US, "f/%.1f", f)
        }
    }
}

@Immutable
data class PostCapturePortraitTarget(
    val galleryUri: Uri,
    val originalId: String,
    val originalFilePath: String,
    val depthMapFilePath: String,
    val alphaMatteFilePath: String,
    val width: Int,
    val height: Int,
    val blurIntensity: Float,
    val focusX: Float,
    val focusY: Float,
    val depthSource: PortraitDepthSource,
    val watermarkConfig: WatermarkConfig,
    val metadata: com.example.util.RealCameraMetadata = com.example.util.RealCameraMetadata()
)

enum class ActiveCameraPanel {
    NONE,
    FILTERS,
    QUICK_SETTINGS,
    VIDEO_SETTINGS
}

enum class CaptureAspectRatio(
    val displayName: String,
    val cameraXRatio: Int,
    val widthRatio: Float,
    val heightRatio: Float
) {
    RATIO_4_3("4:3", AspectRatio.RATIO_4_3, 3f, 4f),
    RATIO_16_9("16:9", AspectRatio.RATIO_16_9, 9f, 16f),
    RATIO_1_1("1:1", AspectRatio.RATIO_4_3, 1f, 1f),
    RATIO_FULL("FULL", AspectRatio.RATIO_16_9, 0f, 0f)
}

enum class FlashMode(val displayName: String) {
    AUTO("Auto"),
    ON("On"),
    OFF("Off"),
    TORCH("Torch");

    fun toCameraXFlashMode(): Int {
        return when (this) {
            AUTO -> ImageCapture.FLASH_MODE_AUTO
            ON -> ImageCapture.FLASH_MODE_ON
            OFF -> ImageCapture.FLASH_MODE_OFF
            TORCH -> ImageCapture.FLASH_MODE_OFF
        }
    }
}

enum class ScreenFlashMode(val displayName: String) {
    OFF("Off"),
    AUTO("Auto"),
    ON("On")
}

enum class GridOption(val displayName: String, val shortLabel: String) {
    OFF("Off", "OFF"),
    THIRDS("Rule of Thirds", "3×3"),
    FOUR_BY_FOUR("4×4 Grid", "4×4"),
    DIAGONAL("Diagonal + Cross", "Diag"),
    GOLDEN_RATIO("Golden Ratio", "Φ"),
    GOLDEN_SPIRAL("Fibonacci Spiral", "Spiral"),
    SQUARE("Square Frame", "1:1"),
    CENTER_CROSS("Center Reticle", "Cross"),
    CINEMA_239("2.39:1 Cinema", "2.39:1")
}

enum class CaptureTimer(val displayName: String, val seconds: Int) {
    OFF("Off", 0),
    SEC_3("3s", 3),
    SEC_5("5s", 5),
    SEC_10("10s", 10),
    CUSTOM("Custom", 15);

    fun effectiveSeconds(customSeconds: Int): Int {
        return if (this == CUSTOM) {
            customSeconds.coerceIn(MIN_CUSTOM_SECONDS, MAX_CUSTOM_SECONDS)
        } else {
            seconds
        }
    }

    companion object {
        const val MIN_CUSTOM_SECONDS = 1
        const val MAX_CUSTOM_SECONDS = 300
        const val DEFAULT_CUSTOM_SECONDS = 15

        fun formatDuration(seconds: Int): String {
            val clamped = seconds.coerceIn(MIN_CUSTOM_SECONDS, MAX_CUSTOM_SECONDS)
            return when {
                clamped >= 60 && clamped % 60 == 0 -> "${clamped / 60}m"
                clamped >= 60 -> "${clamped / 60}m${clamped % 60}s"
                else -> "${clamped}s"
            }
        }
    }
}

enum class CameraFilterCategory(val title: String) {
    ALL("All"),
    FILM("Film"),
    CINEMA("Cinema"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
    MONO("Mono")
}

@Immutable
data class LiveFilterGradingSpec(
    val contrast: Float = 0f,
    val shadowLift: Float = 0f,
    val highlightRolloff: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val isMonochrome: Boolean = false,
    val monoR: Float = 0.299f,
    val monoG: Float = 0.587f,
    val monoB: Float = 0.114f,
    val splitShadowR: Float = 0f,
    val splitShadowG: Float = 0f,
    val splitShadowB: Float = 0f,
    val splitHighlightR: Float = 0f,
    val splitHighlightG: Float = 0f,
    val splitHighlightB: Float = 0f,
    val vignetteStrength: Float = 0f
) {
    fun buildColorMatrix(): ColorMatrix {
        val cm = ColorMatrix()

        // 1. Monochrome or saturation + moderate vibrance approximation for matrix fallback paths
        if (isMonochrome) {
            cm.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        monoR, monoG, monoB, 0f, 0f,
                        monoR, monoG, monoB, 0f, 0f,
                        monoR, monoG, monoB, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        } else {
            val effSat = (1f + saturation + vibrance * 0.48f).coerceIn(0f, 1.85f)
            if (kotlin.math.abs(effSat - 1f) > 0.005f) {
                val satCm = ColorMatrix().apply { setSaturation(effSat) }
                cm.postConcat(satCm)
            }
        }

        // 2. White Balance (temperature + tint) + subtle split tone bias
        val rGain = (1f + temperature * 0.18f + tint * 0.08f + splitHighlightR * 0.25f).coerceIn(0.6f, 1.45f)
        val gGain = (1f + temperature * 0.03f - tint * 0.14f + splitHighlightG * 0.25f).coerceIn(0.6f, 1.45f)
        val bGain = (1f - temperature * 0.20f + tint * 0.08f + splitHighlightB * 0.25f).coerceIn(0.6f, 1.45f)

        // 3. Contrast & shadow matte lift
        val c = (1f + contrast * 0.52f).coerceIn(0.55f, 1.65f)
        val lift = shadowLift * 255f * 0.65f
        val offset = (1f - c) * 128f + lift

        val rOff = offset + splitShadowR * 36f
        val gOff = offset + splitShadowG * 36f
        val bOff = offset + splitShadowB * 36f

        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    rGain * c, 0f, 0f, 0f, rOff,
                    0f, gGain * c, 0f, 0f, gOff,
                    0f, 0f, bGain * c, 0f, bOff,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        return cm
    }
}

enum class CameraFilter(
    val displayName: String,
    val subtitle: String,
    val category: CameraFilterCategory,
    val tintColor: Color,
    val secondaryTintColor: Color,
    val gradingSpec: LiveFilterGradingSpec
) {
    NATURAL(
        displayName = "Natural",
        subtitle = "Clean True Tone",
        category = CameraFilterCategory.ALL,
        tintColor = Color(0xFFE0E0E0),
        secondaryTintColor = Color(0xFFB0BEC5),
        gradingSpec = LiveFilterGradingSpec()
    ),
    LEICA_VIVID(
        displayName = "Vivid Pro",
        subtitle = "Crisp Micro-Contrast",
        category = CameraFilterCategory.PORTRAIT,
        tintColor = Color(0xFFFF7043),
        secondaryTintColor = Color(0xFF42A5F5),
        gradingSpec = LiveFilterGradingSpec(
            contrast = 0.20f,
            highlightRolloff = 0.10f,
            saturation = 0.10f,
            vibrance = 0.28f,
            temperature = 0.04f,
            splitShadowR = -0.02f,
            splitShadowG = 0.01f,
            splitShadowB = 0.04f,
            splitHighlightR = 0.04f,
            splitHighlightG = 0.02f,
            vignetteStrength = 0.10f
        )
    ),
    KODAK_PORTRA(
        displayName = "Portra 400",
        subtitle = "Natural Skin & Soft Tone",
        category = CameraFilterCategory.PORTRAIT,
        tintColor = Color(0xFFFFCC80),
        secondaryTintColor = Color(0xFF80CBC4),
        gradingSpec = LiveFilterGradingSpec(
            contrast = 0.08f,
            shadowLift = 0.03f,
            highlightRolloff = 0.18f,
            saturation = -0.05f,
            vibrance = 0.20f,
            temperature = 0.12f,
            tint = 0.04f,
            splitShadowR = -0.02f,
            splitShadowG = 0.03f,
            splitShadowB = 0.04f,
            splitHighlightR = 0.08f,
            splitHighlightG = 0.05f,
            splitHighlightB = -0.03f,
            vignetteStrength = 0.08f
        )
    ),
    KODAK_GOLD(
        displayName = "Gold 200",
        subtitle = "Warm 35mm Sunlit Film",
        category = CameraFilterCategory.FILM,
        tintColor = Color(0xFFFFB300),
        secondaryTintColor = Color(0xFFFF7043),
        gradingSpec = LiveFilterGradingSpec(
            contrast = 0.14f,
            shadowLift = 0.02f,
            highlightRolloff = 0.12f,
            saturation = 0.08f,
            vibrance = 0.22f,
            temperature = 0.22f,
            splitShadowR = 0.04f,
            splitShadowG = 0.02f,
            splitShadowB = -0.05f,
            splitHighlightR = 0.11f,
            splitHighlightG = 0.06f,
            splitHighlightB = -0.08f,
            vignetteStrength = 0.12f
        )
    ),
    FUJI_CLASSIC(
        displayName = "Chrome",
        subtitle = "Editorial Muted Steel",
        category = CameraFilterCategory.FILM,
        tintColor = Color(0xFF80CBC4),
        secondaryTintColor = Color(0xFF78909C),
        gradingSpec = LiveFilterGradingSpec(
            contrast = 0.22f,
            shadowLift = 0.03f,
            highlightRolloff = 0.16f,
            saturation = -0.20f,
            vibrance = 0.12f,
            temperature = -0.06f,
            tint = -0.04f,
            splitShadowR = -0.04f,
            splitShadowG = 0.03f,
            splitShadowB = 0.06f,
            splitHighlightR = 0.04f,
            splitHighlightG = 0.03f,
            splitHighlightB = -0.02f,
            vignetteStrength = 0.14f
        )
    ),
    FUJI_VELVIA(
        displayName = "Velvia 50",
        subtitle = "Deep Scenic Chromatic",
        category = CameraFilterCategory.LANDSCAPE,
        tintColor = Color(0xFF26A69A),
        secondaryTintColor = Color(0xFF29B6F6),
        gradingSpec = LiveFilterGradingSpec(
            contrast = 0.26f,
            highlightRolloff = 0.10f,
            saturation = 0.18f,
            vibrance = 0.36f,
            tint = 0.04f,
            splitShadowR = -0.03f,
            splitShadowB = 0.06f,
            splitHighlightR = 0.05f,
            splitHighlightG = 0.03f,
            vignetteStrength = 0.12f
        )
    ),
    CINESTILL_800T(
        displayName = "800T Night",
        subtitle = "Tungsten & Neon Cyan",
        category = CameraFilterCategory.CINEMA,
        tintColor = Color(0xFF26C6DA),
        secondaryTintColor = Color(0xFFFF8A65),
        gradingSpec = LiveFilterGradingSpec(
            contrast = 0.24f,
            shadowLift = 0.02f,
            highlightRolloff = 0.14f,
            saturation = 0.04f,
            vibrance = 0.22f,
            temperature = -0.24f,
            tint = -0.06f,
            splitShadowR = -0.07f,
            splitShadowG = 0.04f,
            splitShadowB = 0.12f,
            splitHighlightR = 0.10f,
            splitHighlightG = 0.03f,
            splitHighlightB = -0.05f,
            vignetteStrength = 0.22f
        )
    ),
    CINEMATIC(
        displayName = "Cinematic",
        subtitle = "Teal & Orange Grade",
        category = CameraFilterCategory.CINEMA,
        tintColor = Color(0xFFFF7043),
        secondaryTintColor = Color(0xFF00897B),
        gradingSpec = LiveFilterGradingSpec(
            contrast = 0.28f,
            highlightRolloff = 0.14f,
            saturation = 0.04f,
            vibrance = 0.24f,
            temperature = 0.08f,
            splitShadowR = -0.08f,
            splitShadowG = 0.04f,
            splitShadowB = 0.11f,
            splitHighlightR = 0.12f,
            splitHighlightG = 0.04f,
            splitHighlightB = -0.08f,
            vignetteStrength = 0.18f
        )
    ),
    VINTAGE(
        displayName = "Vintage",
        subtitle = "Kodachrome Amber",
        category = CameraFilterCategory.FILM,
        tintColor = Color(0xFFFFB74D),
        secondaryTintColor = Color(0xFF8D6E63),
        gradingSpec = LiveFilterGradingSpec(
            contrast = 0.12f,
            shadowLift = 0.07f,
            highlightRolloff = 0.18f,
            saturation = -0.10f,
            temperature = 0.20f,
            tint = 0.05f,
            splitShadowR = 0.05f,
            splitShadowG = 0.02f,
            splitShadowB = -0.04f,
            splitHighlightR = 0.11f,
            splitHighlightG = 0.07f,
            splitHighlightB = -0.07f,
            vignetteStrength = 0.20f
        )
    ),
    MOODY(
        displayName = "Moody",
        subtitle = "Deep Slate Shadows",
        category = CameraFilterCategory.CINEMA,
        tintColor = Color(0xFF64B5F6),
        secondaryTintColor = Color(0xFF263238),
        gradingSpec = LiveFilterGradingSpec(
            contrast = 0.30f,
            shadowLift = 0.03f,
            highlightRolloff = 0.16f,
            saturation = -0.24f,
            temperature = -0.14f,
            splitShadowR = -0.06f,
            splitShadowG = -0.01f,
            splitShadowB = 0.10f,
            splitHighlightR = 0.04f,
            splitHighlightG = 0.03f,
            splitHighlightB = 0.01f,
            vignetteStrength = 0.26f
        )
    ),
    GOLDEN_HOUR(
        displayName = "Golden Hr",
        subtitle = "Sunlit Honey & Glow",
        category = CameraFilterCategory.LANDSCAPE,
        tintColor = Color(0xFFFFCA28),
        secondaryTintColor = Color(0xFFFF8F00),
        gradingSpec = LiveFilterGradingSpec(
            contrast = 0.12f,
            highlightRolloff = 0.15f,
            saturation = 0.10f,
            vibrance = 0.25f,
            temperature = 0.26f,
            tint = 0.04f,
            splitShadowR = 0.04f,
            splitShadowG = 0.01f,
            splitShadowB = -0.04f,
            splitHighlightR = 0.13f,
            splitHighlightG = 0.07f,
            splitHighlightB = -0.08f,
            vignetteStrength = 0.10f
        )
    ),
    BW(
        displayName = "Silver B&W",
        subtitle = "High Clarity Mono",
        category = CameraFilterCategory.MONO,
        tintColor = Color(0xFFEEEEEE),
        secondaryTintColor = Color(0xFF757575),
        gradingSpec = LiveFilterGradingSpec(
            contrast = 0.32f,
            highlightRolloff = 0.10f,
            isMonochrome = true,
            monoR = 0.33f,
            monoG = 0.56f,
            monoB = 0.11f,
            vignetteStrength = 0.18f
        )
    ),
    TRI_X_400(
        displayName = "Tri-X 400",
        subtitle = "Street Contrast Noir",
        category = CameraFilterCategory.MONO,
        tintColor = Color(0xFFFAFAFA),
        secondaryTintColor = Color(0xFF212121),
        gradingSpec = LiveFilterGradingSpec(
            contrast = 0.44f,
            highlightRolloff = 0.08f,
            isMonochrome = true,
            monoR = 0.44f,
            monoG = 0.46f,
            monoB = 0.10f,
            vignetteStrength = 0.24f
        )
    ),
    SEPIA_NOIR(
        displayName = "Platinum",
        subtitle = "Warm Archival Silver",
        category = CameraFilterCategory.MONO,
        tintColor = Color(0xFFD7CCC8),
        secondaryTintColor = Color(0xFF5D4037),
        gradingSpec = LiveFilterGradingSpec(
            contrast = 0.22f,
            shadowLift = 0.04f,
            highlightRolloff = 0.14f,
            isMonochrome = true,
            monoR = 0.31f,
            monoG = 0.57f,
            monoB = 0.12f,
            splitShadowR = 0.04f,
            splitShadowG = 0.02f,
            splitShadowB = -0.02f,
            splitHighlightR = 0.07f,
            splitHighlightG = 0.05f,
            splitHighlightB = 0.01f,
            vignetteStrength = 0.20f
        )
    );

    val matrixArray: FloatArray?
        get() = if (this == NATURAL) null else gradingSpec.buildColorMatrix().array

    fun toAndroidColorMatrix(): ColorMatrix? {
        if (this == NATURAL) return null
        return gradingSpec.buildColorMatrix()
    }
}

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val displayName: String,
    val dateAdded: Long,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long = 0L
)

@Immutable
data class BinMediaItem(
    val id: String,
    val originalUriString: String,
    val binFilePath: String,
    val displayName: String,
    val mimeType: String,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long = 0L,
    val dateAddedSeconds: Long = 0L,
    val deletedAtMillis: Long = 0L,
    val relativePath: String = if (isVideo) "Movies/StuDO" else "Pictures/StuDO"
) {
    val binUri: Uri
        get() = Uri.fromFile(java.io.File(binFilePath))
}

data class PhotoDetails(
    val uri: Uri,
    val title: String,
    val dateTaken: String,
    val dimensions: String,
    val fileSize: String,
    val path: String,
    val mimeType: String,
    val isVideo: Boolean = false,
    val duration: String? = null
)

enum class PhotoResolution(val displayName: String, val description: String) {
    ULTRA_RES("Ultra High Resolution", "Hardware unbinned sensor mode when advertised by camera HAL"),
    MAX("Maximum Quality", "Highest genuine resolution advertised for this sensor"),
    HIGH_12MP("High Quality", "Balanced sharpness and responsive capture"),
    STANDARD_8MP("Standard (Efficient)", "Faster processing and compact storage")
}

@Immutable
data class DynamicResolutionOption(
    val resolution: PhotoResolution,
    val title: String,
    val size: android.util.Size,
    val width: Int = size.width,
    val height: Int = size.height,
    val megapixels: Double = (size.width.toDouble() * size.height.toDouble()) / 1_000_000.0,
    val megapixelsText: String = String.format(java.util.Locale.US, "%.2f MP", (size.width.toDouble() * size.height.toDouble()) / 1_000_000.0),
    val dimensionsText: String = "${size.width} × ${size.height}",
    val isStallStream: Boolean = false,
    val description: String
)

enum class CapturePriority(val displayName: String, val shortLabel: String, val description: String) {
    QUALITY("Maximize Quality (Default)", "Quality", "Multi-frame ISP noise reduction, edge refinement & optimal dynamic range"),
    LATENCY("Zero Shutter Lag (Speed)", "Speed", "Minimum capture latency for action, movement and rapid shots")
}

enum class RawCaptureMode(val displayName: String, val description: String) {
    OFF("Off", "Capture standard JPEG only"),
    JPEG_AND_DNG("JPEG + RAW (DNG)", "Save both JPEG photo and full RAW DNG"),
    DNG_ONLY("RAW (DNG) Only", "Save authentic DNG digital negative only")
}

enum class WatermarkStyle(
    val displayName: String,
    val subtitle: String,
    val tag: String
) {
    LEICA_FRAME("Leica White Border", "White border with StuDO emblem & clean metadata", "LEICA"),
    GLASS_FRAME("Premium Glass Frame", "Translucent glass frame, depth blur & manufacturer logo bar", "GLASS"),
    HASSELBLAD("Studio Film Frame", "Dark frame with camera badge & orange timestamp", "HASSEL"),
    CYBERPUNK("Cyberpunk HUD", "Neon coordinates, digital grid & tech tag", "CYBER"),
    RETRO_90S("Retro '90s Stamp", "Classic glowing amber digital clock stamp", "RETRO"),
    STUDIO_BRAND("STU•D•O Monogram", "Clean modern studio typography & signature", "STUDIO"),
    MINIMAL_SIGNATURE("Minimal Signature", "Understated photographer signature & date", "SIGNATURE"),
    METADATA_STRIP("Camera Metadata Strip", "Film strip border with full optical specs", "STRIP"),
    CUSTOM("Custom Studio Design", "User-customised layout and styling", "CUSTOM")
}

enum class WatermarkPosition(val displayName: String) {
    BOTTOM_RIGHT("Bottom Right"),
    BOTTOM_LEFT("Bottom Left"),
    BOTTOM_CENTER("Bottom Center"),
    TOP_RIGHT("Top Right"),
    TOP_LEFT("Top Left"),
    FRAME_BORDER("Bottom Frame Bar"),
    CUSTOM_COORDS("Custom Placement")
}

enum class WatermarkFont(val displayName: String) {
    SANS_SERIF("Sans-Serif"),
    SERIF("Classic Serif"),
    MONOSPACE("Tech Monospace")
}

enum class WatermarkLogoType(val displayName: String) {
    DEVICE_MANUFACTURER("Device OEM Logo"),
    APP_ICON("StuDO Logo"),
    CUSTOM("Custom Upload"),
    NONE("No Logo")
}

enum class WatermarkTextAlignment(val displayName: String) {
    START("Left"),
    CENTER("Center"),
    END("Right")
}

enum class WatermarkLogoAlignment(val displayName: String) {
    START("Left"),
    TOP("Top"),
    END("Right")
}

@Immutable
data class WatermarkConfig(
    val isEnabled: Boolean = false,
    val style: WatermarkStyle = WatermarkStyle.LEICA_FRAME,
    val customText: String = "Shot on STU•D•O",
    val secondaryText: String = "OPTICAL SYSTEM · ULTRA HD",
    val position: WatermarkPosition = WatermarkPosition.FRAME_BORDER,
    val customOffsetX: Float = 0.85f,
    val customOffsetY: Float = 0.92f,
    // Typography
    val font: WatermarkFont = WatermarkFont.SANS_SERIF,
    val isBold: Boolean = true,
    val fontSizeScale: Float = 1.0f,
    val textColorHex: String = "#FFFFFF",
    val letterSpacing: Float = 0.05f,
    val hasShadow: Boolean = true,
    val textOpacity: Float = 1.0f,
    val lineSpacing: Float = 1.15f,
    val textAlignment: WatermarkTextAlignment = WatermarkTextAlignment.START,
    // Logo
    val logoType: WatermarkLogoType = WatermarkLogoType.APP_ICON,
    val customLogoUri: String? = null,
    val logoScale: Float = 1.0f,
    val logoOpacity: Float = 1.0f,
    val logoSpacingDp: Int = 12,
    val logoAlignment: WatermarkLogoAlignment = WatermarkLogoAlignment.START,
    // Appearance & Background
    val backgroundOpacity: Float = 1.0f,
    val backgroundColorHex: String = "#FFFFFF",
    val borderThicknessDp: Int = 0,
    val cornerRadiusDp: Int = 0,
    val paddingDp: Int = 16,
    val rotationDegrees: Float = 0.0f,
    val marginScale: Float = 1.0f,
    // Information displayed
    val showManufacturer: Boolean = true,
    val showDateTime: Boolean = true,
    val showDeviceModel: Boolean = true,
    val showLensInfo: Boolean = true,
    val showExifSpecs: Boolean = true,
    val showResolution: Boolean = false
)

@Immutable
data class WatermarkPreset(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val config: WatermarkConfig
)

enum class WatermarkCacheLimit(val displayName: String, val limitBytes: Long) {
    MB_250("250 MB", 250L * 1024L * 1024L),
    MB_500("500 MB", 500L * 1024L * 1024L),
    GB_1("1 GB", 1L * 1024L * 1024L * 1024L),
    GB_2("2 GB", 2L * 1024L * 1024L * 1024L),
    GB_5("5 GB", 5L * 1024L * 1024L * 1024L),
    GB_10("10 GB", 10L * 1024L * 1024L * 1024L)
}

@Immutable
data class PrivateOriginalStorageStats(
    val maxBytes: Long = WatermarkCacheLimit.GB_1.limitBytes,
    val usedBytes: Long = 0L,
    val originalCount: Int = 0
)

@Immutable
data class PostCaptureWatermarkTarget(
    val galleryUri: Uri,
    val originalId: String,
    val originalFilePath: String,
    val width: Int,
    val height: Int,
    val associatedConfig: WatermarkConfig,
    val iso: String? = null,
    val shutterSpeed: String? = null,
    val aperture: String? = null,
    val focalLength: String? = null,
    val dateTimeFormatted: String? = null,
    val deviceModel: String? = null,
    val lensInfo: String? = null,
    val resolutionText: String? = null,
    val manufacturer: String? = null,
    val modelOnly: String? = null
)

@Immutable
data class EditorWatermarkTarget(
    val sessionId: String,
    val sourceUri: Uri,
    val previewFilePath: String,
    val width: Int,
    val height: Int,
    val initialConfig: WatermarkConfig,
    val isImportedPhoto: Boolean = false,
    val metadata: com.example.util.RealCameraMetadata = com.example.util.RealCameraMetadata()
)

@Immutable
data class PendingEditorWatermarkResult(
    val eventId: Long,
    val config: WatermarkConfig
)

enum class VideoQualityOption(val displayName: String, val shortLabel: String) {
    QUALITY_2160P("4K UHD (2160p)", "4K"),
    QUALITY_1080P("FHD (1080p)", "1080p"),
    QUALITY_720P("HD (720p)", "720p"),
    QUALITY_480P("SD (480p)", "480p")
}

enum class VideoFpsOption(val fps: Int, val displayName: String, val shortLabel: String) {
    FPS_AUTO(0, "Auto FPS", "AUTO"),
    FPS_24(24, "24 FPS (Cinema)", "24"),
    FPS_30(30, "30 FPS", "30"),
    FPS_60(60, "60 FPS", "60"),
    FPS_120(120, "120 FPS (Slow-Mo)", "120")
}

enum class VideoCodecOption(val displayName: String, val shortLabel: String, val mimeType: String) {
    H264("H.264 / AVC", "H.264", "video/avc"),
    HEVC("H.265 / HEVC", "H.265", "video/hevc")
}

enum class VolumeKeyAction(val displayName: String) {
    SHUTTER("Shutter (Photo / Record)"),
    ZOOM("Zoom In / Out"),
    SYSTEM("System Volume")
}

enum class ProControlTab {
    NONE,
    ISO,
    SHUTTER,
    EV,
    FOCUS,
    WB
}

enum class ProWhitebalanceMode(val displayName: String, val camera2Mode: Int, val kelvinText: String) {
    AUTO("Auto", 1, "Auto"),
    DAYLIGHT("Daylight", 5, "5500K"),
    CLOUDY("Cloudy", 6, "6500K"),
    SHADE("Shade", 8, "7500K"),
    INCANDESCENT("Tungsten", 2, "3000K"),
    FLUORESCENT("Fluorescent", 3, "4000K")
}

@Immutable
data class ProCameraSettings(
    val iso: Int? = null,
    val shutterSpeedNanos: Long? = null,
    val evCompensation: Int = 0,
    val focusDistance: Float? = null,
    val whiteBalance: ProWhitebalanceMode = ProWhitebalanceMode.AUTO,
    val isManualFocus: Boolean = false
) {
    val isAuto: Boolean
        get() = iso == null && shutterSpeedNanos == null && evCompensation == 0 && !isManualFocus && whiteBalance == ProWhitebalanceMode.AUTO

    fun formatShutter(): String {
        val s = shutterSpeedNanos ?: return "AUTO"
        return if (s < 1_000_000_000L) {
            val denom = Math.round(1_000_000_000.0 / s)
            "1/${denom}s"
        } else {
            String.format(java.util.Locale.US, "%.1fs", s / 1_000_000_000.0)
        }
    }
}

@Immutable
data class ProCameraCapabilities(
    val isProSupported: Boolean = true,
    val minIso: Int = 100,
    val maxIso: Int = 3200,
    val supportedIsos: List<Int> = listOf(100, 200, 400, 800, 1600, 3200),
    val minShutterNanos: Long = 100_000L,
    val maxShutterNanos: Long = 1_000_000_000L,
    val supportedShutterSpeeds: List<Long> = listOf(
        1_000_000_000L / 1000,
        1_000_000_000L / 500,
        1_000_000_000L / 250,
        1_000_000_000L / 125,
        1_000_000_000L / 60,
        1_000_000_000L / 30,
        1_000_000_000L / 15,
        1_000_000_000L / 8,
        1_000_000_000L / 4,
        1_000_000_000L / 2,
        1_000_000_000L
    ),
    val minFocusDistance: Float = 0f,
    val maxFocusDistance: Float = 10f,
    val isManualFocusSupported: Boolean = true,
    val minEv: Int = -4,
    val maxEv: Int = 4,
    val evStep: Float = 0.5f,
    val supportedAwbModes: List<ProWhitebalanceMode> = ProWhitebalanceMode.values().toList()
)

@Immutable
data class CameraUiState(
    val isCameraReady: Boolean = false,
    val hasPermission: Boolean = false,
    val permissionPermanentlyDenied: Boolean = false,
    val cameraMode: CameraMode = CameraMode.PHOTO,
    val aspectRatio: CaptureAspectRatio = CaptureAspectRatio.RATIO_4_3,
    val videoAspectRatio: CaptureAspectRatio = CaptureAspectRatio.RATIO_16_9,
    val supportedVideoAspectRatios: List<CaptureAspectRatio> = listOf(CaptureAspectRatio.RATIO_16_9),
    val fullScreenAspectRatio: Float = 9f / 20f,
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val hasFrontCamera: Boolean = true,
    val hasBackCamera: Boolean = true,
    val flashMode: FlashMode = FlashMode.OFF,
    val isFlashAvailable: Boolean = false,
    val isTorchActive: Boolean = false,
    val isScreenFlashAvailable: Boolean = false,
    val screenFlashMode: ScreenFlashMode = ScreenFlashMode.OFF,
    val isScreenFlashOverlayVisible: Boolean = false,
    val isScreenFlashLowLightDetected: Boolean = false,
    val zoomRatio: Float = 1.0f,
    val minZoomRatio: Float = 1.0f,
    val maxZoomRatio: Float = 8.0f,
    val isCapturing: Boolean = false,
    val isSavingMedia: Boolean = false,
    val inFlightCapturesCount: Int = 0,
    val isRecordingVideo: Boolean = false,
    val isRecordingPaused: Boolean = false,
    val isAudioEnabled: Boolean = true,
    val isVideoRecordingSupported: Boolean = true,
    val isVideoStabilizationSupported: Boolean = false,
    val isVideoStabilizationEnabled: Boolean = true,
    val recordingDurationSeconds: Long = 0L,
    val videoFps: VideoFpsOption = VideoFpsOption.FPS_AUTO,
    val supportedVideoQualities: List<VideoQualityOption> = listOf(VideoQualityOption.QUALITY_1080P, VideoQualityOption.QUALITY_720P),
    val supportedVideoFps: List<VideoFpsOption> = listOf(VideoFpsOption.FPS_AUTO, VideoFpsOption.FPS_30),
    val supportedFpsPerQuality: Map<VideoQualityOption, List<VideoFpsOption>> = emptyMap(),
    val supportedVideoCodecs: List<VideoCodecOption> = listOf(VideoCodecOption.H264),
    val shutterFlashTrigger: Long = 0L,
    val lastCapturedUri: Uri? = null,
    val focusPoint: Offset? = null,
    val showFocusRing: Boolean = false,
    val isStreaming: Boolean = false,
    val galleryItems: List<MediaItem> = emptyList(),
    val currentGalleryIndex: Int = 0,
    val isGalleryOpen: Boolean = false,
    val isRecycleBinOpen: Boolean = false,
    val binItems: List<BinMediaItem> = emptyList(),
    val binCount: Int = 0,
    val selectedQuickPhotoUri: Uri? = null,
    val userMessage: String? = null,
    // Advanced & Pro features
    val selectedFilter: CameraFilter = CameraFilter.NATURAL,
    val activeCameraPanel: ActiveCameraPanel = ActiveCameraPanel.NONE,
    val isFilterSelectorOpen: Boolean = false,
    val selectedGrid: GridOption = GridOption.OFF,
    val selectedTimer: CaptureTimer = CaptureTimer.OFF,
    val customTimerSeconds: Int = CaptureTimer.DEFAULT_CUSTOM_SECONDS,
    val countdownSecondsLeft: Int? = null,
    val isLevelIndicatorEnabled: Boolean = false,
    val deviceRollAngle: Float = 0f,
    val devicePitchAngle: Float = 0f,
    val isDeviceLevel: Boolean = false,
    val exposureIndex: Int = 0,
    val minExposureIndex: Int = 0,
    val maxExposureIndex: Int = 0,
    val exposureStep: Float = 0f,
    val isExposureSupported: Boolean = false,
    val showExposureSlider: Boolean = false,
    val isHistogramEnabled: Boolean = false,
    val isShutterSoundEnabled: Boolean = true,
    val isQuickSettingsOpen: Boolean = false,
    val isVideoSettingsPanelOpen: Boolean = false,
    val isSettingsOpen: Boolean = false,
    // Persistent Pro Settings
    val photoResolution: PhotoResolution = PhotoResolution.MAX,
    val targetCaptureSize: android.util.Size? = null,
    val actualConfiguredResolution: android.util.Size? = null,
    val availableResolutionOptions: List<DynamicResolutionOption> = emptyList(),
    val activeResolutionOption: DynamicResolutionOption? = null,
    val rawCaptureMode: RawCaptureMode = RawCaptureMode.OFF,
    val isHdrEnabled: Boolean = false,
    val watermarkConfig: WatermarkConfig = WatermarkConfig(),
    val isPostCaptureWatermarkEditingEnabled: Boolean = false,
    val isWatermarkCacheLimitConfigured: Boolean = false,
    val watermarkCacheLimit: WatermarkCacheLimit = WatermarkCacheLimit.GB_1,
    val privateOriginalStorageStats: PrivateOriginalStorageStats = PrivateOriginalStorageStats(),
    val postCaptureWatermarkTarget: PostCaptureWatermarkTarget? = null,
    val isPostCaptureSaving: Boolean = false,
    val watermarkOriginalUnavailableMessage: String? = null,
    val watermarkOriginalUnavailableUri: Uri? = null,
    val galleryMediaRevision: Long = 0L,
    val videoQuality: VideoQualityOption = VideoQualityOption.QUALITY_1080P,
    val videoCodec: VideoCodecOption = VideoCodecOption.H264,
    val isSelfieMirrorEnabled: Boolean = true,
    val isSaveLocationEnabled: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val isHapticFeedbackEnabled: Boolean = true,
    val isKeepScreenAwakeEnabled: Boolean = true,
    val volumeKeyAction: VolumeKeyAction = VolumeKeyAction.SHUTTER,
    val capturePriority: CapturePriority = CapturePriority.QUALITY,
    val standardSensorResolutionText: String = "",
    val standardMaxResolutionSize: android.util.Size? = null,
    // Ultra High Resolution & Diagnostics
    val isUhrSupportedOnCurrentLens: Boolean = false,
    val uhrSize: android.util.Size? = null,
    val uhrMegapixels: Double = 0.0,
    val isRawSupportedOnCurrentLens: Boolean = false,
    val isDiagnosticsDialogOpen: Boolean = false,
    val diagnosticReportText: String = "",
    val viewfinderRebindKey: Long = 0L,
    // OEM Enhancement Architecture
    val oemEnhancementMode: OemEnhancementMode = OemEnhancementMode.AUTOMATIC,
    val activeOemAdapterName: String = "Universal Android Engine",
    val oemEnhancementSummary: String = "Universal Mode Active",
    // Pro Mode Manual Controls
    val proSettings: ProCameraSettings = ProCameraSettings(),
    val proCapabilities: ProCameraCapabilities = ProCameraCapabilities(),
    val activeProTab: ProControlTab = ProControlTab.NONE,
    // Watermark Studio
    val isWatermarkStudioOpen: Boolean = false,
    val watermarkPresets: List<WatermarkPreset> = emptyList(),
    // Professional Photo Studio Editor
    val isPhotoEditorOpen: Boolean = false,
    val editingPhotoUri: Uri? = null,
    val isEditingPhotoImported: Boolean = false,
    val editorWatermarkTarget: EditorWatermarkTarget? = null,
    val pendingEditorWatermarkResult: PendingEditorWatermarkResult? = null,
    // Active camera/lens physical focal length (from Camera2 LENS_INFO_AVAILABLE_FOCAL_LENGTHS / CaptureResult)
    val activeFocalLengthMm: Float? = null,
    val activeFocalLengthText: String? = null,
    // Dedicated Portrait Mode State
    val portraitBlurIntensity: Float = 0.62f,
    val portraitFocusPointNormalized: Offset? = null,
    val portraitDepthSource: PortraitDepthSource = PortraitDepthSource.AI_SEGMENTATION,
    val isPortraitHardwareDepthSupported: Boolean = false,
    val isPortraitSubjectDetected: Boolean = false,
    val portraitSubjectCount: Int = 0,
    val portraitQualityTier: PortraitQualityTier = PortraitQualityTier.BALANCED,
    val postCapturePortraitTarget: PostCapturePortraitTarget? = null,
    val isPortraitEditorOpen: Boolean = false,
    val isPortraitEditorSaving: Boolean = false,
    val portraitSidecarRevision: Long = 0L
) {
    val effectiveTimerSeconds: Int
        get() = selectedTimer.effectiveSeconds(customTimerSeconds)

    val activeTimerLabel: String
        get() = if (selectedTimer == CaptureTimer.CUSTOM) {
            CaptureTimer.formatDuration(customTimerSeconds)
        } else {
            selectedTimer.displayName
        }
}

enum class OemEnhancementMode(val displayName: String, val shortLabel: String, val description: String) {
    AUTOMATIC("Automatic", "AUTO", "Detect and apply verified OEM hardware & ISP enhancements"),
    UNIVERSAL_ONLY("Universal Only", "UNIV", "Strict standard Android Camera2/CameraX implementation")
}

@Immutable
data class DeviceOrientationState(
    val rollAngle: Float = 0f,
    val pitchAngle: Float = 0f,
    val isLevel: Boolean = false
)

@Immutable
data class SettingsUiState(
    val availableResolutionOptions: List<DynamicResolutionOption>,
    val photoResolution: PhotoResolution,
    val isUhrSupportedOnCurrentLens: Boolean,
    val uhrMegapixels: Double,
    val uhrSize: android.util.Size?,
    val standardSensorResolutionText: String,
    val actualConfiguredResolution: android.util.Size?,
    val capturePriority: CapturePriority,
    val isRawSupportedOnCurrentLens: Boolean,
    val rawCaptureMode: RawCaptureMode,
    val isHdrEnabled: Boolean,
    val watermarkConfig: WatermarkConfig,
    val isPostCaptureWatermarkEditingEnabled: Boolean,
    val isWatermarkCacheLimitConfigured: Boolean,
    val watermarkCacheLimit: WatermarkCacheLimit,
    val privateOriginalStorageStats: PrivateOriginalStorageStats,
    val activeOemAdapterName: String,
    val oemEnhancementMode: OemEnhancementMode,
    val supportedVideoQualities: List<VideoQualityOption>,
    val videoQuality: VideoQualityOption,
    val supportedVideoFps: List<VideoFpsOption>,
    val videoFps: VideoFpsOption,
    val isVideoStabilizationSupported: Boolean,
    val isVideoStabilizationEnabled: Boolean,
    val isAudioEnabled: Boolean,
    val supportedVideoCodecs: List<VideoCodecOption> = listOf(VideoCodecOption.H264, VideoCodecOption.HEVC),
    val videoCodec: VideoCodecOption,
    val isSelfieMirrorEnabled: Boolean,
    val isSaveLocationEnabled: Boolean,
    val isKeepScreenAwakeEnabled: Boolean,
    val isHapticFeedbackEnabled: Boolean,
    val volumeKeyAction: VolumeKeyAction,
    val isHistogramEnabled: Boolean,
    val isLevelIndicatorEnabled: Boolean,
    val isShutterSoundEnabled: Boolean,
    val selectedGrid: GridOption,
    val selectedTimer: CaptureTimer,
    val customTimerSeconds: Int = CaptureTimer.DEFAULT_CUSTOM_SECONDS,
    val aspectRatio: CaptureAspectRatio,
    val binCount: Int = 0
)

fun CameraUiState.toSettingsUiState(): SettingsUiState = SettingsUiState(
    availableResolutionOptions = availableResolutionOptions,
    photoResolution = photoResolution,
    isUhrSupportedOnCurrentLens = isUhrSupportedOnCurrentLens,
    uhrMegapixels = uhrMegapixels,
    uhrSize = uhrSize,
    standardSensorResolutionText = standardSensorResolutionText,
    actualConfiguredResolution = actualConfiguredResolution,
    capturePriority = capturePriority,
    isRawSupportedOnCurrentLens = isRawSupportedOnCurrentLens,
    rawCaptureMode = rawCaptureMode,
    isHdrEnabled = isHdrEnabled,
    watermarkConfig = watermarkConfig,
    isPostCaptureWatermarkEditingEnabled = isPostCaptureWatermarkEditingEnabled,
    isWatermarkCacheLimitConfigured = isWatermarkCacheLimitConfigured,
    watermarkCacheLimit = watermarkCacheLimit,
    privateOriginalStorageStats = privateOriginalStorageStats,
    activeOemAdapterName = activeOemAdapterName,
    oemEnhancementMode = oemEnhancementMode,
    supportedVideoQualities = supportedVideoQualities,
    videoQuality = videoQuality,
    supportedVideoFps = supportedVideoFps,
    videoFps = videoFps,
    isVideoStabilizationSupported = isVideoStabilizationSupported,
    isVideoStabilizationEnabled = isVideoStabilizationEnabled,
    isAudioEnabled = isAudioEnabled,
    supportedVideoCodecs = supportedVideoCodecs,
    videoCodec = videoCodec,
    isSelfieMirrorEnabled = isSelfieMirrorEnabled,
    isSaveLocationEnabled = isSaveLocationEnabled,
    isKeepScreenAwakeEnabled = isKeepScreenAwakeEnabled,
    isHapticFeedbackEnabled = isHapticFeedbackEnabled,
    volumeKeyAction = volumeKeyAction,
    isHistogramEnabled = isHistogramEnabled,
    isLevelIndicatorEnabled = isLevelIndicatorEnabled,
    isShutterSoundEnabled = isShutterSoundEnabled,
    selectedGrid = selectedGrid,
    selectedTimer = selectedTimer,
    customTimerSeconds = customTimerSeconds,
    aspectRatio = aspectRatio,
    binCount = binCount
)
