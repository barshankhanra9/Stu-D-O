package com.example.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.model.WatermarkCacheLimit
import com.example.model.WatermarkConfig
import com.example.util.CameraMetadataUtil
import com.example.util.RealCameraMetadata
import com.example.util.WatermarkPainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class PrivateOriginalEntry(
    val originalId: String,
    val fileName: String,
    val createdAtMs: Long,
    val fileSizeBytes: Long,
    val width: Int,
    val height: Int,
    val metadata: RealCameraMetadata
)

data class GalleryWatermarkLink(
    val galleryUri: String,
    val mediaStoreId: Long?,
    val originalId: String,
    val watermarkConfig: WatermarkConfig,
    val updatedAtMs: Long
)

data class CacheEvictionResult(
    val evictedCount: Int,
    val freedBytes: Long,
    val remainingCount: Int,
    val usedBytes: Long
)

sealed class ResolvedOriginalResult {
    data class Available(
        val entry: PrivateOriginalEntry,
        val file: File,
        val watermarkConfig: WatermarkConfig,
        val metadata: RealCameraMetadata
    ) : ResolvedOriginalResult()

    data class Unavailable(val reason: String) : ResolvedOriginalResult()
}

sealed class PostCaptureExportResult {
    data class Success(
        val galleryUri: Uri,
        val width: Int,
        val height: Int,
        val sizeBytes: Long
    ) : PostCaptureExportResult()

    data class Failure(val reason: String) : PostCaptureExportResult()
}

class PrivateOriginalStorageManager(context: Context) {

    private val appContext = context.applicationContext
    private val lock = Any()
    private val storageDir = File(appContext.filesDir, DIRECTORY_NAME)
    private val indexFile = File(storageDir, INDEX_FILE_NAME)

    private val originals = linkedMapOf<String, PrivateOriginalEntry>()
    private val links = linkedMapOf<String, GalleryWatermarkLink>()
    private val activeProtectedIds = mutableSetOf<String>()

    companion object {
        private const val TAG = "PrivateOriginalStorage"
        private const val DIRECTORY_NAME = "private_originals"
        private const val INDEX_FILE_NAME = "originals_index.json"
        private const val NOMEDIA_FILE_NAME = ".nomedia"
        private const val IO_BUFFER_SIZE = 256 * 1024

        private val EXIF_TAGS_TO_PRESERVE = listOf(
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
            ExifInterface.TAG_FLASH
        )

        private val GPS_EXIF_TAGS = listOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP
        )

        fun parseMediaStoreId(uri: Uri): Long? {
            val segments = uri.pathSegments ?: return null
            if (segments.isEmpty()) return null
            val last = segments.lastOrNull() ?: return null
            return last.toLongOrNull()
        }

