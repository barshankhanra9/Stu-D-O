package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class HighResImageMetadata(
    val rawWidth: Int,
    val rawHeight: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val exifOrientation: Int,
    val mimeType: String?
)

data class TileKey(
    val sampleSize: Int,
    val col: Int,
    val row: Int
)

data class DecodedTile(
    val key: TileKey,
    val displayRect: Rect,
    val bitmap: Bitmap
)

interface RegionDecodeSource {
    val isRecycled: Boolean
    fun decodeRegion(rawRect: Rect, sampleSize: Int): Bitmap?
    fun recycle()
}

private class AndroidBitmapRegionDecodeSource(
    private val decoder: BitmapRegionDecoder
) : RegionDecodeSource {
    override val isRecycled: Boolean
        get() = decoder.isRecycled

    override fun decodeRegion(rawRect: Rect, sampleSize: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return decoder.decodeRegion(rawRect, opts)
    }

    override fun recycle() {
        if (!decoder.isRecycled) {
            decoder.recycle()
        }
    }
}

/**
 * Pure geometry and BitmapRegionDecoder helper for sharp pinch-to-zoom without loading
 * full-resolution multi-megapixel bitmaps into a single GPU texture.
 */
object HighResZoomMath {

    const val DEFAULT_MAX_BASE_EDGE_PX = 2048
    const val DEFAULT_TILE_OUTPUT_PX = 512

    fun isOrientationSwapped(exifOrientation: Int): Boolean {
        return exifOrientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            exifOrientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            exifOrientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            exifOrientation == ExifInterface.ORIENTATION_TRANSVERSE
    }

    fun computeFitScale(
        viewportWidth: Float,
        viewportHeight: Float,
        imageWidth: Int,
        imageHeight: Int
    ): Float {
        if (viewportWidth <= 0f || viewportHeight <= 0f || imageWidth <= 0 || imageHeight <= 0) {
            return 1f
        }
        return min(viewportWidth / imageWidth.toFloat(), viewportHeight / imageHeight.toFloat())
    }

    fun computeMaxPanOffsets(
        viewportWidth: Float,
        viewportHeight: Float,
        fittedWidth: Float,
        fittedHeight: Float,
        scale: Float
    ): Offset {
        val maxPanX = ((fittedWidth * scale - viewportWidth) / 2f).coerceAtLeast(0f)
        val maxPanY = ((fittedHeight * scale - viewportHeight) / 2f).coerceAtLeast(0f)
        return Offset(maxPanX, maxPanY)
    }

