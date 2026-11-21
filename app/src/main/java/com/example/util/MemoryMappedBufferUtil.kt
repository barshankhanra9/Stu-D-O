package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.media.ExifInterface
import android.media.Image
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.model.CameraFilter
import com.example.model.CaptureAspectRatio
import com.example.model.WatermarkConfig
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * High-performance Memory-Mapped Buffer and Direct Channel Serializer for JPEG and DNG captures.
 *
 * Minimizes memory pressure by avoiding large heap ByteArray allocations (e.g., 15-50MB byte arrays)
 * and leverages Linux kernel page caching and zero-copy FileChannel.transferTo (sendfile syscall)
 * to serialize high-resolution images to MediaStore with minimal latency.
 */
object MemoryMappedBufferUtil {

    private const val TAG = "MemoryMappedBufferUtil"
    private const val BUFFER_SIZE_BYTES = 256 * 1024 // 256KB buffer for streaming writes

    /**
     * Serializes a native direct ByteBuffer (e.g., from ImageReader JPEG plane) directly
     * into a MediaStore destination Uri using a memory-mapped backing file and zero-copy transfer.
     */
    fun serializeDirectBufferToUri(
        context: Context,
        targetUri: Uri,
        directBuffer: ByteBuffer
    ): Long {
        val size = directBuffer.remaining().toLong()
        if (size <= 0) return 0L

        val tempFile = File.createTempFile("mmap_jpeg_", ".tmp", context.cacheDir)
        try {
            RandomAccessFile(tempFile, "rw").use { raf ->
                val channel = raf.channel
                channel.truncate(size)

                // Memory-map the temporary file in the Linux page cache
                val mappedBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, size)
                val dup = directBuffer.duplicate()
                mappedBuffer.put(dup)
                mappedBuffer.force()

                // Kernel-level zero-copy transfer (sendfile) directly to MediaStore
                context.contentResolver.openFileDescriptor(targetUri, "rw")?.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).channel.use { destChannel ->
                        try {
                            destChannel.truncate(0)
                            destChannel.position(0)
                        } catch (_: Throwable) {}
                        channel.position(0)
                        channel.transferTo(0, size, destChannel)
                    }
                }
            }
            return size
        } catch (t: Throwable) {
            Log.e(TAG, "Error in serializeDirectBufferToUri via memory-mapped buffer", t)
            // Resilient fallback to direct channel write
            return fallbackWriteBuffer(context, targetUri, directBuffer)
        } finally {
            try { tempFile.delete() } catch (_: Throwable) {}
        }
    }

    /**
     * Serializes an uncompressed RAW Sensor image using DngCreator directly through a high-bandwidth
     * buffered channel to a local cache file, then transfers to MediaStore via zero-copy sendfile.
     * This avoids hundreds of thousands of slow Binder IPC roundtrips during DNG writing.
     */
    fun serializeDngToUri(
        context: Context,
        targetUri: Uri,
        characteristics: CameraCharacteristics,
        dngCreator: DngCreator,
        rawImage: Image
    ): Long {
        val tempDng = File.createTempFile("mmap_raw_", ".dng", context.cacheDir)
        try {
            // Write DNG to local high-speed cache with 256KB buffer (separate stream lifecycle from read channel)
            BufferedOutputStream(FileOutputStream(tempDng), BUFFER_SIZE_BYTES).use { outStream ->
                dngCreator.writeImage(outStream, rawImage)
                outStream.flush()
            }

            val dngSize = tempDng.length()
            if (dngSize <= 0L) {
                Log.e(TAG, "DngCreator produced 0-byte DNG file")
                return 0L
            }

            var copiedSuccessfully = false
            val pfd = try {
                context.contentResolver.openFileDescriptor(targetUri, "rw")
            } catch (_: Throwable) {
                null
            }
            if (pfd != null) {
                try {
                    RandomAccessFile(tempDng, "r").use { raf ->
                        val channel = raf.channel
                        pfd.use { fd ->
                            FileOutputStream(fd.fileDescriptor).channel.use { destChannel ->
                                try {
                                    destChannel.truncate(0)
                                    destChannel.position(0)
                                } catch (_: Throwable) {}
                                var transferred = 0L
                                while (transferred < dngSize) {
                                    val step = channel.transferTo(transferred, dngSize - transferred, destChannel)
                                    if (step <= 0L) break
                                    transferred += step
                                }
                                copiedSuccessfully = (transferred == dngSize)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "FileChannel transferTo failed for DNG, trying stream fallback: ${e.message}")
                    copiedSuccessfully = false
                }
            }

            if (!copiedSuccessfully) {
                val outStream = try {
                    context.contentResolver.openOutputStream(targetUri, "w")
                } catch (_: Throwable) {
                    null
                } ?: context.contentResolver.openOutputStream(targetUri)

                if (outStream == null) {
                    Log.e(TAG, "Unable to open output stream for DNG Uri: $targetUri")
                    return 0L
                }
                outStream.use { out ->
                    tempDng.inputStream().use { input ->
                        val copied = input.copyTo(out, BUFFER_SIZE_BYTES)
                        out.flush()
                        if (copied <= 0L) return 0L
                    }
                }
            }

            return dngSize
        } catch (t: Throwable) {
            Log.e(TAG, "Error in serializeDngToUri", t)
            return 0L
        } finally {
            try { dngCreator.close() } catch (_: Throwable) {}
            try { tempDng.delete() } catch (_: Throwable) {}
        }
    }

    /**
     * Processes (orientation, mirror, filter, watermark) and serializes a high-resolution JPEG directly
     * using memory-mapped buffers and buffered streams to avoid heap spikes (such as ByteArrayOutputStream doubling).
     */
    fun processAndSerializeJpeg(
        context: Context,
        targetUri: Uri,
        sourceBuffer: ByteBuffer,
        filter: CameraFilter,
        mirrorSelfie: Boolean,
        watermarkConfig: WatermarkConfig?,
        isSaveLocation: Boolean = false,
        activeFocalLengthMm: Float? = null,
        privateOriginalStorage: com.example.data.PrivateOriginalStorageManager? = null,
        retainCleanOriginal: Boolean = false,
        onOriginalRetained: ((com.example.data.PrivateOriginalEntry) -> Unit)? = null,
        aspectRatio: CaptureAspectRatio = CaptureAspectRatio.RATIO_4_3,
        fullScreenAspectRatio: Float = 9f / 20f
    ): Long {
        val hasFilter = (filter != CameraFilter.NATURAL)
        val hasWatermark = (watermarkConfig?.isEnabled == true)
        val mayNeedCrop = aspectRatio != CaptureAspectRatio.RATIO_4_3
        val needsProcessing = hasWatermark || hasFilter || mirrorSelfie || mayNeedCrop

        if (!needsProcessing) {
            return serializeDirectBufferToUri(context, targetUri, sourceBuffer)
        }

        // Write source buffer to temp file to decode safely via FileDescriptor (native memory allocator)
        val tempIn = File.createTempFile("mmap_in_", ".jpg", context.cacheDir)
        val tempOut = File.createTempFile("mmap_out_", ".jpg", context.cacheDir)
        var originalBitmap: Bitmap? = null
        var currentBitmap: Bitmap? = null
        try {
            val size = sourceBuffer.remaining().toLong()
            RandomAccessFile(tempIn, "rw").use { inRaf ->
                val inChannel = inRaf.channel
                inChannel.truncate(size)
                val mappedIn = inChannel.map(FileChannel.MapMode.READ_WRITE, 0, size)
                val dup = sourceBuffer.duplicate()
                mappedIn.put(dup)
                mappedIn.force()
            }

            // Read EXIF orientation & metadata from tempIn before transforming pixels
            val srcExif = try { ExifInterface(tempIn.absolutePath) } catch (_: Throwable) { null }
            val exifOrientation = srcExif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            ) ?: ExifInterface.ORIENTATION_NORMAL

            // Check encoded bounds to determine whether cropping is genuinely needed
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            RandomAccessFile(tempIn, "r").use { inRaf ->
                BitmapFactory.decodeFileDescriptor(inRaf.fd, null, boundsOpts)
            }
            val (orientedW, orientedH) = AspectRatioCropUtil.orientedDimensions(
                rawWidth = boundsOpts.outWidth,
                rawHeight = boundsOpts.outHeight,
                exifOrientation = exifOrientation
            )
            val needsCrop = AspectRatioCropUtil.needsCrop(
                width = orientedW,
                height = orientedH,
                aspectRatio = aspectRatio,
                fullScreenAspectRatio = fullScreenAspectRatio
            )

            if (!hasWatermark && !hasFilter && !mirrorSelfie && !needsCrop) {
                return serializeDirectBufferToUri(context, targetUri, sourceBuffer)
            }

            // Decode full-resolution unscaled ARGB_8888 bitmap from FileDescriptor
            val decodeOpts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
                inMutable = true
            }
            originalBitmap = RandomAccessFile(tempIn, "r").use { inRaf ->
                BitmapFactory.decodeFileDescriptor(inRaf.fd, null, decodeOpts)
            }

            if (originalBitmap == null) {
                // If decoding fails, write raw buffer directly
                return serializeDirectBufferToUri(context, targetUri, sourceBuffer)
            }

            currentBitmap = originalBitmap

            // 0. Normalize EXIF orientation into upright display pixels before cropping, selfie mirroring & watermarking
            val orientationMatrix = Matrix()
            when (exifOrientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> orientationMatrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> orientationMatrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> orientationMatrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> orientationMatrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> orientationMatrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    orientationMatrix.postRotate(90f)
                    orientationMatrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    orientationMatrix.postRotate(270f)
                    orientationMatrix.postScale(-1f, 1f)
                }
            }
            if (!orientationMatrix.isIdentity) {
                val oriented = Bitmap.createBitmap(
                    currentBitmap, 0, 0, currentBitmap.width, currentBitmap.height, orientationMatrix, true
                )
                if (oriented != currentBitmap && currentBitmap != originalBitmap) currentBitmap.recycle()
                currentBitmap = oriented
            }

            // 0.5 Apply target aspect ratio crop if needed (e.g. FULL, 1:1, 16:9)
            if (needsCrop) {
                val cropped = AspectRatioCropUtil.cropBitmapIfNeeded(
                    bitmap = currentBitmap,
                    aspectRatio = aspectRatio,
                    fullScreenAspectRatio = fullScreenAspectRatio
                )
                if (cropped != currentBitmap && currentBitmap != originalBitmap) currentBitmap.recycle()
                currentBitmap = cropped
            }

            // 1. Mirror upright selfie if requested
            if (mirrorSelfie) {
                val mirrorMatrix = Matrix().apply {
                    postScale(-1f, 1f, currentBitmap.width / 2f, currentBitmap.height / 2f)
                }
                val mirrored = Bitmap.createBitmap(
                    currentBitmap, 0, 0, currentBitmap.width, currentBitmap.height, mirrorMatrix, true
                )
                if (mirrored != currentBitmap && currentBitmap != originalBitmap) currentBitmap.recycle()
                currentBitmap = mirrored
            }

            // 2. Apply professional filmic color filter at full native resolution
            if (hasFilter) {
                currentBitmap = CameraLiveFilterEngine.applyFilterToBitmap(
                    source = currentBitmap,
                    filter = filter,
                    recycleSource = (currentBitmap != originalBitmap)
                )
            }

            // 3. Watermark stamping (retaining clean original privately first if enabled)
            if (hasWatermark) {
                val extractedMeta = if (srcExif != null) {
                    CameraMetadataUtil.extract(srcExif, currentBitmap.width, currentBitmap.height)
                } else {
                    CameraMetadataUtil.fromFile(tempIn, currentBitmap.width, currentBitmap.height)
                }
                val meta = CameraMetadataUtil.withActiveFocalLengthIfMissing(extractedMeta, activeFocalLengthMm)

                if (retainCleanOriginal && privateOriginalStorage != null) {
                    val retained = if (!mirrorSelfie && !hasFilter && !needsCrop) {
                        privateOriginalStorage.saveOriginalFromFile(
                            sourceFile = tempIn,
                            metadata = meta,
                            isSaveLocation = isSaveLocation,
                            protectUntilUnmarked = true
                        )
                    } else {
                        val exifMap = mutableMapOf<String, String>()
                        if (srcExif != null) {
                            val copyTags = mutableListOf(
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
                            if (isSaveLocation) {
                                copyTags.addAll(
                                    listOf(
                                        ExifInterface.TAG_GPS_LATITUDE,
                                        ExifInterface.TAG_GPS_LATITUDE_REF,
                                        ExifInterface.TAG_GPS_LONGITUDE,
                                        ExifInterface.TAG_GPS_LONGITUDE_REF,
                                        ExifInterface.TAG_GPS_ALTITUDE,
                                        ExifInterface.TAG_GPS_ALTITUDE_REF,
                                        ExifInterface.TAG_GPS_TIMESTAMP,
                                        ExifInterface.TAG_GPS_DATESTAMP
                                    )
                                )
                            }
                            for (t in copyTags) {
                                val v = srcExif.getAttribute(t)
                                if (v != null) exifMap[t] = v
                            }
                        }
                        privateOriginalStorage.saveOriginalFromBitmap(
                            bitmap = currentBitmap,
                            exifAttributes = exifMap,
                            metadata = meta,
                            isSaveLocation = isSaveLocation,
                            protectUntilUnmarked = true
                        )
                    }
                    if (retained != null) {
                        onOriginalRetained?.invoke(retained)
                    }
                }

                val stamped = WatermarkPainter.applyWatermark(
                    currentBitmap, watermarkConfig!!, meta, context = context
                )
                if (stamped != currentBitmap && currentBitmap != originalBitmap) currentBitmap.recycle()
                currentBitmap = stamped
            }

            // 4. Compress directly to buffered file output stream (separate lifecycle from read channel)
            BufferedOutputStream(FileOutputStream(tempOut), BUFFER_SIZE_BYTES).use { outStream ->
                currentBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outStream)
                outStream.flush()
            }

            RandomAccessFile(tempOut, "r").use { outRaf ->
                val outChannel = outRaf.channel
                val finalSize = outChannel.size()

                // 5. Zero-copy transfer to destination MediaStore Uri (truncated first)
                val pfd = try {
                    context.contentResolver.openFileDescriptor(targetUri, "rw")
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
                            while (transferred < finalSize) {
                                val step = outChannel.transferTo(transferred, finalSize - transferred, destChannel)
                                if (step <= 0L) break
                                transferred += step
                            }
                        }
                    }
                } else {
                    context.contentResolver.openOutputStream(targetUri)?.use { out ->
                        tempOut.inputStream().use { it.copyTo(out, BUFFER_SIZE_BYTES) }
                        out.flush()
                    } ?: return 0L
                }

                // 6. Restore original EXIF attributes from tempIn into destination
                try {
                    if (srcExif != null) {
                        context.contentResolver.openFileDescriptor(targetUri, "rw")?.use { pfd ->
                            val destExif = ExifInterface(pfd.fileDescriptor)
                            val tags = mutableListOf(
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
                            if (isSaveLocation) {
                                tags.addAll(
                                    listOf(
                                        ExifInterface.TAG_GPS_LATITUDE,
                                        ExifInterface.TAG_GPS_LATITUDE_REF,
                                        ExifInterface.TAG_GPS_LONGITUDE,
                                        ExifInterface.TAG_GPS_LONGITUDE_REF,
                                        ExifInterface.TAG_GPS_ALTITUDE,
                                        ExifInterface.TAG_GPS_ALTITUDE_REF,
                                        ExifInterface.TAG_GPS_TIMESTAMP,
                                        ExifInterface.TAG_GPS_DATESTAMP
                                    )
                                )
                            }
                            for (t in tags) {
                                val v = srcExif.getAttribute(t)
                                if (v != null) destExif.setAttribute(t, v)
                            }
                            destExif.setAttribute(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL.toString()
                            )
                            destExif.saveAttributes()
                        }
                    }
                } catch (_: Throwable) {}

                return finalSize
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in processAndSerializeJpeg", t)
            return fallbackWriteBuffer(context, targetUri, sourceBuffer)
        } finally {
            try {
                if (currentBitmap != null && currentBitmap != originalBitmap && !currentBitmap.isRecycled) {
                    currentBitmap.recycle()
                }
                if (originalBitmap != null && !originalBitmap.isRecycled) {
                    originalBitmap.recycle()
                }
            } catch (_: Throwable) {}
            try { tempIn.delete() } catch (_: Throwable) {}
            try { tempOut.delete() } catch (_: Throwable) {}
        }
    }

    private fun fallbackWriteBuffer(context: Context, targetUri: Uri, buffer: ByteBuffer): Long {
        return try {
            val dup = buffer.duplicate()
            val size = dup.remaining().toLong()
            context.contentResolver.openFileDescriptor(targetUri, "rw")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).channel.use { channel ->
                    try {
                        channel.truncate(0)
                        channel.position(0)
                    } catch (_: Throwable) {}
                    while (dup.hasRemaining()) {
                        channel.write(dup)
                    }
                }
            }
            size
        } catch (e: Exception) {
            Log.e(TAG, "Fallback direct channel write failed", e)
            0L
        }
    }
}
