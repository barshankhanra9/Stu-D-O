package com.example.util

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ByteBuffer
import kotlin.math.abs

@Immutable
data class ProAssistOverlayFrame(
    val frameWidth: Int,
    val frameHeight: Int,
    val peakingBitmap: ImageBitmap? = null,
    val zebraMaskBitmap: ImageBitmap? = null,
    val hasPeakingEdges: Boolean = false,
    val hasZebraHighlights: Boolean = false,
    val frameSequence: Long = 0L
)

/**
 * Single-pass, zero-allocation (after warmup) Y-plane analyzer for Pro Mode Focus Peaking
 * and Zebra Stripes highlight clipping detection.
 *
 * Operates on subsampled Y (luma) pixels from CameraX ImageAnalysis, rotates & mirrors into
 * upright display coordinates matching PreviewView, computes a fast 3x3 Sobel L1 edge gradient
 * for Focus Peaking, and flags blown-out highlights exceeding the selected IRE luma threshold
 * in the exact same loop.
 */
class ProAssistAnalyzer(
    private val subsampleStep: Int = DEFAULT_SUBSAMPLE_STEP
) {
    companion object {
        const val DEFAULT_SUBSAMPLE_STEP = 2
        private const val RING_BUFFER_SIZE = 3
    }

    private var lumaGrid = IntArray(0)
    private var peakingPixels = IntArray(0)
    private var zebraPixels = IntArray(0)

    private val peakingRingBitmaps = arrayOfNulls<Bitmap>(RING_BUFFER_SIZE)
    private val peakingRingImageBitmaps = arrayOfNulls<ImageBitmap>(RING_BUFFER_SIZE)
    private val zebraRingBitmaps = arrayOfNulls<Bitmap>(RING_BUFFER_SIZE)
    private val zebraRingImageBitmaps = arrayOfNulls<ImageBitmap>(RING_BUFFER_SIZE)
    private var ringW = 0
    private var ringH = 0
    private var ringIndex = 0
    private var sequence = 0L

    fun analyzeImageProxy(
        imageProxy: ImageProxy,
        isFrontCamera: Boolean,
        enablePeaking: Boolean,
        peakingColorArgb: Int,
        peakingThreshold: Int,
        enableZebra: Boolean,
        zebraLumaThreshold: Int
    ): ProAssistOverlayFrame? {
        if (!enablePeaking && !enableZebra) return null
        val planes = imageProxy.planes
        if (planes.isEmpty()) return null
        val w = imageProxy.width
        val h = imageProxy.height
        if (w <= 2 || h <= 2) return null

        val yPlane = planes[0]
        return analyzeFromYPlane(
            width = w,
            height = h,
            yBuffer = yPlane.buffer,
            yRowStride = yPlane.rowStride,
            yPixelStride = yPlane.pixelStride,
            rotationDegrees = imageProxy.imageInfo.rotationDegrees,
            isFrontCamera = isFrontCamera,
            enablePeaking = enablePeaking,
            peakingColorArgb = peakingColorArgb,
            peakingThreshold = peakingThreshold,
            enableZebra = enableZebra,
            zebraLumaThreshold = zebraLumaThreshold
        )
    }

    fun analyzeFromYPlane(
        width: Int,
        height: Int,
        yBuffer: ByteBuffer,
        yRowStride: Int,
        yPixelStride: Int,
        rotationDegrees: Int,
        isFrontCamera: Boolean,
        enablePeaking: Boolean,
        peakingColorArgb: Int,
        peakingThreshold: Int,
        enableZebra: Boolean,
        zebraLumaThreshold: Int
    ): ProAssistOverlayFrame? {
        if ((!enablePeaking && !enableZebra) || width <= 2 || height <= 2 || yPixelStride <= 0) {
            return null
        }

        val step = subsampleStep.coerceAtLeast(1)
        val subW = (width + step - 1) / step
        val subH = (height + step - 1) / step
        if (subW < 3 || subH < 3) return null

        val normalizedRot = ((rotationDegrees % 360) + 360) % 360
        val rotated90Or270 = normalizedRot == 90 || normalizedRot == 270
        val outW = if (rotated90Or270) subH else subW
        val outH = if (rotated90Or270) subW else subH
        val totalPixels = outW * outH

        ensureBuffers(outW, outH, totalPixels)

        val yLimit = yBuffer.limit()
        val maxSrcX = width - 1
        val maxSrcY = height - 1
        val grid = lumaGrid

        // 1. Subsample & orient Y plane into contiguous upright luma grid (pure integer math)
        when (normalizedRot) {
            90 -> {
                for (oy in 0 until outH) {
                    val sx = (oy * step).coerceIn(0, maxSrcX)
                    val sxOffset = sx * yPixelStride
                    val rowBase = oy * outW
                    for (ox in 0 until outW) {
                        val mx = if (isFrontCamera) (outW - 1 - ox) else ox
                        val sy = ((outW - 1 - mx) * step).coerceIn(0, maxSrcY)
                        val idx = sy * yRowStride + sxOffset
                        grid[rowBase + ox] = if (idx in 0 until yLimit) (yBuffer.get(idx).toInt() and 0xFF) else 0
                    }
                }
            }
            180 -> {
                for (oy in 0 until outH) {
                    val sy = ((outH - 1 - oy) * step).coerceIn(0, maxSrcY)
                    val syOffset = sy * yRowStride
                    val rowBase = oy * outW
                    for (ox in 0 until outW) {
                        val mx = if (isFrontCamera) (outW - 1 - ox) else ox
                        val sx = ((outW - 1 - mx) * step).coerceIn(0, maxSrcX)
                        val idx = syOffset + sx * yPixelStride
                        grid[rowBase + ox] = if (idx in 0 until yLimit) (yBuffer.get(idx).toInt() and 0xFF) else 0
                    }
                }
            }
            270 -> {
                for (oy in 0 until outH) {
                    val sx = ((outH - 1 - oy) * step).coerceIn(0, maxSrcX)
                    val sxOffset = sx * yPixelStride
                    val rowBase = oy * outW
                    for (ox in 0 until outW) {
                        val mx = if (isFrontCamera) (outW - 1 - ox) else ox
                        val sy = (mx * step).coerceIn(0, maxSrcY)
                        val idx = sy * yRowStride + sxOffset
                        grid[rowBase + ox] = if (idx in 0 until yLimit) (yBuffer.get(idx).toInt() and 0xFF) else 0
                    }
                }
            }
            else -> {
                for (oy in 0 until outH) {
                    val sy = (oy * step).coerceIn(0, maxSrcY)
                    val syOffset = sy * yRowStride
                    val rowBase = oy * outW
                    for (ox in 0 until outW) {
                        val mx = if (isFrontCamera) (outW - 1 - ox) else ox
                        val sx = (mx * step).coerceIn(0, maxSrcX)
                        val idx = syOffset + sx * yPixelStride
                        grid[rowBase + ox] = if (idx in 0 until yLimit) (yBuffer.get(idx).toInt() and 0xFF) else 0
                    }
                }
            }
        }

        // 2. Single combined pass: 3x3 Sobel Focus Peaking + Zebra IRE highlight mask
        val baseRgb = peakingColorArgb and 0x00FFFFFF
        var hasPeaking = false
        var hasZebra = false

        val peakArr = peakingPixels
        val zebraArr = zebraPixels

        if (enablePeaking) {
            // Clear 1px border for Sobel kernel
            val lastRowBase = (outH - 1) * outW
            for (x in 0 until outW) {
                peakArr[x] = 0
                peakArr[lastRowBase + x] = 0
            }
            for (y in 1 until outH - 1) {
                val rb = y * outW
                peakArr[rb] = 0
                peakArr[rb + outW - 1] = 0
            }
        }

        if (enableZebra) {
            val lastRowBase = (outH - 1) * outW
            for (x in 0 until outW) {
                if (grid[x] >= zebraLumaThreshold) {
                    zebraArr[x] = -1
                    hasZebra = true
                } else {
                    zebraArr[x] = 0
                }
                val bIdx = lastRowBase + x
                if (grid[bIdx] >= zebraLumaThreshold) {
                    zebraArr[bIdx] = -1
                    hasZebra = true
                } else {
                    zebraArr[bIdx] = 0
                }
            }
            for (y in 1 until outH - 1) {
                val rb = y * outW
                if (grid[rb] >= zebraLumaThreshold) {
                    zebraArr[rb] = -1
                    hasZebra = true
                } else {
                    zebraArr[rb] = 0
                }
                val rIdx = rb + outW - 1
                if (grid[rIdx] >= zebraLumaThreshold) {
                    zebraArr[rIdx] = -1
                    hasZebra = true
                } else {
                    zebraArr[rIdx] = 0
                }
            }
        }

        for (y in 1 until outH - 1) {
            val rowPrev = (y - 1) * outW
            val rowCurr = y * outW
            val rowNext = (y + 1) * outW

            for (x in 1 until outW - 1) {
                val idx = rowCurr + x

                if (enableZebra) {
                    if (grid[idx] >= zebraLumaThreshold) {
                        zebraArr[idx] = -1 // 0xFFFFFFFF opaque white mask pixel
                        hasZebra = true
                    } else {
                        zebraArr[idx] = 0
                    }
                }

                if (enablePeaking) {
                    val tl = grid[rowPrev + x - 1]
                    val tc = grid[rowPrev + x]
                    val tr = grid[rowPrev + x + 1]
                    val ml = grid[rowCurr + x - 1]
                    val mr = grid[rowCurr + x + 1]
                    val bl = grid[rowNext + x - 1]
                    val bc = grid[rowNext + x]
                    val br = grid[rowNext + x + 1]

                    val gx = -tl + tr - (ml shl 1) + (mr shl 1) - bl + br
                    val gy = -tl - (tc shl 1) - tr + bl + (bc shl 1) + br
                    val mag = abs(gx) + abs(gy)

                    if (mag >= peakingThreshold) {
                        val alpha = (175 + ((mag - peakingThreshold) * 80) / 140).coerceIn(175, 255)
                        peakArr[idx] = (alpha shl 24) or baseRgb
                        hasPeaking = true
                    } else {
                        peakArr[idx] = 0
                    }
                }
            }
        }

        val slot = ringIndex
        ringIndex = (ringIndex + 1) % RING_BUFFER_SIZE
        sequence++

        var peakingImg: ImageBitmap? = null
        if (enablePeaking && hasPeaking) {
            val bmp = obtainPeakingBitmap(slot, outW, outH)
            bmp.setPixels(peakArr, 0, outW, 0, 0, outW, outH)
            peakingImg = peakingRingImageBitmaps[slot]
        }

        var zebraImg: ImageBitmap? = null
        if (enableZebra && hasZebra) {
            val bmp = obtainZebraBitmap(slot, outW, outH)
            bmp.setPixels(zebraArr, 0, outW, 0, 0, outW, outH)
            zebraImg = zebraRingImageBitmaps[slot]
        }

        return ProAssistOverlayFrame(
            frameWidth = outW,
            frameHeight = outH,
            peakingBitmap = peakingImg,
            zebraMaskBitmap = zebraImg,
            hasPeakingEdges = hasPeaking,
            hasZebraHighlights = hasZebra,
            frameSequence = sequence
        )
    }

    private fun ensureBuffers(outW: Int, outH: Int, totalPixels: Int) {
        if (lumaGrid.size != totalPixels) {
            lumaGrid = IntArray(totalPixels)
            peakingPixels = IntArray(totalPixels)
            zebraPixels = IntArray(totalPixels)
        }
        if (ringW != outW || ringH != outH) {
            ringW = outW
            ringH = outH
            for (i in 0 until RING_BUFFER_SIZE) {
                peakingRingBitmaps[i] = null
                peakingRingImageBitmaps[i] = null
                zebraRingBitmaps[i] = null
                zebraRingImageBitmaps[i] = null
            }
        }
    }

    private fun obtainPeakingBitmap(slot: Int, w: Int, h: Int): Bitmap {
        val existing = peakingRingBitmaps[slot]
        if (existing != null && !existing.isRecycled && existing.width == w && existing.height == h) {
            return existing
        }
        val created = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        peakingRingBitmaps[slot] = created
        peakingRingImageBitmaps[slot] = created.asImageBitmap()
        return created
    }

    private fun obtainZebraBitmap(slot: Int, w: Int, h: Int): Bitmap {
        val existing = zebraRingBitmaps[slot]
        if (existing != null && !existing.isRecycled && existing.width == w && existing.height == h) {
            return existing
        }
        val created = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        zebraRingBitmaps[slot] = created
        zebraRingImageBitmaps[slot] = created.asImageBitmap()
        return created
    }

    fun release() {
        for (i in 0 until RING_BUFFER_SIZE) {
            peakingRingBitmaps[i] = null
            peakingRingImageBitmaps[i] = null
            zebraRingBitmaps[i] = null
            zebraRingImageBitmaps[i] = null
        }
        ringW = 0
        ringH = 0
    }
}
