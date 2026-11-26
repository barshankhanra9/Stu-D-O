package com.example.night

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class NightStackConfig(
    val isMaxExposure: Boolean = false,
    val maxLongestEdgePx: Int = NightFrameStacker.recommendedMaxEdgePx(),
    val enableAlignment: Boolean = true,
    val enableDenoiseSharpen: Boolean = true
)

data class StackedNightResult(
    val bitmap: Bitmap,
    val alignedFrameCount: Int,
    val appliedShifts: List<Pair<Int, Int>>,
    val sceneMeanLuminance: Float
)

/**
 * Memory-safe, high-speed multi-frame computational night photography stacker.
 *
 * Designed to avoid OutOfMemoryError on mid-range Android devices:
 * - Decodes burst frames sequentially (one Bitmap in flight at a time, immediately recycled).
 * - Uses two packed 32-bit IntArrays (`accumRG`, `accumBW`) instead of 4 full-frame float arrays.
 * - Performs fast hierarchical translational alignment on downsampled luminance thumbnails,
 *   de-ghosted pixel accumulation with highlight preservation, adaptive shadow brightening,
 *   and 3-row streaming edge-aware denoising + sharpening.
 */
object NightFrameStacker {

    private const val WEIGHT_UNIT = 16
    private const val ALIGN_THUMB_LONG_EDGE = 160
    private const val REFINE_THUMB_LONG_EDGE = 320

    fun recommendedMaxEdgePx(isLowRamDevice: Boolean = false): Int {
        if (isLowRamDevice) return 2560
        val maxHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
        return when {
            maxHeapMb <= 256L -> 2560
            maxHeapMb <= 384L -> 3264
            else -> 3840
        }
    }

    /**
     * Decodes a burst of compressed JPEG frames one by one, aligns and stacks them into a single
     * denoised, shadow-lifted, highlight-preserved upright [Bitmap].
     *
     * Calling code may clear [jpegFrames] as soon as this method returns.
     */
    fun stackJpegBurst(
        jpegFrames: List<ByteArray>,
        config: NightStackConfig = NightStackConfig(),
        mirrorHorizontal: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): StackedNightResult {
        require(jpegFrames.isNotEmpty()) { "Burst frame list must not be empty" }

        if (jpegFrames.size == 1) {
            val single = decodeUprightCappedBitmap(
                jpegBytes = jpegFrames[0],
                maxLongestEdgePx = config.maxLongestEdgePx,
                mirrorHorizontal = mirrorHorizontal
            ) ?: throw IllegalStateException("Failed to decode night burst reference frame")
            val w = single.width
            val h = single.height
            val pixels = IntArray(w * h)
            single.getPixels(pixels, 0, w, 0, 0, w, h)
            val meanLuma = applyNightToneAndDenoise(
                pixels = pixels,
                width = w,
                height = h,
                isMaxExposure = config.isMaxExposure,
                enableDenoiseSharpen = config.enableDenoiseSharpen
            )
            single.setPixels(pixels, 0, w, 0, 0, w, h)
            onProgress(1f)
            return StackedNightResult(
                bitmap = single,
                alignedFrameCount = 1,
                appliedShifts = listOf(0 to 0),
                sceneMeanLuminance = meanLuma
            )
        }

        // Decode reference frame 0 first and immediately capture its dimensions
        val refBitmap = decodeUprightCappedBitmap(
            jpegBytes = jpegFrames[0],
            maxLongestEdgePx = config.maxLongestEdgePx,
            mirrorHorizontal = mirrorHorizontal
        ) ?: throw IllegalStateException("Failed to decode night burst reference frame")

        val width = refBitmap.width
        val height = refBitmap.height
        val pixelCount = width * height

        val refPixels = IntArray(pixelCount)
        refBitmap.getPixels(refPixels, 0, width, 0, 0, width, height)
        val refAlignThumb = if (config.enableAlignment) buildGrayscaleThumb(refBitmap, ALIGN_THUMB_LONG_EDGE) else null
        val refRefineThumb = if (config.enableAlignment) buildGrayscaleThumb(refBitmap, REFINE_THUMB_LONG_EDGE) else null
        refBitmap.recycle()

        val accumRG = IntArray(pixelCount)
        val accumBW = IntArray(pixelCount)
        initAccumulatorFromReference(refPixels, accumRG, accumBW)

        val shifts = mutableListOf(0 to 0)
        val rowBuffer = IntArray(width)
        val total = jpegFrames.size

        for (i in 1 until total) {
            var frameBmp: Bitmap? = null
            try {
                frameBmp = decodeUprightCappedBitmap(
                    jpegBytes = jpegFrames[i],
                    maxLongestEdgePx = config.maxLongestEdgePx,
                    mirrorHorizontal = mirrorHorizontal,
                    exactTargetWidth = width,
                    exactTargetHeight = height
                )
                if (frameBmp != null) {
                    val shift = if (config.enableAlignment && refAlignThumb != null && refRefineThumb != null) {
                        estimateShiftHierarchical(
                            refCoarse = refAlignThumb,
                            refFine = refRefineThumb,
                            candidateBitmap = frameBmp,
                            fullWidth = width,
                            fullHeight = height
                        )
                    } else {
                        0 to 0
                    }
                    shifts.add(shift)
                    accumulateFrameRows(
                        candidateBitmap = frameBmp,
                        dx = shift.first,
                        dy = shift.second,
                        refPixels = refPixels,
                        accumRG = accumRG,
                        accumBW = accumBW,
                        rowBuffer = rowBuffer,
                        width = width,
                        height = height
                    )
                }
            } finally {
                if (frameBmp != null && !frameBmp.isRecycled) {
                    frameBmp.recycle()
                }
            }
            onProgress((i.toFloat() / total.toFloat()) * 0.80f)
        }

        // Normalize stacked RGB back into refPixels in-place
        normalizeAccumulatorsInPlace(refPixels, accumRG, accumBW)
        onProgress(0.88f)

        val meanLuma = applyNightToneAndDenoise(
            pixels = refPixels,
            width = width,
            height = height,
            isMaxExposure = config.isMaxExposure,
            enableDenoiseSharpen = config.enableDenoiseSharpen
        )
        onProgress(0.96f)

        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        outBitmap.setPixels(refPixels, 0, width, 0, 0, width, height)
        onProgress(1f)

        return StackedNightResult(
            bitmap = outBitmap,
            alignedFrameCount = shifts.size,
            appliedShifts = shifts,
            sceneMeanLuminance = meanLuma
        )
    }

