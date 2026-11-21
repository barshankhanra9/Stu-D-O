package com.example.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Dedicated high-performance image processing engine for non-destructive preview
 * and full-resolution background photo export with authentic EXIF preservation.
 *
 * All tone, color, detail, film grading, typography, badge, and vector drawing
 * algorithms operate identically on both interactive preview and full-res export.
 */
object PhotoProcessingEngine {

    private val typefaceCache = ConcurrentHashMap<EditorFontFamily, Typeface>()

    /**
     * Resolves a bundled local font into an Android [Typeface], falling back safely in Robolectric/JVM tests.
     */
    fun resolveTypeface(
        context: Context?,
        fontFamily: EditorFontFamily,
        isBold: Boolean,
        isItalic: Boolean
    ): Typeface {
        val base = if (context != null) {
            typefaceCache.getOrPut(fontFamily) {
                try {
                    ResourcesCompat.getFont(context, fontFamily.fontResId) ?: Typeface.DEFAULT
                } catch (_: Throwable) {
                    Typeface.DEFAULT
                }
            }
        } else {
            Typeface.DEFAULT
        }
        val style = when {
            isBold && isItalic -> Typeface.BOLD_ITALIC
            isBold -> Typeface.BOLD
            isItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return try {
            Typeface.create(base, style)
        } catch (_: Throwable) {
            Typeface.defaultFromStyle(style)
        }
    }

    /**
     * Safely loads an upright Bitmap scaled for responsive interactive preview.
     */
    suspend fun loadPreviewBitmap(context: Context, uri: Uri, maxDimPx: Int = 1920): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            if (options.outWidth <= 0 || options.outHeight <= 0) return@withContext null

            var sampleSize = 1
            val maxSide = max(options.outWidth, options.outHeight)
            while (maxSide / (sampleSize * 2) >= maxDimPx) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val rawBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return@withContext null

            var orientation = ExifInterface.ORIENTATION_NORMAL
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            } catch (_: Exception) {}

