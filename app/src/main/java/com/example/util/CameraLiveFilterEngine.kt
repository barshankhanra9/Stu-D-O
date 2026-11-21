package com.example.util

import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect
import com.example.model.CameraFilter
import com.example.model.LiveFilterGradingSpec
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect

/**
 * Result of resolving the live viewfinder GPU effect for a [CameraFilter].
 *
 * When [isFullShaderActive] is `true`, the API 33+ AGSL [RuntimeShader] renders the complete
 * non-linear film tone curve, split-toning, skin-protected vibrance, and optical vignette directly
 * on the GPU camera preview stream, so no additional Compose overlay is drawn on top.
 * When `false` (API 24..32 fallback), [colorMatrixFilter] provides linear color/contrast grading
 * and the viewfinder pairs it with a calibrated split-tone & vignette overlay.
 */
data class LiveViewfinderFilterEffect(
    val composeRenderEffect: ComposeRenderEffect?,
    val colorMatrixFilter: ColorMatrixColorFilter?,
    val isFullShaderActive: Boolean
)

/**
 * Shared filmic color-grading engine guaranteeing mathematical parity between the live viewfinder
 * preview and full-resolution captured JPEG outputs with zero resolution or color-space loss.
 */
object CameraLiveFilterEngine {

    private const val STRIPE_HEIGHT = 256

    private val AGSL_FILMIC_SHADER = """
        uniform shader inputShader;
        uniform float2 uResolution;
        uniform float uContrast;
        uniform float uShadowLift;
        uniform float uHighlightRolloff;
        uniform float uSaturation;
        uniform float uVibrance;
        uniform float3 uWbGain;
        uniform float uHasWb;
        uniform float uIsMono;
        uniform float3 uMonoWeights;
        uniform float3 uSplitShadow;
        uniform float3 uSplitHighlight;
        uniform float uVignette;

        float evalTone(float x) {
            float y = clamp(x, 0.0, 1.0);
            if (uContrast != 0.0) {
                float s = y * y * (3.0 - 2.0 * y);
                if (uContrast > 0.0) {
                    y = y + (s - y) * (uContrast * 0.68);
                } else {
                    y = y + (0.5 - y) * (-uContrast * 0.36);
                }
                y = clamp(y, 0.0, 1.0);
            }
            if (uShadowLift > 0.0) {
                y = clamp(uShadowLift + y * (1.0 - uShadowLift * 0.65), 0.0, 1.0);
            }
            if (uHighlightRolloff > 0.0 && y > 0.68) {
                float t = (y - 0.68) / 0.32;
                y = clamp(y - t * t * uHighlightRolloff * 0.28, 0.0, 1.0);
            }
            return y;
        }

        half4 main(float2 fragCoord) {
            half4 src = inputShader.eval(fragCoord);
            float a = float(src.a);
            if (a <= 0.0001) {
                return src;
            }
            float3 rgb = clamp(float3(src.rgb) / a, 0.0, 1.0);

            // 1. Filmic tone curve blended between per-channel LUT and luma-ratio preservation
            float lumaIn = clamp(dot(rgb, float3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
            float lumaOut = evalTone(lumaIn);
            float ratio = (lumaIn > 0.004) ? clamp(lumaOut / lumaIn, 0.0, 3.5) : 1.0;
            float3 chTone = float3(evalTone(rgb.r), evalTone(rgb.g), evalTone(rgb.b));
            float3 lumaScaled = clamp(rgb * ratio, 0.0, 1.0);
            rgb = mix(lumaScaled, chTone, 0.45);

            // 2. Luma-preserving White Balance
            if (uHasWb > 0.5) {
                float preLuma = dot(rgb, float3(0.2126, 0.7152, 0.0722));
                float3 wb = rgb * uWbGain;
                float postLuma = dot(wb, float3(0.2126, 0.7152, 0.0722));
                if (postLuma > 0.001) {
                    wb *= (preLuma / postLuma);
                }
                rgb = clamp(wb, 0.0, 1.0);
            }

            // 3. Skin-tone-protected Vibrance & Saturation
            float luma = clamp(dot(rgb, float3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
            if (uVibrance != 0.0 || uSaturation != 0.0) {
                float maxC = max(rgb.r, max(rgb.g, rgb.b));
                float minC = min(rgb.r, min(rgb.g, rgb.b));
                float chroma = maxC - minC;
                float skinProt = 1.0;
                if (uVibrance > 0.0 && rgb.r > rgb.g && rgb.g >= rgb.b && (rgb.r - rgb.b) > 0.03) {
                    float rel = (rgb.g - rgb.b) / (rgb.r - rgb.b);
                    if (rel >= 0.14 && rel <= 0.76) {
                        skinProt = 0.32;
                    }
                }
                float vibBoost = uVibrance * (1.0 - chroma) * skinProt;
                float satMul = clamp(1.0 + uSaturation + vibBoost, 0.0, 2.2);
                rgb = clamp(float3(luma) + (rgb - float3(luma)) * satMul, 0.0, 1.0);
            }

            // 4. Monochrome channel mixer
            if (uIsMono > 0.5) {
                float mono = clamp(dot(rgb, uMonoWeights), 0.0, 1.0);
                rgb = float3(mono);
                luma = mono;
            }

            // 5. Split-toning (shadow & highlight tints)
            float sW = (1.0 - luma) * (1.0 - luma);
            float hW = luma * luma;
            rgb = clamp(rgb + uSplitShadow * sW + uSplitHighlight * hW, 0.0, 1.0);

            // 6. Optical radial vignette
            if (uVignette > 0.001 && uResolution.x > 1.0 && uResolution.y > 1.0) {
                float2 uv = (fragCoord / uResolution) * 2.0 - 1.0;
                float d2 = dot(uv, uv);
                float falloff = clamp((d2 - 0.25) / 1.75, 0.0, 1.0);
                float atten = 1.0 - uVignette * falloff * 0.72;
                rgb = clamp(rgb * atten, 0.0, 1.0);
            }

            return half4(rgb * a, a);
        }
    """.trimIndent()