    /**
     * Stacks a pre-decoded list of same-sized [Bitmap]s without recycling caller-owned inputs.
     * Primarily intended for deterministic unit testing and in-memory bitmap pipelines.
     */
    fun stackBitmaps(
        frames: List<Bitmap>,
        config: NightStackConfig = NightStackConfig(),
        onProgress: (Float) -> Unit = {}
    ): StackedNightResult {
        require(frames.isNotEmpty()) { "Bitmap frame list must not be empty" }
        val ref = frames[0]
        val width = ref.width
        val height = ref.height
        val pixelCount = width * height

        val refPixels = IntArray(pixelCount)
        ref.getPixels(refPixels, 0, width, 0, 0, width, height)

        if (frames.size == 1) {
            val meanLuma = applyNightToneAndDenoise(
                pixels = refPixels,
                width = width,
                height = height,
                isMaxExposure = config.isMaxExposure,
                enableDenoiseSharpen = config.enableDenoiseSharpen
            )
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            out.setPixels(refPixels, 0, width, 0, 0, width, height)
            onProgress(1f)
            return StackedNightResult(
                bitmap = out,
                alignedFrameCount = 1,
                appliedShifts = listOf(0 to 0),
                sceneMeanLuminance = meanLuma
            )
        }

        val refAlignThumb = if (config.enableAlignment) buildGrayscaleThumb(ref, ALIGN_THUMB_LONG_EDGE) else null
        val refRefineThumb = if (config.enableAlignment) buildGrayscaleThumb(ref, REFINE_THUMB_LONG_EDGE) else null
        val accumRG = IntArray(pixelCount)
        val accumBW = IntArray(pixelCount)
        initAccumulatorFromReference(refPixels, accumRG, accumBW)

        val shifts = mutableListOf(0 to 0)
        val rowBuffer = IntArray(width)

        for (i in 1 until frames.size) {
            val cand = frames[i]
            val alignedCand = if (cand.width == width && cand.height == height) {
                cand
            } else {
                Bitmap.createScaledBitmap(cand, width, height, true)
            }
            try {
                val shift = if (config.enableAlignment && refAlignThumb != null && refRefineThumb != null) {
                    estimateShiftHierarchical(
                        refCoarse = refAlignThumb,
                        refFine = refRefineThumb,
                        candidateBitmap = alignedCand,
                        fullWidth = width,
                        fullHeight = height
                    )
                } else {
                    0 to 0
                }
                shifts.add(shift)
                accumulateFrameRows(
                    candidateBitmap = alignedCand,
                    dx = shift.first,
                    dy = shift.second,
                    refPixels = refPixels,
                    accumRG = accumRG,
                    accumBW = accumBW,
                    rowBuffer = rowBuffer,
                    width = width,
                    height = height
                )
            } finally {
                if (alignedCand !== cand && !alignedCand.isRecycled) {
                    alignedCand.recycle()
                }
            }
            onProgress((i.toFloat() / frames.size.toFloat()) * 0.80f)
        }

        normalizeAccumulatorsInPlace(refPixels, accumRG, accumBW)
        onProgress(0.88f)
        val meanLuma = applyNightToneAndDenoise(
            pixels = refPixels,
            width = width,
            height = height,
            isMaxExposure = config.isMaxExposure,
            enableDenoiseSharpen = config.enableDenoiseSharpen
        )
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(refPixels, 0, width, 0, 0, width, height)
        onProgress(1f)
        return StackedNightResult(
            bitmap = out,
            alignedFrameCount = shifts.size,
            appliedShifts = shifts,
            sceneMeanLuminance = meanLuma
        )
    }

