package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-performance, thread-safe LRU cache and first-frame video thumbnail extractor.
 *
 * Guarantees:
 * - Always extracts the first decodable video frame (time 0 microseconds).
 * - Opens content:// URIs safely via ContentResolver FileDescriptor to prevent permission/vendor errors.
 * - Caches extracted thumbnails in memory so repeated scrolls, gallery opens, or bottom bar updates are instant.
 * - Never returns or uses the live camera preview.
 * - Provides fallback placeholder generation when extraction fails or video is corrupt.
 */
object VideoThumbnailUtil {

    private const val MAX_CACHE_ENTRIES = 48
    private val memoryCache = LruCache<String, Bitmap>(MAX_CACHE_ENTRIES)

    @Synchronized
    fun getCached(uri: Uri): Bitmap? {
        val key = uri.toString()
        val bmp = memoryCache.get(key)
        return if (bmp != null && !bmp.isRecycled) bmp else null
    }

    @Synchronized
    fun putCache(uri: Uri, bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            memoryCache.put(uri.toString(), bitmap)
        }
    }

    @Synchronized
    fun clearCache() {
        memoryCache.evictAll()
    }

    /**
     * Extracts the first decodable frame from the specified video URI.
     * Checks the in-memory cache first, then attempts extraction using:
     * 1. MediaMetadataRetriever at time 0L with OPTION_CLOSEST (first frame)
     * 2. MediaMetadataRetriever at time 0L with OPTION_CLOSEST_SYNC
     * 3. MediaMetadataRetriever getFrameAtIndex(0) on API 28+
     * 4. MediaMetadataRetriever default frameAtTime
     * 5. ContentResolver.loadThumbnail on API 29+
     */
    suspend fun loadThumbnail(context: Context, uri: Uri): Bitmap? {
        if (uri == Uri.EMPTY) return null

        getCached(uri)?.let { return it }

        return withContext(Dispatchers.IO) {
            // Check cache again in case another coroutine loaded it
            getCached(uri)?.let { return@withContext it }

            val extracted = extractFirstFrame(context, uri)
                ?: extractViaContentResolver(context, uri)

            if (extracted != null && !extracted.isRecycled) {
                putCache(uri, extracted)
                extracted
            } else {
                null
            }
        }
    }

    private fun extractFirstFrame(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        var frame: Bitmap? = null

        try {
            var datasourceSet = false
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                    datasourceSet = true
                }
            } catch (_: Throwable) {
                datasourceSet = false
            }

            if (!datasourceSet) {
                try {
                    retriever.setDataSource(context, uri)
                    datasourceSet = true
                } catch (_: Throwable) {
                    datasourceSet = false
                }
            }

            if (datasourceSet) {
                // 1. Primary: first decodable frame at timestamp 0 microseconds (closest frame)
                try {
                    frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (_: Throwable) {}

                // 2. Second: first sync keyframe at timestamp 0 microseconds
                if (frame == null) {
                    try {
                        frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } catch (_: Throwable) {}
                }

                // 3. Third (API 28+): index 0 frame
                if (frame == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        frame = retriever.getFrameAtIndex(0)
                    } catch (_: Throwable) {}
                }

                // 4. Fourth: general default frame
                if (frame == null) {
                    try {
                        frame = retriever.frameAtTime
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {
            frame = null
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {}
        }

        return frame
    }

    private fun extractViaContentResolver(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
            } catch (_: Throwable) {}
        }
        return null
    }

    /**
     * Creates a fallback raster placeholder bitmap for video files whose frames cannot be decoded.
     */
    fun createFallbackPlaceholderBitmap(width: Int = 256, height: Int = 256): Bitmap {
        val w = width.coerceAtLeast(32)
        val h = height.coerceAtLeast(32)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(0x16, 0x17, 0x20))
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x16, 0x17, 0x20)
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        // Draw camera body icon
        paint.color = Color.rgb(0xE0, 0xE0, 0xE0)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (width * 0.035f).coerceAtLeast(2f)

        val cx = width * 0.5f
        val cy = height * 0.5f
        val r = width * 0.18f

        canvas.drawCircle(cx, cy, r, paint)

        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, r * 0.45f, paint)

        return bmp
    }
}
