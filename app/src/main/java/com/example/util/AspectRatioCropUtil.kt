package com.example.util

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.ExifInterface
import com.example.model.CaptureAspectRatio
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

object AspectRatioCropUtil {

    /**
     * Computes a center-crop [Rect] in upright display-pixel coordinates ([width] x [height])
     * to match [aspectRatio] (using [fullScreenAspectRatio] when [aspectRatio] == [CaptureAspectRatio.RATIO_FULL]).
     * Returns null when the bitmap already matches the target aspect ratio within tolerance.
     */
    fun computeCenterCropRect(
        width: Int,
        height: Int,
        aspectRatio: CaptureAspectRatio,
        fullScreenAspectRatio: Float
    ): Rect? {
        if (width <= 1 || height <= 1) return null

        val shortOverLong = when (aspectRatio) {
            CaptureAspectRatio.RATIO_4_3 -> 3f / 4f
            CaptureAspectRatio.RATIO_16_9 -> 9f / 16f
            CaptureAspectRatio.RATIO_1_1 -> 1f
            CaptureAspectRatio.RATIO_FULL -> {
                val safeFull = if (fullScreenAspectRatio.isFinite() && fullScreenAspectRatio > 0f) {
                    fullScreenAspectRatio
                } else {
                    9f / 20f
                }
                val normalized = if (safeFull > 1f) 1f / safeFull else safeFull
                normalized.coerceIn(0.25f, 1f)
            }
        }

        val targetBitmapRatio = if (width >= height) {
            1f / shortOverLong
        } else {
            shortOverLong
        }

        val currentRatio = width.toFloat() / height.toFloat()
        if (abs(currentRatio - targetBitmapRatio) <= 0.015f) {
            return null
        }

        val cropWidth: Int
        val cropHeight: Int
        if (currentRatio > targetBitmapRatio) {
            cropHeight = height
            cropWidth = (height * targetBitmapRatio).roundToInt().coerceIn(1, width)
        } else {
            cropWidth = width
            cropHeight = (width / targetBitmapRatio).roundToInt().coerceIn(1, height)
        }

        val left = ((width - cropWidth) / 2).coerceAtLeast(0)
        val top = ((height - cropHeight) / 2).coerceAtLeast(0)
        return Rect(left, top, min(width, left + cropWidth), min(height, top + cropHeight))
    }

    fun needsCrop(
        width: Int,
        height: Int,
        aspectRatio: CaptureAspectRatio,
        fullScreenAspectRatio: Float
    ): Boolean = computeCenterCropRect(width, height, aspectRatio, fullScreenAspectRatio) != null

    fun orientedDimensions(rawWidth: Int, rawHeight: Int, exifOrientation: Int): Pair<Int, Int> {
        return when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE -> rawHeight to rawWidth
            else -> rawWidth to rawHeight
        }
    }

    fun cropBitmapIfNeeded(
        bitmap: Bitmap,
        aspectRatio: CaptureAspectRatio,
        fullScreenAspectRatio: Float
    ): Bitmap {
        val rect = computeCenterCropRect(bitmap.width, bitmap.height, aspectRatio, fullScreenAspectRatio)
            ?: return bitmap
        if (rect.width() <= 0 || rect.height() <= 0) return bitmap
        if (rect.left == 0 && rect.top == 0 && rect.width() == bitmap.width && rect.height() == bitmap.height) {
            return bitmap
        }
        return Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
    }
}