    private fun initAccumulatorFromReference(
        refPixels: IntArray,
        accumRG: IntArray,
        accumBW: IntArray
    ) {
        for (idx in refPixels.indices) {
            val p = refPixels[idx]
            val r = ((p ushr 16) and 0xFF) * WEIGHT_UNIT
            val g = ((p ushr 8) and 0xFF) * WEIGHT_UNIT
            val b = (p and 0xFF) * WEIGHT_UNIT
            accumRG[idx] = (r shl 16) or g
            accumBW[idx] = (b shl 16) or WEIGHT_UNIT
        }
    }

    /**
     * Reads [candidateBitmap] row-by-row into [rowBuffer] and accumulates aligned pixels into
     * [accumRG] and [accumBW] with motion de-ghosting and highlight preservation.
     *
     * Shift convention: candidate shifted by `(dx, dy)` relative to reference means reference `(x, y)`
     * corresponds to candidate `(x + dx, y + dy)`.
     */
    private fun accumulateFrameRows(
        candidateBitmap: Bitmap,
        dx: Int,
        dy: Int,
        refPixels: IntArray,
        accumRG: IntArray,
        accumBW: IntArray,
        rowBuffer: IntArray,
        width: Int,
        height: Int
    ) {
        for (y in 0 until height) {
            val srcY = y + dy
            if (srcY < 0 || srcY >= height) continue
            candidateBitmap.getPixels(rowBuffer, 0, width, 0, srcY, width, 1)
            val rowOffset = y * width
            for (x in 0 until width) {
                val srcX = x + dx
                if (srcX < 0 || srcX >= width) continue

                val idx = rowOffset + x
                val refP = refPixels[idx]
                val rr = (refP ushr 16) and 0xFF
                val rg = (refP ushr 8) and 0xFF
                val rb = refP and 0xFF

                val srcP = rowBuffer[srcX]
                val sr = (srcP ushr 16) and 0xFF
                val sg = (srcP ushr 8) and 0xFF
                val sb = srcP and 0xFF

                val refLuma = (rr * 77 + rg * 150 + rb * 29) ushr 8
                val srcLuma = (sr * 77 + sg * 150 + sb * 29) ushr 8

                // Highlight preservation: keep streetlamps, signs, and bright speculars from clipping
                if (refLuma >= 224 && srcLuma > refLuma) {
                    continue
                }
                if (srcLuma >= 248 && refLuma < 210) {
                    continue
                }

                // Motion de-ghosting: allow moderate brightness/exposure boost while rejecting moving objects
                val dr = abs(sr - rr)
                val dg = abs(sg - rg)
                val db = abs(sb - rb)
                val maxChannelDiff = max(dr, max(dg, db))
                val lumaRatioDelta = abs(srcLuma - refLuma)

                val weight = when {
                    maxChannelDiff <= 38 -> WEIGHT_UNIT
                    lumaRatioDelta <= 54 && maxChannelDiff <= 72 -> 12
                    lumaRatioDelta <= 82 && maxChannelDiff <= 104 -> 6
                    else -> 0
                }
                if (weight == 0) continue

                // Preserve highlight chroma/headroom when candidate starts clipping
                val adjR: Int
                val adjG: Int
                val adjB: Int
                if (srcLuma > 225 && refLuma in 160..223) {
                    val softScale = 225f / srcLuma.toFloat()
                    adjR = (sr * softScale).toInt().coerceIn(0, 255)
                    adjG = (sg * softScale).toInt().coerceIn(0, 255)
                    adjB = (sb * softScale).toInt().coerceIn(0, 255)
                } else {
                    adjR = sr
                    adjG = sg
                    adjB = sb
                }

                val rgPacked = accumRG[idx]
                val bwPacked = accumBW[idx]
                val nextR = ((rgPacked ushr 16) and 0xFFFF) + adjR * weight
                val nextG = (rgPacked and 0xFFFF) + adjG * weight
                val nextB = ((bwPacked ushr 16) and 0xFFFF) + adjB * weight
                val nextW = (bwPacked and 0xFFFF) + weight
                accumRG[idx] = (nextR shl 16) or nextG
                accumBW[idx] = (nextB shl 16) or nextW
            }
        }
    }