    /**
     * Computes power-of-2 sampleSize so base image fits comfortably within maxBaseEdgePx.
     */
    fun computeBaseSampleSize(
        displayWidth: Int,
        displayHeight: Int,
        maxBaseEdgePx: Int = DEFAULT_MAX_BASE_EDGE_PX
    ): Int {
        val maxDim = max(displayWidth, displayHeight)
        if (maxDim <= 0 || maxBaseEdgePx <= 0) return 1
        var sampleSize = 1
        while (maxDim / sampleSize > maxBaseEdgePx) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * Computes target power-of-2 inSampleSize needed for the current zoom level so decoded
     * image resolution is always >= 1x screen pixel density.
     */
    fun computeTargetSampleSize(
        displayWidth: Int,
        displayHeight: Int,
        fitScale: Float,
        zoomScale: Float
    ): Int {
        if (displayWidth <= 0 || displayHeight <= 0 || fitScale <= 0f || zoomScale <= 0f) {
            return 1
        }
        val renderedWidth = displayWidth.toFloat() * fitScale * zoomScale
        if (renderedWidth <= 0f) return 1
        val rawPixelsPerScreenPixel = displayWidth.toFloat() / renderedWidth
        var sampleSize = 1
        while (sampleSize * 2 <= rawPixelsPerScreenPixel) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * Computes visible tile grid keys and their upright display-coordinate rectangles,
     * prioritized by proximity to the viewport center.
     */
    fun computeVisibleTiles(
        displayWidth: Int,
        displayHeight: Int,
        viewportWidth: Float,
        viewportHeight: Float,
        fitScale: Float,
        scale: Float,
        offset: Offset,
        sampleSize: Int,
        tileOutputSizePx: Int = DEFAULT_TILE_OUTPUT_PX
    ): List<Pair<TileKey, Rect>> {
        if (displayWidth <= 0 || displayHeight <= 0 ||
            viewportWidth <= 0f || viewportHeight <= 0f ||
            fitScale <= 0f || scale <= 0f || sampleSize <= 0
        ) {
            return emptyList()
        }

        val totalScale = fitScale * scale
        val visLeft = ((0f - viewportWidth / 2f - offset.x) / totalScale + displayWidth / 2f)
            .coerceIn(0f, displayWidth.toFloat())
        val visRight = ((viewportWidth - viewportWidth / 2f - offset.x) / totalScale + displayWidth / 2f)
            .coerceIn(0f, displayWidth.toFloat())
        val visTop = ((0f - viewportHeight / 2f - offset.y) / totalScale + displayHeight / 2f)
            .coerceIn(0f, displayHeight.toFloat())
        val visBottom = ((viewportHeight - viewportHeight / 2f - offset.y) / totalScale + displayHeight / 2f)
            .coerceIn(0f, displayHeight.toFloat())

        if (visRight <= visLeft || visBottom <= visTop) {
            return emptyList()
        }

        val tileSpan = max(1, tileOutputSizePx * sampleSize)
        val startCol = floor(visLeft / tileSpan).toInt().coerceAtLeast(0)
        val endCol = (ceil(visRight / tileSpan).toInt() - 1)
            .coerceIn(startCol, max(0, (displayWidth - 1) / tileSpan))
        val startRow = floor(visTop / tileSpan).toInt().coerceAtLeast(0)
        val endRow = (ceil(visBottom / tileSpan).toInt() - 1)
            .coerceIn(startRow, max(0, (displayHeight - 1) / tileSpan))

        val centerDispX = (visLeft + visRight) * 0.5f
        val centerDispY = (visTop + visBottom) * 0.5f

        val result = ArrayList<Pair<TileKey, Rect>>((endCol - startCol + 1) * (endRow - startRow + 1))
        for (row in startRow..endRow) {
            val top = row * tileSpan
            val bottom = min(displayHeight, (row + 1) * tileSpan)
            if (bottom <= top) continue
            for (col in startCol..endCol) {
                val left = col * tileSpan
                val right = min(displayWidth, (col + 1) * tileSpan)
                if (right <= left) continue
                result.add(TileKey(sampleSize, col, row) to Rect(left, top, right, bottom))
            }
        }

        // Prioritize center tiles so the focal subject sharpens first
        result.sortBy { (_, rect) ->
            val dx = rect.exactCenterX() - centerDispX
            val dy = rect.exactCenterY() - centerDispY
            dx * dx + dy * dy
        }
        return result
    }

    /**
     * Maps a rectangle in upright display space [0..displayW, 0..displayH] back into raw encoded
     * image coordinates [0..rawW, 0..rawH] according to EXIF orientation.
     */
    fun displayRectToRawRect(
        displayRect: Rect,
        rawWidth: Int,
        rawHeight: Int,
        exifOrientation: Int
    ): Rect {
        val l = displayRect.left
        val t = displayRect.top
        val r = displayRect.right
        val b = displayRect.bottom

        val rawRect = when (exifOrientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                Rect(rawWidth - r, t, rawWidth - l, b)
            ExifInterface.ORIENTATION_ROTATE_180 ->
                Rect(rawWidth - r, rawHeight - b, rawWidth - l, rawHeight - t)
            ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                Rect(l, rawHeight - b, r, rawHeight - t)
            ExifInterface.ORIENTATION_TRANSPOSE ->
                Rect(t, l, b, r)
            ExifInterface.ORIENTATION_ROTATE_90 ->
                Rect(t, rawHeight - r, b, rawHeight - l)
            ExifInterface.ORIENTATION_TRANSVERSE ->
                Rect(rawWidth - b, rawHeight - r, rawWidth - t, rawHeight - l)
            ExifInterface.ORIENTATION_ROTATE_270 ->
                Rect(rawWidth - b, l, rawWidth - t, r)
            else ->
                Rect(l, t, r, b)
        }

        val clampedLeft = rawRect.left.coerceIn(0, rawWidth)
        val clampedTop = rawRect.top.coerceIn(0, rawHeight)
        val clampedRight = rawRect.right.coerceIn(clampedLeft, rawWidth)
        val clampedBottom = rawRect.bottom.coerceIn(clampedTop, rawHeight)
        return Rect(clampedLeft, clampedTop, clampedRight, clampedBottom)
    }

    fun buildOrientationMatrix(exifOrientation: Int): Matrix {
        return Matrix().apply {
            when (exifOrientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    postRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    postRotate(270f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            }
        }
    }

    fun applyOrientationToBitmap(bitmap: Bitmap, exifOrientation: Int): Bitmap {
        val matrix = buildOrientationMatrix(exifOrientation)
        if (matrix.isIdentity) return bitmap
        val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (transformed !== bitmap) {
            bitmap.recycle()
        }
        return transformed
    }
}

/**
 * Manages asynchronous high-resolution decoding, orientation normalization, bounded tile LRU
 * caching, and region decoding for a single photo displayed in ZoomablePhotoView.
 */
class HighResPhotoSession private constructor(
    val metadata: HighResImageMetadata,
    val baseBitmap: Bitmap,
    val baseSampleSize: Int,
    private val regionDecoder: RegionDecodeSource?,
    private val tempFile: File?
) {
    private val decoderLock = Any()

    @Volatile
    private var isClosed = false

    @Volatile
    var highResFallbackBitmap: Bitmap? = null
        private set

    private val maxTileCacheBytes: Int = run {
        val heapSixth = (Runtime.getRuntime().maxMemory() / 6L).toInt()
        heapSixth.coerceIn(8 * 1024 * 1024, 36 * 1024 * 1024)
    }

    private val tileCache = object : LruCache<TileKey, DecodedTile>(maxTileCacheBytes) {
        override fun sizeOf(key: TileKey, value: DecodedTile): Int {
            return value.bitmap.allocationByteCount.coerceAtLeast(1)
        }
    }

    val supportsRegionDecoding: Boolean
        get() = regionDecoder != null && !isClosed

    val hasHigherResolutionThanBase: Boolean
        get() = baseSampleSize > 1 ||
            metadata.displayWidth > baseBitmap.width ||
            metadata.displayHeight > baseBitmap.height

    fun getCachedTilesSnapshot(preferredSampleSize: Int): List<DecodedTile> {
        val snapshot = synchronized(tileCache) {
            tileCache.snapshot()
        }
        if (snapshot.isEmpty()) return emptyList()
        val exact = mutableListOf<DecodedTile>()
        val coarser = mutableListOf<DecodedTile>()
        for ((key, tile) in snapshot) {
            if (tile.bitmap.isRecycled) continue
            if (key.sampleSize == preferredSampleSize) {
                exact.add(tile)
            } else if (key.sampleSize > preferredSampleSize && key.sampleSize < baseSampleSize) {
                coarser.add(tile)
            }
        }
        // Paint coarser tiles first, then exact sharp tiles on top
        coarser.sortByDescending { it.key.sampleSize }
        return coarser + exact
    }

    suspend fun loadMissingTiles(
        requests: List<Pair<TileKey, Rect>>
    ): List<DecodedTile> = withContext(Dispatchers.IO) {
        val decoder = regionDecoder ?: return@withContext emptyList()
        if (isClosed || requests.isEmpty()) return@withContext emptyList()

        val newlyDecoded = mutableListOf<DecodedTile>()
        for ((key, displayRect) in requests) {
            currentCoroutineContext().ensureActive()
            if (isClosed) break

            val existing = synchronized(tileCache) { tileCache.get(key) }
            if (existing != null && !existing.bitmap.isRecycled) {
                continue
            }

            val rawRect = HighResZoomMath.displayRectToRawRect(
                displayRect = displayRect,
                rawWidth = metadata.rawWidth,
                rawHeight = metadata.rawHeight,
                exifOrientation = metadata.exifOrientation
            )
            if (rawRect.width() <= 0 || rawRect.height() <= 0) continue

            val decodedRaw: Bitmap? = synchronized(decoderLock) {
                if (isClosed || decoder.isRecycled) {
                    null
                } else {
                    try {
                        decoder.decodeRegion(rawRect, key.sampleSize)
                    } catch (_: Throwable) {
                        null
                    }
                }
            }

            if (decodedRaw != null) {
                val oriented = try {
                    HighResZoomMath.applyOrientationToBitmap(decodedRaw, metadata.exifOrientation)
                } catch (_: Throwable) {
                    decodedRaw
                }
                val tile = DecodedTile(key = key, displayRect = displayRect, bitmap = oriented)
                synchronized(tileCache) {
                    if (!isClosed) {
                        tileCache.put(key, tile)
                    }
                }
                newlyDecoded.add(tile)
            }
        }
        newlyDecoded
    }

    /**
     * Fallback for non-tileable image formats when user zooms beyond baseBitmap resolution:
     * decodes a higher-resolution full image capped at 4096px edge asynchronously once.
     */
    suspend fun ensureHighResFallback(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext null
        val existing = highResFallbackBitmap
        if (existing != null && !existing.isRecycled) return@withContext existing

        val maxFallbackSample = HighResZoomMath.computeBaseSampleSize(
            metadata.displayWidth,
            metadata.displayHeight,
            maxBaseEdgePx = 4096
        )
        if (maxFallbackSample >= baseSampleSize) return@withContext null

        val decoded = decodeSampledOrientedBitmap(context, uri, maxFallbackSample, metadata.exifOrientation)
        if (decoded != null && !isClosed) {
            highResFallbackBitmap = decoded
        } else {
            decoded?.let { if (!it.isRecycled) it.recycle() }
        }
        highResFallbackBitmap
    }

    fun close() {
        synchronized(decoderLock) {
            if (isClosed) return
            isClosed = true
            synchronized(tileCache) {
                tileCache.evictAll()
            }
            highResFallbackBitmap = null
            try {
                regionDecoder?.let {
                    if (!it.isRecycled) it.recycle()
                }
            } catch (_: Throwable) {}
            try {
                tempFile?.delete()
            } catch (_: Throwable) {}
        }
    }

    companion object {
        suspend fun open(
            context: Context,
            uri: Uri,
            maxBaseEdgePx: Int = HighResZoomMath.DEFAULT_MAX_BASE_EDGE_PX,
            regionSourceFactory: ((Context, Uri) -> Pair<RegionDecodeSource?, File?>)? = null
        ): HighResPhotoSession? = withContext(Dispatchers.IO) {
            val metadata = readImageMetadata(context, uri) ?: return@withContext null
            val baseSampleSize = HighResZoomMath.computeBaseSampleSize(
                displayWidth = metadata.displayWidth,
                displayHeight = metadata.displayHeight,
                maxBaseEdgePx = maxBaseEdgePx
            )

            val baseBitmap = decodeSampledOrientedBitmap(
                context = context,
                uri = uri,
                sampleSize = baseSampleSize,
                exifOrientation = metadata.exifOrientation
            ) ?: return@withContext null

            var regionDecoder: RegionDecodeSource? = null
            var tempFile: File? = null

            // Only allocate a region decoder when the source actually has more pixels than baseBitmap
            if (baseSampleSize > 1 ||
                metadata.displayWidth > baseBitmap.width ||
                metadata.displayHeight > baseBitmap.height
            ) {
                val opened = if (regionSourceFactory != null) {
                    regionSourceFactory(context, uri)
                } else {
                    val (brd, tmp) = openRegionDecoder(context, uri)
                    (brd?.let { AndroidBitmapRegionDecodeSource(it) }) to tmp
                }
                regionDecoder = opened.first
                tempFile = opened.second
            }

            HighResPhotoSession(
                metadata = metadata,
                baseBitmap = baseBitmap,
                baseSampleSize = baseSampleSize,
                regionDecoder = regionDecoder,
                tempFile = tempFile
            )
        }

        fun readImageMetadata(context: Context, uri: Uri): HighResImageMetadata? {
            val orientation = try {
                openUriInputStream(context, uri)?.use { stream ->
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (_: Throwable) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                openUriInputStream(context, uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
            } catch (_: Throwable) {
                return null
            }

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null
            }

            val swapped = HighResZoomMath.isOrientationSwapped(orientation)
            val dispW = if (swapped) bounds.outHeight else bounds.outWidth
            val dispH = if (swapped) bounds.outWidth else bounds.outHeight

            return HighResImageMetadata(
                rawWidth = bounds.outWidth,
                rawHeight = bounds.outHeight,
                displayWidth = dispW,
                displayHeight = dispH,
                exifOrientation = orientation,
                mimeType = bounds.outMimeType
            )
        }

        private fun decodeSampledOrientedBitmap(
            context: Context,
            uri: Uri,
            sampleSize: Int,
            exifOrientation: Int
        ): Bitmap? {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = try {
                openUriInputStream(context, uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            } catch (_: OutOfMemoryError) {
                val fallbackOpts = BitmapFactory.Options().apply {
                    inSampleSize = (sampleSize * 2).coerceAtLeast(2)
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                try {
                    openUriInputStream(context, uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, fallbackOpts)
                    }
                } catch (_: Throwable) {
                    null
                }
            } catch (_: Throwable) {
                null
            } ?: return null

            return try {
                HighResZoomMath.applyOrientationToBitmap(raw, exifOrientation)
            } catch (_: Throwable) {
                raw
            }
        }

        private fun openRegionDecoder(context: Context, uri: Uri): Pair<BitmapRegionDecoder?, File?> {
            // 1. Prefer direct file path or seekable ParcelFileDescriptor (zero temp file copy)
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                val path = uri.path!!
                try {
                    val decoder = createDecoderFromPath(path)
                    if (decoder != null) return decoder to null
                } catch (_: Throwable) {}
            }

            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val decoder = createDecoderFromParcelFd(pfd)
                    if (decoder != null) return decoder to null
                }
            } catch (_: Throwable) {}

            // 2. Fallback to stream (copy to cache temp file only if stream isn't directly supported)
            try {
                openUriInputStream(context, uri)?.use { stream ->
                    val decoder = createDecoderFromStream(stream)
                    if (decoder != null) return decoder to null
                }
            } catch (_: Throwable) {}

            // 3. Last resort: spool stream to private cache temp file so random-access tile decoding works
            var temp: File? = null
            return try {
                val file = File.createTempFile("zoom_src_", ".tmp", context.cacheDir)
                temp = file
                openUriInputStream(context, uri)?.use { input ->
                    FileOutputStream(file).use { out ->
                        input.copyTo(out, 128 * 1024)
                        out.flush()
                    }
                } ?: run {
                    file.delete()
                    return null to null
                }
                val decoder = createDecoderFromPath(file.absolutePath)
                if (decoder != null) {
                    decoder to file
                } else {
                    file.delete()
                    null to null
                }
            } catch (_: Throwable) {
                try { temp?.delete() } catch (_: Throwable) {}
                null to null
            }
        }

        @Suppress("DEPRECATION")
        private fun createDecoderFromPath(path: String): BitmapRegionDecoder? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(path)?.let { return it }
            }
            return BitmapRegionDecoder.newInstance(path, false)
        }

        @Suppress("DEPRECATION")
        private fun createDecoderFromParcelFd(pfd: android.os.ParcelFileDescriptor): BitmapRegionDecoder? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(pfd)?.let { return it }
            }
            return BitmapRegionDecoder.newInstance(pfd.fileDescriptor, false)
        }

        @Suppress("DEPRECATION")
        private fun createDecoderFromStream(stream: java.io.InputStream): BitmapRegionDecoder? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(stream)?.let { return it }
            }
            return BitmapRegionDecoder.newInstance(stream, false)
        }

        private fun openUriInputStream(context: Context, uri: Uri): java.io.InputStream? {
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                val f = File(uri.path!!)
                if (f.exists() && f.isFile) {
                    return f.inputStream()
                }
            }
            return context.contentResolver.openInputStream(uri)
        }
    }
}
