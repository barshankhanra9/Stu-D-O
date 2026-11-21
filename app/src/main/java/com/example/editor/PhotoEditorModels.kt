package com.example.editor

import android.graphics.ColorMatrix
import android.graphics.RectF
import androidx.annotation.FontRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.BrandingWatermark
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.Details
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.HdrStrong
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Loyalty
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vignette
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.example.R
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Grouping for the 17 professional Lightroom-class Tune controls.
 */
enum class AdjustmentCategory(val title: String) {
    ALL("All"),
    LIGHT("Light"),
    COLOR("Color"),
    DETAIL("Detail"),
    EFFECTS("Effects")
}

/**
 * Non-destructive adjustment parameters for professional color, tone, and detail manipulation.
 */
data class PhotoAdjustmentState(
    val exposure: Float = 0f,       // -2.0f .. +2.0f EV stops
    val brightness: Float = 0f,     // -1.0f .. +1.0f midtone lift
    val contrast: Float = 0f,       // -1.0f .. +1.0f S-curve contrast
    val highlights: Float = 0f,     // -1.0f .. +1.0f highlight recovery/boost
    val shadows: Float = 0f,        // -1.0f .. +1.0f shadow lift/deepen
    val whites: Float = 0f,         // -1.0f .. +1.0f independent white point
    val blacks: Float = 0f,         // -1.0f .. +1.0f independent black point
    val temperature: Float = 0f,    // -1.0f .. +1.0f cool blue <-> warm amber
    val tint: Float = 0f,           // -1.0f .. +1.0f green <-> magenta
    val vibrance: Float = 0f,       // -1.0f .. +1.0f smart skin-protected saturation
    val saturation: Float = 0f,     // -1.0f .. +1.0f global chrominance (-1 = mono, +1 = 2x)
    val clarity: Float = 0f,        // -1.0f .. +1.0f midtone local contrast
    val texture: Float = 0f,        // -1.0f .. +1.0f high-frequency micro-detail
    val dehaze: Float = 0f,         // -1.0f .. +1.0f atmospheric veil removal/addition
    val sharpness: Float = 0f,      //  0.0f .. +1.0f edge-aware unsharp mask
    val vignette: Float = 0f,       //  0.0f .. +1.0f radial lens falloff
    val grain: Float = 0f           //  0.0f .. +1.0f realistic film grain
) {
    fun isDefault(): Boolean {
        return exposure == 0f &&
            brightness == 0f &&
            contrast == 0f &&
            highlights == 0f &&
            shadows == 0f &&
            whites == 0f &&
            blacks == 0f &&
            temperature == 0f &&
            tint == 0f &&
            vibrance == 0f &&
            saturation == 0f &&
            clarity == 0f &&
            texture == 0f &&
            dehaze == 0f &&
            sharpness == 0f &&
            vignette == 0f &&
            grain == 0f
    }

    fun getValue(tool: AdjustmentTool): Float = when (tool) {
        AdjustmentTool.EXPOSURE -> exposure
        AdjustmentTool.BRIGHTNESS -> brightness
        AdjustmentTool.CONTRAST -> contrast
        AdjustmentTool.HIGHLIGHTS -> highlights
        AdjustmentTool.SHADOWS -> shadows
        AdjustmentTool.WHITES -> whites
        AdjustmentTool.BLACKS -> blacks
        AdjustmentTool.TEMPERATURE -> temperature
        AdjustmentTool.TINT -> tint
        AdjustmentTool.VIBRANCE -> vibrance
        AdjustmentTool.SATURATION -> saturation
        AdjustmentTool.CLARITY -> clarity
        AdjustmentTool.TEXTURE -> texture
        AdjustmentTool.DEHAZE -> dehaze
        AdjustmentTool.SHARPNESS -> sharpness
        AdjustmentTool.VIGNETTE -> vignette
        AdjustmentTool.GRAIN -> grain
    }

    fun withValue(tool: AdjustmentTool, value: Float): PhotoAdjustmentState {
        val clamped = value.coerceIn(tool.minVal, tool.maxVal)
        return when (tool) {
            AdjustmentTool.EXPOSURE -> copy(exposure = clamped)
            AdjustmentTool.BRIGHTNESS -> copy(brightness = clamped)
            AdjustmentTool.CONTRAST -> copy(contrast = clamped)
            AdjustmentTool.HIGHLIGHTS -> copy(highlights = clamped)
            AdjustmentTool.SHADOWS -> copy(shadows = clamped)
            AdjustmentTool.WHITES -> copy(whites = clamped)
            AdjustmentTool.BLACKS -> copy(blacks = clamped)
            AdjustmentTool.TEMPERATURE -> copy(temperature = clamped)
            AdjustmentTool.TINT -> copy(tint = clamped)
            AdjustmentTool.VIBRANCE -> copy(vibrance = clamped)
            AdjustmentTool.SATURATION -> copy(saturation = clamped)
            AdjustmentTool.CLARITY -> copy(clarity = clamped)
            AdjustmentTool.TEXTURE -> copy(texture = clamped)
            AdjustmentTool.DEHAZE -> copy(dehaze = clamped)
            AdjustmentTool.SHARPNESS -> copy(sharpness = clamped)
            AdjustmentTool.VIGNETTE -> copy(vignette = clamped)
            AdjustmentTool.GRAIN -> copy(grain = clamped)
        }
    }

    fun requiresPixelPipeline(): Boolean = !isDefault()

    /**
     * Lightweight linear ColorMatrix approximation for hardware-only fallback callers.
     */
    fun toColorMatrix(): ColorMatrix {
        val result = ColorMatrix()
        val expFactor = Math.pow(2.0, (exposure * 0.85).toDouble()).toFloat()
        val brightOffset = brightness * 42f
        result.postConcat(
            ColorMatrix(
                floatArrayOf(
                    expFactor, 0f, 0f, 0f, brightOffset,
                    0f, expFactor, 0f, 0f, brightOffset,
                    0f, 0f, expFactor, 0f, brightOffset,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        if (contrast != 0f) {
            val c = (1f + contrast * 0.55f).coerceIn(0.35f, 1.75f)
            val t = (1f - c) * 128f
            result.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        c, 0f, 0f, 0f, t,
                        0f, c, 0f, 0f, t,
                        0f, 0f, c, 0f, t,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        val satScale = (1f + saturation + vibrance * 0.45f).coerceIn(0f, 2.2f)
        if (abs(satScale - 1f) > 0.005f) {
            val satM = ColorMatrix()
            satM.setSaturation(satScale)
            result.postConcat(satM)
        }
        return result
    }

    fun toComposeColorMatrix(): androidx.compose.ui.graphics.ColorMatrix {
        return androidx.compose.ui.graphics.ColorMatrix(toColorMatrix().array)
    }
}

/**
 * Individual adjustment parameters (all 17 professional Tune tools).
 */
enum class AdjustmentTool(
    val title: String,
    val category: AdjustmentCategory,
    val icon: ImageVector,
    val minVal: Float,
    val maxVal: Float,
    val defaultVal: Float
) {
    EXPOSURE("Exposure", AdjustmentCategory.LIGHT, Icons.Default.Brightness6, -2.0f, 2.0f, 0f),
    BRIGHTNESS("Brightness", AdjustmentCategory.LIGHT, Icons.Default.LightMode, -1.0f, 1.0f, 0f),
    CONTRAST("Contrast", AdjustmentCategory.LIGHT, Icons.Default.Contrast, -1.0f, 1.0f, 0f),
    HIGHLIGHTS("Highlights", AdjustmentCategory.LIGHT, Icons.Default.WbSunny, -1.0f, 1.0f, 0f),
    SHADOWS("Shadows", AdjustmentCategory.LIGHT, Icons.Default.DarkMode, -1.0f, 1.0f, 0f),
    WHITES("Whites", AdjustmentCategory.LIGHT, Icons.Default.HdrStrong, -1.0f, 1.0f, 0f),
    BLACKS("Blacks", AdjustmentCategory.LIGHT, Icons.Default.Tonality, -1.0f, 1.0f, 0f),
    TEMPERATURE("Temp", AdjustmentCategory.COLOR, Icons.Default.Thermostat, -1.0f, 1.0f, 0f),
    TINT("Tint", AdjustmentCategory.COLOR, Icons.Default.Palette, -1.0f, 1.0f, 0f),
    VIBRANCE("Vibrance", AdjustmentCategory.COLOR, Icons.Default.Flare, -1.0f, 1.0f, 0f),
    SATURATION("Saturation", AdjustmentCategory.COLOR, Icons.Default.ColorLens, -1.0f, 1.0f, 0f),
    CLARITY("Clarity", AdjustmentCategory.DETAIL, Icons.Default.Details, -1.0f, 1.0f, 0f),
    TEXTURE("Texture", AdjustmentCategory.DETAIL, Icons.Default.Texture, -1.0f, 1.0f, 0f),
    DEHAZE("Dehaze", AdjustmentCategory.DETAIL, Icons.Default.Dehaze, -1.0f, 1.0f, 0f),
    SHARPNESS("Sharpness", AdjustmentCategory.DETAIL, Icons.Default.BlurOn, 0.0f, 1.0f, 0f),
    VIGNETTE("Vignette", AdjustmentCategory.EFFECTS, Icons.Default.Vignette, 0.0f, 1.0f, 0f),
    GRAIN("Grain", AdjustmentCategory.EFFECTS, Icons.Default.Grain, 0.0f, 1.0f, 0f);

    fun formatValue(value: Float): String = when (this) {
        EXPOSURE -> String.format(Locale.US, "%+.2f EV", value)
        SHARPNESS, VIGNETTE, GRAIN -> String.format(Locale.US, "%.0f", value * 100f)
        else -> String.format(Locale.US, "%+.0f", value * 100f)
    }
}

/**
 * 15 curated professional filter categories + ALL.
 */
enum class FilterCategory(val title: String) {
    ALL("All"),
    CINEMATIC("Cinematic"),
    FILM("Film"),
    ANALOG("Analog"),
    VINTAGE("Vintage"),
    MOODY("Moody"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
    STREET("Street"),
    BW("B&W"),
    WARM("Warm"),
    COOL("Cool"),
    EDITORIAL("Editorial"),
    TRAVEL("Travel"),
    MINIMAL("Minimal"),
    DRAMATIC("Dramatic")
}

/**
 * Multi-stage studio grading recipe used identically by live preview and full-res export.
 */
data class FilterGradingSpec(
    val contrast: Float = 0f,               // S-curve contrast (-0.5 .. +0.6)
    val saturation: Float = 0f,             // Saturation delta (-1.0 .. +0.6)
    val vibrance: Float = 0f,               // Smart vibrance delta
    val temperature: Float = 0f,            // Warm/cool shift
    val tint: Float = 0f,                   // Green/magenta shift
    val shadowLift: Float = 0f,             // Matte black floor (0.0 .. 0.14)
    val highlightRolloff: Float = 0f,       // Soft highlight compression (0.0 .. 0.25)
    val splitShadowR: Float = 0f,           // Shadow split-tone R (-1..+1)
    val splitShadowG: Float = 0f,           // Shadow split-tone G (-1..+1)
    val splitShadowB: Float = 0f,           // Shadow split-tone B (-1..+1)
    val splitHighlightR: Float = 0f,        // Highlight split-tone R (-1..+1)
    val splitHighlightG: Float = 0f,        // Highlight split-tone G (-1..+1)
    val splitHighlightB: Float = 0f,        // Highlight split-tone B (-1..+1)
    val isMonochrome: Boolean = false,
    val monoRWeight: Float = 0.299f,
    val monoGWeight: Float = 0.587f,
    val monoBWeight: Float = 0.114f,
    val clarityBoost: Float = 0f,
    val grainBoost: Float = 0f
)

/**
 * Curated collection of studio-grade photographic presets with distinctive color grading,
 * tonal curves, split toning, and film response.
 */
enum class PhotoFilterType(
    val displayName: String,
    val subtitle: String,
    val category: FilterCategory,
    val accentColor: Color,
    val spec: FilterGradingSpec
) {
    ORIGINAL(
        "Natural",
        "Unedited capture",
        FilterCategory.ALL,
        Color(0xFFE4E4E7),
        FilterGradingSpec()
    ),

    // Cinematic
    CINEMATIC_TEAL(
        "Teal & Orange",
        "Cinema blockbuster split tone",
        FilterCategory.CINEMATIC,
        Color(0xFF26C6DA),
        FilterGradingSpec(
            contrast = 0.26f, saturation = 0.08f, vibrance = 0.20f,
            shadowLift = 0.03f, highlightRolloff = 0.12f,
            splitShadowR = -0.10f, splitShadowG = 0.06f, splitShadowB = 0.16f,
            splitHighlightR = 0.16f, splitHighlightG = 0.06f, splitHighlightB = -0.10f
        )
    ),
    ANAMORPHIC_NIGHT(
        "Anamorphic",
        "Deep cyan shadows & crisp highlights",
        FilterCategory.CINEMATIC,
        Color(0xFF4FC3F7),
        FilterGradingSpec(
            contrast = 0.32f, saturation = -0.06f, vibrance = 0.14f, temperature = -0.12f,
            shadowLift = 0.02f, highlightRolloff = 0.14f,
            splitShadowR = -0.08f, splitShadowG = 0.04f, splitShadowB = 0.18f,
            splitHighlightR = 0.08f, splitHighlightG = 0.07f, splitHighlightB = 0.02f
        )
    ),

    // Film
    PORTRA_400(
        "Portra 400",
        "Soft skin tones & luminous warmth",
        FilterCategory.FILM,
        Color(0xFFFFCC80),
        FilterGradingSpec(
            contrast = 0.08f, saturation = -0.04f, vibrance = 0.18f, temperature = 0.12f,
            shadowLift = 0.03f, highlightRolloff = 0.16f,
            splitShadowR = 0.04f, splitShadowG = 0.03f, splitShadowB = 0.01f,
            splitHighlightR = 0.09f, splitHighlightG = 0.05f, splitHighlightB = -0.04f,
            grainBoost = 0.10f
        )
    ),
    KODACHROME_64(
        "Kodachrome",
        "Rich reds & archival slide contrast",
        FilterCategory.FILM,
        Color(0xFFEF5350),
        FilterGradingSpec(
            contrast = 0.28f, saturation = 0.14f, vibrance = 0.16f, temperature = 0.08f,
            highlightRolloff = 0.08f,
            splitShadowR = 0.02f, splitShadowG = -0.02f, splitShadowB = -0.04f,
            splitHighlightR = 0.10f, splitHighlightG = 0.03f, splitHighlightB = -0.05f,
            grainBoost = 0.12f
        )
    ),

    // Analog
    FUJI_SUPERIA(
        "Superia 800",
        "Emerald shadows & nostalgic grain",
        FilterCategory.ANALOG,
        Color(0xFF66BB6A),
        FilterGradingSpec(
            contrast = 0.16f, saturation = 0.05f, tint = -0.08f,
            shadowLift = 0.05f, highlightRolloff = 0.10f,
            splitShadowR = -0.04f, splitShadowG = 0.09f, splitShadowB = 0.05f,
            splitHighlightR = 0.07f, splitHighlightG = 0.04f, splitHighlightB = -0.03f,
            grainBoost = 0.18f
        )
    ),
    CINESTILL_800T(
        "800T Tungsten",
        "Tungsten cyan halation & night glow",
        FilterCategory.ANALOG,
        Color(0xFF4DD0E1),
        FilterGradingSpec(
            contrast = 0.24f, saturation = 0.06f, temperature = -0.22f, tint = 0.05f,
            shadowLift = 0.03f, highlightRolloff = 0.10f,
            splitShadowR = -0.09f, splitShadowG = 0.03f, splitShadowB = 0.14f,
            splitHighlightR = 0.14f, splitHighlightG = 0.04f, splitHighlightB = -0.02f,
            grainBoost = 0.16f
        )
    ),

    // Vintage
    NOSTALGIA_90S(
        "Nostalgia '90s",
        "Faded matte blacks & sunlit amber",
        FilterCategory.VINTAGE,
        Color(0xFFFFB74D),
        FilterGradingSpec(
            contrast = -0.06f, saturation = -0.14f, temperature = 0.18f,
            shadowLift = 0.08f, highlightRolloff = 0.18f,
            splitShadowR = 0.06f, splitShadowG = 0.04f, splitShadowB = -0.02f,
            splitHighlightR = 0.12f, splitHighlightG = 0.08f, splitHighlightB = -0.06f,
            grainBoost = 0.22f
        )
    ),
    SEPIA_ARCHIVE(
        "Archive '74",
        "Aged silver halide & warm paper",
        FilterCategory.VINTAGE,
        Color(0xFFD7CCC8),
        FilterGradingSpec(
            contrast = 0.12f, saturation = -0.58f, temperature = 0.24f,
            shadowLift = 0.06f, highlightRolloff = 0.16f,
            splitShadowR = 0.08f, splitShadowG = 0.04f, splitShadowB = -0.04f,
            splitHighlightR = 0.14f, splitHighlightG = 0.09f, splitHighlightB = -0.05f,
            grainBoost = 0.20f
        )
    ),

    // Moody
    OBSIDIAN_MOOD(
        "Obsidian",
        "Crushed blacks & desaturated slate",
        FilterCategory.MOODY,
        Color(0xFF78909C),
        FilterGradingSpec(
            contrast = 0.34f, saturation = -0.32f, temperature = -0.08f,
            shadowLift = 0.01f, highlightRolloff = 0.15f,
            splitShadowR = -0.04f, splitShadowG = -0.01f, splitShadowB = 0.06f,
            splitHighlightR = -0.02f, splitHighlightG = 0.00f, splitHighlightB = 0.03f
        )
    ),
    FOREST_DUSK(
        "Forest Dusk",
        "Deep pine shadows & subdued earth",
        FilterCategory.MOODY,
        Color(0xFF81C784),
        FilterGradingSpec(
            contrast = 0.22f, saturation = -0.22f, tint = -0.10f,
            shadowLift = 0.04f, highlightRolloff = 0.14f,
            splitShadowR = -0.06f, splitShadowG = 0.07f, splitShadowB = 0.02f,
            splitHighlightR = 0.06f, splitHighlightG = 0.04f, splitHighlightB = -0.04f
        )
    ),

    // Portrait
    VELVET_SKIN(
        "Velvet Skin",
        "Luminous highlights & clean warmth",
        FilterCategory.PORTRAIT,
        Color(0xFFFFAB91),
        FilterGradingSpec(
            contrast = 0.06f, saturation = -0.06f, vibrance = 0.16f, temperature = 0.09f, tint = 0.04f,
            shadowLift = 0.02f, highlightRolloff = 0.14f,
            splitHighlightR = 0.08f, splitHighlightG = 0.04f, splitHighlightB = 0.01f
        )
    ),
    STUDIO_GLOW(
        "Studio Glow",
        "Soft commercial beauty contrast",
        FilterCategory.PORTRAIT,
        Color(0xFFF8BBD0),
        FilterGradingSpec(
            contrast = 0.14f, saturation = 0.02f, vibrance = 0.14f, temperature = 0.05f,
            highlightRolloff = 0.12f,
            splitShadowR = 0.03f, splitShadowG = 0.01f, splitShadowB = 0.03f,
            splitHighlightR = 0.06f, splitHighlightG = 0.04f, splitHighlightB = 0.02f
        )
    ),

    // Landscape
    ALPINE_CRISP(
        "Alpine Crisp",
        "Vibrant foliage & deep azure skies",
        FilterCategory.LANDSCAPE,
        Color(0xFF42A5F5),
        FilterGradingSpec(
            contrast = 0.22f, saturation = 0.10f, vibrance = 0.28f, temperature = -0.04f,
            clarityBoost = 0.25f,
            splitShadowR = -0.03f, splitShadowG = 0.02f, splitShadowB = 0.07f,
            splitHighlightR = 0.04f, splitHighlightG = 0.04f, splitHighlightB = 0.0f
        )
    ),
    VIVID_POP(
        "Vivid Horizon",
        "Punchy landscape color & depth",
        FilterCategory.LANDSCAPE,
        Color(0xFFFFCA28),
        FilterGradingSpec(
            contrast = 0.28f, saturation = 0.22f, vibrance = 0.24f,
            clarityBoost = 0.20f
        )
    ),

    // Street Photography
    GRITTY_35(
        "Street 35",
        "Hard midtone micro-contrast",
        FilterCategory.STREET,
        Color(0xFFB0BEC5),
        FilterGradingSpec(
            contrast = 0.36f, saturation = -0.24f,
            clarityBoost = 0.35f, grainBoost = 0.15f,
            splitShadowR = -0.03f, splitShadowG = 0.0f, splitShadowB = 0.05f
        )
    ),
    NEON_RAIN(
        "Cyber Alley",
        "Electric magenta & cobalt shadows",
        FilterCategory.STREET,
        Color(0xFFCE93D8),
        FilterGradingSpec(
            contrast = 0.30f, saturation = 0.18f, vibrance = 0.22f, tint = 0.14f, temperature = -0.14f,
            splitShadowR = 0.02f, splitShadowG = -0.06f, splitShadowB = 0.16f,
            splitHighlightR = 0.12f, splitHighlightG = -0.02f, splitHighlightB = 0.10f
        )
    ),

    // Black & White
    LEICA_BW(
        "Leica Mono",
        "High-contrast silver gelatin B&W",
        FilterCategory.BW,
        Color(0xFFFAFAFA),
        FilterGradingSpec(
            contrast = 0.42f, isMonochrome = true,
            monoRWeight = 0.36f, monoGWeight = 0.54f, monoBWeight = 0.10f,
            clarityBoost = 0.22f
        )
    ),
    ILFORD_HP5(
        "HP5 Classic",
        "Organic midrange tones & silver grain",
        FilterCategory.BW,
        Color(0xFFBDBDBD),
        FilterGradingSpec(
            contrast = 0.18f, isMonochrome = true,
            shadowLift = 0.04f, highlightRolloff = 0.10f,
            monoRWeight = 0.30f, monoGWeight = 0.58f, monoBWeight = 0.12f,
            grainBoost = 0.22f
        )
    ),

    // Warm
    WARM_FILM(
        "Golden Hour",
        "Sun-drenched amber highlights",
        FilterCategory.WARM,
        Color(0xFFFFA726),
        FilterGradingSpec(
            contrast = 0.14f, saturation = 0.08f, vibrance = 0.18f, temperature = 0.28f,
            highlightRolloff = 0.10f,
            splitHighlightR = 0.14f, splitHighlightG = 0.07f, splitHighlightB = -0.08f
        )
    ),
    SAHARA_OCHRE(
        "Ochre Sun",
        "Warm terracotta & honey tones",
        FilterCategory.WARM,
        Color(0xFFFF8A65),
        FilterGradingSpec(
            contrast = 0.18f, saturation = 0.04f, temperature = 0.22f, tint = 0.06f,
            shadowLift = 0.03f,
            splitShadowR = 0.06f, splitShadowG = 0.01f, splitShadowB = -0.04f,
            splitHighlightR = 0.12f, splitHighlightG = 0.05f, splitHighlightB = -0.07f
        )
    ),

    // Cool
    COOL_CHROME(
        "Cool Chrome",
        "Crisp Arctic blues & platinum",
        FilterCategory.COOL,
        Color(0xFF80DEEA),
        FilterGradingSpec(
            contrast = 0.18f, saturation = -0.06f, vibrance = 0.12f, temperature = -0.26f,
            splitShadowR = -0.06f, splitShadowG = 0.01f, splitShadowB = 0.12f,
            splitHighlightR = -0.02f, splitHighlightG = 0.03f, splitHighlightB = 0.08f
        )
    ),
    MUTED_EARTH(
        "Nordic Mist",
        "Low-contrast Scandinavian cool pastel",
        FilterCategory.COOL,
        Color(0xFF90CAF9),
        FilterGradingSpec(
            contrast = -0.10f, saturation = -0.26f, temperature = -0.16f,
            shadowLift = 0.06f, highlightRolloff = 0.12f,
            splitShadowR = -0.03f, splitShadowG = 0.02f, splitShadowB = 0.08f
        )
    ),

    // Editorial
    MAGAZINE_PRINT(
        "Editorial Print",
        "Clean neutral skin & crisp ink blacks",
        FilterCategory.EDITORIAL,
        Color(0xFFE0E0E0),
        FilterGradingSpec(
            contrast = 0.20f, saturation = -0.12f, vibrance = 0.14f,
            shadowLift = 0.02f, highlightRolloff = 0.12f,
            clarityBoost = 0.14f
        )
    ),
    PARISIAN_MATTE(
        "Atelier",
        "Ivory highlights & refined muted shadow",
        FilterCategory.EDITORIAL,
        Color(0xFFFFE0B2),
        FilterGradingSpec(
            contrast = 0.10f, saturation = -0.16f, temperature = 0.06f,
            shadowLift = 0.05f, highlightRolloff = 0.14f,
            splitShadowR = 0.02f, splitShadowG = 0.01f, splitShadowB = 0.04f,
            splitHighlightR = 0.07f, splitHighlightG = 0.05f, splitHighlightB = 0.01f
        )
    ),

    // Travel
    MEDITERRANEAN(
        "Riviera",
        "Sunlit azure coast & golden stone",
        FilterCategory.TRAVEL,
        Color(0xFF29B6F6),
        FilterGradingSpec(
            contrast = 0.16f, saturation = 0.12f, vibrance = 0.24f, temperature = 0.08f,
            splitShadowR = -0.04f, splitShadowG = 0.04f, splitShadowB = 0.10f,
            splitHighlightR = 0.10f, splitHighlightG = 0.06f, splitHighlightB = -0.04f
        )
    ),
    KYOTO_MIST(
        "Kyoto Dawn",
        "Gentle jade shadows & clean air",
        FilterCategory.TRAVEL,
        Color(0xFF80CBC4),
        FilterGradingSpec(
            contrast = 0.08f, saturation = -0.08f, vibrance = 0.16f, tint = -0.06f,
            shadowLift = 0.04f, highlightRolloff = 0.12f,
            splitShadowR = -0.04f, splitShadowG = 0.06f, splitShadowB = 0.06f
        )
    ),

    // Minimal
    PURE_CLEAN(
        "Pure Minimal",
        "Airy neutrals & soft contrast",
        FilterCategory.MINIMAL,
        Color(0xFFECEFF1),
        FilterGradingSpec(
            contrast = -0.06f, saturation = -0.22f,
            shadowLift = 0.04f, highlightRolloff = 0.10f
        )
    ),

    // Dramatic
    CHIAROSCURO(
        "Chiaroscuro",
        "Sculpted dramatic light & deep shadow",
        FilterCategory.DRAMATIC,
        Color(0xFFFFD54F),
        FilterGradingSpec(
            contrast = 0.44f, saturation = -0.10f, vibrance = 0.18f,
            clarityBoost = 0.34f,
            splitShadowR = -0.04f, splitShadowG = -0.02f, splitShadowB = 0.05f,
            splitHighlightR = 0.08f, splitHighlightG = 0.04f, splitHighlightB = -0.04f
        )
    );

    val matrixArray: FloatArray?
        get() = toColorMatrix()?.array

    fun toColorMatrix(): ColorMatrix? {
        if (this == ORIGINAL) return null
        val cm = ColorMatrix()
        if (spec.isMonochrome) {
            cm.setSaturation(0f)
        } else if (spec.saturation != 0f) {
            cm.setSaturation((1f + spec.saturation).coerceIn(0f, 2f))
        }
        if (spec.contrast != 0f) {
            val c = (1f + spec.contrast * 0.55f).coerceIn(0.4f, 1.7f)
            val t = (1f - c) * 128f
            cm.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        c, 0f, 0f, 0f, t,
                        0f, c, 0f, 0f, t,
                        0f, 0f, c, 0f, t,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        return cm
    }

    fun toComposeColorMatrix(): androidx.compose.ui.graphics.ColorMatrix? {
        val cm = toColorMatrix() ?: return null
        return androidx.compose.ui.graphics.ColorMatrix(cm.array)
    }
}

/**
 * Standard photographic crop aspect ratio options.
 */
enum class CropAspectRatio(val displayName: String, val ratio: Float?) {
    FREE("Free", null),
    ORIGINAL("Original", null),
    SQUARE("1:1", 1.0f),
    RATIO_4_3("4:3", 4f / 3f),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_3_2("3:2", 3f / 2f),
    RATIO_9_16("9:16", 9f / 16f)
}

/**
 * Crop and geometric orientation transformation state.
 */
data class CropState(
    val rotationDegrees: Int = 0,               // 0, 90, 180, 270
    val isFlippedHorizontal: Boolean = false,
    val isFlippedVertical: Boolean = false,
    val cropRect: RectF = RectF(0f, 0f, 1f, 1f), // Normalized (0..1) relative to rotated+flipped bitmap
    val aspectRatio: CropAspectRatio = CropAspectRatio.ORIGINAL
) {
    fun isDefault(): Boolean {
        return rotationDegrees == 0 &&
            !isFlippedHorizontal &&
            !isFlippedVertical &&
            cropRect.left <= 0.001f && cropRect.top <= 0.001f &&
            cropRect.right >= 0.999f && cropRect.bottom >= 0.999f &&
            aspectRatio == CropAspectRatio.ORIGINAL
    }

    fun hasActiveCropRect(): Boolean {
        return cropRect.left > 0.005f ||
            cropRect.top > 0.005f ||
            cropRect.right < 0.995f ||
            cropRect.bottom < 0.995f
    }
}

/**
 * Drawing brush styles.
 */
enum class DrawBrushType(val title: String) {
    PEN("Pen"),
    MARKER("Marker"),
    HIGHLIGHTER("Highlighter"),
    NEON("Neon"),
    CALLIGRAPHY("Chisel")
}

/**
 * Vector drawing path item for freehand pen annotations.
 */
data class DrawPathItem(
    val id: String = UUID.randomUUID().toString(),
    val points: List<Offset>,
    val color: Color,
    val strokeWidth: Float,
    val isEraser: Boolean = false,
    val opacity: Float = 1.0f,
    val brushType: DrawBrushType = DrawBrushType.PEN
)

/**
 * Curated open-source typography collection bundled locally in res/font/.
 */
enum class EditorFontFamily(
    val displayName: String,
    val category: String,
    @param:FontRes val fontResId: Int
) {
    MODERN_SANS("Plus Jakarta", "Modern Sans Serif", R.font.plus_jakarta_sans),
    CLEAN_SANS("Inter UI", "Modern Sans Serif", R.font.inter),
    ELEGANT_SERIF("Playfair", "Elegant Serif", R.font.playfair_display),
    BOLD_DISPLAY("Bebas Neue", "Bold Display", R.font.bebas_neue),
    MINIMAL_GROTESK("Space Grotesk", "Minimal", R.font.space_grotesk),
    HANDWRITTEN("Caveat", "Handwritten", R.font.caveat),
    SCRIPT("Pacifico", "Script", R.font.pacifico),
    VINTAGE("Special Elite", "Vintage", R.font.special_elite),
    CONDENSED("Oswald", "Condensed", R.font.oswald),
    EDITORIAL("Garamond", "Editorial", R.font.cormorant_garamond),
    CINEMATIC("Cinzel", "Cinematic", R.font.cinzel),
    MONOSPACE("JetBrains Mono", "Monospace", R.font.jetbrains_mono);

    fun toComposeFontFamily(): FontFamily = FontFamily(Font(fontResId))
}

enum class EditorTextAlign(val title: String) {
    LEFT("Left"),
    CENTER("Center"),
    RIGHT("Right")
}

enum class TextBackgroundStyle(val title: String) {
    NONE("None"),
    ROUNDED("Rounded"),
    PILL("Pill"),
    OUTLINE("Frame")
}

/**
 * Customizable draggable, rotatable, scalable text overlay item.
 */
data class TextOverlayItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val color: Color = Color.White,
    val textOpacity: Float = 1.0f,
    val fontSize: Float = 24f,
    val fontFamily: EditorFontFamily = EditorFontFamily.MODERN_SANS,
    val fontWeight: Int = 700, // 400 = Regular, 600 = SemiBold, 700 = Bold, 800 = ExtraBold
    val isBold: Boolean = true,
    val isItalic: Boolean = false,
    val textAlign: EditorTextAlign = EditorTextAlign.CENTER,
    val letterSpacing: Float = 0.02f, // em
    val lineSpacing: Float = 1.15f,   // multiplier
    val hasBackground: Boolean = true,
    val backgroundStyle: TextBackgroundStyle = TextBackgroundStyle.ROUNDED,
    val backgroundColor: Color = Color(0xFF0C0E14),
    val backgroundOpacity: Float = 0.68f,
    val hasShadow: Boolean = true,
    val hasOutline: Boolean = false,
    val outlineColor: Color = Color.Black,
    val outlineWidth: Float = 2f,
    val positionPercent: Offset = Offset(0.5f, 0.45f), // Normalized 0..1 center in viewport
    val scale: Float = 1.0f,
    val rotation: Float = 0f
)

/**
 * Badge library categories, styles, and shapes.
 */
enum class BadgeCategory(val title: String) {
    ALL("All"),
    FAVORITES("Saved"),
    PHOTOGRAPHY("Photo"),
    DATES("Dates"),
    LOCATION("Location"),
    TRAVEL("Travel"),
    LIFESTYLE("Lifestyle"),
    FOOD("Food"),
    OCCASIONS("Occasions"),
    MINIMAL("Minimal"),
    DECORATIVE("Decor"),
    VINTAGE("Vintage"),
    CINEMATIC("Cinema"),
    SOCIAL("Social")
}

enum class BadgeVisualStyle(val title: String) {
    MINIMAL("Minimal"),
    MODERN("Modern"),
    RETRO("Retro"),
    VINTAGE("Vintage"),
    EDITORIAL("Editorial"),
    CINEMATIC("Cinematic"),
    COLORFUL("Colorful"),
    PROFESSIONAL("Pro")
}

enum class BadgeShape(val title: String) {
    PILL("Pill"),
    ROUNDED_RECT("Card"),
    CIRCLE("Circle"),
    RIBBON("Ribbon"),
    STAMP("Stamp"),
    OUTLINED("Outlined"),
    FRAME("Frame")
}

data class BadgeStyleConfig(
    val backgroundColor: Color = Color(0xFF12141C),
    val textColor: Color = Color(0xFFF4F4F5),
    val borderColor: Color = Color(0xFFFACC15),
    val opacity: Float = 0.92f,
    val borderThicknessDp: Float = 1.4f,
    val cornerRadiusDp: Float = 12f,
    val fontFamily: EditorFontFamily = EditorFontFamily.MODERN_SANS,
    val fontSizeSp: Float = 12f,
    val hasShadow: Boolean = true,
    val visualStyle: BadgeVisualStyle = BadgeVisualStyle.MODERN,
    val shape: BadgeShape = BadgeShape.ROUNDED_RECT
)

/**
 * Pre-styled photographic badges and design elements.
 * Preserves the original 6 enum constants for full backward compatibility while expanding
 * into a rich categorized design library.
 */
enum class StickerType(
    val title: String,
    val subtitle: String,
    val category: BadgeCategory = BadgeCategory.PHOTOGRAPHY,
    val defaultText: String = title,
    val defaultStyle: BadgeStyleConfig = BadgeStyleConfig()
) {
    STUDIO_EMBLEM(
        "STU•D•O",
        "Pro Optics Monogram",
        BadgeCategory.PHOTOGRAPHY,
        "STU • D • O",
        BadgeStyleConfig(
            backgroundColor = Color(0xFF12131A),
            textColor = Color(0xFFFACC15),
            borderColor = Color(0xFFF59E0B),
            cornerRadiusDp = 10f,
            fontFamily = EditorFontFamily.MODERN_SANS,
            visualStyle = BadgeVisualStyle.PROFESSIONAL,
            shape = BadgeShape.ROUNDED_RECT
        )
    ),
    DATE_TIMESTAMP(
        "DIGITAL DATE",
        "Glowing Amber Timestamp",
        BadgeCategory.DATES,
        "2026.11.18",
        BadgeStyleConfig(
            backgroundColor = Color(0xFF08080A),
            textColor = Color(0xFFFFA000),
            borderColor = Color(0xFFFFA000),
            opacity = 0.85f,
            borderThicknessDp = 0f,
            cornerRadiusDp = 6f,
            fontFamily = EditorFontFamily.MONOSPACE,
            visualStyle = BadgeVisualStyle.RETRO,
            shape = BadgeShape.ROUNDED_RECT
        )
    ),
    LEICA_RED_DOT(
        "STUDIO DOT",
        "Red Aperture Badge",
        BadgeCategory.PHOTOGRAPHY,
        "PRO",
        BadgeStyleConfig(
            backgroundColor = Color(0xFFE53935),
            textColor = Color.White,
            borderColor = Color.White,
            borderThicknessDp = 1.2f,
            cornerRadiusDp = 24f,
            fontFamily = EditorFontFamily.BOLD_DISPLAY,
            visualStyle = BadgeVisualStyle.MODERN,
            shape = BadgeShape.CIRCLE
        )
    ),
    METADATA_CHIP(
        "CAMERA EXIF",
        "Optical Specs Pill",
        BadgeCategory.PHOTOGRAPHY,
        "EXIF SPECS",
        BadgeStyleConfig(
            backgroundColor = Color(0xFF101218),
            textColor = Color(0xFFE4E4E7),
            borderColor = Color(0x66FFFFFF),
            cornerRadiusDp = 20f,
            fontFamily = EditorFontFamily.MONOSPACE,
            fontSizeSp = 10.5f,
            visualStyle = BadgeVisualStyle.PROFESSIONAL,
            shape = BadgeShape.PILL
        )
    ),
    FILM_FRAME(
        "35mm FILM",
        "Analog Stock Badge",
        BadgeCategory.VINTAGE,
        "35MM FILM",
        BadgeStyleConfig(
            backgroundColor = Color(0xFF0A0A0C),
            textColor = Color(0xFFFFD54F),
            borderColor = Color(0xFFFFD54F),
            cornerRadiusDp = 6f,
            fontFamily = EditorFontFamily.CONDENSED,
            visualStyle = BadgeVisualStyle.VINTAGE,
            shape = BadgeShape.FRAME
        )
    ),
    RAW_CERTIFIED(
        "GENUINE RAW",
        "Certified Negative",
        BadgeCategory.PHOTOGRAPHY,
        "GENUINE RAW · DNG",
        BadgeStyleConfig(
            backgroundColor = Color(0xFF141C2B),
            textColor = Color(0xFF90CAF9),
            borderColor = Color(0xFF64B5F6),
            cornerRadiusDp = 8f,
            fontFamily = EditorFontFamily.MONOSPACE,
            fontSizeSp = 10.5f,
            visualStyle = BadgeVisualStyle.PROFESSIONAL,
            shape = BadgeShape.OUTLINED
        )
    ),

    // Expanded Design Library
    LOCATION_PIN(
        "GEO PIN",
        "City & Coordinates Tag",
        BadgeCategory.LOCATION,
        "TOKYO · SHIBUYA",
        BadgeStyleConfig(
            backgroundColor = Color(0xFF121620),
            textColor = Color(0xFFF4F4F5),
            borderColor = Color(0xFF88C8FF),
            cornerRadiusDp = 20f,
            fontFamily = EditorFontFamily.MINIMAL_GROTESK,
            visualStyle = BadgeVisualStyle.MODERN,
            shape = BadgeShape.PILL
        )
    ),
    LOCATION_COORDS(
        "GPS LOG",
        "Minimal Coordinates Strip",
        BadgeCategory.LOCATION,
        "35.6762° N · 139.6503° E",
        BadgeStyleConfig(
            backgroundColor = Color(0xFF0B0D12),
            textColor = Color(0xFF88C8FF),
            borderColor = Color(0xFF88C8FF),
            borderThicknessDp = 1f,
            cornerRadiusDp = 8f,
            fontFamily = EditorFontFamily.MONOSPACE,
            fontSizeSp = 10f,
            visualStyle = BadgeVisualStyle.MINIMAL,
            shape = BadgeShape.OUTLINED
        )
    ),
    TRAVEL_PASSPORT(
        "PASSPORT",
        "Vintage Arrival Stamp",
        BadgeCategory.TRAVEL,
        "ARRIVED · 2026",
        BadgeStyleConfig(
            backgroundColor = Color(0xFF1A1410),
            textColor = Color(0xFFFDE68A),
            borderColor = Color(0xFFF59E0B),
            borderThicknessDp = 1.8f,
            cornerRadiusDp = 28f,
            fontFamily = EditorFontFamily.VINTAGE,
            fontSizeSp = 11f,
            visualStyle = BadgeVisualStyle.VINTAGE,
            shape = BadgeShape.STAMP
        )
    ),
    TRAVEL_BOARDING(
        "FLIGHT PASS",
        "Editorial Boarding Label",
        BadgeCategory.TRAVEL,
        "BOARDING · GATE A12",
        BadgeStyleConfig(
            backgroundColor = Color(0xFFF4F4F5),
            textColor = Color(0xFF0E1016),
            borderColor = Color(0xFF0E1016),
            borderThicknessDp = 1.2f,
            cornerRadiusDp = 6f,
            fontFamily = EditorFontFamily.CONDENSED,
            visualStyle = BadgeVisualStyle.EDITORIAL,
            shape = BadgeShape.RIBBON
        )
    ),
    FOOD_TASTING(
        "CHEF NOTE",
        "Warm Bistro Tasting Card",
        BadgeCategory.FOOD,
        "BON APPÉTIT",
        BadgeStyleConfig(
            backgroundColor = Color(0xFF231812),
            textColor = Color(0xFFFFE0B2),
            borderColor = Color(0xFFFFB74D),
            cornerRadiusDp = 14f,
            fontFamily = EditorFontFamily.EDITORIAL,
            fontSizeSp = 13f,
            visualStyle = BadgeVisualStyle.EDITORIAL,
            shape = BadgeShape.FRAME
        )
    ),
    FOOD_CAFE(
        "ROASTERY",
        "Minimal Morning Cup Tag",
        BadgeCategory.FOOD,
        "MORNING BREW",
        BadgeStyleConfig(
            backgroundColor = Color(0xFF1C1714),
            textColor = Color(0xFFD7CCC8),
            borderColor = Color(0xFFA1887F),
            cornerRadiusDp = 20f,
            fontFamily = EditorFontFamily.SCRIPT,
            fontSizeSp = 13f,
            visualStyle = BadgeVisualStyle.MINIMAL,
            shape = BadgeShape.PILL
        )
    ),
    LIFESTYLE_JOURNAL(
        "DAILY LOG",
        "Clean Journal Note",
        BadgeCategory.LIFESTYLE,
        "SLOW MORNING",
        BadgeStyleConfig(
            backgroundColor = Color(0xFF181A22),
            textColor = Color(0xFFE4E4E7),
            borderColor = Color(0x66FFFFFF),
            cornerRadiusDp = 18f,
            fontFamily = EditorFontFamily.HANDWRITTEN,
            fontSizeSp = 14f,
            visualStyle = BadgeVisualStyle.MINIMAL,
            shape = BadgeShape.PILL
        )
    ),
    LIFESTYLE_VIBES(
        "GOLDEN HOUR",
        "Colorful Sunset Gradient Tag",
        BadgeCategory.LIFESTYLE,
        "GOLDEN HOUR",
        BadgeStyleConfig(
            backgroundColor = Color(0xFF3E1E14),
            textColor = Color(0xFFFFE082),
            borderColor = Color(0xFFFF8A65),
            cornerRadiusDp = 20f,
            fontFamily = EditorFontFamily.BOLD_DISPLAY,
            fontSizeSp = 13f,
            visualStyle = BadgeVisualStyle.COLORFUL,
            shape = BadgeShape.PILL
        )
    ),
    DATE_ARCHIVE(
        "ARCHIVE LOG",
        "Editorial Capture Date",
        BadgeCategory.DATES,
        "NOV 2026 · ARCHIVE",
        BadgeStyleConfig(
            backgroundColor = Color(0xFF111318),
            textColor = Color(0xFFE4E4E7),
            borderColor = Color(0xFFFACC15),
            cornerRadiusDp = 4f,
            fontFamily = EditorFontFamily.ELEGANT_SERIF,
            fontSizeSp = 11.5f,
            visualStyle = BadgeVisualStyle.EDITORIAL,
            shape = BadgeShape.FRAME
        )
    ),
    OCCASION_CELEBRATE(
        "MILESTONE",
        "Festive Editorial Ribbon",
        BadgeCategory.OCCASIONS,
        "SPECIAL EDITION",
        BadgeStyleConfig(
            backgroundColor = Color(0xFF2B1C2E),
            textColor = Color(0xFFF8BBD0),
            borderColor = Color(0xFFF48FB1),
            cornerRadiusDp = 8f,
            fontFamily = EditorFontFamily.ELEGANT_SERIF,
            fontSizeSp = 12f,
            visualStyle = BadgeVisualStyle.COLORFUL,
            shape = BadgeShape.RIBBON
        )
    ),
    MINIMAL_TAG(
        "PURE LABEL",
        "Minimal Hairline Tag",
        BadgeCategory.MINIMAL,
        "NO. 01",
        BadgeStyleConfig(
            backgroundColor = Color(0x00000000),
            textColor = Color(0xFFF4F4F5),
            borderColor = Color(0xAAFFFFFF),
            borderThicknessDp = 1f,
            cornerRadiusDp = 4f,
            fontFamily = EditorFontFamily.MINIMAL_GROTESK,
            fontSizeSp = 11f,
            visualStyle = BadgeVisualStyle.MINIMAL,
            shape = BadgeShape.OUTLINED
        )
    ),
    DECOR_APERTURE(
        "EDITION SEAL",
        "Circular Studio Seal",
        BadgeCategory.DECORATIVE,
        "FINE ART",
        BadgeStyleConfig(
            backgroundColor = Color(0xFF12141C),
            textColor = Color(0xFFFACC15),
            borderColor = Color(0xFFFACC15),
            borderThicknessDp = 1.6f,
            cornerRadiusDp = 32f,
            fontFamily = EditorFontFamily.CINEMATIC,
            fontSizeSp = 10f,
            visualStyle = BadgeVisualStyle.EDITORIAL,
            shape = BadgeShape.CIRCLE
        )
    ),
    VINTAGE_KODAK(
        "400 ISO STAMP",
        "Retro Negative Stamp",
        BadgeCategory.VINTAGE,
        "ISO 400 · KODAK",
        BadgeStyleConfig(
            backgroundColor = Color(0xFF241B08),
            textColor = Color(0xFFFFD54F),
            borderColor = Color(0xFFF59E0B),
            borderThicknessDp = 1.5f,
            cornerRadiusDp = 6f,
            fontFamily = EditorFontFamily.VINTAGE,
            fontSizeSp = 11f,
            visualStyle = BadgeVisualStyle.RETRO,
            shape = BadgeShape.STAMP
        )
    ),
    CINEMA_ANAMORPHIC(
        "2.39:1 SCOPE",
        "Anamorphic Cinema Slate",
        BadgeCategory.CINEMATIC,
        "2.39:1 · ANAMORPHIC",
        BadgeStyleConfig(
            backgroundColor = Color(0xFF0A0C10),
            textColor = Color(0xFFE4E4E7),
            borderColor = Color(0xFF4FC3F7),
            borderThicknessDp = 1.2f,
            cornerRadiusDp = 6f,
            fontFamily = EditorFontFamily.CINEMATIC,
            fontSizeSp = 10.5f,
            visualStyle = BadgeVisualStyle.CINEMATIC,
            shape = BadgeShape.FRAME
        )
    ),
    CINEMA_SCENE(
        "SCENE TAKE",
        "Director Slate Label",
        BadgeCategory.CINEMATIC,
        "SCENE 01 · TAKE 04",
        BadgeStyleConfig(
            backgroundColor = Color(0xFF121319),
            textColor = Color(0xFFFACC15),
            borderColor = Color(0x44FFFFFF),
            cornerRadiusDp = 8f,
            fontFamily = EditorFontFamily.MONOSPACE,
            fontSizeSp = 10.5f,
            visualStyle = BadgeVisualStyle.CINEMATIC,
            shape = BadgeShape.ROUNDED_RECT
        )
    ),
    SOCIAL_TAG(
        "STORY HANDLE",
        "Modern Social Handle Pill",
        BadgeCategory.SOCIAL,
        "@STUDO.CAM",
        BadgeStyleConfig(
            backgroundColor = Color(0xFF151926),
            textColor = Color.White,
            borderColor = Color(0xFF88C8FF),
            cornerRadiusDp = 24f,
            fontFamily = EditorFontFamily.MODERN_SANS,
            fontSizeSp = 11.5f,
            visualStyle = BadgeVisualStyle.MODERN,
            shape = BadgeShape.PILL
        )
    ),
    SOCIAL_LIVE(
        "FEATURED",
        "High-Impact Ribbon Tag",
        BadgeCategory.SOCIAL,
        "SHOT ON STU•D•O",
        BadgeStyleConfig(
            backgroundColor = Color(0xFFFACC15),
            textColor = Color(0xFF0B0B0D),
            borderColor = Color(0xFFFACC15),
            borderThicknessDp = 0f,
            cornerRadiusDp = 18f,
            fontFamily = EditorFontFamily.CONDENSED,
            fontSizeSp = 12f,
            visualStyle = BadgeVisualStyle.COLORFUL,
            shape = BadgeShape.RIBBON
        )
    );

    fun isLegacyHardwareBound(): Boolean =
        this == DATE_TIMESTAMP || this == METADATA_CHIP || this == FILM_FRAME || this == RAW_CERTIFIED
}

data class SavedBadgePreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: StickerType,
    val customText: String,
    val styleConfig: BadgeStyleConfig
)

data class StickerOverlayItem(
    val id: String = UUID.randomUUID().toString(),
    val type: StickerType,
    val customText: String? = null,
    val positionPercent: Offset = Offset(0.5f, 0.80f),
    val scale: Float = 1.0f,
    val rotation: Float = 0f,
    val styleConfig: BadgeStyleConfig = type.defaultStyle,
    val isFavorite: Boolean = false
)

/**
 * Top-level editor tabs.
 */
enum class EditorTab(val title: String, val icon: ImageVector) {
    ADJUST("Tune", Icons.Default.Tune),
    CROP("Crop", Icons.Default.Crop),
    FILTERS("Filters", Icons.Default.AutoAwesome),
    DRAW("Draw", Icons.Default.Edit),
    TEXT("Text", Icons.Default.TextFields),
    STICKERS("Badges", Icons.Default.Loyalty),
    WATERMARK("Watermark", Icons.Default.BrandingWatermark)
}

/**
 * Draggable bottom sheet detents (mirrors Watermark Studio's tactile 3-detent architecture).
 */
enum class EditorPanelDetent {
    COLLAPSED,
    PARTIAL,
    EXPANDED
}

/**
 * Snapshot for the undo/redo stack across all editing categories.
 */
data class PhotoEditSnapshot(
    val adjustments: PhotoAdjustmentState = PhotoAdjustmentState(),
    val filter: PhotoFilterType = PhotoFilterType.ORIGINAL,
    val filterIntensity: Float = 1.0f,
    val crop: CropState = CropState(),
    val drawingPaths: List<DrawPathItem> = emptyList(),
    val textItems: List<TextOverlayItem> = emptyList(),
    val stickerItems: List<StickerOverlayItem> = emptyList(),
    val watermarkConfig: com.example.model.WatermarkConfig? = null
)