    private fun normalizeAccumulatorsInPlace(
        outPixels: IntArray,
        accumRG: IntArray,
        accumBW: IntArray
    ) {
        for (idx in outPixels.indices) {
            val rgPacked = accumRG[idx]
            val bwPacked = accumBW[idx]
            val w = max(1, bwPacked and 0xFFFF)
            val r = (((rgPacked ushr 16) and 0xFFFF) / w).coerceIn(0, 255)
            val g = ((rgPacked and 0xFFFF) / w).coerceIn(0, 255)
            val b = (((bwPacked ushr 16) and 0xFFFF) / w).coerceIn(0, 255)
            outPixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /**
     * Applies adaptive low-light shadow lift with highlight protection, subtle shadow vibrance,
     * and 3-row streaming edge-aware denoising + unsharp detail crispness.
     */
    internal fun applyNightToneAndDenoise(
        pixels: IntArray,
        width: Int,
        height: Int,
        isMaxExposure: Boolean,
        enableDenoiseSharpen: Boolean
    ): Float {
        if (pixels.isEmpty() || width <= 0 || height <= 0) return 0f

        // 1. Sample average scene luminance
        var lumaSum = 0.0
        var sampleCount = 0
        val step = max(1, pixels.size / 16384)
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            val l = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            lumaSum += l
            sampleCount++
            i += step
        }
        val meanLuma = if (sampleCount > 0) (lumaSum / sampleCount).toFloat() else 0.25f

        // Adapt shadow gain: stronger when scene is truly dark, subtle when already well-exposed
        val darknessScore = ((0.42f - meanLuma) / 0.34f).coerceIn(0.15f, 1.0f)
        val gamma = if (isMaxExposure) {
            1.0f - (0.36f * darknessScore) // ~0.64 in dark scenes
        } else {
            1.0f - (0.28f * darknessScore) // ~0.72 in dark scenes
        }
        val shadowToeLift = if (isMaxExposure) 0.03f * darknessScore else 0.02f * darknessScore

        // Build 256-entry highlight-preserving luminance ratio LUT
        val gainLut = FloatArray(256)
        gainLut[0] = 1f
        for (v in 1..255) {
            val x = v / 255f
            // Smooth shadow lift + highlight shoulder roll-off above 0.74
            val lifted = (x.pow(gamma) + shadowToeLift * (1f - x) * (1f - x)).coerceIn(0f, 1f)
            val compressed = if (lifted > 0.74f) {
                val t = (lifted - 0.74f) / 0.26f
                0.74f + 0.26f * (t / (1f + 0.35f * t * t)) * 1.25f
            } else {
                lifted
            }.coerceIn(0f, 1f)
            gainLut[v] = (compressed / x).coerceIn(0.85f, 3.2f)
        }

        // Apply luminance-ratio tone curve + subtle shadow chroma restoration
        val vibStrength = if (isMaxExposure) 0.14f else 0.10f
        for (idx in pixels.indices) {
            val p = pixels[idx]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF

            val lumaInt = ((r * 54 + g * 183 + b * 19) ushr 8).coerceIn(0, 255)
            val gain = gainLut[lumaInt]
            // Preserve near-white pixels strictly against blowing out
            val highlightAtten = if (lumaInt > 218) {
                val t = (lumaInt - 218) / 37f
                gain * (1f - t) + 1.0f * t
            } else {
                gain
            }

            var nr = (r * highlightAtten)
            var ng = (g * highlightAtten)
            var nb = (b * highlightAtten)

            // Gentle vibrance boost in midtones/shadows without clipping skin or highlights
            val nl = (0.2126f * nr + 0.7152f * ng + 0.0722f * nb)
            val maxC = max(nr, max(ng, nb))
            val minC = min(nr, min(ng, nb))
            val chroma = (maxC - minC) / 255f
            val lNorm = (nl / 255f).coerceIn(0f, 1f)
            val satBoost = 1f + vibStrength * (1f - chroma) * (1f - lNorm * lNorm)
            nr = nl + (nr - nl) * satBoost
            ng = nl + (ng - nl) * satBoost
            nb = nl + (nb - nl) * satBoost

            val ir = nr.roundToInt().coerceIn(0, 255)
            val ig = ng.roundToInt().coerceIn(0, 255)
            val ib = nb.roundToInt().coerceIn(0, 255)
            pixels[idx] = (0xFF shl 24) or (ir shl 16) or (ig shl 8) or ib
        }

        // 2. Streaming 3-row edge-aware bilateral denoise + micro-contrast sharpen
        if (enableDenoiseSharpen && width >= 3 && height >= 3) {
            applyStreamingEdgeAwareDenoiseAndSharpen(pixels, width, height)
        }

        return meanLuma
    }

    private fun applyStreamingEdgeAwareDenoiseAndSharpen(
        pixels: IntArray,
        width: Int,
        height: Int
    ) {
        var prevRow = IntArray(width)
        var curRow = IntArray(width)
        var nextRow = IntArray(width)

        System.arraycopy(pixels, 0, prevRow, 0, width)
        System.arraycopy(pixels, width, curRow, 0, width)

        val edgeThreshold = 24
        val sharpenStrength = 0.22f

        for (y in 1 until height - 1) {
            System.arraycopy(pixels, (y + 1) * width, nextRow, 0, width)
            val dstOffset = y * width

            for (x in 1 until width - 1) {
                val c = curRow[x]
                val cr = (c ushr 16) and 0xFF
                val cg = (c ushr 8) and 0xFF
                val cb = c and 0xFF
                val cLuma = (cr * 77 + cg * 150 + cb * 29) ushr 8

                val nTop = prevRow[x]
                val nBot = nextRow[x]
                val nLeft = curRow[x - 1]
                val nRight = curRow[x + 1]

                var sumR = cr * 4
                var sumG = cg * 4
                var sumB = cb * 4
                var wSum = 4
                var edgeMax = 0

                val neighbors = intArrayOf(nTop, nBot, nLeft, nRight)
                for (n in neighbors) {
                    val nr = (n ushr 16) and 0xFF
                    val ng = (n ushr 8) and 0xFF
                    val nb = n and 0xFF
                    val nLuma = (nr * 77 + ng * 150 + nb * 29) ushr 8
                    val diff = abs(nLuma - cLuma)
                    if (diff > edgeMax) edgeMax = diff
                    if (diff <= edgeThreshold) {
                        val w = if (diff <= 10) 2 else 1
                        sumR += nr * w
                        sumG += ng * w
                        sumB += nb * w
                        wSum += w
                    }
                }

                val smoothR = sumR.toFloat() / wSum
                val smoothG = sumG.toFloat() / wSum
                val smoothB = sumB.toFloat() / wSum

                // Preserve flat denoised regions while crisping real structural edges
                val outR: Int
                val outG: Int
                val outB: Int
                if (edgeMax > edgeThreshold) {
                    val detailR = cr - smoothR
                    val detailG = cg - smoothG
                    val detailB = cb - smoothB
                    outR = (cr + detailR * sharpenStrength).roundToInt().coerceIn(0, 255)
                    outG = (cg + detailG * sharpenStrength).roundToInt().coerceIn(0, 255)
                    outB = (cb + detailB * sharpenStrength).roundToInt().coerceIn(0, 255)
                } else {
                    outR = smoothR.roundToInt().coerceIn(0, 255)
                    outG = smoothG.roundToInt().coerceIn(0, 255)
                    outB = smoothB.roundToInt().coerceIn(0, 255)
                }
                pixels[dstOffset + x] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
            }

            // Rotate row buffers without allocating new arrays
            val tmp = prevRow
            prevRow = curRow
            curRow = nextRow
            nextRow = tmp
        }
    }

    private data class GrayscaleThumb(
        val width: Int,
        val height: Int,
        val luma: ByteArray
    )

    private fun buildGrayscaleThumb(bitmap: Bitmap, longEdge: Int): GrayscaleThumb {
        val bw = bitmap.width.coerceAtLeast(1)
        val bh = bitmap.height.coerceAtLeast(1)
        val scale = min(1f, longEdge.toFloat() / max(bw, bh).toFloat())
        val tw = (bw * scale).roundToInt().coerceAtLeast(16)
        val th = (bh * scale).roundToInt().coerceAtLeast(16)
        val scaled = if (tw == bw && th == bh) bitmap else Bitmap.createScaledBitmap(bitmap, tw, th, true)
        return try {
            val px = IntArray(tw * th)
            scaled.getPixels(px, 0, tw, 0, 0, tw, th)
            val luma = ByteArray(tw * th)
            for (i in px.indices) {
                val p = px[i]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                luma[i] = ((r * 77 + g * 150 + b * 29) ushr 8).toByte()
            }
            GrayscaleThumb(tw, th, luma)
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) {
                scaled.recycle()
            }
        }
    }