            val matrix = matrixForExifOrientation(orientation)
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                if (rotated != rawBitmap) rawBitmap.recycle()
                rotated
            } else {
                rawBitmap
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun matrixForExifOrientation(orientation: Int): Matrix {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                m.setRotate(180f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.setRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.setRotate(270f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(270f)
        }
        return m
    }

    /**
     * Applies rotation, flip, and optionally normalized sub-rect cropping cleanly to [source].
     * Always returns a mutable ARGB_8888 copy distinct from [source] unless [recycleSource] is true.
     */
    fun createOrientedBitmap(
        source: Bitmap,
        crop: CropState,
        applyCropRect: Boolean = true,
        recycleSource: Boolean = false
    ): Bitmap {
        var working = if (crop.rotationDegrees != 0 || crop.isFlippedHorizontal || crop.isFlippedVertical) {
            val matrix = Matrix()
            if (crop.rotationDegrees != 0) {
                matrix.postRotate(crop.rotationDegrees.toFloat())
            }
            if (crop.isFlippedHorizontal || crop.isFlippedVertical) {
                matrix.postScale(
                    if (crop.isFlippedHorizontal) -1f else 1f,
                    if (crop.isFlippedVertical) -1f else 1f
                )
            }
            val oriented = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            if (recycleSource && oriented !== source) source.recycle()
            oriented
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true).also {
                if (recycleSource) source.recycle()
            }
        }

        if (applyCropRect && crop.hasActiveCropRect()) {
            val origW = working.width
            val origH = working.height
            val rect = crop.cropRect
            val cropX = (rect.left * origW).roundToInt().coerceIn(0, origW - 1)
            val cropY = (rect.top * origH).roundToInt().coerceIn(0, origH - 1)
            val cropW = ((rect.right - rect.left) * origW).roundToInt().coerceIn(1, origW - cropX)
            val cropH = ((rect.bottom - rect.top) * origH).roundToInt().coerceIn(1, origH - cropY)
            val cropped = Bitmap.createBitmap(working, cropX, cropY, cropW, cropH)
            if (cropped !== working) working.recycle()
            working = cropped
        }

        return if (working.isMutable && working.config == Bitmap.Config.ARGB_8888) {
            working
        } else {
            val mutable = working.copy(Bitmap.Config.ARGB_8888, true)
            working.recycle()
            mutable
        }
    }

    /**
     * Combines filter preset and non-destructive adjustments into a lightweight ColorMatrix approximation.
     */
    fun buildCombinedColorMatrix(adjustments: PhotoAdjustmentState, filter: PhotoFilterType): ColorMatrix {
        val result = ColorMatrix()
        filter.toColorMatrix()?.let { result.postConcat(it) }
        result.postConcat(adjustments.toColorMatrix())
        return result
    }

    fun buildCombinedComposeColorMatrix(
        adjustments: PhotoAdjustmentState,
        filter: PhotoFilterType
    ): androidx.compose.ui.graphics.ColorMatrix {
        return androidx.compose.ui.graphics.ColorMatrix(buildCombinedColorMatrix(adjustments, filter).array)
    }

    /**
     * Builds a genuine non-linear photographic tone curve LUT (Lookup Table).
     * Pure black (0) and pure white (255) remain anchored cleanly when whites/blacks are unmodified.
     */
    fun createToneCurveLut(highlights: Float, shadows: Float): IntArray {
        val lut = IntArray(256)
        if (highlights == 0f && shadows == 0f) {
            for (i in 0..255) lut[i] = i
            return lut
        }
        for (i in 0..255) {
            val x = i / 255.0
            val shadowWeight = (1.0 - x) * (1.0 - x) * Math.sqrt(x) * 2.0
            val shadowDelta = shadows * 0.40 * shadowWeight
            val highlightWeight = x * x * (1.0 - x) * 2.6
            val highlightDelta = highlights * 0.40 * highlightWeight
            val y = (x + shadowDelta + highlightDelta).coerceIn(0.0, 1.0)
            lut[i] = (y * 255.0).roundToInt().coerceIn(0, 255)
        }
        return lut
    }

    /**
     * Applies the non-linear selective tone curve in-place using small stripe buffers (256 rows).
     */
    fun applyToneCurveInPlace(bitmap: Bitmap, highlights: Float, shadows: Float) {
        if (highlights == 0f && shadows == 0f) return
        val lut = createToneCurveLut(highlights, shadows)
        val width = bitmap.width
        val height = bitmap.height
        val stripeHeight = 256
        val stripePixels = IntArray(width * stripeHeight)

        for (top in 0 until height step stripeHeight) {
            val h = minOf(stripeHeight, height - top)
            bitmap.getPixels(stripePixels, 0, width, 0, top, width, h)
            val count = width * h
            for (i in 0 until count) {
                val c = stripePixels[i]
                val a = (c shr 24) and 0xFF
                val r = lut[(c shr 16) and 0xFF]
                val g = lut[(c shr 8) and 0xFF]
                val b = lut[c and 0xFF]
                stripePixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            bitmap.setPixels(stripePixels, 0, width, 0, top, width, h)
        }
    }

    /**
     * Applies a 3x3 unsharp convolution kernel using memory-efficient line buffers.
     */
    fun applySharpnessFilter(source: Bitmap, intensity: Float): Bitmap {
        if (intensity <= 0.01f) return source

        val width = source.width
        val height = source.height
        val strength = (intensity * 1.5f).coerceIn(0f, 2.0f)
        val centerWeight = 1f + 4f * strength
        val cardWeight = -strength

        var prevRow = IntArray(width)
        var currRow = IntArray(width)
        var nextRow = IntArray(width)
        val outRow = IntArray(width)

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        source.getPixels(currRow, 0, width, 0, 0, width, 1)
        output.setPixels(currRow, 0, width, 0, 0, width, 1)

        if (height <= 2) {
            if (height == 2) {
                source.getPixels(nextRow, 0, width, 0, 1, width, 1)
                output.setPixels(nextRow, 0, width, 0, 1, width, 1)
            }
            return output
        }

        source.getPixels(nextRow, 0, width, 0, 1, width, 1)

        for (y in 1 until height - 1) {
            val temp = prevRow
            prevRow = currRow
            currRow = nextRow
            nextRow = temp
            source.getPixels(nextRow, 0, width, 0, y + 1, width, 1)

            outRow[0] = currRow[0]
            for (x in 1 until width - 1) {
                val cPixel = currRow[x]
                val tPixel = prevRow[x]
                val bPixel = nextRow[x]
                val lPixel = currRow[x - 1]
                val rPixel = currRow[x + 1]

                val a = (cPixel shr 24) and 0xFF
                val cR = (cPixel shr 16) and 0xFF
                val cG = (cPixel shr 8) and 0xFF
                val cB = cPixel and 0xFF

                val tR = (tPixel shr 16) and 0xFF
                val tG = (tPixel shr 8) and 0xFF
                val tB = tPixel and 0xFF

                val bR = (bPixel shr 16) and 0xFF
                val bG = (bPixel shr 8) and 0xFF
                val bB = bPixel and 0xFF

                val lR = (lPixel shr 16) and 0xFF
                val lG = (lPixel shr 8) and 0xFF
                val lB = lPixel and 0xFF

                val rR = (rPixel shr 16) and 0xFF
                val rG = (rPixel shr 8) and 0xFF
                val rB = rPixel and 0xFF

                val newR = (cR * centerWeight + (tR + bR + lR + rR) * cardWeight).roundToInt().coerceIn(0, 255)
                val newG = (cG * centerWeight + (tG + bG + lG + rG) * cardWeight).roundToInt().coerceIn(0, 255)
                val newB = (cB * centerWeight + (tB + bB + lB + rB) * cardWeight).roundToInt().coerceIn(0, 255)

                outRow[x] = (a shl 24) or (newR shl 16) or (newG shl 8) or newB
            }
            outRow[width - 1] = currRow[width - 1]
            output.setPixels(outRow, 0, width, 0, y, width, 1)
        }

        source.getPixels(nextRow, 0, width, 0, height - 1, width, 1)
        output.setPixels(nextRow, 0, width, 0, height - 1, width, 1)
        return output
    }

    /**
     * Renders a preview bitmap using the exact same tone curve and sharpness convolution
     * as the legacy test contract.
     */
    fun renderToneAndSharpnessPreview(
        baseBitmap: Bitmap,
        highlights: Float,
        shadows: Float,
        sharpness: Float
    ): Bitmap {
        val bmp = if (highlights != 0f || shadows != 0f) {
            val copy = baseBitmap.copy(Bitmap.Config.ARGB_8888, true)
            applyToneCurveInPlace(copy, highlights, shadows)
            copy
        } else {
            baseBitmap
        }

        if (sharpness > 0.01f) {
            val sharpened = applySharpnessFilter(bmp, sharpness)
            if (bmp != baseBitmap && bmp != sharpened) {
                bmp.recycle()
            }
            return sharpened
        }
        return bmp
    }

    /**
     * Builds the unified 256-entry tonal LUT combining Exposure, Blacks, Whites, Shadows,
     * Highlights, Brightness, Contrast, Dehaze, and Filter Tone Shaping.
     */
    fun createMasterToneCurveLut(
        adjustments: PhotoAdjustmentState,
        filterSpec: FilterGradingSpec = FilterGradingSpec(),
        filterIntensity: Float = 0f
    ): FloatArray {
        val lut = FloatArray(256)
        val fi = filterIntensity.coerceIn(0f, 1f)
        val expMul = if (adjustments.exposure != 0f) 2.0.pow((adjustments.exposure * 0.72).toDouble()).toFloat() else 1f
        val blacksShift = adjustments.blacks * 0.14f
        val whitesScale = 1f + adjustments.whites * 0.24f
        val dehaze = adjustments.dehaze
        val effContrast = (adjustments.contrast + filterSpec.contrast * fi + dehaze * 0.22f).coerceIn(-1f, 1.35f)
        val shadowLift = (filterSpec.shadowLift * fi).coerceIn(0f, 0.2f)
        val highlightRolloff = (filterSpec.highlightRolloff * fi).coerceIn(0f, 0.35f)
        val gamma = if (adjustments.brightness != 0f) 2.0.pow((-adjustments.brightness * 0.55).toDouble()).toFloat() else 1f

        for (i in 0..255) {
            var y = i / 255f

            // 1. Exposure with smooth filmic highlight shoulder roll-off
            if (expMul != 1f) {
                var e = y * expMul
                if (e > 0.84f) {
                    val over = e - 0.84f
                    e = 0.84f + (over / (1f + over * 1.45f)) * 0.32f
                }
                y = e.coerceIn(0f, 1f)
            }

            // 2. Independent Blacks & Whites point shaping
            if (blacksShift != 0f) {
                val blackInfluence = (1f - y) * (1f - y)
                y = (y + blacksShift * blackInfluence).coerceIn(0f, 1f)
            }
            if (adjustments.whites != 0f) {
                val whiteInfluence = y * y
                y = (y + (whitesScale - 1f) * 0.45f * whiteInfluence).coerceIn(0f, 1f)
            }

            // 3. Atmospheric Dehaze veil adjustment
            if (dehaze > 0f) {
                val veil = dehaze * 0.085f
                y = ((y - veil) / (1f - veil)).coerceIn(0f, 1f)
            } else if (dehaze < 0f) {
                val mist = -dehaze * 0.14f
                y = (y * (1f - mist) + mist * 0.65f).coerceIn(0f, 1f)
            }

            // 4. Selective Highlights & Shadows bell curves
            if (adjustments.shadows != 0f) {
                val shadowW = (1f - y) * (1f - y) * kotlin.math.sqrt(y) * 2.0f
                y = (y + adjustments.shadows * 0.40f * shadowW).coerceIn(0f, 1f)
            }
            if (adjustments.highlights != 0f) {
                val highW = y * y * (1f - y) * 2.6f
                y = (y + adjustments.highlights * 0.40f * highW).coerceIn(0f, 1f)
            }

            // 5. Midtone perceptual brightness gamma
            if (gamma != 1f && y > 0f && y < 1f) {
                y = y.pow(gamma).coerceIn(0f, 1f)
            }

            // 6. Smooth photographic S-curve Contrast
            if (effContrast != 0f) {
                val s = y * y * (3f - 2f * y)
                y = if (effContrast > 0f) {
                    y + (s - y) * (effContrast * 0.72f)
                } else {
                    y + (0.5f - y) * (-effContrast * 0.36f)
                }.coerceIn(0f, 1f)
            }

            // 7. Filter shadow matte lift & soft highlight rolloff
            if (shadowLift > 0f) {
                y = (shadowLift + y * (1f - shadowLift * 0.65f)).coerceIn(0f, 1f)
            }
            if (highlightRolloff > 0f && y > 0.68f) {
                val t = (y - 0.68f) / 0.32f
                y -= t * t * highlightRolloff * 0.28f
            }

            lut[i] = y.coerceIn(0f, 1f)
        }
        return lut
    }

    /**
     * Unified non-destructive image processing pipeline shared identically by preview & export.
     * Supports all 17 Tune adjustments + 28 Filter presets + Filter Intensity in memory-safe stripes/rows.
     */
    fun processEditedBitmap(
        source: Bitmap,
        adjustments: PhotoAdjustmentState,
        filter: PhotoFilterType,
        filterIntensity: Float = 1.0f,
        recycleSource: Boolean = false
    ): Bitmap {
        val fi = if (filter == PhotoFilterType.ORIGINAL) 0f else filterIntensity.coerceIn(0f, 1f)
        val spec = filter.spec

        val hasAnyToneOrColor = !adjustments.isDefault() || fi > 0.005f
        if (!hasAnyToneOrColor) {
            return if (recycleSource) source else source.copy(Bitmap.Config.ARGB_8888, true)
        }

        val width = source.width
        val height = source.height
        var working = if (recycleSource && source.isMutable && source.config == Bitmap.Config.ARGB_8888) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true).also {
                if (recycleSource && it !== source) source.recycle()
            }
        }

        // Stage 1: Per-Pixel Tone Curve, White Balance, Vibrance (skin-protected), Saturation, Monochrome, Split-Toning, Grain
        val toneLut = createMasterToneCurveLut(adjustments, spec, fi)
        val effTemp = (adjustments.temperature + spec.temperature * fi).coerceIn(-1.5f, 1.5f)
        val effTint = (adjustments.tint + spec.tint * fi).coerceIn(-1.5f, 1.5f)
        val effVibrance = (adjustments.vibrance + spec.vibrance * fi).coerceIn(-1.5f, 1.5f)
        val effSaturation = (adjustments.saturation + spec.saturation * fi + max(0f, adjustments.dehaze) * 0.16f).coerceIn(-1.0f, 1.8f)
        val effClarity = (adjustments.clarity + spec.clarityBoost * fi).coerceIn(-1.2f, 1.5f)
        val effGrain = (adjustments.grain + spec.grainBoost * fi).coerceIn(0f, 1f)

        val needsToneOrColorPass = adjustments.exposure != 0f ||
            adjustments.brightness != 0f ||
            adjustments.contrast != 0f ||
            adjustments.highlights != 0f ||
            adjustments.shadows != 0f ||
            adjustments.whites != 0f ||
            adjustments.blacks != 0f ||
            adjustments.dehaze != 0f ||
            effTemp != 0f ||
            effTint != 0f ||
            effVibrance != 0f ||
            effSaturation != 0f ||
            effGrain > 0.01f ||
            fi > 0.005f

        if (needsToneOrColorPass) {
            val stripeH = 256
            val pixels = IntArray(width * stripeH)

            val tempRMul = 1f + effTemp * 0.22f
            val tempGMul = 1f + effTemp * 0.04f
            val tempBMul = 1f - effTemp * 0.24f

            val tintRMul = 1f + effTint * 0.11f
            val tintGMul = 1f - effTint * 0.20f
            val tintBMul = 1f + effTint * 0.11f

            val wbR = tempRMul * tintRMul
            val wbG = tempGMul * tintGMul
            val wbB = tempBMul * tintBMul

            for (top in 0 until height step stripeH) {
                val h = minOf(stripeH, height - top)
                working.getPixels(pixels, 0, width, 0, top, width, h)
                val count = width * h
                for (idx in 0 until count) {
                    val c = pixels[idx]
                    val a = (c ushr 24) and 0xFF
                    val ri = (c ushr 16) and 0xFF
                    val gi = (c ushr 8) and 0xFF
                    val bi = c and 0xFF

                    var r = ri / 255f
                    var g = gi / 255f
                    var b = bi / 255f

                    // Tone curve blended cleanly between per-channel LUT and luma-ratio preservation
                    val lumaIn = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
                    val lumaIdx = (lumaIn * 255f).roundToInt().coerceIn(0, 255)
                    val lumaOut = toneLut[lumaIdx]
                    val ratio = if (lumaIn > 0.004f) (lumaOut / lumaIn).coerceIn(0f, 3.5f) else 1f

                    val rCh = toneLut[ri]
                    val gCh = toneLut[gi]
                    val bCh = toneLut[bi]

                    r = (rCh * 0.45f + (r * ratio).coerceIn(0f, 1f) * 0.55f)
                    g = (gCh * 0.45f + (g * ratio).coerceIn(0f, 1f) * 0.55f)
                    b = (bCh * 0.45f + (b * ratio).coerceIn(0f, 1f) * 0.55f)

                    // White Balance (Temperature & Tint) with luma preservation
                    if (effTemp != 0f || effTint != 0f) {
                        val preLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                        var wr = r * wbR
                        var wg = g * wbG
                        var wb = b * wbB
                        val postLuma = 0.2126f * wr + 0.7152f * wg + 0.0722f * wb
                        if (postLuma > 0.001f) {
                            val norm = preLuma / postLuma
                            wr *= norm
                            wg *= norm
                            wb *= norm
                        }
                        r = wr.coerceIn(0f, 1f)
                        g = wg.coerceIn(0f, 1f)
                        b = wb.coerceIn(0f, 1f)
                    }

                    // Skin-tone protected Vibrance + Saturation
                    val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
                    if (effVibrance != 0f || effSaturation != 0f) {
                        val maxC = max(r, max(g, b))
                        val minC = min(r, min(g, b))
                        val chroma = maxC - minC

                        val skinProtection = if (effVibrance > 0f && r > g && g >= b && (r - b) > 0.03f) {
                            val rel = (g - b) / (r - b)
                            // Warm peach/brown skin-tone hue zone (~10°..50°): attenuate vibrance boost up to 68%
                            if (rel in 0.14f..0.76f) 0.32f else 1.0f
                        } else {
                            1.0f
                        }
                        val vibBoost = effVibrance * (1f - chroma) * skinProtection
                        val satMul = (1f + effSaturation + vibBoost).coerceIn(0f, 2.4f)
                        r = (luma + (r - luma) * satMul).coerceIn(0f, 1f)
                        g = (luma + (g - luma) * satMul).coerceIn(0f, 1f)
                        b = (luma + (b - luma) * satMul).coerceIn(0f, 1f)
                    }

                    // Filter Monochrome Mixer
                    if (spec.isMonochrome && fi > 0f) {
                        val mono = (r * spec.monoRWeight + g * spec.monoGWeight + b * spec.monoBWeight).coerceIn(0f, 1f)
                        r += (mono - r) * fi
                        g += (mono - g) * fi
                        b += (mono - b) * fi
                    }

                    // Filter Split Toning (Shadow & Highlight tints)
                    if (fi > 0f) {
                        val sW = (1f - luma) * (1f - luma) * fi
                        val hW = luma * luma * fi
                        r = (r + spec.splitShadowR * sW + spec.splitHighlightR * hW).coerceIn(0f, 1f)
                        g = (g + spec.splitShadowG * sW + spec.splitHighlightG * hW).coerceIn(0f, 1f)
                        b = (b + spec.splitShadowB * sW + spec.splitHighlightB * hW).coerceIn(0f, 1f)
                    }

                    // Organic silver-halide Film Grain (midtone weighted)
                    if (effGrain > 0.01f) {
                        val pxY = top + (idx / width)
                        val pxX = idx % width
                        var seed = (pxX * 374761393) xor (pxY * 668265263) xor 0x5bd1e995
                        seed = (seed xor (seed ushr 13)) * 1274126177
                        val noise = (((seed xor (seed ushr 16)) and 0xFFFF) / 32767.5f) - 1f
                        val midWeight = (1f - abs(luma - 0.5f) * 1.4f).coerceIn(0.25f, 1f)
                        val grainDelta = noise * effGrain * 0.14f * midWeight
                        r = (r + grainDelta).coerceIn(0f, 1f)
                        g = (g + grainDelta).coerceIn(0f, 1f)
                        b = (b + grainDelta).coerceIn(0f, 1f)
                    }

                    pixels[idx] = (a shl 24) or
                        ((r * 255f).roundToInt().coerceIn(0, 255) shl 16) or
                        ((g * 255f).roundToInt().coerceIn(0, 255) shl 8) or
                        (b * 255f).roundToInt().coerceIn(0, 255)
                }
                working.setPixels(pixels, 0, width, 0, top, width, h)
            }
        }

        // Stage 2: Spatial Detail (Clarity 5x5 local midtone contrast + Texture 3x3 + Edge-Aware Sharpness)
        if (abs(effClarity) > 0.01f || abs(adjustments.texture) > 0.01f || adjustments.sharpness > 0.01f) {
            val detailed = applyDetailConvolutionLineBuffered(
                source = working,
                clarity = effClarity,
                texture = adjustments.texture,
                sharpness = adjustments.sharpness
            )
            if (detailed !== working) {
                working.recycle()
                working = detailed
            }
        }

        // Stage 3: Optical Radial Vignette
        if (adjustments.vignette > 0.01f) {
            val vCanvas = Canvas(working)
            applyVignette(vCanvas, width.toFloat(), height.toFloat(), adjustments.vignette)
        }

        return working
    }

    /**
     * Memory-safe line-buffered spatial detail processor combining:
     * - Clarity (5x5 local contrast weighted softly in midtones)
     * - Texture (3x3 fine surface micro-detail)
     * - Sharpness (edge-cored 3x3 crispness without amplifying smooth noise)
     */
    private fun applyDetailConvolutionLineBuffered(
        source: Bitmap,
        clarity: Float,
        texture: Float,
        sharpness: Float
    ): Bitmap {
        val width = source.width
        val height = source.height
        if (width < 5 || height < 5) {
            return if (sharpness > 0.01f) applySharpnessFilter(source, sharpness) else source
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val copyRow = IntArray(width)
        // Copy top/bottom 2 border rows unmodified
        for (y in 0 until 2) {
            source.getPixels(copyRow, 0, width, 0, y, width, 1)
            output.setPixels(copyRow, 0, width, 0, y, width, 1)
            source.getPixels(copyRow, 0, width, 0, height - 1 - y, width, 1)
            output.setPixels(copyRow, 0, width, 0, height - 1 - y, width, 1)
        }

        val rows = Array(5) { IntArray(width) }
        for (i in 0 until 5) {
            source.getPixels(rows[i], 0, width, 0, i, width, 1)
        }
        val outRow = IntArray(width)

        val clarityAmt = clarity * 0.72f
        val textureAmt = texture * 0.55f
        val sharpAmt = sharpness * 0.85f

        for (y in 2 until height - 2) {
            if (y > 2) {
                val recycled = rows[0]
                rows[0] = rows[1]
                rows[1] = rows[2]
                rows[2] = rows[3]
                rows[3] = rows[4]
                rows[4] = recycled
                source.getPixels(rows[4], 0, width, 0, y + 2, width, 1)
            }

            val r0 = rows[0]
            val r1 = rows[1]
            val r2 = rows[2]
            val r3 = rows[3]
            val r4 = rows[4]

            outRow[0] = r2[0]
            outRow[1] = r2[1]
            outRow[width - 2] = r2[width - 2]
            outRow[width - 1] = r2[width - 1]

            for (x in 2 until width - 2) {
                val c = r2[x]
                val a = (c ushr 24) and 0xFF
                val cR = (c ushr 16) and 0xFF
                val cG = (c ushr 8) and 0xFF
                val cB = c and 0xFF

                var deltaLuma = 0f

                // 3x3 neighborhood average luma for Texture & Sharpness
                if (textureAmt != 0f || sharpAmt > 0.01f) {
                    val pTL = r1[x - 1]; val pT = r1[x]; val pTR = r1[x + 1]
                    val pL = r2[x - 1]; val pR = r2[x + 1]
                    val pBL = r3[x - 1]; val pB = r3[x]; val pBR = r3[x + 1]

                    val avgR3 = (((pTL ushr 16) and 0xFF) + ((pT ushr 16) and 0xFF) + ((pTR ushr 16) and 0xFF) +
                        ((pL ushr 16) and 0xFF) + ((pR ushr 16) and 0xFF) +
                        ((pBL ushr 16) and 0xFF) + ((pB ushr 16) and 0xFF) + ((pBR ushr 16) and 0xFF)) / 8f
                    val avgG3 = (((pTL ushr 8) and 0xFF) + ((pT ushr 8) and 0xFF) + ((pTR ushr 8) and 0xFF) +
                        ((pL ushr 8) and 0xFF) + ((pR ushr 8) and 0xFF) +
                        ((pBL ushr 8) and 0xFF) + ((pB ushr 8) and 0xFF) + ((pBR ushr 8) and 0xFF)) / 8f
                    val avgB3 = ((pTL and 0xFF) + (pT and 0xFF) + (pTR and 0xFF) +
                        (pL and 0xFF) + (pR and 0xFF) +
                        (pBL and 0xFF) + (pB and 0xFF) + (pBR and 0xFF)) / 8f

                    val fineLumaDelta = (0.299f * (cR - avgR3) + 0.587f * (cG - avgG3) + 0.114f * (cB - avgB3))
                    deltaLuma += fineLumaDelta * textureAmt
                    // Subtle noise coring threshold (5.0 / 255) for Sharpness so smooth regions stay clean
                    if (sharpAmt > 0.01f && abs(fineLumaDelta) > 4.5f) {
                        deltaLuma += fineLumaDelta * sharpAmt
                    }
                }

                // 5x5 coarse neighborhood average for midtone-weighted Clarity
                if (clarityAmt != 0f) {
                    var sumR5 = 0
                    var sumG5 = 0
                    var sumB5 = 0
                    for (ry in 0 until 5) {
                        val row = rows[ry]
                        for (dx in -2..2) {
                            val p = row[x + dx]
                            sumR5 += (p ushr 16) and 0xFF
                            sumG5 += (p ushr 8) and 0xFF
                            sumB5 += p and 0xFF
                        }
                    }
                    val coarseDelta = (0.299f * (cR - sumR5 / 25f) + 0.587f * (cG - sumG5 / 25f) + 0.114f * (cB - sumB5 / 25f))
                    val cLuma = (0.299f * cR + 0.587f * cG + 0.114f * cB) / 255f
                    val midWeight = (4f * cLuma * (1f - cLuma)).coerceIn(0.15f, 1f)
                    deltaLuma += coarseDelta * clarityAmt * midWeight
                }

                val outR = (cR + deltaLuma).roundToInt().coerceIn(0, 255)
                val outG = (cG + deltaLuma).roundToInt().coerceIn(0, 255)
                val outB = (cB + deltaLuma).roundToInt().coerceIn(0, 255)
                outRow[x] = (a shl 24) or (outR shl 16) or (outG shl 8) or outB
            }

            output.setPixels(outRow, 0, width, 0, y, width, 1)
        }

        return output
    }

    /**
     * Generates small, fast preview thumbnails for the filter carousel from [source].
     */
    suspend fun generateFilterThumbnails(
        source: Bitmap,
        thumbSizePx: Int = 128
    ): Map<PhotoFilterType, Bitmap> = withContext(Dispatchers.Default) {
        val side = min(source.width, source.height).coerceAtLeast(1)
        val sx = (source.width - side) / 2
        val sy = (source.height - side) / 2
        val square = Bitmap.createBitmap(source, sx, sy, side, side)
        val tinyBase = Bitmap.createScaledBitmap(square, thumbSizePx, thumbSizePx, true)
        if (square !== source) square.recycle()

        val result = LinkedHashMap<PhotoFilterType, Bitmap>(PhotoFilterType.entries.size)
        val neutralAdjustments = PhotoAdjustmentState()
        try {
            PhotoFilterType.entries.forEach { preset ->
                result[preset] = if (preset == PhotoFilterType.ORIGINAL) {
                    tinyBase.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    processEditedBitmap(
                        source = tinyBase,
                        adjustments = neutralAdjustments,
                        filter = preset,
                        filterIntensity = 1.0f,
                        recycleSource = false
                    )
                }
            }
        } finally {
            if (!tinyBase.isRecycled) tinyBase.recycle()
        }
        result
    }

    /**
     * Renders radial vignette gradient on the target canvas.
     */
    fun applyVignette(canvas: Canvas, width: Float, height: Float, intensity: Float) {
        if (intensity <= 0.01f) return

        val cx = width / 2f
        val cy = height / 2f
        val radius = hypot(cx.toDouble(), cy.toDouble()).toFloat()

        val maxAlpha = (intensity * 210f).roundToInt().coerceIn(0, 240)
        val colors = intArrayOf(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.argb((maxAlpha * 0.25f).toInt(), 0, 0, 0),
            android.graphics.Color.argb(maxAlpha, 0, 0, 0)
        )
        val stops = floatArrayOf(0f, 0.65f, 1.0f)

        val shader = RadialGradient(cx, cy, radius, colors, stops, Shader.TileMode.CLAMP)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        canvas.drawRect(0f, 0f, width, height, paint)
    }

    data class RenderedEditorTempResult(
        val file: java.io.File,
        val width: Int,
        val height: Int
    )

    /**
     * Renders a WYSIWYG preview bitmap combining Draw, Text, Badges, and an enabled Watermark.
     * Caller owns the returned Bitmap.
     */
    fun renderEditorPreviewWithWatermark(
        context: Context,
        gradedBase: Bitmap,
        snapshot: PhotoEditSnapshot,
        metadata: com.example.util.RealCameraMetadata
    ): Bitmap? {
        val wmConfig = snapshot.watermarkConfig ?: return null
        if (!wmConfig.isEnabled || gradedBase.isRecycled) return null

        val working = gradedBase.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        return try {
            val w = working.width
            val h = working.height
            if (snapshot.drawingPaths.isNotEmpty()) {
                renderDrawingPaths(working, snapshot.drawingPaths)
            }
            if (snapshot.textItems.isNotEmpty()) {
                val textCanvas = Canvas(working)
                val densityFactor = w / 600f
                snapshot.textItems.forEach { textItem ->
                    renderTextOverlay(context, textCanvas, textItem, w.toFloat(), h.toFloat(), densityFactor)
                }
            }
            if (snapshot.stickerItems.isNotEmpty()) {
                val stickerCanvas = Canvas(working)
                val densityFactor = w / 600f
                snapshot.stickerItems.forEach { sticker ->
                    val targetX = sticker.positionPercent.x * w
                    val targetY = sticker.positionPercent.y * h
                    stickerCanvas.save()
                    stickerCanvas.translate(targetX, targetY)
                    if (sticker.rotation != 0f) stickerCanvas.rotate(sticker.rotation)
                    if (sticker.scale != 1.0f) stickerCanvas.scale(sticker.scale, sticker.scale)
                    renderStickerBadge(stickerCanvas, sticker, densityFactor, metadata, context)
                    stickerCanvas.restore()
                }
            }
            val watermarked = com.example.util.WatermarkPainter.applyWatermark(
                source = working,
                config = wmConfig,
                metadata = metadata,
                context = context
            )
            if (watermarked !== working && !working.isRecycled) {
                working.recycle()
            }
            watermarked
        } catch (t: Throwable) {
            if (!working.isRecycled) working.recycle()
            null
        }
    }

    /**
     * Full-resolution non-destructive image renderer and exporter.
     * Preserves original file without any modification, and saves a high-quality copy
     * to MediaStore with authentic EXIF metadata preserved.
     */
    suspend fun exportFullResolutionEditedImage(
        context: Context,
        sourceUri: Uri,
        snapshot: PhotoEditSnapshot,
        isImportedPhoto: Boolean = false
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            var sampleSize = 1
            val maxSupportedExportDim = 6144
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                val maxSide = max(bounds.outWidth, bounds.outHeight)
                while (maxSide / sampleSize > maxSupportedExportDim) {
                    sampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var workingBitmap = context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return@withContext null

            var srcExifOrientation = ExifInterface.ORIENTATION_NORMAL
            try {
                context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                    srcExifOrientation = ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }
            } catch (_: Exception) {}

            val exifMatrix = matrixForExifOrientation(srcExifOrientation)
            if (!exifMatrix.isIdentity) {
                val rotated = Bitmap.createBitmap(workingBitmap, 0, 0, workingBitmap.width, workingBitmap.height, exifMatrix, true)
                if (rotated !== workingBitmap) workingBitmap.recycle()
                workingBitmap = rotated
            }

            // Apply crop, rotation & flip identically to preview
            workingBitmap = createOrientedBitmap(
                source = workingBitmap,
                crop = snapshot.crop,
                applyCropRect = true,
                recycleSource = true
            )

            // Apply all 17 Tune adjustments + Filter + Intensity identically to preview
            workingBitmap = processEditedBitmap(
                source = workingBitmap,
                adjustments = snapshot.adjustments,
                filter = snapshot.filter,
                filterIntensity = snapshot.filterIntensity,
                recycleSource = true
            )

            var finalWidth = workingBitmap.width
            var finalHeight = workingBitmap.height

            // Render vector drawing paths
            if (snapshot.drawingPaths.isNotEmpty()) {
                renderDrawingPaths(workingBitmap, snapshot.drawingPaths)
            }

            // Render typography overlays centered accurately at positionPercent
            if (snapshot.textItems.isNotEmpty()) {
                val textCanvas = Canvas(workingBitmap)
                val densityFactor = finalWidth / 600f
                snapshot.textItems.forEach { textItem ->
                    renderTextOverlay(context, textCanvas, textItem, finalWidth.toFloat(), finalHeight.toFloat(), densityFactor)
                }
            }

            val photoMetadata = com.example.util.CameraMetadataUtil.fromUri(
                context = context,
                uri = sourceUri,
                width = finalWidth,
                height = finalHeight,
                allowDeviceFallback = !isImportedPhoto
            )

            // Render badges centered accurately at positionPercent
            if (snapshot.stickerItems.isNotEmpty()) {
                val stickerCanvas = Canvas(workingBitmap)
                val densityFactor = finalWidth / 600f
                snapshot.stickerItems.forEach { sticker ->
                    val targetX = sticker.positionPercent.x * finalWidth
                    val targetY = sticker.positionPercent.y * finalHeight

                    stickerCanvas.save()
                    stickerCanvas.translate(targetX, targetY)
                    if (sticker.rotation != 0f) stickerCanvas.rotate(sticker.rotation)
                    if (sticker.scale != 1.0f) stickerCanvas.scale(sticker.scale, sticker.scale)
                    renderStickerBadge(stickerCanvas, sticker, densityFactor, photoMetadata, context)
                    stickerCanvas.restore()
                }
            }

            // Apply configured Watermark from Watermark Studio if enabled
            val wmConfig = snapshot.watermarkConfig
            if (wmConfig != null && wmConfig.isEnabled) {
                val watermarked = com.example.util.WatermarkPainter.applyWatermark(
                    source = workingBitmap,
                    config = wmConfig,
                    metadata = photoMetadata,
                    context = context
                )
                if (watermarked !== workingBitmap) {
                    if (!workingBitmap.isRecycled) workingBitmap.recycle()
                    workingBitmap = watermarked
                }
                finalWidth = workingBitmap.width
                finalHeight = workingBitmap.height
            }

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val displayName = "StuDO_Edit_$timeStamp.jpg"

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StuDO_Edited")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            var savedUri: Uri? = null
            try {
                savedUri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                ) ?: return@withContext null

                context.contentResolver.openOutputStream(savedUri)?.use { outStream ->
                    workingBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outStream)
                    outStream.flush()
                } ?: throw IllegalStateException("Failed to open output stream for $savedUri")

                try {
                    val pfd = context.contentResolver.openFileDescriptor(savedUri, "rw")
                    pfd?.use { fd ->
                        val destExif = ExifInterface(fd.fileDescriptor)
                        context.contentResolver.openInputStream(sourceUri)?.use { origStream ->
                            val srcExif = ExifInterface(origStream)
                            val tagsToCopy = arrayOf(
                                ExifInterface.TAG_DATETIME,
                                ExifInterface.TAG_DATETIME_ORIGINAL,
                                ExifInterface.TAG_DATETIME_DIGITIZED,
                                ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                                ExifInterface.TAG_MAKE,
                                ExifInterface.TAG_MODEL,
                                ExifInterface.TAG_FOCAL_LENGTH,
                                ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                                ExifInterface.TAG_F_NUMBER,
                                ExifInterface.TAG_EXPOSURE_TIME,
                                ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
                                ExifInterface.TAG_ISO_SPEED_RATINGS,
                                ExifInterface.TAG_WHITE_BALANCE,
                                ExifInterface.TAG_FLASH,
                                ExifInterface.TAG_GPS_LATITUDE,
                                ExifInterface.TAG_GPS_LATITUDE_REF,
                                ExifInterface.TAG_GPS_LONGITUDE,
                                ExifInterface.TAG_GPS_LONGITUDE_REF,
                                ExifInterface.TAG_GPS_ALTITUDE,
                                ExifInterface.TAG_GPS_ALTITUDE_REF,
                                ExifInterface.TAG_GPS_TIMESTAMP,
                                ExifInterface.TAG_GPS_DATESTAMP
                            )
                            for (tag in tagsToCopy) {
                                val v = srcExif.getAttribute(tag)
                                if (!v.isNullOrBlank()) destExif.setAttribute(tag, v)
                            }
                        }
                        destExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                        destExif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, finalWidth.toString())
                        destExif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, finalHeight.toString())
                        destExif.setAttribute(ExifInterface.TAG_SOFTWARE, "StuDO Professional Photo Studio")
                        destExif.saveAttributes()
                    }
                } catch (ex: Exception) {
                    android.util.Log.w("PhotoProcessingEngine", "EXIF tag copy note: ${ex.message}")
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(savedUri, contentValues, null, null)
                }

                savedUri
            } catch (t: Throwable) {
                if (savedUri != null) {
                    try { context.contentResolver.delete(savedUri, null, null) } catch (_: Exception) {}
                }
                android.util.Log.e("PhotoProcessingEngine", "Failed to export edited photo", t)
                null
            } finally {
                if (!workingBitmap.isRecycled) workingBitmap.recycle()
            }
        } catch (t: Throwable) {
            android.util.Log.e("PhotoProcessingEngine", "Export exception", t)
            null
        }
    }

    /**
     * Renders full-featured typography overlays (alignment, multi-line spacing, letter spacing,
     * custom fonts, background card/pill/frame, shadow, stroke outline) centered on [positionPercent].
     */
    fun renderTextOverlay(
        context: Context?,
        canvas: Canvas,
        textItem: TextOverlayItem,
        canvasWidth: Float,
        canvasHeight: Float,
        densityFactor: Float
    ) {
        if (textItem.text.isBlank()) return

        val tf = resolveTypeface(
            context = context,
            fontFamily = textItem.fontFamily,
            isBold = textItem.isBold || textItem.fontWeight >= 650,
            isItalic = textItem.isItalic
        )
        val alphaInt = (textItem.textOpacity.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textItem.color.toArgb()
            alpha = alphaInt
            textSize = textItem.fontSize * densityFactor
            typeface = tf
            letterSpacing = textItem.letterSpacing
            if (textItem.fontWeight >= 750 && !tf.isBold) {
                isFakeBoldText = true
            }
            if (textItem.hasShadow) {
                setShadowLayer(
                    6f * densityFactor,
                    0f,
                    2f * densityFactor,
                    android.graphics.Color.argb((alphaInt * 0.65f).roundToInt(), 0, 0, 0)
                )
            }
        }

        val lines = textItem.text.split("\n")
        val maxLineW = lines.maxOfOrNull { paint.measureText(it) }?.coerceAtLeast(10f) ?: 10f
        val layoutWidth = (maxLineW + 12f * densityFactor).roundToInt().coerceAtLeast(1)

        val alignment = when (textItem.textAlign) {
            EditorTextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
            EditorTextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            EditorTextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }

        val layout = StaticLayout.Builder
            .obtain(textItem.text, 0, textItem.text.length, paint, layoutWidth)
            .setAlignment(alignment)
            .setLineSpacing(0f, textItem.lineSpacing.coerceIn(0.8f, 2.2f))
            .setIncludePad(true)
            .build()

        val textW = layout.width.toFloat()
        val textH = layout.height.toFloat()

        val targetX = textItem.positionPercent.x * canvasWidth
        val targetY = textItem.positionPercent.y * canvasHeight

        canvas.save()
        canvas.translate(targetX, targetY)
        if (textItem.rotation != 0f) canvas.rotate(textItem.rotation)
        if (textItem.scale != 1.0f) canvas.scale(textItem.scale, textItem.scale)

        val bgStyle = if (!textItem.hasBackground) TextBackgroundStyle.NONE else textItem.backgroundStyle
        if (bgStyle != TextBackgroundStyle.NONE) {
            val padX = if (bgStyle == TextBackgroundStyle.PILL) 24f * densityFactor else 18f * densityFactor
            val padY = 12f * densityFactor
            val bgRect = RectF(-textW / 2f - padX, -textH / 2f - padY, textW / 2f + padX, textH / 2f + padY)
            val corner = when (bgStyle) {
                TextBackgroundStyle.PILL -> bgRect.height() / 2f
                TextBackgroundStyle.ROUNDED -> 14f * densityFactor
                TextBackgroundStyle.OUTLINE -> 8f * densityFactor
                TextBackgroundStyle.NONE -> 0f
            }
            val bgAlpha = (textItem.backgroundOpacity.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textItem.backgroundColor.toArgb()
                alpha = bgAlpha
            }
            if (bgStyle == TextBackgroundStyle.OUTLINE) {
                val frameBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = textItem.backgroundColor.toArgb()
                    alpha = (bgAlpha * 0.45f).roundToInt()
                }
                canvas.drawRoundRect(bgRect, corner, corner, frameBgPaint)
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = textItem.color.toArgb()
                    alpha = alphaInt
                    style = Paint.Style.STROKE
                    strokeWidth = 2f * densityFactor
                }
                canvas.drawRoundRect(bgRect, corner, corner, borderPaint)
            } else {
                canvas.drawRoundRect(bgRect, corner, corner, bgPaint)
            }
        }

        canvas.translate(-textW / 2f, -textH / 2f)

        if (textItem.hasOutline) {
            val strokePaint = TextPaint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = textItem.outlineWidth * densityFactor * 2f
                strokeJoin = Paint.Join.ROUND
                color = textItem.outlineColor.toArgb()
                alpha = alphaInt
                clearShadowLayer()
            }
            val strokeLayout = StaticLayout.Builder
                .obtain(textItem.text, 0, textItem.text.length, strokePaint, layoutWidth)
                .setAlignment(alignment)
                .setLineSpacing(0f, textItem.lineSpacing.coerceIn(0.8f, 2.2f))
                .setIncludePad(true)
                .build()
            strokeLayout.draw(canvas)
        }

        layout.draw(canvas)
        canvas.restore()
    }

    /**
     * Renders drawing and eraser vector paths into an offscreen layer before compositing over
     * [targetBitmap], supporting brush styles and opacity cleanly.
     */
    fun renderDrawingPaths(
        targetBitmap: Bitmap,
        drawingPaths: List<DrawPathItem>
    ) {
        if (drawingPaths.isEmpty()) return
        val w = targetBitmap.width.toFloat()
        val h = targetBitmap.height.toFloat()
        val drawCanvas = Canvas(targetBitmap)
        val layerCount = drawCanvas.saveLayer(0f, 0f, w, h, null)
        try {
            drawingPaths.forEach { item ->
                if (item.points.size >= 2) {
                    val path = Path()
                    val p0 = item.points[0]
                    path.moveTo(p0.x * w, p0.y * h)

                    for (i in 1 until item.points.size) {
                        val pPrev = item.points[i - 1]
                        val pCur = item.points[i]
                        val midX = (pPrev.x + pCur.x) / 2f * w
                        val midY = (pPrev.y + pCur.y) / 2f * h
                        path.quadTo(pPrev.x * w, pPrev.y * h, midX, midY)
                    }
                    val pLast = item.points.last()
                    path.lineTo(pLast.x * w, pLast.y * h)

                    val baseStrokePx = item.strokeWidth * (w / 400f).coerceAtLeast(1.5f)
                    if (item.isEraser) {
                        val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.STROKE
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                            strokeWidth = baseStrokePx
                            color = android.graphics.Color.TRANSPARENT
                            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                        }
                        drawCanvas.drawPath(path, erasePaint)
                    } else {
                        val baseArgb = item.color.toArgb()
                        val itemAlpha = (item.opacity.coerceIn(0.05f, 1f) * 255f).roundToInt()

                        when (item.brushType) {
                            DrawBrushType.NEON -> {
                                val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    style = Paint.Style.STROKE
                                    strokeCap = Paint.Cap.ROUND
                                    strokeJoin = Paint.Join.ROUND
                                    strokeWidth = baseStrokePx * 2.1f
                                    color = baseArgb
                                    alpha = (itemAlpha * 0.45f).roundToInt()
                                    maskFilter = BlurMaskFilter(baseStrokePx * 0.9f, BlurMaskFilter.Blur.NORMAL)
                                }
                                drawCanvas.drawPath(path, glowPaint)
                                val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    style = Paint.Style.STROKE
                                    strokeCap = Paint.Cap.ROUND
                                    strokeJoin = Paint.Join.ROUND
                                    strokeWidth = baseStrokePx * 0.72f
                                    color = android.graphics.Color.WHITE
                                    alpha = itemAlpha
                                }
                                drawCanvas.drawPath(path, corePaint)
                            }
                            DrawBrushType.HIGHLIGHTER -> {
                                val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    style = Paint.Style.STROKE
                                    strokeCap = Paint.Cap.SQUARE
                                    strokeJoin = Paint.Join.BEVEL
                                    strokeWidth = baseStrokePx * 1.65f
                                    color = baseArgb
                                    alpha = min(itemAlpha, (0.42f * 255f).roundToInt())
                                }
                                drawCanvas.drawPath(path, hiPaint)
                            }
                            DrawBrushType.MARKER -> {
                                val mkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    style = Paint.Style.STROKE
                                    strokeCap = Paint.Cap.ROUND
                                    strokeJoin = Paint.Join.ROUND
                                    strokeWidth = baseStrokePx * 1.25f
                                    color = baseArgb
                                    alpha = (itemAlpha * 0.88f).roundToInt()
                                }
                                drawCanvas.drawPath(path, mkPaint)
                            }
                            DrawBrushType.CALLIGRAPHY -> {
                                val calPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    style = Paint.Style.STROKE
                                    strokeCap = Paint.Cap.BUTT
                                    strokeJoin = Paint.Join.MITER
                                    strokeWidth = baseStrokePx * 1.15f
                                    color = baseArgb
                                    alpha = itemAlpha
                                }
                                drawCanvas.drawPath(path, calPaint)
                            }
                            DrawBrushType.PEN -> {
                                val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    style = Paint.Style.STROKE
                                    strokeCap = Paint.Cap.ROUND
                                    strokeJoin = Paint.Join.ROUND
                                    strokeWidth = baseStrokePx
                                    color = baseArgb
                                    alpha = itemAlpha
                                }
                                drawCanvas.drawPath(path, penPaint)
                            }
                        }
                    }
                }
            }
        } finally {
            drawCanvas.restoreToCount(layerCount)
        }
    }

    /**
     * Formats exposure triangle text from genuine photo EXIF metadata for badges.
     * Returns null if no authentic camera optical parameters or device model are available.
     */
    fun formatBadgeChipText(metadata: com.example.util.RealCameraMetadata?): String? {
        val parts = mutableListOf<String>()
        metadata?.focalLength?.let { if (it.isNotBlank()) parts.add(it) }
        metadata?.aperture?.let { if (it.isNotBlank()) parts.add(it) }
        metadata?.shutterSpeed?.let { if (it.isNotBlank()) parts.add(it) }
        metadata?.iso?.let { if (it.isNotBlank()) parts.add(it) }

        return if (parts.isNotEmpty()) {
            parts.joinToString(" · ")
        } else if (!metadata?.deviceModel.isNullOrBlank()) {
            metadata.deviceModel
        } else {
            null
        }
    }

    /**
     * Formats film frame badge text using authentic ISO and focal length from photo EXIF.
     */
    fun formatFilmFrameText(metadata: com.example.util.RealCameraMetadata?): String? {
        val focal = metadata?.focalLength?.takeIf { it.isNotBlank() }
        val iso = metadata?.iso?.takeIf { it.isNotBlank() }
        return when {
            focal != null && iso != null -> "$focal FILM · $iso"
            focal != null -> "$focal FILM"
            iso != null -> "FILM · $iso"
            else -> null
        }
    }

    /**
     * Resolves the effective display label for a badge, preserving legacy EXIF suppression
     * when no custom override has been entered on hardware-bound badges.
     */
    fun resolveBadgeDisplayText(
        sticker: StickerOverlayItem,
        metadata: com.example.util.RealCameraMetadata?
    ): String? {
        val custom = sticker.customText?.takeIf { it.isNotBlank() }
        if (custom != null) return custom
        return when (sticker.type) {
            StickerType.DATE_TIMESTAMP -> metadata?.dateTimeFormatted?.takeIf { it.isNotBlank() }
            StickerType.METADATA_CHIP -> formatBadgeChipText(metadata)
            StickerType.FILM_FRAME -> formatFilmFrameText(metadata)
            else -> sticker.type.defaultText
        }
    }

    fun renderStickerBadge(
        canvas: Canvas,
        sticker: StickerOverlayItem,
        density: Float,
        metadata: com.example.util.RealCameraMetadata? = null,
        context: Context? = null
    ) {
        val displayText = resolveBadgeDisplayText(sticker, metadata) ?: return
        val badgeStyle = sticker.styleConfig
        val alphaInt = (badgeStyle.opacity.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        val tf = resolveTypeface(context, badgeStyle.fontFamily, isBold = true, isItalic = false)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = badgeStyle.textColor.toArgb()
            alpha = alphaInt
            textSize = badgeStyle.fontSizeSp * 1.18f * density
            typeface = tf
            textAlign = Paint.Align.CENTER
            if (badgeStyle.hasShadow) {
                setShadowLayer(5f * density, 0f, 2f * density, android.graphics.Color.argb((alphaInt * 0.5f).roundToInt(), 0, 0, 0))
            }
        }

        val bounds = Rect()
        textPaint.getTextBounds(displayText, 0, displayText.length, bounds)
        val measuredW = textPaint.measureText(displayText)
        val baselineY = -(bounds.top + bounds.bottom) / 2f

        when (badgeStyle.shape) {
            BadgeShape.CIRCLE -> {
                val radius = max(24f * density, (measuredW / 2f) + 12f * density)
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = badgeStyle.backgroundColor.toArgb()
                    alpha = alphaInt
                }
                canvas.drawCircle(0f, 0f, radius, bgPaint)
                if (badgeStyle.borderThicknessDp > 0.05f) {
                    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = badgeStyle.borderColor.toArgb()
                        alpha = alphaInt
                        this.style = Paint.Style.STROKE
                        strokeWidth = badgeStyle.borderThicknessDp * 1.3f * density
                    }
                    canvas.drawCircle(0f, 0f, radius - borderPaint.strokeWidth / 2f, borderPaint)
                }
                canvas.drawText(displayText, 0f, baselineY, textPaint)
            }
            else -> {
                val padX = when (badgeStyle.shape) {
                    BadgeShape.PILL -> 20f * density
                    BadgeShape.RIBBON -> 24f * density
                    else -> 16f * density
                }
                val padY = 12f * density
                val w = max(68f * density, measuredW + padX * 2f)
                val h = max(36f * density, bounds.height() + padY * 2f)
                val rect = RectF(-w / 2f, -h / 2f, w / 2f, h / 2f)

                val corner = when (badgeStyle.shape) {
                    BadgeShape.PILL -> h / 2f
                    BadgeShape.RIBBON -> 4f * density
                    else -> badgeStyle.cornerRadiusDp * density
                }

                if (badgeStyle.shape != BadgeShape.OUTLINED && badgeStyle.backgroundColor.alpha > 0.01f) {
                    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = badgeStyle.backgroundColor.toArgb()
                        alpha = ((badgeStyle.backgroundColor.alpha * badgeStyle.opacity) * 255f).roundToInt().coerceIn(0, 255)
                        if (badgeStyle.hasShadow) {
                            setShadowLayer(8f * density, 0f, 3f * density, android.graphics.Color.argb(120, 0, 0, 0))
                        }
                    }
                    if (badgeStyle.shape == BadgeShape.RIBBON) {
                        val notch = h * 0.24f
                        val path = Path().apply {
                            moveTo(rect.left, rect.top)
                            lineTo(rect.right, rect.top)
                            lineTo(rect.right - notch, 0f)
                            lineTo(rect.right, rect.bottom)
                            lineTo(rect.left, rect.bottom)
                            lineTo(rect.left + notch, 0f)
                            close()
                        }
                        canvas.drawPath(path, bgPaint)
                    } else {
                        canvas.drawRoundRect(rect, corner, corner, bgPaint)
                    }
                }

                if (badgeStyle.borderThicknessDp > 0.05f || badgeStyle.shape == BadgeShape.OUTLINED || badgeStyle.shape == BadgeShape.STAMP || badgeStyle.shape == BadgeShape.FRAME) {
                    val borderW = max(badgeStyle.borderThicknessDp, 1.1f) * 1.25f * density
                    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = badgeStyle.borderColor.toArgb()
                        alpha = alphaInt
                        this.style = Paint.Style.STROKE
                        strokeWidth = borderW
                    }
                    if (badgeStyle.shape == BadgeShape.FRAME) {
                        canvas.drawRoundRect(rect, corner, corner, borderPaint)
                        val innerRect = RectF(rect.left + 3f * density, rect.top + 3f * density, rect.right - 3f * density, rect.bottom - 3f * density)
                        borderPaint.strokeWidth = borderW * 0.55f
                        canvas.drawRoundRect(innerRect, (corner - 2f * density).coerceAtLeast(1f), (corner - 2f * density).coerceAtLeast(1f), borderPaint)
                    } else if (badgeStyle.shape == BadgeShape.STAMP) {
                        borderPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
                        canvas.drawRoundRect(rect, corner, corner, borderPaint)
                    } else if (badgeStyle.shape != BadgeShape.RIBBON) {
                        canvas.drawRoundRect(rect, corner, corner, borderPaint)
                    }
                }

                canvas.drawText(displayText, 0f, baselineY, textPaint)
            }
        }
    }

    suspend fun renderEditedBitmapToTempFile(
        context: Context,
        sourceUri: Uri,
        snapshot: PhotoEditSnapshot,
        isImportedPhoto: Boolean = false
    ): RenderedEditorTempResult? = withContext(Dispatchers.IO) {
        try {
            // loadPreviewBitmap already applies ExifInterface.TAG_ORIENTATION upright
            val base = loadPreviewBitmap(context, sourceUri, maxDimPx = 2048) ?: return@withContext null
            var workingBitmap = createOrientedBitmap(
                source = base,
                crop = snapshot.crop,
                applyCropRect = true,
                recycleSource = true
            )

            workingBitmap = processEditedBitmap(
                source = workingBitmap,
                adjustments = snapshot.adjustments,
                filter = snapshot.filter,
                filterIntensity = snapshot.filterIntensity,
                recycleSource = true
            )

            val finalWidth = workingBitmap.width
            val finalHeight = workingBitmap.height

            if (snapshot.drawingPaths.isNotEmpty()) {
                renderDrawingPaths(workingBitmap, snapshot.drawingPaths)
            }

            if (snapshot.textItems.isNotEmpty()) {
                val textCanvas = Canvas(workingBitmap)
                val densityFactor = finalWidth / 600f
                snapshot.textItems.forEach { textItem ->
                    renderTextOverlay(context, textCanvas, textItem, finalWidth.toFloat(), finalHeight.toFloat(), densityFactor)
                }
            }

            val photoMetadata = com.example.util.CameraMetadataUtil.fromUri(
                context = context,
                uri = sourceUri,
                width = finalWidth,
                height = finalHeight,
                allowDeviceFallback = !isImportedPhoto
            )

            if (snapshot.stickerItems.isNotEmpty()) {
                val stickerCanvas = Canvas(workingBitmap)
                val densityFactor = finalWidth / 600f
                snapshot.stickerItems.forEach { sticker ->
                    val targetX = sticker.positionPercent.x * finalWidth
                    val targetY = sticker.positionPercent.y * finalHeight
                    stickerCanvas.save()
                    stickerCanvas.translate(targetX, targetY)
                    if (sticker.rotation != 0f) stickerCanvas.rotate(sticker.rotation)
                    if (sticker.scale != 1.0f) stickerCanvas.scale(sticker.scale, sticker.scale)
                    renderStickerBadge(stickerCanvas, sticker, densityFactor, photoMetadata, context)
                    stickerCanvas.restore()
                }
            }

            val tempFile = java.io.File(context.cacheDir, "studo_editor_watermark_${System.nanoTime()}.jpg")
            java.io.FileOutputStream(tempFile).use { out ->
                workingBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                out.flush()
            }
            if (!workingBitmap.isRecycled) workingBitmap.recycle()
            RenderedEditorTempResult(
                file = tempFile,
                width = finalWidth,
                height = finalHeight
            )
        } catch (t: Throwable) {
            null
        }
    }
}

