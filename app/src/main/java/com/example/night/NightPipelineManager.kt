package com.example.night

import android.Manifest
import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CaptureRequest
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.data.CacheEvictionResult
import com.example.data.PrivateOriginalStorageManager
import com.example.model.CameraFilter
import com.example.model.CaptureAspectRatio
import com.example.model.NightExposureMode
import com.example.model.WatermarkConfig
import com.example.util.AspectRatioCropUtil
import com.example.util.CameraLiveFilterEngine
import com.example.util.CameraMetadataUtil
import com.example.util.WatermarkPainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

data class NightBindingCapability(
    val isExtensionSupported: Boolean,
    val effectiveSelector: CameraSelector,
    val estimatedLatencyMs: Long? = null
)

data class NightBurstSaveResult(
    val uri: Uri,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val frameCount: Int,
    val usedHardwareExtension: Boolean
)

/**
 * Hybrid Night Capture Pipeline Manager:
 * 1. Primary: Queries CameraX [ExtensionsManager] for [ExtensionMode.NIGHT] on the active camera.
 *    When supported, supplies the vendor Night extension [CameraSelector] for native OEM processing.
 * 2. Fallback: Captures a rapid burst of 4–6 frames in-memory with AE/AF locked and boosted EV/ISO
 *    via [Camera2CameraControl], stacks & aligns frames on [Dispatchers.Default] via [NightFrameStacker],
 *    and writes the high-res output JPEG cleanly to MediaStore with restored EXIF metadata.
 */
class NightPipelineManager(context: Context) {

    private val appContext = context.applicationContext
    private val burstCaptureExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var cachedExtensionsManager: ExtensionsManager? = null

    fun shutdown() {
        try {
            burstCaptureExecutor.shutdownNow()
        } catch (_: Throwable) {}
    }