    private fun estimateShiftHierarchical(
        refCoarse: GrayscaleThumb,
        refFine: GrayscaleThumb,
        candidateBitmap: Bitmap,
        fullWidth: Int,
        fullHeight: Int
    ): Pair<Int, Int> {
        val candCoarse = buildGrayscaleThumb(candidateBitmap, ALIGN_THUMB_LONG_EDGE)
        val maxSearchCoarse = max(6, (max(refCoarse.width, refCoarse.height) * 0.09f).roundToInt().coerceAtMost(14))
        val (coarseDx, coarseDy) = searchBestTranslation(
            ref = refCoarse,
            cand = candCoarse,
            centerDx = 0,
            centerDy = 0,
            radius = maxSearchCoarse
        )

        val candFine = buildGrayscaleThumb(candidateBitmap, REFINE_THUMB_LONG_EDGE)
        val ratioFineOverCoarseX = refFine.width.toFloat() / refCoarse.width.toFloat()
        val ratioFineOverCoarseY = refFine.height.toFloat() / refCoarse.height.toFloat()
        val initFineDx = (coarseDx * ratioFineOverCoarseX).roundToInt()
        val initFineDy = (coarseDy * ratioFineOverCoarseY).roundToInt()
        val (fineDx, fineDy) = searchBestTranslation(
            ref = refFine,
            cand = candFine,
            centerDx = initFineDx,
            centerDy = initFineDy,
            radius = 3
        )

        val scaleFullX = fullWidth.toFloat() / refFine.width.toFloat()
        val scaleFullY = fullHeight.toFloat() / refFine.height.toFloat()
        val fullDx = (fineDx * scaleFullX).roundToInt()
        val fullDy = (fineDy * scaleFullY).roundToInt()

        // Guard against outlier shift exceeding 10% of frame dimensions
        val maxDx = (fullWidth * 0.10f).roundToInt()
        val maxDy = (fullHeight * 0.10f).roundToInt()
        return fullDx.coerceIn(-maxDx, maxDx) to fullDy.coerceIn(-maxDy, maxDy)
    }

