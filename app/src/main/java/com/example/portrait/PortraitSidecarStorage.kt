package com.example.portrait

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.data.CameraPreferencesRepository
import com.example.data.PostCaptureExportResult
import com.example.data.PrivateOriginalStorageManager
import com.example.model.PortraitDepthSource
import com.example.model.PostCapturePortraitTarget
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

data class PortraitSidecarEntry(
    val sidecarId: String,
    val cleanFileName: String,
    val matteFileName: String,
    val depthFileName: String,
    val maskWidth: Int,
    val maskHeight: Int,
    val width: Int,
    val height: Int,
    val blurIntensity: Float,
    val focusX: Float,
    val focusY: Float,
    val depthSource: PortraitDepthSource,
    val hasSubject: Boolean,
    val subjectCount: Int,
    val watermarkConfig: WatermarkConfig,
    val metadata: RealCameraMetadata,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

data class PortraitGalleryLink(
    val galleryUri: String,
    val mediaStoreId: Long?,
    val sidecarId: String,
    val updatedAtMs: Long
)

/**
 * Persistent non-destructive sidecar storage for Portrait Mode captures.
 * Stores the untouched pre-bokeh, pre-watermark upright JPEG original alongside lossless PNG
 * alpha matte and continuous depth map, enabling non-destructive blur & refocus adjustments
 * at any time after capture without degrading the original pixels.
 */
class PortraitSidecarStorage(context: Context) {

    private val appContext = context.applicationContext
    private val lock = Any()
    private val storageDir = File(appContext.filesDir, DIRECTORY_NAME)
    private val indexFile = File(storageDir, INDEX_FILE_NAME)

    private val entries = linkedMapOf<String, PortraitSidecarEntry>()
    private val links = linkedMapOf<String, PortraitGalleryLink>()

    companion object {
        private const val TAG = "PortraitSidecarStorage"
        private const val DIRECTORY_NAME = "portrait_sidecars"
        private const val INDEX_FILE_NAME = "portrait_index.json"
        private const val NOMEDIA_FILE_NAME = ".nomedia"
        private const val IO_BUFFER_SIZE = 256 * 1024
        private const val MAX_SIDECAR_COUNT = 80

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

        fun encodeFloatFieldToPng(field: FloatArray, width: Int, height: Int, outFile: File): Boolean {
            if (width <= 0 || height <= 0 || field.size < width * height) return false
            val count = width * height
            val pixels = IntArray(count)
            for (i in 0 until count) {
                val v = (field[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            var bmp: Bitmap? = null
            return try {
                bmp = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                BufferedOutputStream(FileOutputStream(outFile), 64 * 1024).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.flush()
                }
                true
            } catch (_: Throwable) {
                false
            } finally {
                try {
                    if (bmp != null && !bmp.isRecycled) bmp.recycle()
                } catch (_: Throwable) {}
            }
        }

        fun decodePngToFloatField(file: File, expectedW: Int, expectedH: Int): FloatArray? {
            if (!file.exists() || file.length() <= 0L) return null
            var bmp: Bitmap? = null
            return try {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                }
                bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
                val w = bmp.width
                val h = bmp.height
                if (w <= 0 || h <= 0) return null
                val pixels = IntArray(w * h)
                bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                val out = FloatArray(w * h)
                for (i in pixels.indices) {
                    val v = (pixels[i] ushr 16) and 0xFF
                    out[i] = v / 255f
                }
                if (w == expectedW && h == expectedH) {
                    out
                } else {
                    PortraitBokehRenderer.resampleField(out, w, h, expectedW, expectedH)
                }
            } catch (_: Throwable) {
                null
            } finally {
                try {
                    if (bmp != null && !bmp.isRecycled) bmp.recycle()
                } catch (_: Throwable) {}
            }
        }
    }

    init {
        synchronized(lock) {
            ensureStorageDirLocked()
            loadIndexLocked()
            pruneMissingLocked()
        }
    }

    fun savePortraitSidecar(
        cleanUprightBitmap: Bitmap,
        segmentation: PortraitSegmentationResult,
        blurIntensity: Float,
        focusX: Float,
        focusY: Float,
        exifAttributes: Map<String, String>,
        metadata: RealCameraMetadata,
        watermarkConfig: WatermarkConfig,
        isSaveLocation: Boolean
    ): PortraitSidecarEntry? {
        if (cleanUprightBitmap.isRecycled) return null
        synchronized(lock) {
            ensureStorageDirLocked()
        }
        val id = UUID.randomUUID().toString()
        val cleanName = "clean_$id.jpg"
        val matteName = "matte_$id.png"
        val depthName = "depth_$id.png"

        val cleanFile = File(storageDir, cleanName)
        val matteFile = File(storageDir, matteName)
        val depthFile = File(storageDir, depthName)

        return try {
            BufferedOutputStream(FileOutputStream(cleanFile), IO_BUFFER_SIZE).use { out ->
                cleanUprightBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                out.flush()
            }
            try {
                val destExif = ExifInterface(cleanFile.absolutePath)
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

            val matteOk = encodeFloatFieldToPng(
                segmentation.alphaMatte,
                segmentation.maskWidth,
                segmentation.maskHeight,
                matteFile
            )
            val depthOk = encodeFloatFieldToPng(
                segmentation.depthMap,
                segmentation.maskWidth,
                segmentation.maskHeight,
                depthFile
            )
            if (!matteOk || !depthOk || !cleanFile.exists() || cleanFile.length() <= 0L) {
                try { cleanFile.delete() } catch (_: Throwable) {}
                try { matteFile.delete() } catch (_: Throwable) {}
                try { depthFile.delete() } catch (_: Throwable) {}
                return null
            }

            val now = System.currentTimeMillis()
            val entry = PortraitSidecarEntry(
                sidecarId = id,
                cleanFileName = cleanName,
                matteFileName = matteName,
                depthFileName = depthName,
                maskWidth = segmentation.maskWidth,
                maskHeight = segmentation.maskHeight,
                width = cleanUprightBitmap.width,
                height = cleanUprightBitmap.height,
                blurIntensity = blurIntensity.coerceIn(0f, 1f),
                focusX = focusX.coerceIn(0f, 1f),
                focusY = focusY.coerceIn(0f, 1f),
                depthSource = segmentation.source,
                hasSubject = segmentation.hasSubject,
                subjectCount = segmentation.subjectCount,
                watermarkConfig = watermarkConfig,
                metadata = metadata,
                createdAtMs = now,
                updatedAtMs = now
            )

            synchronized(lock) {
                entries[id] = entry
                pruneOldestIfNeededLocked()
                saveIndexLocked()
            }
            entry
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Failed saving portrait sidecar: ${t.message}", t)
            try { cleanFile.delete() } catch (_: Throwable) {}
            try { matteFile.delete() } catch (_: Throwable) {}
            try { depthFile.delete() } catch (_: Throwable) {}
            null
        }
    }

    fun linkGalleryUri(galleryUri: Uri, sidecarId: String) {
        synchronized(lock) {
            if (!entries.containsKey(sidecarId)) return
            val uriStr = galleryUri.toString()
            links[uriStr] = PortraitGalleryLink(
                galleryUri = uriStr,
                mediaStoreId = PrivateOriginalStorageManager.parseMediaStoreId(galleryUri),
                sidecarId = sidecarId,
                updatedAtMs = System.currentTimeMillis()
            )
            saveIndexLocked()
        }
    }

    fun unlinkGalleryUri(galleryUri: Uri) {
        synchronized(lock) {
            val uriStr = galleryUri.toString()
            val mediaId = PrivateOriginalStorageManager.parseMediaStoreId(galleryUri)
            var changed = links.remove(uriStr) != null
            if (mediaId != null) {
                val matching = links.values.filter { it.mediaStoreId == mediaId }.map { it.galleryUri }
                for (k in matching) {
                    if (links.remove(k) != null) changed = true
                }
            }
            if (changed) {
                saveIndexLocked()
            }
        }
    }

    fun hasPortraitSidecar(galleryUri: Uri): Boolean {
        return synchronized(lock) {
            val link = findLinkLocked(galleryUri) ?: return@synchronized false
            val entry = entries[link.sidecarId] ?: return@synchronized false
            val clean = File(storageDir, entry.cleanFileName)
            val matte = File(storageDir, entry.matteFileName)
            val depth = File(storageDir, entry.depthFileName)
            clean.exists() && clean.length() > 0L && matte.exists() && depth.exists()
        }
    }

    fun resolveForGalleryUri(galleryUri: Uri): PostCapturePortraitTarget? {
        return synchronized(lock) {
            val link = findLinkLocked(galleryUri) ?: return@synchronized null
            val entry = entries[link.sidecarId] ?: return@synchronized null
            val clean = File(storageDir, entry.cleanFileName)
            val matte = File(storageDir, entry.matteFileName)
            val depth = File(storageDir, entry.depthFileName)
            if (!clean.exists() || clean.length() <= 0L || !matte.exists() || !depth.exists()) {
                return@synchronized null
            }
            PostCapturePortraitTarget(
                galleryUri = galleryUri,
                originalId = entry.sidecarId,
                originalFilePath = clean.absolutePath,
                depthMapFilePath = depth.absolutePath,
                alphaMatteFilePath = matte.absolutePath,
                width = entry.width,
                height = entry.height,
                blurIntensity = entry.blurIntensity,
                focusX = entry.focusX,
                focusY = entry.focusY,
                depthSource = entry.depthSource,
                watermarkConfig = entry.watermarkConfig,
                metadata = entry.metadata
            )
        }
    }

    fun loadSegmentationForTarget(target: PostCapturePortraitTarget): PortraitSegmentationResult? {
        val entry = synchronized(lock) { entries[target.originalId] } ?: return null
        val matteFile = File(target.alphaMatteFilePath)
        val depthFile = File(target.depthMapFilePath)
        val alpha = decodePngToFloatField(matteFile, entry.maskWidth, entry.maskHeight) ?: return null
        val depth = decodePngToFloatField(depthFile, entry.maskWidth, entry.maskHeight) ?: return null
        val focusDepth = HardwareDepthProcessor.sampleFocusDepth(
            depthMap = depth,
            width = entry.maskWidth,
            height = entry.maskHeight,
            normX = target.focusX,
            normY = target.focusY
        )
        return PortraitSegmentationResult(
            maskWidth = entry.maskWidth,
            maskHeight = entry.maskHeight,
            alphaMatte = alpha,
            depthMap = depth,
            focusDepth = focusDepth,
            subjectCount = entry.subjectCount,
            hasSubject = entry.hasSubject,
            source = entry.depthSource
        )
    }

    suspend fun reRenderAndUpdateExisting(
        target: PostCapturePortraitTarget,
        newBlurIntensity: Float,
        newFocusX: Float,
        newFocusY: Float,
        isSaveLocation: Boolean
    ): PostCaptureExportResult = withContext(Dispatchers.IO) {
        val cleanFile = File(target.originalFilePath)
        if (!cleanFile.exists() || cleanFile.length() <= 0L) {
            return@withContext PostCaptureExportResult.Failure("Original portrait image is unavailable.")
        }
        val segmentation = loadSegmentationForTarget(target)
            ?: return@withContext PostCaptureExportResult.Failure("Portrait depth map is unavailable.")

        val rendered = renderPortraitToTargetUri(
            cleanFile = cleanFile,
            segmentation = segmentation,
            blurIntensity = newBlurIntensity.coerceIn(0f, 1f),
            focusX = newFocusX.coerceIn(0f, 1f),
            focusY = newFocusY.coerceIn(0f, 1f),
            baseMetadata = target.metadata,
            watermarkConfig = target.watermarkConfig,
            targetUri = target.galleryUri,
            isSaveLocation = isSaveLocation
        ) ?: return@withContext PostCaptureExportResult.Failure("Failed updating portrait photo.")

        try {
            val updateValues = ContentValues().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
            }
            appContext.contentResolver.update(target.galleryUri, updateValues, null, null)
        } catch (_: Throwable) {}

        updateSidecarParams(target.originalId, newBlurIntensity, newFocusX, newFocusY)
        PostCaptureExportResult.Success(target.galleryUri, rendered.first, rendered.second, rendered.third)
    }

    suspend fun reRenderAndSaveCopy(
        target: PostCapturePortraitTarget,
        newBlurIntensity: Float,
        newFocusX: Float,
        newFocusY: Float,
        isSaveLocation: Boolean
    ): PostCaptureExportResult = withContext(Dispatchers.IO) {
        val cleanFile = File(target.originalFilePath)
        if (!cleanFile.exists() || cleanFile.length() <= 0L) {
            return@withContext PostCaptureExportResult.Failure("Original portrait image is unavailable.")
        }
        val segmentation = loadSegmentationForTarget(target)
            ?: return@withContext PostCaptureExportResult.Failure("Portrait depth map is unavailable.")

        var insertedUri: Uri? = null
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val fileName = "StuDO_PORTRAIT_$timeStamp.jpg"
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
            ) ?: return@withContext PostCaptureExportResult.Failure("Could not create gallery entry.")

            val rendered = renderPortraitToTargetUri(
                cleanFile = cleanFile,
                segmentation = segmentation,
                blurIntensity = newBlurIntensity.coerceIn(0f, 1f),
                focusX = newFocusX.coerceIn(0f, 1f),
                focusY = newFocusY.coerceIn(0f, 1f),
                baseMetadata = target.metadata,
                watermarkConfig = target.watermarkConfig,
                targetUri = insertedUri,
                isSaveLocation = isSaveLocation
            ) ?: run {
                try { appContext.contentResolver.delete(insertedUri, null, null) } catch (_: Throwable) {}
                return@withContext PostCaptureExportResult.Failure("Failed rendering portrait copy.")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val finalizeValues = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    appContext.contentResolver.update(insertedUri, finalizeValues, null, null)
                } catch (_: Throwable) {}
            }

            // Link copy to a cloned sidecar entry so each copy preserves independent blur parameters
            val clonedId = cloneSidecarEntryMetadata(
                sourceSidecarId = target.originalId,
                blurIntensity = newBlurIntensity,
                focusX = newFocusX,
                focusY = newFocusY
            )
            linkGalleryUri(insertedUri, clonedId ?: target.originalId)

            PostCaptureExportResult.Success(insertedUri, rendered.first, rendered.second, rendered.third)
        } catch (t: Throwable) {
            insertedUri?.let {
                try { appContext.contentResolver.delete(it, null, null) } catch (_: Throwable) {}
            }
            PostCaptureExportResult.Failure(t.localizedMessage ?: "Failed saving portrait copy.")
        }
    }

    private fun cloneSidecarEntryMetadata(
        sourceSidecarId: String,
        blurIntensity: Float,
        focusX: Float,
        focusY: Float
    ): String? {
        val source = synchronized(lock) { entries[sourceSidecarId] } ?: return null
        val newId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val cloned = source.copy(
            sidecarId = newId,
            blurIntensity = blurIntensity.coerceIn(0f, 1f),
            focusX = focusX.coerceIn(0f, 1f),
            focusY = focusY.coerceIn(0f, 1f),
            createdAtMs = now,
            updatedAtMs = now
        )
        synchronized(lock) {
            entries[newId] = cloned
            saveIndexLocked()
        }
        return newId
    }

    private fun updateSidecarParams(
        sidecarId: String,
        blurIntensity: Float,
        focusX: Float,
        focusY: Float
    ) {
        synchronized(lock) {
            val cur = entries[sidecarId] ?: return
            entries[sidecarId] = cur.copy(
                blurIntensity = blurIntensity.coerceIn(0f, 1f),
                focusX = focusX.coerceIn(0f, 1f),
                focusY = focusY.coerceIn(0f, 1f),
                updatedAtMs = System.currentTimeMillis()
            )
            saveIndexLocked()
        }
    }

    private fun renderPortraitToTargetUri(
        cleanFile: File,
        segmentation: PortraitSegmentationResult,
        blurIntensity: Float,
        focusX: Float,
        focusY: Float,
        baseMetadata: RealCameraMetadata,
        watermarkConfig: WatermarkConfig,
        targetUri: Uri,
        isSaveLocation: Boolean
    ): Triple<Int, Int, Long>? {
        var cleanBmp: Bitmap? = null
        var bokehBmp: Bitmap? = null
        var finalBmp: Bitmap? = null
        var tempOutFile: File? = null
        return try {
            val decodeOpts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            cleanBmp = BitmapFactory.decodeFile(cleanFile.absolutePath, decodeOpts) ?: return null
            val srcExif = try { ExifInterface(cleanFile.absolutePath) } catch (_: Throwable) { null }

            bokehBmp = PortraitBokehRenderer.renderFullResolutionPortrait(
                originalUpright = cleanBmp,
                segmentation = segmentation,
                options = BokehRenderOptions(
                    blurIntensity = blurIntensity,
                    focusX = focusX,
                    focusY = focusY
                )
            )
            if (bokehBmp !== cleanBmp && !cleanBmp.isRecycled) {
                cleanBmp.recycle()
            }

            finalBmp = if (watermarkConfig.isEnabled) {
                val freshMeta = if (srcExif != null) {
                    CameraMetadataUtil.extract(srcExif, bokehBmp.width, bokehBmp.height)
                } else {
                    baseMetadata
                }
                WatermarkPainter.applyWatermark(bokehBmp, watermarkConfig, freshMeta, appContext).also { stamped ->
                    if (stamped !== bokehBmp && !bokehBmp.isRecycled) {
                        bokehBmp.recycle()
                    }
                }
            } else {
                bokehBmp
            }

            tempOutFile = File.createTempFile("studo_portrait_export_", ".jpg", appContext.cacheDir)
            RandomAccessFile(tempOutFile, "rw").use { outRaf ->
                BufferedOutputStream(FileOutputStream(outRaf.fd), IO_BUFFER_SIZE).use { out ->
                    finalBmp.compress(Bitmap.CompressFormat.JPEG, 100, out)
                    out.flush()
                }
            }

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
            Triple(finalBmp.width, finalBmp.height, bytesWritten)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "renderPortraitToTargetUri failed: ${t.message}", t)
            null
        } finally {
            try {
                if (finalBmp != null && !finalBmp.isRecycled) finalBmp.recycle()
                if (bokehBmp != null && bokehBmp !== finalBmp && !bokehBmp.isRecycled) bokehBmp.recycle()
                if (cleanBmp != null && cleanBmp !== bokehBmp && cleanBmp !== finalBmp && !cleanBmp.isRecycled) cleanBmp.recycle()
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
                        }
                    }
                    return size
                }
            }
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
        } catch (_: Throwable) {
            0L
        }
    }

    private fun findLinkLocked(galleryUri: Uri): PortraitGalleryLink? {
        val uriStr = galleryUri.toString()
        links[uriStr]?.let { return it }
        val mediaId = PrivateOriginalStorageManager.parseMediaStoreId(galleryUri)
        if (mediaId != null) {
            return links.values.firstOrNull { it.mediaStoreId == mediaId }
        }
        return null
    }

    private fun ensureStorageDirLocked() {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        val nomedia = File(storageDir, NOMEDIA_FILE_NAME)
        if (!nomedia.exists()) {
            try { nomedia.createNewFile() } catch (_: Throwable) {}
        }
    }

    private fun pruneMissingLocked() {
        val missing = entries.values.filter { entry ->
            val f1 = File(storageDir, entry.cleanFileName)
            val f2 = File(storageDir, entry.matteFileName)
            val f3 = File(storageDir, entry.depthFileName)
            !f1.exists() || !f2.exists() || !f3.exists()
        }.map { it.sidecarId }
        if (missing.isNotEmpty()) {
            for (id in missing) {
                entries.remove(id)
                val stale = links.values.filter { it.sidecarId == id }.map { it.galleryUri }
                for (u in stale) links.remove(u)
            }
            saveIndexLocked()
        }
    }

    private fun pruneOldestIfNeededLocked() {
        if (entries.size <= MAX_SIDECAR_COUNT) return
        val ordered = entries.values.sortedBy { it.createdAtMs }
        val toRemoveCount = entries.size - MAX_SIDECAR_COUNT
        for (i in 0 until toRemoveCount) {
            val victim = ordered[i]
            entries.remove(victim.sidecarId)
            val staleLinks = links.values.filter { it.sidecarId == victim.sidecarId }.map { it.galleryUri }
            for (u in staleLinks) links.remove(u)
            val stillRefClean = entries.values.any { it.cleanFileName == victim.cleanFileName }
            if (!stillRefClean) {
                try { File(storageDir, victim.cleanFileName).delete() } catch (_: Throwable) {}
                try { File(storageDir, victim.matteFileName).delete() } catch (_: Throwable) {}
                try { File(storageDir, victim.depthFileName).delete() } catch (_: Throwable) {}
            }
        }
    }

    private fun loadIndexLocked() {
        entries.clear()
        links.clear()
        if (!indexFile.exists() || indexFile.length() <= 0L) return
        try {
            val root = JSONObject(indexFile.readText(Charsets.UTF_8))
            val arr = root.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("sidecarId")
                val clean = obj.optString("cleanFileName")
                val matte = obj.optString("matteFileName")
                val depth = obj.optString("depthFileName")
                if (id.isBlank() || clean.isBlank() || matte.isBlank() || depth.isBlank()) continue
                val sourceStr = obj.optString("depthSource", PortraitDepthSource.AI_SEGMENTATION.name)
                val source = PortraitDepthSource.values().firstOrNull { it.name == sourceStr }
                    ?: PortraitDepthSource.AI_SEGMENTATION
                val wmObj = obj.optJSONObject("watermarkConfig")
                val wmCfg = if (wmObj != null) {
                    CameraPreferencesRepository.watermarkConfigFromJson(wmObj, defaultEnabled = false)
                } else {
                    WatermarkConfig(isEnabled = false)
                }
                val metaObj = obj.optJSONObject("metadata")
                val meta = if (metaObj != null) metadataFromJson(metaObj) else RealCameraMetadata()
                entries[id] = PortraitSidecarEntry(
                    sidecarId = id,
                    cleanFileName = clean,
                    matteFileName = matte,
                    depthFileName = depth,
                    maskWidth = obj.optInt("maskWidth", 1),
                    maskHeight = obj.optInt("maskHeight", 1),
                    width = obj.optInt("width", 0),
                    height = obj.optInt("height", 0),
                    blurIntensity = obj.optDouble("blurIntensity", 0.62).toFloat(),
                    focusX = obj.optDouble("focusX", 0.5).toFloat(),
                    focusY = obj.optDouble("focusY", 0.45).toFloat(),
                    depthSource = source,
                    hasSubject = obj.optBoolean("hasSubject", true),
                    subjectCount = obj.optInt("subjectCount", 1),
                    watermarkConfig = wmCfg,
                    metadata = meta,
                    createdAtMs = obj.optLong("createdAtMs", 0L),
                    updatedAtMs = obj.optLong("updatedAtMs", 0L)
                )
            }
            val linksArr = root.optJSONArray("links") ?: JSONArray()
            for (i in 0 until linksArr.length()) {
                val obj = linksArr.optJSONObject(i) ?: continue
                val uri = obj.optString("galleryUri")
                val sidecarId = obj.optString("sidecarId")
                if (uri.isBlank() || sidecarId.isBlank()) continue
                val mediaId = if (obj.has("mediaStoreId") && !obj.isNull("mediaStoreId")) {
                    obj.optLong("mediaStoreId")
                } else null
                links[uri] = PortraitGalleryLink(
                    galleryUri = uri,
                    mediaStoreId = mediaId,
                    sidecarId = sidecarId,
                    updatedAtMs = obj.optLong("updatedAtMs", 0L)
                )
            }
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Failed loading portrait index: ${t.message}", t)
        }
    }

    private fun saveIndexLocked() {
        ensureStorageDirLocked()
        val root = JSONObject()
        val arr = JSONArray()
        for (e in entries.values) {
            arr.put(
                JSONObject().apply {
                    put("sidecarId", e.sidecarId)
                    put("cleanFileName", e.cleanFileName)
                    put("matteFileName", e.matteFileName)
                    put("depthFileName", e.depthFileName)
                    put("maskWidth", e.maskWidth)
                    put("maskHeight", e.maskHeight)
                    put("width", e.width)
                    put("height", e.height)
                    put("blurIntensity", e.blurIntensity.toDouble())
                    put("focusX", e.focusX.toDouble())
                    put("focusY", e.focusY.toDouble())
                    put("depthSource", e.depthSource.name)
                    put("hasSubject", e.hasSubject)
                    put("subjectCount", e.subjectCount)
                    put("watermarkConfig", CameraPreferencesRepository.watermarkConfigToJson(e.watermarkConfig))
                    put("metadata", metadataToJson(e.metadata))
                    put("createdAtMs", e.createdAtMs)
                    put("updatedAtMs", e.updatedAtMs)
                }
            )
        }
        root.put("entries", arr)

        val linksArr = JSONArray()
        for (link in links.values) {
            linksArr.put(
                JSONObject().apply {
                    put("galleryUri", link.galleryUri)
                    put("mediaStoreId", link.mediaStoreId ?: JSONObject.NULL)
                    put("sidecarId", link.sidecarId)
                    put("updatedAtMs", link.updatedAtMs)
                }
            )
        }
        root.put("links", linksArr)

        val tmp = File(storageDir, "$INDEX_FILE_NAME.tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(indexFile)) {
            indexFile.writeText(root.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun metadataToJson(meta: RealCameraMetadata): JSONObject = JSONObject().apply {
        put("iso", meta.iso ?: JSONObject.NULL)
        put("shutterSpeed", meta.shutterSpeed ?: JSONObject.NULL)
        put("aperture", meta.aperture ?: JSONObject.NULL)
        put("focalLength", meta.focalLength ?: JSONObject.NULL)
        put("dateTimeFormatted", meta.dateTimeFormatted ?: JSONObject.NULL)
        put("deviceModel", meta.deviceModel ?: JSONObject.NULL)
        put("lensInfo", meta.lensInfo ?: JSONObject.NULL)
        put("resolutionText", meta.resolutionText ?: JSONObject.NULL)
        put("manufacturer", meta.manufacturer ?: JSONObject.NULL)
        put("modelOnly", meta.modelOnly ?: JSONObject.NULL)
    }

    private fun metadataFromJson(obj: JSONObject): RealCameraMetadata {
        fun optStr(k: String): String? =
            if (obj.has(k) && !obj.isNull(k)) obj.optString(k).takeIf { it.isNotBlank() } else null
        return RealCameraMetadata(
            iso = optStr("iso"),
            shutterSpeed = optStr("shutterSpeed"),
            aperture = optStr("aperture"),
            focalLength = optStr("focalLength"),
            dateTimeFormatted = optStr("dateTimeFormatted"),
            deviceModel = optStr("deviceModel"),
            lensInfo = optStr("lensInfo"),
            resolutionText = optStr("resolutionText"),
            manufacturer = optStr("manufacturer"),
            modelOnly = optStr("modelOnly")
        )
    }
}
