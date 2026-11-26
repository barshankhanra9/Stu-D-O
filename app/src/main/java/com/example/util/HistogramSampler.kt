package com.example.util

import androidx.camera.core.ImageProxy
import com.example.model.HistogramData
import java.nio.ByteBuffer

/**
 * High-FPS, zero-allocation YUV_420_888 histogram sampler.
 * Subsamples every 4th row and 4th column on a background ImageAnalysis executor
 * and computes normalized Luma, Red, Green, and Blue distributions along with
 * pure black (0) and pure white (255) pixel clipping fractions.
 */
object HistogramSampler {

    const val DEFAULT_BIN_COUNT = 32
    const val SUBSAMPLE_STEP = 4

    class ScratchBuffers(val binCount: Int = DEFAULT_BIN_COUNT) {
        val luma = IntArray(binCount)
        val red = IntArray(binCount)
        val green = IntArray(binCount)
        val blue = IntArray(binCount)

        fun clear() {
            luma.fill(0)
            red.fill(0)
            green.fill(0)
            blue.fill(0)
        }
    }

    fun sampleImageProxy(
        imageProxy: ImageProxy,
        buffers: ScratchBuffers,
        step: Int = SUBSAMPLE_STEP
    ): HistogramData {
        val planes = imageProxy.planes
        if (planes.isEmpty()) return HistogramData.Empty

        val w = imageProxy.width
        val h = imageProxy.height
        if (w <= 0 || h <= 0) return HistogramData.Empty

        val yPlane = planes[0]
        val hasUv = planes.size >= 3
        val uPlane = if (hasUv) planes[1] else null
        val vPlane = if (hasUv) planes[2] else null

        return sampleFromYuvBuffers(
            width = w,
            height = h,
            yBuffer = yPlane.buffer,
            yRowStride = yPlane.rowStride,
            yPixelStride = yPlane.pixelStride,
            uBuffer = uPlane?.buffer,
            uRowStride = uPlane?.rowStride ?: 0,
            uPixelStride = uPlane?.pixelStride ?: 0,
            vBuffer = vPlane?.buffer,
            vRowStride = vPlane?.rowStride ?: 0,
            vPixelStride = vPlane?.pixelStride ?: 0,
            buffers = buffers,
            step = step
        )
    }

    fun sampleFromYuvBuffers(
        width: Int,
        height: Int,
        yBuffer: ByteBuffer,
        yRowStride: Int,
        yPixelStride: Int,
        uBuffer: ByteBuffer?,
        uRowStride: Int,
        uPixelStride: Int,
        vBuffer: ByteBuffer?,
        vRowStride: Int,
        vPixelStride: Int,
        buffers: ScratchBuffers,
        step: Int = SUBSAMPLE_STEP
    ): HistogramData {
        if (width <= 0 || height <= 0) return HistogramData.Empty
        val safeStep = step.coerceAtLeast(1)
        val binCount = buffers.binCount
        buffers.clear()

        val lumaBins = buffers.luma
        val redBins = buffers.red
        val greenBins = buffers.green
        val blueBins = buffers.blue

        val yLimit = yBuffer.limit()
        val hasUv = uBuffer != null && vBuffer != null && uPixelStride > 0 && vPixelStride > 0
        val uLimit = uBuffer?.limit() ?: 0
        val vLimit = vBuffer?.limit() ?: 0

        var totalSamples = 0
        var shadowClipped = 0
        var highlightClipped = 0

        var row = 0
        while (row < height) {
            val yRowOffset = row * yRowStride
            val uvRow = row shr 1
            val uRowOffset = uvRow * uRowStride
            val vRowOffset = uvRow * vRowStride

            var col = 0
            while (col < width) {
                val yIdx = yRowOffset + col * yPixelStride
                if (yIdx in 0 until yLimit) {
                    val yVal = yBuffer.get(yIdx).toInt() and 0xFF
                    totalSamples++
                    if (yVal == 0) {
                        shadowClipped++
                    } else if (yVal == 255) {
                        highlightClipped++
                    }

                    val lumaBin = ((yVal * binCount) / 256).coerceIn(0, binCount - 1)
                    lumaBins[lumaBin]++

                    if (hasUv) {
                        val uvCol = col shr 1
                        val uIdx = uRowOffset + uvCol * uPixelStride
                        val vIdx = vRowOffset + uvCol * vPixelStride
                        val uCentered = if (uIdx in 0 until uLimit) ((uBuffer!!.get(uIdx).toInt() and 0xFF) - 128) else 0
                        val vCentered = if (vIdx in 0 until vLimit) ((vBuffer!!.get(vIdx).toInt() and 0xFF) - 128) else 0

                        // Fast fixed-point BT.601 YUV -> RGB
                        val yScaled = yVal shl 10
                        val r = ((yScaled + 1404 * vCentered) shr 10).coerceIn(0, 255)
                        val g = ((yScaled - 345 * uCentered - 715 * vCentered) shr 10).coerceIn(0, 255)
                        val b = ((yScaled + 1774 * uCentered) shr 10).coerceIn(0, 255)

                        redBins[((r * binCount) / 256).coerceIn(0, binCount - 1)]++
                        greenBins[((g * binCount) / 256).coerceIn(0, binCount - 1)]++
                        blueBins[((b * binCount) / 256).coerceIn(0, binCount - 1)]++
                    } else {
                        redBins[lumaBin]++
                        greenBins[lumaBin]++
                        blueBins[lumaBin]++
                    }
                }
                col += safeStep
            }
            row += safeStep
        }

        if (totalSamples == 0) return HistogramData.Empty

        var lumaPeak = 1
        var rgbPeak = 1
        for (i in 0 until binCount) {
            if (lumaBins[i] > lumaPeak) lumaPeak = lumaBins[i]
            if (redBins[i] > rgbPeak) rgbPeak = redBins[i]
            if (greenBins[i] > rgbPeak) rgbPeak = greenBins[i]
            if (blueBins[i] > rgbPeak) rgbPeak = blueBins[i]
        }

        val invLumaPeak = 1f / lumaPeak.toFloat()
        val invRgbPeak = 1f / rgbPeak.toFloat()

        val lumaNorm = List(binCount) { i -> (lumaBins[i] * invLumaPeak).coerceIn(0f, 1f) }
        val redNorm = List(binCount) { i -> (redBins[i] * invRgbPeak).coerceIn(0f, 1f) }
        val greenNorm = List(binCount) { i -> (greenBins[i] * invRgbPeak).coerceIn(0f, 1f) }
        val blueNorm = List(binCount) { i -> (blueBins[i] * invRgbPeak).coerceIn(0f, 1f) }

        val invTotal = 1f / totalSamples.toFloat()
        return HistogramData(
            lumaBins = lumaNorm,
            redBins = redNorm,
            greenBins = greenNorm,
            blueBins = blueNorm,
            shadowClipFraction = (shadowClipped * invTotal).coerceIn(0f, 1f),
            highlightClipFraction = (highlightClipped * invTotal).coerceIn(0f, 1f)
        )
    }
}