    private fun searchBestTranslation(
        ref: GrayscaleThumb,
        cand: GrayscaleThumb,
        centerDx: Int,
        centerDy: Int,
        radius: Int
    ): Pair<Int, Int> {
        val w = min(ref.width, cand.width)
        val h = min(ref.height, cand.height)
        if (w < 16 || h < 16) return centerDx to centerDy

        val marginX = max(radius + 2, (w * 0.14f).roundToInt())
        val marginY = max(radius + 2, (h * 0.14f).roundToInt())
        val startX = marginX.coerceIn(0, w - 1)
        val endX = (w - marginX).coerceIn(startX + 1, w)
        val startY = marginY.coerceIn(0, h - 1)
        val endY = (h - marginY).coerceIn(startY + 1, h)

        var bestDx = centerDx
        var bestDy = centerDy
        var bestScore = Long.MAX_VALUE

        for (dy in (centerDy - radius)..(centerDy + radius)) {
            for (dx in (centerDx - radius)..(centerDx + radius)) {
                var sad = 0L
                var count = 0
                var y = startY
                while (y < endY) {
                    val cy = y + dy
                    if (cy in 0 until h) {
                        val refRow = y * ref.width
                        val candRow = cy * cand.width
                        var x = startX
                        while (x < endX) {
                            val cx = x + dx
                            if (cx in 0 until w) {
                                val rVal = ref.luma[refRow + x].toInt() and 0xFF
                                val cVal = cand.luma[candRow + cx].toInt() and 0xFF
                                sad += abs(rVal - cVal)
                                count++
                            }
                            x += 2
                        }
                    }
                    y += 2
                }
                if (count > 0) {
                    val normalizedSad = (sad * 1024L) / count
                    if (normalizedSad < bestScore) {
                        bestScore = normalizedSad
                        bestDx = dx
                        bestDy = dy
                    }
                }
            }
        }
        return bestDx to bestDy
    }