        fun formatStorageSize(bytes: Long): String {
            val safe = bytes.coerceAtLeast(0L)
            return when {
                safe >= 1024L * 1024L * 1024L -> {
                    val gb = safe / (1024.0 * 1024.0 * 1024.0)
                    if (kotlin.math.abs(gb - Math.round(gb)) < 0.05) {
                        "${Math.round(gb)} GB"
                    } else {
                        String.format(Locale.US, "%.2f GB", gb)
                    }
                }
                safe >= 1024L * 1024L -> {
                    val mb = safe / (1024.0 * 1024.0)
                    if (mb >= 100.0) {
                        String.format(Locale.US, "%.0f MB", mb)
                    } else {
                        String.format(Locale.US, "%.1f MB", mb)
                    }
                }
                safe >= 1024L -> String.format(Locale.US, "%.0f KB", safe / 1024.0)
                else -> "$safe B"
            }
        }
    }

    init {
        synchronized(lock) {
            ensureStorageDirectoryLocked()
            loadIndexLocked()
            pruneMissingEntriesLocked()
        }
    }

    fun getStorageDirectory(): File {
        synchronized(lock) {
            ensureStorageDirectoryLocked()
            return storageDir
        }
    }

    fun markOriginalActive(originalId: String) {
        synchronized(lock) {
            activeProtectedIds.add(originalId)
        }
    }

    fun unmarkOriginalActive(originalId: String) {
        synchronized(lock) {
            activeProtectedIds.remove(originalId)
        }
    }

    fun isOriginalActive(originalId: String): Boolean {
        synchronized(lock) {
            return activeProtectedIds.contains(originalId)
        }
    }

    fun saveOriginalFromUri(
        sourceUri: Uri,
        metadata: RealCameraMetadata,
        isSaveLocation: Boolean,
        protectUntilUnmarked: Boolean = true
    ): PrivateOriginalEntry? {
        ensureStorageDirectoryLockedCompat()
        val id = UUID.randomUUID().toString()
        val fileName = "orig_$id.jpg"
        val destFile = File(storageDir, fileName)
        if (protectUntilUnmarked) {
            markOriginalActive(id)
        }
        return try {
            appContext.contentResolver.openInputStream(sourceUri)?.use { input ->
                BufferedOutputStream(FileOutputStream(destFile), IO_BUFFER_SIZE).use { out ->
                    input.copyTo(out, IO_BUFFER_SIZE)
                    out.flush()
                }
            } ?: run {
                destFile.delete()
                if (protectUntilUnmarked) unmarkOriginalActive(id)
                return null
            }

            if (!isSaveLocation) {
                stripGpsFromFile(destFile)
            }

            val registered = registerSavedFile(id, destFile, metadata)
            if (registered == null) {
                destFile.delete()
                if (protectUntilUnmarked) unmarkOriginalActive(id)
            }
            registered
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Failed saving clean original from URI: ${t.message}", t)
            try { destFile.delete() } catch (_: Throwable) {}
            if (protectUntilUnmarked) unmarkOriginalActive(id)
            null
        }
    }

    fun saveOriginalFromFile(
        sourceFile: File,
        metadata: RealCameraMetadata,
        isSaveLocation: Boolean,
        protectUntilUnmarked: Boolean = true
    ): PrivateOriginalEntry? {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) return null
        ensureStorageDirectoryLockedCompat()
        val id = UUID.randomUUID().toString()
        val fileName = "orig_$id.jpg"
        val destFile = File(storageDir, fileName)
        if (protectUntilUnmarked) {
            markOriginalActive(id)
        }
        return try {
            FileInputStream(sourceFile).use { input ->
                BufferedOutputStream(FileOutputStream(destFile), IO_BUFFER_SIZE).use { out ->
                    input.copyTo(out, IO_BUFFER_SIZE)
                    out.flush()
                }
            }

            if (!isSaveLocation) {
                stripGpsFromFile(destFile)
            }

            val registered = registerSavedFile(id, destFile, metadata)
            if (registered == null) {
                destFile.delete()
                if (protectUntilUnmarked) unmarkOriginalActive(id)
            }
            registered
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Failed saving clean original from File: ${t.message}", t)
            try { destFile.delete() } catch (_: Throwable) {}
            if (protectUntilUnmarked) unmarkOriginalActive(id)
            null
        }
    }

    fun saveOriginalFromBitmap(
        bitmap: Bitmap,
        exifAttributes: Map<String, String>,
        metadata: RealCameraMetadata,
        isSaveLocation: Boolean,
        protectUntilUnmarked: Boolean = true
    ): PrivateOriginalEntry? {
        if (bitmap.isRecycled) return null
        ensureStorageDirectoryLockedCompat()
        val id = UUID.randomUUID().toString()
        val fileName = "orig_$id.jpg"
        val destFile = File(storageDir, fileName)
        if (protectUntilUnmarked) {
            markOriginalActive(id)
        }
        return try {
            BufferedOutputStream(FileOutputStream(destFile), IO_BUFFER_SIZE).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                out.flush()
            }
            try {
                val destExif = ExifInterface(destFile.absolutePath)
                for ((k, v) in exifAttributes) {
                    if (isSaveLocation || !GPS_EXIF_TAGS.contains(k)) {
                        destExif.setAttribute(k, v)
                    }
                }
                if (!isSaveLocation) {
                    for (gpsTag in GPS_EXIF_TAGS) {
                        destExif.setAttribute(gpsTag, null)
                    }
                }
                destExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                destExif.saveAttributes()
            } catch (_: Throwable) {}

            val registered = registerSavedFile(id, destFile, metadata)
            if (registered == null) {
                destFile.delete()
                if (protectUntilUnmarked) unmarkOriginalActive(id)
            }
            registered
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Failed saving clean original from Bitmap: ${t.message}", t)
            try { destFile.delete() } catch (_: Throwable) {}
            if (protectUntilUnmarked) unmarkOriginalActive(id)
            null
        }
    }

    private fun registerSavedFile(
        id: String,
        destFile: File,
        fallbackMetadata: RealCameraMetadata
    ): PrivateOriginalEntry? {
        if (!destFile.exists() || destFile.length() <= 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(destFile.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val exifOrientation = try {
            ExifInterface(destFile.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val isRotatedSideways = exifOrientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            exifOrientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            exifOrientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            exifOrientation == ExifInterface.ORIENTATION_TRANSVERSE
        val displayW = if (isRotatedSideways) bounds.outHeight else bounds.outWidth
        val displayH = if (isRotatedSideways) bounds.outWidth else bounds.outHeight

        val fileExifMeta = CameraMetadataUtil.fromFile(destFile, displayW, displayH)
        val mergedMeta = mergeMetadataAuthentically(fileExifMeta, fallbackMetadata, displayW, displayH)

        val entry = PrivateOriginalEntry(
            originalId = id,
            fileName = destFile.name,
            createdAtMs = System.currentTimeMillis(),
            fileSizeBytes = destFile.length(),
            width = displayW,
            height = displayH,
            metadata = mergedMeta
        )

        synchronized(lock) {
            originals[id] = entry
            saveIndexLocked()
        }
        return entry
    }

    fun removeOriginalById(originalId: String) {
        synchronized(lock) {
            activeProtectedIds.remove(originalId)
            val entry = originals.remove(originalId)
            if (entry != null) {
                try { File(storageDir, entry.fileName).delete() } catch (_: Throwable) {}
            }
            val staleLinks = links.values.filter { it.originalId == originalId }.map { it.galleryUri }
            for (k in staleLinks) {
                links.remove(k)
            }
            if (entry != null || staleLinks.isNotEmpty()) {
                saveIndexLocked()
            }
        }
    }

    fun linkGalleryOutput(
        galleryUri: Uri,
        originalId: String,
        watermarkConfig: WatermarkConfig
    ) {
        synchronized(lock) {
            if (!originals.containsKey(originalId)) return
            val uriStr = galleryUri.toString()
            val link = GalleryWatermarkLink(
                galleryUri = uriStr,
                mediaStoreId = parseMediaStoreId(galleryUri),
                originalId = originalId,
                watermarkConfig = watermarkConfig,
                updatedAtMs = System.currentTimeMillis()
            )
            links[uriStr] = link
            saveIndexLocked()
        }
    }

    fun unlinkGalleryUri(galleryUri: Uri) {
        synchronized(lock) {
            val uriStr = galleryUri.toString()
            val mediaId = parseMediaStoreId(galleryUri)
            var changed = links.remove(uriStr) != null
            if (mediaId != null) {
                val matchingKeys = links.values.filter { it.mediaStoreId == mediaId }.map { it.galleryUri }
                for (k in matchingKeys) {
                    if (links.remove(k) != null) changed = true
                }
            }
            if (changed) {
                saveIndexLocked()
            }
        }
    }

    fun resolveForGalleryUri(galleryUri: Uri): ResolvedOriginalResult {
        val snapshot: Triple<GalleryWatermarkLink?, PrivateOriginalEntry?, File?> = synchronized(lock) {
            val uriStr = galleryUri.toString()
            val mediaId = parseMediaStoreId(galleryUri)
            val link = links[uriStr]
                ?: if (mediaId != null) links.values.firstOrNull { it.mediaStoreId == mediaId } else null

            if (link == null) {
                return@synchronized Triple(null, null, null)
            }
            val entry = originals[link.originalId]
            val file = entry?.let { File(storageDir, it.fileName) }
            Triple(link, entry, file)
        }

        val (link, entry, file) = snapshot
        if (link == null) {
            return ResolvedOriginalResult.Unavailable(
                "No clean private original is associated with this photo. Watermarks can only be re-edited on photos captured while Post-Capture Watermark Editing is turned on."
            )
        }
        if (entry == null || file == null || !file.exists() || !file.isFile || file.length() <= 0L) {
            return ResolvedOriginalResult.Unavailable(
                "The private clean original for this photo is unavailable or was removed from cache. Your gallery photo remains unchanged."
            )
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(file.absolutePath, bounds)
        } catch (_: Throwable) {}

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return ResolvedOriginalResult.Unavailable(
                "The private clean original file appears corrupted or unreadable. Your gallery photo remains unchanged."
            )
        }

        val exifOrientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val isRotatedSideways = exifOrientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            exifOrientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            exifOrientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            exifOrientation == ExifInterface.ORIENTATION_TRANSVERSE
        val displayW = if (isRotatedSideways) bounds.outHeight else bounds.outWidth
        val displayH = if (isRotatedSideways) bounds.outWidth else bounds.outHeight

        val freshFileMeta = CameraMetadataUtil.fromFile(file, displayW, displayH)
        val mergedMeta = mergeMetadataAuthentically(freshFileMeta, entry.metadata, displayW, displayH)

        return ResolvedOriginalResult.Available(
            entry = entry,
            file = file,
            watermarkConfig = link.watermarkConfig,
            metadata = mergedMeta
        )
    }

    fun enforceCacheLimit(limit: WatermarkCacheLimit, protectedOriginalId: String? = null): CacheEvictionResult {
        return enforceCacheLimitBytes(limit.limitBytes, protectedOriginalId)
    }

    fun enforceCacheLimitBytes(limitBytes: Long, protectedOriginalId: String? = null): CacheEvictionResult {
        synchronized(lock) {
            pruneMissingEntriesLocked()
            val targetMax = limitBytes.coerceAtLeast(0L)
            var currentUsed = originals.values.sumOf { it.fileSizeBytes }
            if (currentUsed <= targetMax) {
                return CacheEvictionResult(
                    evictedCount = 0,
                    freedBytes = 0L,
                    remainingCount = originals.size,
                    usedBytes = currentUsed
                )
            }

            // Oldest eligible private originals first (LRU by createdAtMs)
            val orderedCandidates = originals.values.sortedBy { it.createdAtMs }
            var evictedCount = 0
            var freedBytes = 0L

            for (candidate in orderedCandidates) {
                if (currentUsed <= targetMax) break
                if (candidate.originalId == protectedOriginalId || activeProtectedIds.contains(candidate.originalId)) {
                    continue
                }
                val file = File(storageDir, candidate.fileName)
                val actualLen = if (file.exists()) file.length() else candidate.fileSizeBytes
                val deleted = if (file.exists()) file.delete() else true
                if (deleted || !file.exists()) {
                    originals.remove(candidate.originalId)
                    val staleLinks = links.values.filter { it.originalId == candidate.originalId }.map { it.galleryUri }
                    for (k in staleLinks) {
                        links.remove(k)
                    }
                    evictedCount++
                    freedBytes += actualLen.coerceAtLeast(candidate.fileSizeBytes)
                    currentUsed = (currentUsed - candidate.fileSizeBytes).coerceAtLeast(0L)
                }
            }

            if (evictedCount > 0) {
                saveIndexLocked()
            }

            return CacheEvictionResult(
                evictedCount = evictedCount,
                freedBytes = freedBytes,
                remainingCount = originals.size,
                usedBytes = originals.values.sumOf { it.fileSizeBytes }
            )
        }
    }

    fun clearAllOriginals(): CacheEvictionResult {
        synchronized(lock) {
            var evictedCount = 0
            var freedBytes = 0L
            val toRemove = originals.values.filterNot { activeProtectedIds.contains(it.originalId) }
            for (entry in toRemove) {
                val file = File(storageDir, entry.fileName)
                val size = if (file.exists()) file.length() else entry.fileSizeBytes
                if (!file.exists() || file.delete()) {
                    originals.remove(entry.originalId)
                    val staleLinks = links.values.filter { it.originalId == entry.originalId }.map { it.galleryUri }
                    for (k in staleLinks) {
                        links.remove(k)
                    }
                    evictedCount++
                    freedBytes += size.coerceAtLeast(0L)
                }
            }
            if (evictedCount > 0) {
                saveIndexLocked()
            }
            val remainingUsed = originals.values.sumOf { it.fileSizeBytes }
            return CacheEvictionResult(
                evictedCount = evictedCount,
                freedBytes = freedBytes,
                remainingCount = originals.size,
                usedBytes = remainingUsed
            )
        }
    }

    fun getUsedStorageBytes(): Long {
        synchronized(lock) {
            return originals.values.sumOf { it.fileSizeBytes }
        }
    }

    fun getRetainedOriginalsCount(): Int {
        synchronized(lock) {
            return originals.size
        }
    }

    fun getAllOriginals(): List<PrivateOriginalEntry> {
        synchronized(lock) {
            return originals.values.sortedBy { it.createdAtMs }
        }
    }

    fun getLinkForGalleryUri(galleryUri: Uri): GalleryWatermarkLink? {
        synchronized(lock) {
            val uriStr = galleryUri.toString()
            val mediaId = parseMediaStoreId(galleryUri)
            return links[uriStr]
                ?: if (mediaId != null) links.values.firstOrNull { it.mediaStoreId == mediaId } else null
        }
    }

    suspend fun updateExistingGalleryPhoto(
        galleryUri: Uri,
        originalId: String,
        config: WatermarkConfig,
        isSaveLocation: Boolean
    ): PostCaptureExportResult = withContext(Dispatchers.IO) {
        val originalEntry = synchronized(lock) { originals[originalId] }
            ?: return@withContext PostCaptureExportResult.Failure("Private clean original is unavailable.")
        val originalFile = File(storageDir, originalEntry.fileName)
        if (!originalFile.exists() || originalFile.length() <= 0L) {
            return@withContext PostCaptureExportResult.Failure("Private clean original file is missing.")
        }

        markOriginalActive(originalId)
        try {
            val (w, h, bytes) = writeRenderedOriginalToTargetUri(
                originalFile = originalFile,
                baseMetadata = originalEntry.metadata,
                config = config,
                targetUri = galleryUri,
                isSaveLocation = isSaveLocation
            ) ?: return@withContext PostCaptureExportResult.Failure("Failed rendering photo to gallery URI.")

            // Update gallery MediaStore timestamp/dimensions safely so listeners/loaders refresh
            try {
                val updateValues = ContentValues().apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
                }
                appContext.contentResolver.update(galleryUri, updateValues, null, null)
            } catch (_: Throwable) {}

            linkGalleryOutput(galleryUri, originalId, config)
            PostCaptureExportResult.Success(galleryUri, w, h, bytes)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "updateExistingGalleryPhoto failed: ${t.message}", t)
            PostCaptureExportResult.Failure(t.localizedMessage ?: "Failed updating photo.")
        } finally {
            unmarkOriginalActive(originalId)
        }
    }

    suspend fun saveNewGalleryCopyFromOriginal(
        originalId: String,
        config: WatermarkConfig,
        isSaveLocation: Boolean
    ): PostCaptureExportResult = withContext(Dispatchers.IO) {
        val originalEntry = synchronized(lock) { originals[originalId] }
            ?: return@withContext PostCaptureExportResult.Failure("Private clean original is unavailable.")
        val originalFile = File(storageDir, originalEntry.fileName)
        if (!originalFile.exists() || originalFile.length() <= 0L) {
            return@withContext PostCaptureExportResult.Failure("Private clean original file is missing.")
        }

        markOriginalActive(originalId)
        var insertedUri: Uri? = null
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val prefix = if (config.isEnabled) "StuDO_WM" else "StuDO_CLEAN"
            val fileName = "${prefix}_$timeStamp.jpg"

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StuDO")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            insertedUri = appContext.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: return@withContext PostCaptureExportResult.Failure("Could not allocate gallery destination.")

            val (w, h, bytes) = writeRenderedOriginalToTargetUri(
                originalFile = originalFile,
                baseMetadata = originalEntry.metadata,
                config = config,
                targetUri = insertedUri,
                isSaveLocation = isSaveLocation
            ) ?: run {
                try { appContext.contentResolver.delete(insertedUri, null, null) } catch (_: Throwable) {}
                return@withContext PostCaptureExportResult.Failure("Failed writing copy to gallery.")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val finalizeValues = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    appContext.contentResolver.update(insertedUri, finalizeValues, null, null)
                } catch (_: Throwable) {}
            }

            linkGalleryOutput(insertedUri, originalId, config)
            PostCaptureExportResult.Success(insertedUri, w, h, bytes)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "saveNewGalleryCopyFromOriginal failed: ${t.message}", t)
            insertedUri?.let { u ->
                try { appContext.contentResolver.delete(u, null, null) } catch (_: Throwable) {}
            }
            PostCaptureExportResult.Failure(t.localizedMessage ?: "Failed creating gallery copy.")
        } finally {
            unmarkOriginalActive(originalId)
        }
    }

    private fun writeRenderedOriginalToTargetUri(
        originalFile: File,
        baseMetadata: RealCameraMetadata,
        config: WatermarkConfig,
        targetUri: Uri,
        isSaveLocation: Boolean
    ): Triple<Int, Int, Long>? {
        val srcExif = try { ExifInterface(originalFile.absolutePath) } catch (_: Throwable) { null }

        if (!config.isEnabled) {
            // Unwatermarked output directly from clean original with zero extra watermark stamping
            var tempCopy: File? = null
            return try {
                tempCopy = File.createTempFile("studo_clean_export_", ".jpg", appContext.cacheDir)
                FileInputStream(originalFile).use { input ->
                    BufferedOutputStream(FileOutputStream(tempCopy), IO_BUFFER_SIZE).use { out ->
                        input.copyTo(out, IO_BUFFER_SIZE)
                        out.flush()
                    }
                }
                if (!isSaveLocation) {
                    stripGpsFromFile(tempCopy)
                }
                val bytesWritten = copyFileToUri(tempCopy, targetUri)
                if (bytesWritten <= 0L) return null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(tempCopy.absolutePath, bounds)
                Triple(bounds.outWidth.coerceAtLeast(1), bounds.outHeight.coerceAtLeast(1), bytesWritten)
            } finally {
                try { tempCopy?.delete() } catch (_: Throwable) {}
            }
        }

        // Watermark enabled: decode full-resolution clean original, normalize orientation, stamp watermark at full resolution
        var rawBmp: Bitmap? = null
        var uprightBmp: Bitmap? = null
        var stampedBmp: Bitmap? = null
        var tempOutFile: File? = null
        return try {
            rawBmp = BitmapFactory.decodeFile(originalFile.absolutePath) ?: return null
            val orientation = srcExif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            ) ?: ExifInterface.ORIENTATION_NORMAL

            val orientMatrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> orientMatrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> orientMatrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> orientMatrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> orientMatrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> orientMatrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    orientMatrix.postRotate(90f)
                    orientMatrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    orientMatrix.postRotate(270f)
                    orientMatrix.postScale(-1f, 1f)
                }
            }

            uprightBmp = if (!orientMatrix.isIdentity) {
                val oriented = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, orientMatrix, true)
                if (oriented !== rawBmp) rawBmp.recycle()
                oriented
            } else {
                rawBmp
            }

            val freshMeta = if (srcExif != null) {
                CameraMetadataUtil.extract(srcExif, uprightBmp.width, uprightBmp.height)
            } else {
                CameraMetadataUtil.fromFile(originalFile, uprightBmp.width, uprightBmp.height)
            }
            val effectiveMeta = mergeMetadataAuthentically(freshMeta, baseMetadata, uprightBmp.width, uprightBmp.height)

            stampedBmp = WatermarkPainter.applyWatermark(uprightBmp, config, effectiveMeta, appContext)
            if (stampedBmp !== uprightBmp && !uprightBmp.isRecycled) {
                uprightBmp.recycle()
            }

            tempOutFile = File.createTempFile("studo_wm_export_", ".jpg", appContext.cacheDir)
            RandomAccessFile(tempOutFile, "rw").use { outRaf ->
                BufferedOutputStream(FileOutputStream(outRaf.fd), IO_BUFFER_SIZE).use { out ->
                    stampedBmp.compress(Bitmap.CompressFormat.JPEG, 100, out)
                    out.flush()
                }
            }

            // Write EXIF into temp file before transferring to gallery URI so ContentResolver reads complete headers
            if (srcExif != null) {
                try {
                    val destExif = ExifInterface(tempOutFile.absolutePath)
                    for (tag in EXIF_TAGS_TO_PRESERVE) {
                        val v = srcExif.getAttribute(tag)
                        if (v != null) destExif.setAttribute(tag, v)
                    }
                    if (isSaveLocation) {
                        for (gpsTag in GPS_EXIF_TAGS) {
                            val v = srcExif.getAttribute(gpsTag)
                            if (v != null) destExif.setAttribute(gpsTag, v)
                        }
                    } else {
                        for (gpsTag in GPS_EXIF_TAGS) {
                            destExif.setAttribute(gpsTag, null)
                        }
                    }
                    destExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                    destExif.saveAttributes()
                } catch (_: Throwable) {}
            }

            val bytesWritten = copyFileToUri(tempOutFile, targetUri)
            if (bytesWritten <= 0L) return null
            Triple(stampedBmp.width, stampedBmp.height, bytesWritten)
        } finally {
            try {
                if (stampedBmp != null && !stampedBmp.isRecycled) stampedBmp.recycle()
                if (uprightBmp != null && uprightBmp !== stampedBmp && !uprightBmp.isRecycled) uprightBmp.recycle()
                if (rawBmp != null && rawBmp !== uprightBmp && rawBmp !== stampedBmp && !rawBmp.isRecycled) rawBmp.recycle()
            } catch (_: Throwable) {}
            try { tempOutFile?.delete() } catch (_: Throwable) {}
        }
    }

    private fun copyFileToUri(sourceFile: File, targetUri: Uri): Long {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) return 0L
        return try {
            val size = sourceFile.length()
            RandomAccessFile(sourceFile, "r").use { srcRaf ->
                val srcChannel = srcRaf.channel
                val pfd = try {
                    appContext.contentResolver.openFileDescriptor(targetUri, "rw")
                } catch (_: Throwable) {
                    null
                }
                if (pfd != null) {
                    pfd.use { fd ->
                        FileOutputStream(fd.fileDescriptor).channel.use { destChannel ->
                            try {
                                destChannel.truncate(0)
                                destChannel.position(0)
                            } catch (_: Throwable) {}
                            var transferred = 0L
                            while (transferred < size) {
                                val step = srcChannel.transferTo(transferred, size - transferred, destChannel)
                                if (step <= 0L) break
                                transferred += step
                            }
                            if (transferred < size) {
                                srcRaf.seek(transferred)
                                val buf = ByteArray(IO_BUFFER_SIZE)
                                val out = FileOutputStream(fd.fileDescriptor)
                                while (true) {
                                    val read = srcRaf.read(buf)
                                    if (read <= 0) break
                                    out.write(buf, 0, read)
                                }
                                out.flush()
                            }
                        }
                    }
                    return size
                }
            }
            // Fallback to openOutputStream if openFileDescriptor isn't supported by provider
            val outStream = try {
                appContext.contentResolver.openOutputStream(targetUri, "wt")
            } catch (_: Throwable) {
                appContext.contentResolver.openOutputStream(targetUri)
            } ?: return 0L
            outStream.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out, IO_BUFFER_SIZE)
                }
                out.flush()
            }
            size
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "copyFileToUri failed: ${t.message}", t)
            0L
        }
    }

    private fun stripGpsFromFile(file: File) {
        try {
            val exif = ExifInterface(file.absolutePath)
            var changed = false
            for (tag in GPS_EXIF_TAGS) {
                if (exif.getAttribute(tag) != null) {
                    exif.setAttribute(tag, null)
                    changed = true
                }
            }
            if (changed) {
                exif.saveAttributes()
            }
        } catch (_: Throwable) {}
    }

    private fun mergeMetadataAuthentically(
        primary: RealCameraMetadata,
        secondary: RealCameraMetadata,
        width: Int,
        height: Int
    ): RealCameraMetadata {
        val focal = primary.focalLength?.takeIf { it.isNotBlank() } ?: secondary.focalLength?.takeIf { it.isNotBlank() }
        val resText = primary.resolutionText?.takeIf { it.isNotBlank() } ?: secondary.resolutionText?.takeIf { it.isNotBlank() }
            ?: if (width > 0 && height > 0) {
                val mp = (width.toLong() * height.toLong()) / 1_000_000.0
                String.format(Locale.US, "%.1f MP (%d×%d)", mp, width, height)
            } else null

        return RealCameraMetadata(
            iso = primary.iso?.takeIf { it.isNotBlank() } ?: secondary.iso?.takeIf { it.isNotBlank() },
            shutterSpeed = primary.shutterSpeed?.takeIf { it.isNotBlank() } ?: secondary.shutterSpeed?.takeIf { it.isNotBlank() },
            aperture = primary.aperture?.takeIf { it.isNotBlank() } ?: secondary.aperture?.takeIf { it.isNotBlank() },
            focalLength = focal,
            dateTimeFormatted = primary.dateTimeFormatted?.takeIf { it.isNotBlank() } ?: secondary.dateTimeFormatted?.takeIf { it.isNotBlank() },
            deviceModel = primary.deviceModel?.takeIf { it.isNotBlank() } ?: secondary.deviceModel?.takeIf { it.isNotBlank() },
            lensInfo = focal,
            resolutionText = resText,
            manufacturer = primary.manufacturer?.takeIf { it.isNotBlank() } ?: secondary.manufacturer?.takeIf { it.isNotBlank() },
            modelOnly = primary.modelOnly?.takeIf { it.isNotBlank() } ?: secondary.modelOnly?.takeIf { it.isNotBlank() }
        )
    }

    private fun ensureStorageDirectoryLockedCompat() {
        synchronized(lock) {
            ensureStorageDirectoryLocked()
        }
    }

    private fun ensureStorageDirectoryLocked() {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        val nomedia = File(storageDir, NOMEDIA_FILE_NAME)
        if (!nomedia.exists()) {
            try {
                nomedia.createNewFile()
            } catch (_: Throwable) {}
        }
    }

    private fun pruneMissingEntriesLocked() {
        val missingIds = originals.values.filter { entry ->
            val f = File(storageDir, entry.fileName)
            !f.exists() || f.length() <= 0L
        }.map { it.originalId }

        if (missingIds.isNotEmpty()) {
            for (id in missingIds) {
                originals.remove(id)
            }
            // Note: Keep or remove orphaned links? Removing orphaned links is clean, OR wait:
            // If an external deletion / corrupted file occurred, keeping a missing-file check vs missing-link check
            // both produce Unavailable(...) cleanly, while pruning missing entries keeps storage stats 100% accurate.
            saveIndexLocked()
        }
    }

    private fun loadIndexLocked() {
        originals.clear()
        links.clear()
        if (!indexFile.exists() || indexFile.length() <= 0L) return
        try {
            val content = indexFile.readText(Charsets.UTF_8)
            if (content.isBlank()) return
            val root = JSONObject(content)

            val origArr = root.optJSONArray("originals") ?: JSONArray()
            for (i in 0 until origArr.length()) {
                val obj = origArr.optJSONObject(i) ?: continue
                val id = obj.optString("originalId")
                val fileName = obj.optString("fileName")
                if (id.isBlank() || fileName.isBlank()) continue
                val metaObj = obj.optJSONObject("metadata")
                val meta = if (metaObj != null) metadataFromJson(metaObj) else RealCameraMetadata()
                originals[id] = PrivateOriginalEntry(
                    originalId = id,
                    fileName = fileName,
                    createdAtMs = obj.optLong("createdAtMs", 0L),
                    fileSizeBytes = obj.optLong("fileSizeBytes", 0L),
                    width = obj.optInt("width", 0),
                    height = obj.optInt("height", 0),
                    metadata = meta
                )
            }

            val linksArr = root.optJSONArray("links") ?: JSONArray()
            for (i in 0 until linksArr.length()) {
                val obj = linksArr.optJSONObject(i) ?: continue
                val galleryUri = obj.optString("galleryUri")
                val originalId = obj.optString("originalId")
                if (galleryUri.isBlank() || originalId.isBlank()) continue
                val mediaStoreId = if (obj.has("mediaStoreId") && !obj.isNull("mediaStoreId")) {
                    obj.optLong("mediaStoreId")
                } else null
                val cfgObj = obj.optJSONObject("watermarkConfig")
                val cfg = if (cfgObj != null) {
                    CameraPreferencesRepository.watermarkConfigFromJson(cfgObj, defaultEnabled = true)
                } else {
                    WatermarkConfig(isEnabled = true)
                }
                links[galleryUri] = GalleryWatermarkLink(
                    galleryUri = galleryUri,
                    mediaStoreId = mediaStoreId,
                    originalId = originalId,
                    watermarkConfig = cfg,
                    updatedAtMs = obj.optLong("updatedAtMs", 0L)
                )
            }
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Failed loading private originals index: ${t.message}", t)
        }
    }

    private fun saveIndexLocked() {
        ensureStorageDirectoryLocked()
        val root = JSONObject()
        val origArr = JSONArray()
        for (entry in originals.values) {
            origArr.put(
                JSONObject().apply {
                    put("originalId", entry.originalId)
                    put("fileName", entry.fileName)
                    put("createdAtMs", entry.createdAtMs)
                    put("fileSizeBytes", entry.fileSizeBytes)
                    put("width", entry.width)
                    put("height", entry.height)
                    put("metadata", metadataToJson(entry.metadata))
                }
            )
        }
        root.put("originals", origArr)

        val linksArr = JSONArray()
        for (link in links.values) {
            linksArr.put(
                JSONObject().apply {
                    put("galleryUri", link.galleryUri)
                    put("mediaStoreId", link.mediaStoreId ?: JSONObject.NULL)
                    put("originalId", link.originalId)
                    put("watermarkConfig", CameraPreferencesRepository.watermarkConfigToJson(link.watermarkConfig))
                    put("updatedAtMs", link.updatedAtMs)
                }
            )
        }
        root.put("links", linksArr)

        val tempFile = File(storageDir, "$INDEX_FILE_NAME.tmp")
        try {
            tempFile.writeText(root.toString(), Charsets.UTF_8)
            if (!tempFile.renameTo(indexFile)) {
                indexFile.writeText(root.toString(), Charsets.UTF_8)
                tempFile.delete()
            }
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Failed saving private originals index: ${t.message}", t)
            try { tempFile.delete() } catch (_: Throwable) {}
        }
    }

    private fun metadataToJson(meta: RealCameraMetadata): JSONObject {
        fun JSONObject.putNullableString(key: String, value: String?) {
            put(key, value ?: JSONObject.NULL)
        }
        return JSONObject().apply {
            putNullableString("iso", meta.iso)
            putNullableString("shutterSpeed", meta.shutterSpeed)
            putNullableString("aperture", meta.aperture)
            putNullableString("focalLength", meta.focalLength)
            putNullableString("dateTimeFormatted", meta.dateTimeFormatted)
            putNullableString("deviceModel", meta.deviceModel)
            putNullableString("lensInfo", meta.lensInfo)
            putNullableString("resolutionText", meta.resolutionText)
            putNullableString("manufacturer", meta.manufacturer)
            putNullableString("modelOnly", meta.modelOnly)
        }
    }

    private fun metadataFromJson(obj: JSONObject): RealCameraMetadata {
        fun JSONObject.optNullableString(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return optString(key).takeIf { it.isNotBlank() }
        }
        return RealCameraMetadata(
            iso = obj.optNullableString("iso"),
            shutterSpeed = obj.optNullableString("shutterSpeed"),
            aperture = obj.optNullableString("aperture"),
            focalLength = obj.optNullableString("focalLength"),
            dateTimeFormatted = obj.optNullableString("dateTimeFormatted"),
            deviceModel = obj.optNullableString("deviceModel"),
            lensInfo = obj.optNullableString("lensInfo"),
            resolutionText = obj.optNullableString("resolutionText"),
            manufacturer = obj.optNullableString("manufacturer"),
            modelOnly = obj.optNullableString("modelOnly")
        )
    }
}