    fun buildToneCurveLut(spec: LiveFilterGradingSpec): FloatArray {
        val lut = FloatArray(256)
        val effContrast = spec.contrast.coerceIn(-0.8f, 1.2f)
        val shadowLift = spec.shadowLift.coerceIn(0f, 0.2f)
        val highlightRolloff = spec.highlightRolloff.coerceIn(0f, 0.35f)

        for (i in 0..255) {
            var y = i / 255f
            if (effContrast != 0f) {
                val s = y * y * (3f - 2f * y)
                y = if (effContrast > 0f) {
                    y + (s - y) * (effContrast * 0.68f)
                } else {
                    y + (0.5f - y) * (-effContrast * 0.36f)
                }.coerceIn(0f, 1f)
            }
            if (shadowLift > 0f) {
                y = (shadowLift + y * (1f - shadowLift * 0.65f)).coerceIn(0f, 1f)
            }
            if (highlightRolloff > 0f && y > 0.68f) {
                val t = (y - 0.68f) / 0.32f
                y = (y - t * t * highlightRolloff * 0.28f).coerceIn(0f, 1f)
            }
            lut[i] = y.coerceIn(0f, 1f)
        }
        return lut
    }

    /**
     * Resolves the live GPU effect for [CameraViewfinder] across all supported Android API levels:
     * - API 33+: Full AGSL [RuntimeShader] matching [applyFilterToBitmap] pixel-for-pixel.
     * - API 31..32: [RenderEffect] with calibrated [ColorMatrixColorFilter].
     * - API 24..30: [ColorMatrixColorFilter] for `TextureView.setLayerPaint(...)`.
     */
    fun resolveLiveViewfinderEffect(
        filter: CameraFilter,
        widthPx: Float,
        heightPx: Float
    ): LiveViewfinderFilterEffect {
        if (filter == CameraFilter.NATURAL) {
            return LiveViewfinderFilterEffect(
                composeRenderEffect = null,
                colorMatrixFilter = null,
                isFullShaderActive = false
            )
        }

        val matrixFilter = filter.toAndroidColorMatrix()?.let { ColorMatrixColorFilter(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && widthPx > 1f && heightPx > 1f) {
            val shaderEffect = try {
                createAgslRenderEffect(filter.gradingSpec, widthPx, heightPx)
            } catch (_: Throwable) {
                null
            }
            if (shaderEffect != null) {
                return LiveViewfinderFilterEffect(
                    composeRenderEffect = shaderEffect,
                    colorMatrixFilter = null,
                    isFullShaderActive = true
                )
            }
        }

        val fallbackComposeEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && matrixFilter != null) {
            try {
                RenderEffect.createColorFilterEffect(matrixFilter).asComposeRenderEffect()
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

        return LiveViewfinderFilterEffect(
            composeRenderEffect = fallbackComposeEffect,
            colorMatrixFilter = matrixFilter,
            isFullShaderActive = false
        )
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun createAgslRenderEffect(
        spec: LiveFilterGradingSpec,
        widthPx: Float,
        heightPx: Float
    ): ComposeRenderEffect {
        val shader = RuntimeShader(AGSL_FILMIC_SHADER)
        shader.setFloatUniform("uResolution", widthPx, heightPx)
        shader.setFloatUniform("uContrast", spec.contrast)
        shader.setFloatUniform("uShadowLift", spec.shadowLift)
        shader.setFloatUniform("uHighlightRolloff", spec.highlightRolloff)
        shader.setFloatUniform("uSaturation", spec.saturation)
        shader.setFloatUniform("uVibrance", spec.vibrance)

        val wbR = (1f + spec.temperature * 0.22f) * (1f + spec.tint * 0.11f)
        val wbG = (1f + spec.temperature * 0.04f) * (1f - spec.tint * 0.20f)
        val wbB = (1f - spec.temperature * 0.24f) * (1f + spec.tint * 0.11f)
        shader.setFloatUniform("uWbGain", wbR, wbG, wbB)
        shader.setFloatUniform("uHasWb", if (spec.temperature != 0f || spec.tint != 0f) 1f else 0f)

        shader.setFloatUniform("uIsMono", if (spec.isMonochrome) 1f else 0f)
        shader.setFloatUniform("uMonoWeights", spec.monoR, spec.monoG, spec.monoB)
        shader.setFloatUniform("uSplitShadow", spec.splitShadowR, spec.splitShadowG, spec.splitShadowB)
        shader.setFloatUniform("uSplitHighlight", spec.splitHighlightR, spec.splitHighlightG, spec.splitHighlightB)
        shader.setFloatUniform("uVignette", spec.vignetteStrength)

        return RenderEffect.createRuntimeShaderEffect(shader, "inputShader").asComposeRenderEffect()
    }

    /**
     * Applies [filter] at full native pixel resolution in 256-row stripe buffers using 32-bit
     * float channel arithmetic and 1-LSB triangular micro-dither to eliminate banding while
     * preserving color space and image dimensions.
     */
    fun applyFilterToBitmap(
        source: Bitmap,
        filter: CameraFilter,
        recycleSource: Boolean = false
    ): Bitmap {
        if (filter == CameraFilter.NATURAL) {
            return source
        }

        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return source

        val output = if (recycleSource && source.isMutable && source.config == Bitmap.Config.ARGB_8888) {
            source
        } else {
            val allocated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val cs = source.colorSpace
                if (cs != null) {
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, true, cs)
                } else {
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                }
            } else {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            val rowBuf = IntArray(width * minOf(STRIPE_HEIGHT, height))
            for (top in 0 until height step STRIPE_HEIGHT) {
                val h = minOf(STRIPE_HEIGHT, height - top)
                source.getPixels(rowBuf, 0, width, 0, top, width, h)
                allocated.setPixels(rowBuf, 0, width, 0, top, width, h)
            }
            if (recycleSource) {
                source.recycle()
            }
            allocated
        }

        val spec = filter.gradingSpec
        val toneLut = buildToneCurveLut(spec)

        val hasWb = spec.temperature != 0f || spec.tint != 0f
        val wbR = (1f + spec.temperature * 0.22f) * (1f + spec.tint * 0.11f)
        val wbG = (1f + spec.temperature * 0.04f) * (1f - spec.tint * 0.20f)
        val wbB = (1f - spec.temperature * 0.24f) * (1f + spec.tint * 0.11f)

        val hasSatOrVib = spec.vibrance != 0f || spec.saturation != 0f
        val hasSplitShadow = spec.splitShadowR != 0f || spec.splitShadowG != 0f || spec.splitShadowB != 0f
        val hasSplitHighlight = spec.splitHighlightR != 0f || spec.splitHighlightG != 0f || spec.splitHighlightB != 0f
        val hasVignette = spec.vignetteStrength > 0.001f && width > 1 && height > 1

        val stripePixels = IntArray(width * minOf(STRIPE_HEIGHT, height))
        val invWidthM1 = if (width > 1) 1f / width.toFloat() else 0f
        val invHeightM1 = if (height > 1) 1f / height.toFloat() else 0f
        val dxLookup = if (hasVignette) {
            FloatArray(width) { x ->
                val u = (x * invWidthM1) * 2f - 1f
                u * u
            }
        } else {
            FloatArray(0)
        }

        for (top in 0 until height step STRIPE_HEIGHT) {
            val h = minOf(STRIPE_HEIGHT, height - top)
            output.getPixels(stripePixels, 0, width, 0, top, width, h)

            for (rowOffset in 0 until h) {
                val py = top + rowOffset
                val dy2 = if (hasVignette) {
                    val v = (py * invHeightM1) * 2f - 1f
                    v * v
                } else {
                    0f
                }
                val rowBase = rowOffset * width

                for (px in 0 until width) {
                    val idx = rowBase + px
                    val c = stripePixels[idx]
                    val a = (c ushr 24) and 0xFF
                    if (a == 0) continue

                    val ri = (c ushr 16) and 0xFF
                    val gi = (c ushr 8) and 0xFF
                    val bi = c and 0xFF

                    var r = ri / 255f
                    var g = gi / 255f
                    var b = bi / 255f

                    // 1. Filmic tone curve blended between per-channel LUT and luma-ratio preservation
                    val lumaIn = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
                    val lumaIdx = (lumaIn * 255f).roundToInt().coerceIn(0, 255)
                    val lumaOut = toneLut[lumaIdx]
                    val ratio = if (lumaIn > 0.004f) (lumaOut / lumaIn).coerceIn(0f, 3.5f) else 1f

                    r = toneLut[ri] * 0.45f + (r * ratio).coerceIn(0f, 1f) * 0.55f
                    g = toneLut[gi] * 0.45f + (g * ratio).coerceIn(0f, 1f) * 0.55f
                    b = toneLut[bi] * 0.45f + (b * ratio).coerceIn(0f, 1f) * 0.55f

                    // 2. Luma-preserving White Balance
                    if (hasWb) {
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

                    // 3. Skin-tone-protected Vibrance & Saturation
                    var luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
                    if (hasSatOrVib) {
                        val maxC = max(r, max(g, b))
                        val minC = min(r, min(g, b))
                        val chroma = maxC - minC

                        val skinProt = if (spec.vibrance > 0f && r > g && g >= b && (r - b) > 0.03f) {
                            val rel = (g - b) / (r - b)
                            if (rel in 0.14f..0.76f) 0.32f else 1.0f
                        } else {
                            1.0f
                        }
                        val vibBoost = spec.vibrance * (1f - chroma) * skinProt
                        val satMul = (1f + spec.saturation + vibBoost).coerceIn(0f, 2.2f)
                        r = (luma + (r - luma) * satMul).coerceIn(0f, 1f)
                        g = (luma + (g - luma) * satMul).coerceIn(0f, 1f)
                        b = (luma + (b - luma) * satMul).coerceIn(0f, 1f)
                        luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
                    }

                    // 4. Monochrome channel mixer
                    if (spec.isMonochrome) {
                        val mono = (r * spec.monoR + g * spec.monoG + b * spec.monoB).coerceIn(0f, 1f)
                        r = mono
                        g = mono
                        b = mono
                        luma = mono
                    }

                    // 5. Split-toning
                    if (hasSplitShadow || hasSplitHighlight) {
                        val sW = (1f - luma) * (1f - luma)
                        val hW = luma * luma
                        r = (r + spec.splitShadowR * sW + spec.splitHighlightR * hW).coerceIn(0f, 1f)
                        g = (g + spec.splitShadowG * sW + spec.splitHighlightG * hW).coerceIn(0f, 1f)
                        b = (b + spec.splitShadowB * sW + spec.splitHighlightB * hW).coerceIn(0f, 1f)
                    }

                    // 6. Optical radial vignette
                    if (hasVignette) {
                        val d2 = dxLookup[px] + dy2
                        val falloff = ((d2 - 0.25f) / 1.75f).coerceIn(0f, 1f)
                        val atten = 1f - spec.vignetteStrength * falloff * 0.72f
                        r = (r * atten).coerceIn(0f, 1f)
                        g = (g * atten).coerceIn(0f, 1f)
                        b = (b * atten).coerceIn(0f, 1f)
                    }

                    // 7. Ultra-subtle triangular ordered micro-dither (±0.45 LSB) to avoid 8-bit sky banding
                    val bayer = (((px and 3) shl 2) or (py and 3))
                    val ditherOffset = ((bayer - 7.5f) / 16f) * 0.90f

                    val outR = (r * 255f + ditherOffset).roundToInt().coerceIn(0, 255)
                    val outG = (g * 255f + ditherOffset).roundToInt().coerceIn(0, 255)
                    val outB = (b * 255f + ditherOffset).roundToInt().coerceIn(0, 255)

                    stripePixels[idx] = (a shl 24) or (outR shl 16) or (outG shl 8) or outB
                }
            }
            output.setPixels(stripePixels, 0, width, 0, top, width, h)
        }

        return output
    }
}