    internal fun decodeUprightCappedBitmap(
        jpegBytes: ByteArray,
        maxLongestEdgePx: Int,
        mirrorHorizontal: Boolean = false,
        exactTargetWidth: Int? = null,
        exactTargetHeight: Int? = null
    ): Bitmap? {
        val exifOrientation = try {
            ByteArrayInputStream(jpegBytes).use { s ->
                ExifInterface(s).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, boundsOpts)
        val rawW = boundsOpts.outWidth
        val rawH = boundsOpts.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        val longest = max(rawW, rawH)
        var sampleSize = 1
        if (maxLongestEdgePx > 0 && longest > maxLongestEdgePx) {
            while ((longest / (sampleSize * 2)) >= maxLongestEdgePx) {
                sampleSize *= 2
            }
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, decodeOpts)
            ?: return null

        val matrix = Matrix()
        when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
        }
        if (mirrorHorizontal) {
            matrix.postScale(-1f, 1f)
        }

        val upright = if (!matrix.isIdentity) {
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated !== decoded && !decoded.isRecycled) decoded.recycle()
            rotated
        } else {
            decoded
        }

        if (exactTargetWidth != null && exactTargetHeight != null &&
            (upright.width != exactTargetWidth || upright.height != exactTargetHeight)
        ) {
            val scaled = Bitmap.createScaledBitmap(upright, exactTargetWidth, exactTargetHeight, true)
            if (scaled !== upright && !upright.isRecycled) upright.recycle()
            return scaled
        }

        val uprightLongest = max(upright.width, upright.height)
        if (maxLongestEdgePx > 0 && uprightLongest > maxLongestEdgePx) {
            val scale = maxLongestEdgePx.toFloat() / uprightLongest.toFloat()
            val targetW = (upright.width * scale).roundToInt().coerceAtLeast(1)
            val targetH = (upright.height * scale).roundToInt().coerceAtLeast(1)
            val capped = Bitmap.createScaledBitmap(upright, targetW, targetH, true)
            if (capped !== upright && !upright.isRecycled) upright.recycle()
            return capped
        }
        return upright
    }
}