    /**
     * Probes [ExtensionsManager] for [ExtensionMode.NIGHT] availability on [baseSelector].
     */
    suspend fun resolveNightBinding(
        cameraProvider: ProcessCameraProvider,
        baseSelector: CameraSelector,
        mainExecutor: Executor
    ): NightBindingCapability {
        return try {
            val extManager = cachedExtensionsManager ?: run {
                val future = ExtensionsManager.getInstanceAsync(appContext, cameraProvider)
                suspendCancellableCoroutine<ExtensionsManager?> { cont ->
                    future.addListener(
                        {
                            try {
                                if (cont.isActive) cont.resume(future.get())
                            } catch (_: Throwable) {
                                if (cont.isActive) cont.resume(null)
                            }
                        },
                        mainExecutor
                    )
                }?.also { cachedExtensionsManager = it }
            }

            if (extManager != null && extManager.isExtensionAvailable(baseSelector, ExtensionMode.NIGHT)) {
                val nightSelector = extManager.getExtensionEnabledCameraSelector(baseSelector, ExtensionMode.NIGHT)
                val latencyMs = try {
                    val range = extManager.getEstimatedCaptureLatencyRange(
                        nightSelector,
                        ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                    )
                    if (range != null) {
                        ((range.lower + range.upper) / 2L).coerceIn(800L, 6000L)
                    } else {
                        null
                    }
                } catch (_: Throwable) {
                    null
                }
                NightBindingCapability(
                    isExtensionSupported = true,
                    effectiveSelector = nightSelector,
                    estimatedLatencyMs = latencyMs
                )
            } else {
                NightBindingCapability(
                    isExtensionSupported = false,
                    effectiveSelector = baseSelector,
                    estimatedLatencyMs = null
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.d(TAG, "ExtensionMode.NIGHT unavailable, falling back to Multi-Frame Stack: ${t.message}")
            NightBindingCapability(
                isExtensionSupported = false,
                effectiveSelector = baseSelector,
                estimatedLatencyMs = null
            )
        }
    }

    /**
     * Computes a realistic capture duration estimate (in seconds) based on Auto vs Max setting,
     * hardware extension latency, and real-time scene darkness telemetry.
     */
    fun estimateCaptureDurationSeconds(
        exposureMode: NightExposureMode,
        isExtensionSupported: Boolean,
        extensionLatencyMs: Long?,
        isLowLightDetected: Boolean,
        lastExposureNs: Long,
        lastIso: Int
    ): Float {
        if (isExtensionSupported && extensionLatencyMs != null && extensionLatencyMs > 0L) {
            val hwSec = (extensionLatencyMs / 1000f).coerceIn(1.0f, 5.5f)
            val multiplier = if (exposureMode == NightExposureMode.MAX) 1.25f else 1.0f
            return (hwSec * multiplier).coerceIn(1.5f, 6.0f)
        }

        val darknessBoost = when {
            lastExposureNs >= 65_000_000L || lastIso >= 1600 -> 1.0f
            isLowLightDetected || lastExposureNs >= 33_000_000L || lastIso >= 600 -> 0.5f
            else -> -0.5f
        }
        val base = exposureMode.baseDurationSeconds
        return (base + darknessBoost).coerceIn(1.5f, 5.0f)
    }

    /**
     * Captures a rapid 4–6 frame burst via [imageCapture], boosts exposure compensation & locks AE/AF
     * during acquisition, aligns & stacks on [Dispatchers.Default], applies crop/filter/watermark,
     * and writes a finalized JPEG to MediaStore.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    suspend fun captureAndStackNightBurst(
        imageCapture: ImageCapture,
        cameraControl: CameraControl?,
        cameraInfo: CameraInfo?,
        exposureMode: NightExposureMode,
        aspectRatio: CaptureAspectRatio,
        fullScreenAspectRatio: Float,
        filter: CameraFilter,
        isFrontCamera: Boolean,
        mirrorSelfie: Boolean,
        watermarkConfig: WatermarkConfig?,
        isSaveLocation: Boolean,
        activeFocalLengthMm: Float?,
        privateOriginalStorage: PrivateOriginalStorageManager?,
        retainCleanOriginal: Boolean,
        watermarkCacheLimitBytes: Long,
        onEvictionNotice: ((CacheEvictionResult) -> Unit)? = null,
        onProgress: (progress: Float, isProcessingStage: Boolean) -> Unit,
        onBurstShutterTick: () -> Unit = {}
    ): NightBurstSaveResult {
        val frameCount = exposureMode.burstFrameCount
        val originalExposureIndex = try {
            cameraInfo?.exposureState?.exposureCompensationIndex ?: 0
        } catch (_: Throwable) {
            0
        }
        val expRange = try {
            cameraInfo?.exposureState?.exposureCompensationRange
        } catch (_: Throwable) {
            null
        }
        val boostedExposureIndex = if (expRange != null && expRange.upper > originalExposureIndex) {
            val positiveSpan = expRange.upper - originalExposureIndex
            val fraction = if (exposureMode == NightExposureMode.MAX) 0.72f else 0.42f
            (originalExposureIndex + (positiveSpan * fraction).roundToInt())
                .coerceIn(expRange.lower, expRange.upper)
        } else {
            originalExposureIndex
        }

        val c2Control = cameraControl?.let {
            try {
                Camera2CameraControl.from(it)
            } catch (_: Throwable) {
                null
            }
        }

        val burstJpegs = ArrayList<ByteArray>(frameCount)
        var firstFrameExifTags: Map<String, String> = emptyMap()
        var firstFrameMetadata: com.example.util.RealCameraMetadata? = null

        try {
            onProgress(0.05f, false)

            // Apply AE lock + moderate/high EV boost during burst acquisition
            if (cameraControl != null) {
                try {
                    if (boostedExposureIndex != originalExposureIndex) {
                        cameraControl.setExposureCompensationIndex(boostedExposureIndex)
                    }
                    c2Control?.setCaptureRequestOptions(
                        CaptureRequestOptions.Builder()
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                            .setCaptureRequestOption(
                                CaptureRequest.CONTROL_AWB_LOCK,
                                true
                            )
                            .build()
                    )
                } catch (t: Throwable) {
                    Log.d(TAG, "Optional Camera2 AE/EV boost skipped: ${t.message}")
                }
                // Brief settle so AE/EV boost takes effect cleanly on frame 0
                delay(110L)
            }

            onBurstShutterTick()

            // Acquire burst frames in-memory without touching MediaStore
            for (idx in 0 until frameCount) {
                val frameBytes = captureSingleInMemoryJpeg(imageCapture)
                burstJpegs.add(frameBytes)
                if (idx == 0) {
                    firstFrameExifTags = extractExifTags(frameBytes, isSaveLocation)
                    firstFrameMetadata = CameraMetadataUtil.fromByteArray(frameBytes)
                }
                val progress = 0.08f + ((idx + 1).toFloat() / frameCount.toFloat()) * 0.54f
                onProgress(progress, false)
            }
        } finally {
            // Immediately restore normal preview AE/AWB/EV once burst frames are captured
            if (cameraControl != null) {
                try {
                    c2Control?.clearCaptureRequestOptions()
                } catch (_: Throwable) {}
                try {
                    cameraControl.setExposureCompensationIndex(originalExposureIndex)
                } catch (_: Throwable) {}
            }
        }

        onProgress(0.64f, true)

        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLowRam = try {
            am?.isLowRamDevice == true
        } catch (_: Throwable) {
            false
        }
        val stackConfig = NightStackConfig(
            isMaxExposure = exposureMode == NightExposureMode.MAX,
            maxLongestEdgePx = NightFrameStacker.recommendedMaxEdgePx(isLowRam),
            enableAlignment = true,
            enableDenoiseSharpen = true
        )

        var stackedBitmap: Bitmap? = null
        try {
            val stackResult = withContext(Dispatchers.Default) {
                NightFrameStacker.stackJpegBurst(
                    jpegFrames = burstJpegs,
                    config = stackConfig,
                    mirrorHorizontal = isFrontCamera && mirrorSelfie,
                    onProgress = { stackFrac ->
                        onProgress(0.64f + stackFrac * 0.26f, true)
                    }
                )
            }
            // Free raw compressed burst buffers immediately after stacking completes
            burstJpegs.clear()
            stackedBitmap = stackResult.bitmap

            // Apply crop, active filter, optional private clean original, watermark, and save to MediaStore
            val saveRes = withContext(Dispatchers.IO) {
                var currentBmp: Bitmap = stackedBitmap!!
                val cropped = AspectRatioCropUtil.cropBitmapIfNeeded(
                    bitmap = currentBmp,
                    aspectRatio = aspectRatio,
                    fullScreenAspectRatio = fullScreenAspectRatio
                )
                if (cropped !== currentBmp) {
                    currentBmp.recycle()
                    currentBmp = cropped
                    stackedBitmap = currentBmp
                }

                if (filter != CameraFilter.NATURAL) {
                    val filtered = CameraLiveFilterEngine.applyFilterToBitmap(
                        source = currentBmp,
                        filter = filter,
                        recycleSource = true
                    )
                    currentBmp = filtered
                    stackedBitmap = currentBmp
                }

                val hasWatermark = watermarkConfig?.isEnabled == true
                var retainedEntryId: String? = null
                if (hasWatermark && retainCleanOriginal && privateOriginalStorage != null) {
                    val meta = CameraMetadataUtil.withActiveFocalLengthIfMissing(
                        firstFrameMetadata ?: CameraMetadataUtil.fallbackMetadata(currentBmp.width, currentBmp.height),
                        activeFocalLengthMm
                    )
                    val entry = privateOriginalStorage.saveOriginalFromBitmap(
                        bitmap = currentBmp,
                        exifAttributes = firstFrameExifTags,
                        metadata = meta,
                        isSaveLocation = isSaveLocation,
                        protectUntilUnmarked = true
                    )
                    retainedEntryId = entry?.originalId
                }

                if (hasWatermark && watermarkConfig != null) {
                    val meta = CameraMetadataUtil.withActiveFocalLengthIfMissing(
                        firstFrameMetadata ?: CameraMetadataUtil.fallbackMetadata(currentBmp.width, currentBmp.height),
                        activeFocalLengthMm
                    )
                    val stamped = WatermarkPainter.applyWatermark(currentBmp, watermarkConfig, meta, appContext)
                    if (stamped !== currentBmp) {
                        currentBmp.recycle()
                        currentBmp = stamped
                        stackedBitmap = currentBmp
                    }
                }

                onProgress(0.94f, true)

                val (savedUri, fileBytes) = writeBitmapToMediaStore(
                    bitmap = currentBmp,
                    exifAttributes = firstFrameExifTags,
                    isSaveLocation = isSaveLocation
                )

                if (retainedEntryId != null && privateOriginalStorage != null && watermarkConfig != null) {
                    privateOriginalStorage.linkGalleryOutput(savedUri, retainedEntryId, watermarkConfig)
                    val eviction = privateOriginalStorage.enforceCacheLimitBytes(
                        limitBytes = watermarkCacheLimitBytes,
                        protectedOriginalId = retainedEntryId
                    )
                    privateOriginalStorage.unmarkOriginalActive(retainedEntryId)
                    if (eviction.evictedCount > 0) {
                        withContext(Dispatchers.Main) {
                            onEvictionNotice?.invoke(eviction)
                        }
                    }
                }

                NightBurstSaveResult(
                    uri = savedUri,
                    width = currentBmp.width,
                    height = currentBmp.height,
                    sizeBytes = fileBytes,
                    frameCount = stackResult.alignedFrameCount,
                    usedHardwareExtension = false
                )
            }
            onProgress(1f, true)
            return saveRes
        } finally {
            burstJpegs.clear()
            if (stackedBitmap != null && !stackedBitmap!!.isRecycled) {
                stackedBitmap!!.recycle()
            }
        }
    }

    private suspend fun captureSingleInMemoryJpeg(imageCapture: ImageCapture): ByteArray {
        return suspendCancellableCoroutine { cont ->
            imageCapture.takePicture(
                burstCaptureExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val jpegBytes = imageProxyToJpegBytes(image)
                            if (cont.isActive) {
                                cont.resume(jpegBytes)
                            }
                        } catch (t: Throwable) {
                            if (cont.isActive) {
                                cont.resumeWithException(t)
                            }
                        } finally {
                            // Immediately release hardware ImageProxy buffer
                            try {
                                image.close()
                            } catch (_: Throwable) {}
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (cont.isActive) {
                            cont.resumeWithException(exception)
                        }
                    }
                }
            )
        }
    }

    private fun imageProxyToJpegBytes(image: ImageProxy): ByteArray {
        return when (image.format) {
            ImageFormat.JPEG -> {
                val buf = image.planes[0].buffer
                buf.rewind()
                ByteArray(buf.remaining()).also { buf.get(it) }
            }
            ImageFormat.YUV_420_888 -> {
                val nv21 = yuv420ToNv21(image)
                val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
                ByteArrayOutputStream().use { out ->
                    yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 96, out)
                    out.toByteArray()
                }
            }
            else -> {
                val bmp = image.toBitmap()
                try {
                    ByteArrayOutputStream().use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 96, out)
                        out.toByteArray()
                    }
                } finally {
                    if (!bmp.isRecycled) bmp.recycle()
                }
            }
        }
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = (width / 2) * (height / 2)
        val nv21 = ByteArray(ySize + uvSize * 2)

        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val yLimit = yBuf.limit()

        var pos = 0
        if (yPixelStride == 1 && yRowStride == width && yLimit >= ySize) {
            yBuf.rewind()
            yBuf.get(nv21, 0, ySize)
            pos = ySize
        } else {
            for (r in 0 until height) {
                val rowOff = r * yRowStride
                for (c in 0 until width) {
                    val idx = rowOff + c * yPixelStride
                    nv21[pos++] = if (idx in 0 until yLimit) yBuf.get(idx) else 0
                }
            }
        }

        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uLimit = uBuf.limit()
        val vLimit = vBuf.limit()

        for (r in 0 until height / 2) {
            val uOff = r * uRowStride
            val vOff = r * vRowStride
            for (c in 0 until width / 2) {
                val vIdx = vOff + c * vPixelStride
                val uIdx = uOff + c * uPixelStride
                nv21[pos++] = if (vIdx in 0 until vLimit) vBuf.get(vIdx) else 128.toByte()
                nv21[pos++] = if (uIdx in 0 until uLimit) uBuf.get(uIdx) else 128.toByte()
            }
        }
        return nv21
    }

    private fun extractExifTags(jpegBytes: ByteArray, isSaveLocation: Boolean): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            ByteArrayInputStream(jpegBytes).use { stream ->
                val exif = ExifInterface(stream)
                val baseTags = listOf(
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
                for (t in baseTags) {
                    exif.getAttribute(t)?.let { map[t] = it }
                }
                if (isSaveLocation) {
                    val gpsTags = listOf(
                        ExifInterface.TAG_GPS_LATITUDE,
                        ExifInterface.TAG_GPS_LATITUDE_REF,
                        ExifInterface.TAG_GPS_LONGITUDE,
                        ExifInterface.TAG_GPS_LONGITUDE_REF,
                        ExifInterface.TAG_GPS_ALTITUDE,
                        ExifInterface.TAG_GPS_ALTITUDE_REF,
                        ExifInterface.TAG_GPS_TIMESTAMP,
                        ExifInterface.TAG_GPS_DATESTAMP
                    )
                    for (t in gpsTags) {
                        exif.getAttribute(t)?.let { map[t] = it }
                    }
                }
            }
        } catch (_: Throwable) {}
        return map
    }

    private fun writeBitmapToMediaStore(
        bitmap: Bitmap,
        exifAttributes: Map<String, String>,
        isSaveLocation: Boolean
    ): Pair<Uri, Long> {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val fileName = "StuDO_NIGHT_$timeStamp.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StuDO")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("Failed to create MediaStore entry for Night Mode photo")

        var tempFile: java.io.File? = null
        try {
            tempFile = java.io.File.createTempFile("studo_night_", ".jpg", appContext.cacheDir)
            java.io.RandomAccessFile(tempFile, "rw").use { raf ->
                java.io.BufferedOutputStream(java.io.FileOutputStream(raf.fd), 256 * 1024).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 98, out)
                    out.flush()
                }
                val size = raf.channel.size()
                if (size > 0L) {
                    resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                        java.io.FileOutputStream(pfd.fileDescriptor).channel.use { dst ->
                            try {
                                dst.truncate(0)
                                dst.position(0)
                            } catch (_: Throwable) {}
                            raf.channel.position(0)
                            raf.channel.transferTo(0, size, dst)
                        }
                    }
                }
            }

            try {
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val destExif = ExifInterface(pfd.fileDescriptor)
                    for ((k, v) in exifAttributes) {
                        destExif.setAttribute(k, v)
                    }
                    if (!isSaveLocation) {
                        val gpsTags = listOf(
                            ExifInterface.TAG_GPS_LATITUDE,
                            ExifInterface.TAG_GPS_LATITUDE_REF,
                            ExifInterface.TAG_GPS_LONGITUDE,
                            ExifInterface.TAG_GPS_LONGITUDE_REF,
                            ExifInterface.TAG_GPS_ALTITUDE,
                            ExifInterface.TAG_GPS_ALTITUDE_REF,
                            ExifInterface.TAG_GPS_TIMESTAMP,
                            ExifInterface.TAG_GPS_DATESTAMP
                        )
                        for (t in gpsTags) destExif.setAttribute(t, null)
                    }
                    // Stacked bitmap is already rotated upright & mirrored if applicable
                    destExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                    destExif.saveAttributes()
                }
            } catch (_: Throwable) {}

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val clearPending = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                try {
                    resolver.update(uri, clearPending, null, null)
                } catch (_: Throwable) {}
            }

            val savedSize = try {
                resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            } catch (_: Throwable) {
                0L
            }
            if (savedSize <= 0L) {
                try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
                throw IllegalStateException("Saved Night Mode JPEG is 0 bytes")
            }
            return uri to savedSize
        } catch (t: Throwable) {
            try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
            throw t
        } finally {
            try { tempFile?.delete() } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val TAG = "NightPipelineManager"
    }
}
