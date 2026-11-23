package com.example.data

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import android.media.ExifInterface
import com.example.model.CameraFilter
import com.example.model.CaptureAspectRatio
import com.example.model.MediaItem
import com.example.model.PhotoDetails
import com.example.util.AspectRatioCropUtil
import com.example.util.CameraLiveFilterEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

class MediaCaptureManager(private val context: Context) {

    private val captureCallbackExecutor = Executors.newSingleThreadExecutor()
    private val postProcessingExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun shutdown() {
        mainHandler.removeCallbacksAndMessages(null)
        try { captureCallbackExecutor.shutdownNow() } catch (_: Throwable) {}
        try { postProcessingExecutor.shutdownNow() } catch (_: Throwable) {}
    }

    fun hasSufficientStorage(minBytes: Long = 50L * 1024L * 1024L): Boolean {
        return try {
            val path = context.filesDir?.absolutePath ?: return true
            val stat = android.os.StatFs(path)
            val total = stat.totalBytes
            if (total <= 0L) true else stat.availableBytes >= minBytes
        } catch (_: Throwable) {
            true
        }
    }

    fun capturePhoto(
        imageCapture: ImageCapture,
        aspectRatio: CaptureAspectRatio = CaptureAspectRatio.RATIO_4_3,
        fullScreenAspectRatio: Float = 9f / 20f,
        filter: CameraFilter = CameraFilter.NATURAL,
        isFrontCamera: Boolean = false,
        mirrorSelfie: Boolean = true,
        watermarkConfig: com.example.model.WatermarkConfig? = null,
        isSaveLocation: Boolean = false,
        activeFocalLengthMm: Float? = null,
        privateOriginalStorage: PrivateOriginalStorageManager? = null,
        retainCleanOriginal: Boolean = false,
        watermarkCacheLimitBytes: Long = 0L,
        onEvictionNotice: ((CacheEvictionResult) -> Unit)? = null,
        isPortraitMode: Boolean = false,
        portraitBlurIntensity: Float = 0.62f,
        portraitFocusX: Float? = null,
        portraitFocusY: Float? = null,
        portraitDepthFrameProvider: (() -> com.example.portrait.HardwareDepthFrame?)? = null,
        portraitSidecarStorage: com.example.portrait.PortraitSidecarStorage? = null,
        portraitPipelineManager: com.example.portrait.PortraitPipelineManager? = null,
        isPortraitOemBokehCapture: Boolean = false,
        onPortraitProcessed: ((com.example.model.PortraitDepthSource, Int, com.example.model.PortraitCaptureTier) -> Unit)? = null,
        onCaptureStarted: () -> Unit = {},
        onShutterReady: () -> Unit = {},
        onPhotoSaved: (Uri, Int, Int, Long) -> Unit,
        onError: (ImageCaptureException) -> Unit
    ) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val fileName = "StuDO_$timeStamp.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StuDO")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val metadata = ImageCapture.Metadata().apply {
            if (isFrontCamera && mirrorSelfie) {
                isReversedHorizontal = true
            }
            if (isSaveLocation) {
                val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (hasFine || hasCoarse) {
                    try {
                        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                        val loc = lm?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                            ?: lm?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                        if (loc != null) {
                            location = loc
                        }
                    } catch (_: SecurityException) {}
                }
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).setMetadata(metadata).build()

        val captureStartedAction = onCaptureStarted

        imageCapture.takePicture(
            outputOptions,
            captureCallbackExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onCaptureStarted() {
                    mainHandler.post { captureStartedAction() }
                }

                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri
                    if (savedUri != null) {
                        // The camera hardware sensor capture has finished and written the raw JPEG.
                        // Release shutter button IMMEDIATELY so camera pipeline is ready for another capture!
                        mainHandler.post { onShutterReady() }

                        // Decouple image saving, EXIF processing, watermarking, and finalization to background thread pool
                        postProcessingExecutor.execute {
                            processAndFinalizePhoto(
                                savedUri = savedUri,
                                aspectRatio = aspectRatio,
                                fullScreenAspectRatio = fullScreenAspectRatio,
                                filter = filter,
                                isFrontCamera = isFrontCamera,
                                mirrorSelfie = mirrorSelfie,
                                watermarkConfig = watermarkConfig,
                                isSaveLocation = isSaveLocation,
                                activeFocalLengthMm = activeFocalLengthMm,
                                privateOriginalStorage = privateOriginalStorage,
                                retainCleanOriginal = retainCleanOriginal,
                                watermarkCacheLimitBytes = watermarkCacheLimitBytes,
                                onEvictionNotice = onEvictionNotice,
                                isPortraitMode = isPortraitMode,
                                portraitBlurIntensity = portraitBlurIntensity,
                                portraitFocusX = portraitFocusX,
                                portraitFocusY = portraitFocusY,
                                portraitDepthFrameProvider = portraitDepthFrameProvider,
                                portraitSidecarStorage = portraitSidecarStorage,
                                portraitPipelineManager = portraitPipelineManager,
                                isPortraitOemBokehCapture = isPortraitOemBokehCapture,
                                onPortraitProcessed = onPortraitProcessed,
                                onComplete = { uri, w, h, bytes ->
                                    mainHandler.post {
                                        onPhotoSaved(uri, w, h, bytes)
                                    }
                                },
                                onFailure = { exc ->
                                    mainHandler.post {
                                        onError(exc)
                                    }
                                }
                            )
                        }
                    } else {
                        mainHandler.post {
                            onShutterReady()
                            onError(
                                ImageCaptureException(
                                    ImageCapture.ERROR_FILE_IO,
                                    "Failed to resolve saved photo URI",
                                    null
                                )
                            )
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    mainHandler.post {
                        onShutterReady()
                        onError(exception)
                    }
                }
            }
        )
    }

    private fun processAndFinalizePhoto(
        savedUri: Uri,
        aspectRatio: CaptureAspectRatio,
        fullScreenAspectRatio: Float,
        filter: CameraFilter,
        isFrontCamera: Boolean,
        mirrorSelfie: Boolean,
        watermarkConfig: com.example.model.WatermarkConfig?,
        isSaveLocation: Boolean,
        activeFocalLengthMm: Float? = null,
        privateOriginalStorage: PrivateOriginalStorageManager? = null,
        retainCleanOriginal: Boolean = false,
        watermarkCacheLimitBytes: Long = 0L,
        onEvictionNotice: ((CacheEvictionResult) -> Unit)? = null,
        isPortraitMode: Boolean = false,
        portraitBlurIntensity: Float = 0.62f,
        portraitFocusX: Float? = null,
        portraitFocusY: Float? = null,
        portraitDepthFrameProvider: (() -> com.example.portrait.HardwareDepthFrame?)? = null,
        portraitSidecarStorage: com.example.portrait.PortraitSidecarStorage? = null,
        portraitPipelineManager: com.example.portrait.PortraitPipelineManager? = null,
        isPortraitOemBokehCapture: Boolean = false,
        onPortraitProcessed: ((com.example.model.PortraitDepthSource, Int, com.example.model.PortraitCaptureTier) -> Unit)? = null,
        onComplete: (Uri, Int, Int, Long) -> Unit,
        onFailure: (ImageCaptureException) -> Unit
    ) {
        val hasFilter = (filter != CameraFilter.NATURAL)
        // CameraX OutputFileOptions.Metadata.isReversedHorizontal already mirrors the saved front camera JPEG
        val shouldMirror = false
        val hasWatermark = watermarkConfig?.isEnabled == true

        var exifOrientation = ExifInterface.ORIENTATION_NORMAL
        val exifAttributes = mutableMapOf<String, String>()
        var photoMetadata: com.example.util.RealCameraMetadata? = null

        try {
            context.contentResolver.openInputStream(savedUri)?.use { stream ->
                val exif = ExifInterface(stream)
                photoMetadata = com.example.util.CameraMetadataUtil.extract(exif)
                exifOrientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                val tags = listOf(
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
                for (t in tags) {
                    val v = exif.getAttribute(t)
                    if (v != null) exifAttributes[t] = v
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
                        val v = exif.getAttribute(t)
                        if (v != null) exifAttributes[t] = v
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.d("MediaCaptureManager", "Note reading initial EXIF tags: ${t.message}")
        }

        val (rawBoundsW, rawBoundsH) = try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(savedUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            }
            opts.outWidth to opts.outHeight
        } catch (_: Throwable) {
            0 to 0
        }
        val (orientedW, orientedH) = AspectRatioCropUtil.orientedDimensions(
            rawWidth = rawBoundsW,
            rawHeight = rawBoundsH,
            exifOrientation = exifOrientation
        )
        val needsCrop = AspectRatioCropUtil.needsCrop(
            width = orientedW,
            height = orientedH,
            aspectRatio = aspectRatio,
            fullScreenAspectRatio = fullScreenAspectRatio
        )

        val needsPixelProcessing = hasFilter || shouldMirror || needsCrop || hasWatermark || isPortraitMode
        val shouldRetainCleanOriginal = hasWatermark && retainCleanOriginal && privateOriginalStorage != null
        var retainedEntry: PrivateOriginalEntry? = null
        var savedSidecarEntry: com.example.portrait.PortraitSidecarEntry? = null

        if (needsPixelProcessing) {
            var tempOutFile: java.io.File? = null
            var originalBitmap: Bitmap? = null
            var currentBitmap: Bitmap? = null
            try {
                // Preserve raw untouched JPEG stream bytes when no crop/filter/portrait is applied
                if (shouldRetainCleanOriginal && !hasFilter && !needsCrop && !isPortraitMode) {
                    val baseMeta = photoMetadata ?: com.example.util.CameraMetadataUtil.fromUri(context, savedUri)
                    val metaWithLens = com.example.util.CameraMetadataUtil.withActiveFocalLengthIfMissing(baseMeta, activeFocalLengthMm)
                    retainedEntry = privateOriginalStorage?.saveOriginalFromUri(
                        sourceUri = savedUri,
                        metadata = metaWithLens,
                        isSaveLocation = isSaveLocation,
                        protectUntilUnmarked = true
                    )
                }

                context.contentResolver.openInputStream(savedUri)?.use { input ->
                    val portraitSampleSize = if (isPortraitMode && rawBoundsW > 0 && rawBoundsH > 0) {
                        com.example.portrait.PortraitPipelineManager.computePortraitDecodeSampleSize(
                            rawWidth = rawBoundsW,
                            rawHeight = rawBoundsH
                        )
                    } else {
                        1
                    }
                    val decodeOpts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inScaled = false
                        inMutable = true
                        inSampleSize = portraitSampleSize
                    }
                    originalBitmap = BitmapFactory.decodeStream(input, null, decodeOpts)
                    val original = originalBitmap
                    if (original != null) {
                        val transformMatrix = Matrix()
                        when (exifOrientation) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> transformMatrix.postRotate(90f)
                            ExifInterface.ORIENTATION_ROTATE_180 -> transformMatrix.postRotate(180f)
                            ExifInterface.ORIENTATION_ROTATE_270 -> transformMatrix.postRotate(270f)
                            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transformMatrix.postScale(-1f, 1f)
                            ExifInterface.ORIENTATION_FLIP_VERTICAL -> transformMatrix.postScale(1f, -1f)
                            ExifInterface.ORIENTATION_TRANSPOSE -> {
                                transformMatrix.postRotate(90f)
                                transformMatrix.postScale(-1f, 1f)
                            }
                            ExifInterface.ORIENTATION_TRANSVERSE -> {
                                transformMatrix.postRotate(270f)
                                transformMatrix.postScale(-1f, 1f)
                            }
                        }

                        var uprightBitmap: Bitmap = if (!transformMatrix.isIdentity) {
                            val transformed = Bitmap.createBitmap(
                                original, 0, 0, original.width, original.height, transformMatrix, true
                            )
                            if (transformed != original) original.recycle()
                            transformed
                        } else {
                            original
                        }

                        if (needsCrop) {
                            val cropped = AspectRatioCropUtil.cropBitmapIfNeeded(
                                bitmap = uprightBitmap,
                                aspectRatio = aspectRatio,
                                fullScreenAspectRatio = fullScreenAspectRatio
                            )
                            if (cropped != uprightBitmap && uprightBitmap != original) {
                                uprightBitmap.recycle()
                            }
                            uprightBitmap = cropped
                        }
                        currentBitmap = uprightBitmap

                        // Step 2: Apply professional filmic color filter at full resolution
                        if (hasFilter) {
                            val active = currentBitmap!!
                            currentBitmap = CameraLiveFilterEngine.applyFilterToBitmap(
                                source = active,
                                filter = filter,
                                recycleSource = (active != original)
                            )
                        }

                        // Step 2.5: Portrait Mode ~12MP resolution cap + 4-tier capture waterfall & sidecar preservation
                        if (isPortraitMode) {
                            val activePrePortrait = currentBitmap!!
                            val cappedBmp = com.example.portrait.PortraitPipelineManager.capPortraitResolution(
                                bitmap = activePrePortrait,
                                maxLongestEdge = com.example.portrait.PortraitPipelineManager.MAX_PORTRAIT_EDGE_PX
                            )
                            if (cappedBmp !== activePrePortrait && activePrePortrait != original) {
                                activePrePortrait.recycle()
                            }
                            currentBitmap = cappedBmp

                            if (isPortraitOemBokehCapture) {
                                // Tier 1: CameraX ExtensionMode.BOKEH already rendered bokeh directly on sensor/HAL
                                portraitPipelineManager?.executeCaptureWaterfall(
                                    uprightBitmap = cappedBmp,
                                    isOemExtensionCapture = true,
                                    focusX = portraitFocusX,
                                    focusY = portraitFocusY
                                )
                                onPortraitProcessed?.let { cb ->
                                    mainHandler.post {
                                        cb(
                                            com.example.model.PortraitDepthSource.HYBRID,
                                            1,
                                            com.example.model.PortraitCaptureTier.CAMERA_X_BOKEH
                                        )
                                    }
                                }
                            } else {
                                val waterfallResult = portraitPipelineManager?.executeCaptureWaterfall(
                                    uprightBitmap = cappedBmp,
                                    isOemExtensionCapture = false,
                                    focusX = portraitFocusX,
                                    focusY = portraitFocusY,
                                    hardwareDepthProvider = portraitDepthFrameProvider
                                )
                                val segmentation = waterfallResult?.segmentation
                                    ?: com.example.portrait.PortraitSegmentationEngine.segmentBitmap(
                                        uprightBitmap = cappedBmp,
                                        isPreviewStream = false,
                                        focusX = portraitFocusX,
                                        focusY = portraitFocusY,
                                        hardwareDepthFrame = try {
                                            portraitDepthFrameProvider?.invoke()
                                        } catch (_: Throwable) {
                                            null
                                        }
                                    )
                                val rawMeta = photoMetadata ?: com.example.util.CameraMetadataUtil.fromUri(
                                    context, savedUri, cappedBmp.width, cappedBmp.height
                                )
                                val metaWithLens = com.example.util.CameraMetadataUtil.withActiveFocalLengthIfMissing(
                                    rawMeta, activeFocalLengthMm
                                )
                                val wmCfgForSidecar = watermarkConfig ?: com.example.model.WatermarkConfig(isEnabled = false)
                                savedSidecarEntry = portraitSidecarStorage?.savePortraitSidecar(
                                    cleanUprightBitmap = cappedBmp,
                                    segmentation = segmentation,
                                    blurIntensity = portraitBlurIntensity,
                                    focusX = portraitFocusX ?: 0.5f,
                                    focusY = portraitFocusY ?: 0.45f,
                                    exifAttributes = exifAttributes,
                                    metadata = metaWithLens,
                                    watermarkConfig = wmCfgForSidecar,
                                    isSaveLocation = isSaveLocation
                                )

                                val bokehBmp = com.example.portrait.PortraitBokehRenderer.renderFullResolutionPortrait(
                                    originalUpright = cappedBmp,
                                    segmentation = segmentation,
                                    options = com.example.portrait.BokehRenderOptions(
                                        blurIntensity = portraitBlurIntensity,
                                        focusX = portraitFocusX,
                                        focusY = portraitFocusY
                                    )
                                )
                                if (bokehBmp !== cappedBmp && cappedBmp != original) {
                                    cappedBmp.recycle()
                                }
                                currentBitmap = bokehBmp
                                onPortraitProcessed?.let { cb ->
                                    mainHandler.post {
                                        cb(
                                            segmentation.source,
                                            segmentation.subjectCount,
                                            segmentation.captureTier
                                        )
                                    }
                                }
                            }
                        }

                        // Retain clean pre-watermark bitmap if crop/filter/portrait altered the raw image
                        if (shouldRetainCleanOriginal && retainedEntry == null) {
                            val active = currentBitmap!!
                            val rawMeta = photoMetadata ?: com.example.util.CameraMetadataUtil.fromUri(context, savedUri, active.width, active.height)
                            val metaWithLens = com.example.util.CameraMetadataUtil.withActiveFocalLengthIfMissing(rawMeta, activeFocalLengthMm)
                            retainedEntry = privateOriginalStorage?.saveOriginalFromBitmap(
                                bitmap = active,
                                exifAttributes = exifAttributes,
                                metadata = metaWithLens,
                                isSaveLocation = isSaveLocation,
                                protectUntilUnmarked = true
                            )
                        }

                        // Step 3: Apply watermark stamping with genuine photo metadata
                        if (hasWatermark && watermarkConfig != null) {
                            val active = currentBitmap!!
                            val rawMeta = photoMetadata ?: com.example.util.CameraMetadataUtil.fromUri(context, savedUri, active.width, active.height)
                            val meta = com.example.util.CameraMetadataUtil.withActiveFocalLengthIfMissing(rawMeta, activeFocalLengthMm)
                            val stamped = com.example.util.WatermarkPainter.applyWatermark(active, watermarkConfig, meta, context)
                            if (stamped != active && active != original) active.recycle()
                            currentBitmap = stamped
                        }

                        // Step 4: Compress to temp cache file first, then atomically truncate & copy to savedUri
                        val finalBmp = currentBitmap!!
                        tempOutFile = java.io.File.createTempFile("studo_proc_", ".jpg", context.cacheDir)
                        java.io.RandomAccessFile(tempOutFile, "rw").use { outRaf ->
                            java.io.BufferedOutputStream(java.io.FileOutputStream(outRaf.fd), 256 * 1024).use { out ->
                                finalBmp.compress(Bitmap.CompressFormat.JPEG, 100, out)
                                out.flush()
                            }
                            val outChannel = outRaf.channel
                            val outSize = outChannel.size()
                            if (outSize > 0) {
                                context.contentResolver.openFileDescriptor(savedUri, "rw")?.use { pfd ->
                                    java.io.FileOutputStream(pfd.fileDescriptor).channel.use { destChannel ->
                                        try {
                                            destChannel.truncate(0)
                                            destChannel.position(0)
                                        } catch (_: Throwable) {}
                                        outChannel.position(0)
                                        outChannel.transferTo(0, outSize, destChannel)
                                    }
                                }
                            }
                        }

                        // Step 5: Restore EXIF metadata (respecting location privacy)
                        try {
                            context.contentResolver.openFileDescriptor(savedUri, "rw")?.use { pfd ->
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
                                    for (t in gpsTags) {
                                        destExif.setAttribute(t, null)
                                    }
                                }
                                destExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                                destExif.saveAttributes()
                            }
                        } catch (t: Throwable) {
                            android.util.Log.w("MediaCaptureManager", "Could not restore all EXIF tags: ${t.message}")
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("MediaCaptureManager", "Non-fatal post-processing error, falling back to original capture: ${t.message}")
            } finally {
                try {
                    if (currentBitmap != null && currentBitmap != originalBitmap && !currentBitmap!!.isRecycled) {
                        currentBitmap!!.recycle()
                    }
                    if (originalBitmap != null && !originalBitmap!!.isRecycled) {
                        originalBitmap!!.recycle()
                    }
                } catch (_: Throwable) {}
                try { tempOutFile?.delete() } catch (_: Throwable) {}
            }
        }

        // Finalize MediaStore pending flag
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val updateValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            try {
                context.contentResolver.update(savedUri, updateValues, null, null)
            } catch (e: Exception) {
                android.util.Log.w("MediaCaptureManager", "Could not clear IS_PENDING on saved photo: ${e.message}")
            }
        }

        val (savedW, savedH, savedBytes) = getMediaDimensionsAndSize(savedUri)
        if (savedBytes <= 0) {
            retainedEntry?.let { entry ->
                privateOriginalStorage?.unmarkOriginalActive(entry.originalId)
            }
            // Clean up 0-byte orphan MediaStore entry
            try {
                context.contentResolver.delete(savedUri, null, null)
            } catch (delEx: Exception) {
                android.util.Log.w("MediaCaptureManager", "Failed to delete empty MediaStore record: ${delEx.message}")
            }
            onFailure(
                ImageCaptureException(
                    ImageCapture.ERROR_FILE_IO,
                    "Saved photo is empty (0 bytes). MediaStore entry cleaned up.",
                    null
                )
            )
            return
        }

        val retained = retainedEntry
        if (retained != null && privateOriginalStorage != null && watermarkConfig != null) {
            privateOriginalStorage.linkGalleryOutput(savedUri, retained.originalId, watermarkConfig)
            val eviction = privateOriginalStorage.enforceCacheLimitBytes(
                limitBytes = watermarkCacheLimitBytes,
                protectedOriginalId = retained.originalId
            )
            privateOriginalStorage.unmarkOriginalActive(retained.originalId)
            if (eviction.evictedCount > 0) {
                onEvictionNotice?.let { cb -> mainHandler.post { cb(eviction) } }
            }
        }

        if (savedSidecarEntry != null && portraitSidecarStorage != null) {
            portraitSidecarStorage.linkGalleryUri(savedUri, savedSidecarEntry!!.sidecarId)
        }

        onComplete(savedUri, savedW, savedH, savedBytes)
    }

    fun startVideoRecording(
        videoCapture: VideoCapture<Recorder>,
        isAudioEnabled: Boolean = true,
        onEvent: (VideoRecordEvent) -> Unit
    ): Recording {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val fileName = "StuDO_VID_$timeStamp.mp4"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/StuDO")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        val pendingRecording = videoCapture.output
            .prepareRecording(context, mediaStoreOutputOptions)

        val hasAudioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val recording = if (hasAudioPermission && isAudioEnabled) {
            try {
                pendingRecording.withAudioEnabled()
            } catch (_: SecurityException) {
                pendingRecording
            }
        } else {
            pendingRecording
        }

        return recording.start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                val outUri = event.outputResults.outputUri
                val fileSize = try {
                    context.contentResolver.openFileDescriptor(outUri, "r")?.use { it.statSize } ?: 0L
                } catch (_: Exception) {
                    0L
                }
                val isEmptyOrCorrupt = fileSize <= 0L ||
                    (event.hasError() && (
                        event.error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA ||
                            event.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE
                        ))
                if (isEmptyOrCorrupt && outUri != Uri.EMPTY) {
                    deleteMedia(outUri)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outUri != Uri.EMPTY) {
                    val updateValues = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    try {
                        context.contentResolver.update(outUri, updateValues, null, null)
                    } catch (_: Exception) {}
                }
            }
            onEvent(event)
        }
    }

    fun deleteMedia(uri: Uri): Boolean {
        return try {
            val rows = context.contentResolver.delete(uri, null, null)
            rows > 0
        } catch (_: Exception) {
            false
        }
    }

    fun getAllMedia(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()

        // 1. Query Images
        try {
            val imgProjection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
            )
            val imgUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            context.contentResolver.query(imgUri, imgProjection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val mimeCol = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                val widthCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(imgUri, id)
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "Photo" else "Photo"
                    val date = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "image/jpeg" else "image/jpeg"
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val width = if (widthCol >= 0) cursor.getInt(widthCol) else 0
                    val height = if (heightCol >= 0) cursor.getInt(heightCol) else 0

                    items.add(
                        MediaItem(
                            id = id,
                            uri = uri,
                            isVideo = false,
                            displayName = name,
                            dateAdded = date,
                            mimeType = mime,
                            sizeBytes = size,
                            width = width,
                            height = height
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        // 2. Query Videos
        try {
            val vidProjection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.DURATION
            )
            val vidUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

            context.contentResolver.query(vidUri, vidProjection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                val mimeCol = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(vidUri, id)
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "Video" else "Video"
                    val date = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "video/mp4" else "video/mp4"
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val width = if (widthCol >= 0) cursor.getInt(widthCol) else 0
                    val height = if (heightCol >= 0) cursor.getInt(heightCol) else 0
                    val dur = if (durCol >= 0) cursor.getLong(durCol) else 0L

                    items.add(
                        MediaItem(
                            id = id,
                            uri = uri,
                            isVideo = true,
                            displayName = name,
                            dateAdded = date,
                            mimeType = mime,
                            sizeBytes = size,
                            width = width,
                            height = height,
                            durationMs = dur
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        // Sort both photos and videos newest first
        return items.sortedByDescending { it.dateAdded }
    }

    fun getMediaDetails(item: MediaItem): PhotoDetails {
        val formattedDate = if (item.dateAdded > 0L) {
            SimpleDateFormat("MMM d, yyyy · HH:mm:ss", Locale.getDefault()).format(Date(item.dateAdded * 1000))
        } else "Recent"

        val sizeBytes = item.sizeBytes
        val formattedSize = when {
            sizeBytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", sizeBytes / (1024.0 * 1024.0))
            sizeBytes >= 1024 -> String.format(Locale.US, "%.1f KB", sizeBytes / 1024.0)
            sizeBytes > 0 -> "$sizeBytes B"
            else -> "Standard"
        }

        val dimensions = if (item.width > 0 && item.height > 0) {
            "${item.width} × ${item.height}"
        } else "Standard resolution"

        val durStr = if (item.isVideo && item.durationMs > 0) {
            val sec = item.durationMs / 1000
            val min = sec / 60
            val s = sec % 60
            String.format(Locale.US, "%02d:%02d", min, s)
        } else null

        return PhotoDetails(
            uri = item.uri,
            title = item.displayName,
            dateTaken = formattedDate,
            dimensions = dimensions,
            fileSize = formattedSize,
            path = if (item.isVideo) "Movies/StuDO" else "Pictures/StuDO",
            mimeType = item.mimeType,
            isVideo = item.isVideo,
            duration = durStr
        )
    }

    fun getLatestPhotoUri(): Uri? {
        val all = getAllMedia()
        return all.firstOrNull()?.uri
    }

    fun getMediaDimensionsAndSize(uri: Uri): Triple<Int, Int, Long> {
        var width = 0
        var height = 0
        var size = 0L
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                size = pfd.statSize
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
                width = options.outWidth
                height = options.outHeight
            }
        } catch (_: Exception) {}
        return Triple(width, height, size)
    }
}
