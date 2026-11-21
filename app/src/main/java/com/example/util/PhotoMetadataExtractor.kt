package com.example.util

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.example.model.MediaItem
import com.example.model.PhotoDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

data class MetadataField(
    val label: String,
    val value: String
)

data class GpsCoordinates(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val gpsDateStamp: String? = null
)

data class RichPhotoMetadata(
    val title: String?,
    val formatBadge: String?,
    val isVideo: Boolean = false,
    val heroHighlights: List<String> = emptyList(),
    val imageFields: List<MetadataField> = emptyList(),
    val cameraFields: List<MetadataField> = emptyList(),
    val captureFields: List<MetadataField> = emptyList(),
    val gps: GpsCoordinates? = null,
    val locationFields: List<MetadataField> = emptyList(),
    val otherFields: List<MetadataField> = emptyList()
) {
    val isEmpty: Boolean
        get() = imageFields.isEmpty() &&
            cameraFields.isEmpty() &&
            captureFields.isEmpty() &&
            gps == null &&
            locationFields.isEmpty() &&
            otherFields.isEmpty()
}

object PhotoMetadataExtractor {

    private val placeholderFallbackValues = setOf(
        "Recent",
        "Standard",
        "Standard resolution",
        "Unknown"
    )

    private val videoExtensions = setOf("mp4", "mov", "mkv", "3gp", "webm", "m4v", "avi")

    fun isVideoMedia(item: MediaItem, fallbackDetails: PhotoDetails? = null): Boolean {
        if (item.isVideo || fallbackDetails?.isVideo == true) return true
        val mime = (item.mimeType.takeIf { it.isNotBlank() } ?: fallbackDetails?.mimeType).orEmpty()
            .lowercase(Locale.US)
        if (mime.startsWith("video/")) return true
        if (mime.startsWith("image/")) return false
        val name = (item.displayName.takeIf { it.isNotBlank() } ?: item.uri.lastPathSegment).orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return ext in videoExtensions
    }

    suspend fun extract(
        context: Context,
        item: MediaItem,
        fallbackDetails: PhotoDetails? = null
    ): RichPhotoMetadata = withContext(Dispatchers.IO) {
        if (isVideoMedia(item, fallbackDetails)) {
            extractVideoMetadata(context, item, fallbackDetails)
        } else {
            extractPhotoMetadata(context, item, fallbackDetails)
        }
    }

    private fun extractPhotoMetadata(
        context: Context,
        item: MediaItem,
        fallbackDetails: PhotoDetails?
    ): RichPhotoMetadata {
        val imageFields = mutableListOf<MetadataField>()
        val cameraFields = mutableListOf<MetadataField>()
        val captureFields = mutableListOf<MetadataField>()
        val locationFields = mutableListOf<MetadataField>()
        val otherFields = mutableListOf<MetadataField>()
        val heroHighlights = mutableListOf<String>()

        val mediaStoreMeta = queryMediaStoreInfo(context, item.uri)
        val exif = readExif(context, item.uri)
        val bounds = readImageBounds(context, item.uri)

        val resolvedName = mediaStoreMeta.displayName?.takeIf { it.isNotBlank() }
            ?: item.displayName.takeIf { it.isNotBlank() }
            ?: fallbackDetails?.title?.takeIf { it.isNotBlank() }

        val resolvedMime = mediaStoreMeta.mimeType?.takeIf { it.isNotBlank() }
            ?: bounds.outMimeType?.takeIf { it.isNotBlank() }
            ?: item.mimeType.takeIf { it.isNotBlank() }
            ?: fallbackDetails?.mimeType?.takeIf { it.isNotBlank() }

        val orientation = exif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED
        ) ?: ExifInterface.ORIENTATION_UNDEFINED
        val isSwapped = HighResZoomMath.isOrientationSwapped(orientation)

        val exifW = exif?.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0) ?: 0
        val exifH = exif?.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0) ?: 0

        val displayW = when {
            bounds.outWidth > 0 && bounds.outHeight > 0 -> if (isSwapped) bounds.outHeight else bounds.outWidth
            mediaStoreMeta.width > 0 -> mediaStoreMeta.width
            exifW > 0 && exifH > 0 -> if (isSwapped) exifH else exifW
            else -> item.width
        }
        val displayH = when {
            bounds.outWidth > 0 && bounds.outHeight > 0 -> if (isSwapped) bounds.outWidth else bounds.outHeight
            mediaStoreMeta.height > 0 -> mediaStoreMeta.height
            exifW > 0 && exifH > 0 -> if (isSwapped) exifW else exifH
            else -> item.height
        }

        val sizeBytes = when {
            mediaStoreMeta.sizeBytes > 0L -> mediaStoreMeta.sizeBytes
            item.sizeBytes > 0L -> item.sizeBytes
            else -> queryFileDescriptorSize(context, item.uri)
        }

        // --- Image Section ---
        resolvedName?.let { imageFields.add(MetadataField("File Name", it)) }
        formatMimeLabel(resolvedMime)?.let { imageFields.add(MetadataField("Format", it)) }

        if (displayW > 0 && displayH > 0) {
            val mp = (displayW.toDouble() * displayH.toDouble()) / 1_000_000.0
            val mpStr = if (mp >= 0.1) String.format(Locale.US, " (%.1f MP)", mp) else ""
            imageFields.add(MetadataField("Resolution", "$displayW × $displayH$mpStr"))
        } else {
            fallbackDetails?.dimensions
                ?.takeIf { it.isNotBlank() && it !in placeholderFallbackValues }
                ?.let { imageFields.add(MetadataField("Resolution", it)) }
        }

        val formattedSize = formatBytes(sizeBytes)
            ?: fallbackDetails?.fileSize?.takeIf { it.isNotBlank() && it !in placeholderFallbackValues }
        formattedSize?.let { imageFields.add(MetadataField("File Size", it)) }

        val capturedDate = extractExifCaptureTimestamp(exif)
            ?: formatEpochMillis(mediaStoreMeta.dateTakenMs)
            ?: formatEpochSeconds(item.dateAdded)
            ?: fallbackDetails?.dateTaken?.takeIf { it.isNotBlank() && it !in placeholderFallbackValues }
        capturedDate?.let { imageFields.add(MetadataField("Captured", it)) }

        val resolvedPath = mediaStoreMeta.relativePath?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: item.uri.path?.takeIf { item.uri.scheme == "file" && it.isNotBlank() }
            ?: fallbackDetails?.path?.takeIf { it.isNotBlank() && it !in placeholderFallbackValues }
        resolvedPath?.let { imageFields.add(MetadataField("Location Path", it)) }

        formatColorSpace(exif?.getAttributeInt(ExifInterface.TAG_COLOR_SPACE, -1))?.let {
            imageFields.add(MetadataField("Color Space", it))
        }
        formatOrientationLabel(orientation)?.let {
            imageFields.add(MetadataField("Orientation", it))
        }

        // --- Camera Section ---
        val make = cleanTag(exif?.getAttribute(ExifInterface.TAG_MAKE))
        val model = cleanTag(exif?.getAttribute(ExifInterface.TAG_MODEL))
        val lensMake = cleanTag(exif?.getAttribute(ExifInterface.TAG_LENS_MAKE))
        val lensModel = cleanTag(exif?.getAttribute(ExifInterface.TAG_LENS_MODEL))
        val lensSpec = cleanTag(exif?.getAttribute(ExifInterface.TAG_LENS_SPECIFICATION))
        val software = cleanTag(exif?.getAttribute(ExifInterface.TAG_SOFTWARE))

        make?.let { cameraFields.add(MetadataField("Manufacturer", it)) }
        model?.let { cameraFields.add(MetadataField("Model", it)) }

        val combinedLens = when {
            lensMake != null && lensModel != null -> {
                if (lensModel.contains(lensMake, ignoreCase = true)) lensModel else "$lensMake $lensModel"
            }
            lensModel != null -> lensModel
            lensMake != null -> lensMake
            else -> formatLensSpecification(lensSpec)
        }
        combinedLens?.let { cameraFields.add(MetadataField("Lens", it)) }
        software?.let { cameraFields.add(MetadataField("Software", it)) }

        // --- Capture Settings Section ---
        val aperture = formatAperture(
            fNumber = exif?.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0),
            apertureValue = exif?.getAttributeDouble(ExifInterface.TAG_APERTURE_VALUE, -1.0)
        )
        aperture?.let {
            captureFields.add(MetadataField("Aperture", it))
            heroHighlights.add(it)
        }

        val shutterSpeed = formatExposureTime(
            exposureTimeSec = exif?.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0),
            shutterApex = exif?.getAttributeDouble(ExifInterface.TAG_SHUTTER_SPEED_VALUE, Double.NaN)
        )
        shutterSpeed?.let {
            captureFields.add(MetadataField("Shutter Speed", it))
            heroHighlights.add(it)
        }

        val iso = parseIsoValue(exif)
        iso?.let {
            val isoStr = "ISO $it"
            captureFields.add(MetadataField("ISO", isoStr))
            heroHighlights.add(isoStr)
        }

        val focalLength = formatFocalLength(
            focalMm = exif?.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1.0),
            focal35Mm = exif?.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, -1)
        )
        focalLength?.let {
            captureFields.add(MetadataField("Focal Length", it.first))
            heroHighlights.add(it.second)
        }

        val exposureBias = formatExposureBias(exif?.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, Double.NaN))
        exposureBias?.let { captureFields.add(MetadataField("Exposure Bias", it)) }

        val flashStr = formatFlashMode(exif?.getAttributeInt(ExifInterface.TAG_FLASH, -1))
        flashStr?.let { captureFields.add(MetadataField("Flash", it)) }

        val wbStr = formatWhiteBalance(exif?.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, -1))
        wbStr?.let { captureFields.add(MetadataField("White Balance", it)) }

        val meteringStr = formatMeteringMode(exif?.getAttributeInt(ExifInterface.TAG_METERING_MODE, -1))
        meteringStr?.let { captureFields.add(MetadataField("Metering Mode", it)) }

        val programStr = formatExposureProgram(exif?.getAttributeInt(ExifInterface.TAG_EXPOSURE_PROGRAM, -1))
        programStr?.let { captureFields.add(MetadataField("Exposure Program", it)) }

        // --- Location (GPS) Section ---
        val gps = extractGpsCoordinates(exif)
        if (gps != null) {
            locationFields.add(MetadataField("Coordinates", formatCoordinatesDecimal(gps.latitude, gps.longitude)))
            locationFields.add(MetadataField("DMS", formatCoordinatesDms(gps.latitude, gps.longitude)))
            gps.altitudeMeters?.let { alt ->
                locationFields.add(MetadataField("Altitude", String.format(Locale.US, "%.1f m", alt)))
            }
            gps.gpsDateStamp?.let { stamp ->
                locationFields.add(MetadataField("GPS Timestamp", stamp))
            }
        }

        // --- Additional EXIF Metadata Section ---
        formatSceneCaptureType(exif?.getAttributeInt(ExifInterface.TAG_SCENE_CAPTURE_TYPE, -1))?.let {
            otherFields.add(MetadataField("Scene Type", it))
        }
        val digitalZoom = exif?.getAttributeDouble(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, Double.NaN)
        if (digitalZoom != null && !digitalZoom.isNaN() && digitalZoom > 1.001) {
            otherFields.add(MetadataField("Digital Zoom", String.format(Locale.US, "%.2fx", digitalZoom)))
        }
        formatProcessingLevel(exif?.getAttributeInt(ExifInterface.TAG_CONTRAST, -1))?.let {
            otherFields.add(MetadataField("Contrast", it))
        }
        formatProcessingLevel(exif?.getAttributeInt(ExifInterface.TAG_SATURATION, -1))?.let {
            otherFields.add(MetadataField("Saturation", it))
        }
        formatProcessingLevel(exif?.getAttributeInt(ExifInterface.TAG_SHARPNESS, -1))?.let {
            otherFields.add(MetadataField("Sharpness", it))
        }
        formatExifVersion(cleanTag(exif?.getAttribute(ExifInterface.TAG_EXIF_VERSION)))?.let {
            otherFields.add(MetadataField("EXIF Version", it))
        }
        cleanTag(exif?.getAttribute(ExifInterface.TAG_ARTIST))?.let {
            otherFields.add(MetadataField("Artist", it))
        }
        cleanTag(exif?.getAttribute(ExifInterface.TAG_COPYRIGHT))?.let {
            otherFields.add(MetadataField("Copyright", it))
        }
        cleanTag(exif?.getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID))?.let {
            otherFields.add(MetadataField("Unique ID", it))
        }

        return RichPhotoMetadata(
            title = resolvedName,
            formatBadge = shortFormatBadge(resolvedMime, false),
            isVideo = false,
            heroHighlights = heroHighlights.take(4),
            imageFields = imageFields,
            cameraFields = cameraFields,
            captureFields = captureFields,
            gps = gps,
            locationFields = locationFields,
            otherFields = otherFields
        )
    }

    private fun extractVideoMetadata(
        context: Context,
        item: MediaItem,
        fallbackDetails: PhotoDetails?
    ): RichPhotoMetadata {
        val imageFields = mutableListOf<MetadataField>()
        val cameraFields = mutableListOf<MetadataField>()
        val captureFields = mutableListOf<MetadataField>()
        val locationFields = mutableListOf<MetadataField>()
        val heroHighlights = mutableListOf<String>()

        val mediaStoreMeta = queryMediaStoreInfo(context, item.uri)
        val retriever = MediaMetadataRetriever()
        var videoW = 0
        var videoH = 0
        var durationMs = item.durationMs
        var bitrate = 0L
        var fps: Double? = null
        var gps: GpsCoordinates? = null
        var captureDate: String? = null

        try {
            if (item.uri.scheme == "file" && !item.uri.path.isNullOrBlank()) {
                retriever.setDataSource(item.uri.path)
            } else {
                retriever.setDataSource(context, item.uri)
            }
            videoW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            videoH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) {
                val tmp = videoW
                videoW = videoH
                videoH = tmp
            }
            if (durationMs <= 0L) {
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
            bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
            val locStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            gps = parseIso6709Location(locStr)
            captureDate = cleanTag(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
        } catch (_: Throwable) {
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }

        val resolvedName = mediaStoreMeta.displayName?.takeIf { it.isNotBlank() }
            ?: item.displayName.takeIf { it.isNotBlank() }
            ?: fallbackDetails?.title?.takeIf { it.isNotBlank() }
        val resolvedMime = mediaStoreMeta.mimeType?.takeIf { it.isNotBlank() }
            ?: item.mimeType.takeIf { it.isNotBlank() }
            ?: fallbackDetails?.mimeType?.takeIf { it.isNotBlank() }

        val w = if (videoW > 0) videoW else if (mediaStoreMeta.width > 0) mediaStoreMeta.width else item.width
        val h = if (videoH > 0) videoH else if (mediaStoreMeta.height > 0) mediaStoreMeta.height else item.height
        val sizeBytes = when {
            mediaStoreMeta.sizeBytes > 0L -> mediaStoreMeta.sizeBytes
            item.sizeBytes > 0L -> item.sizeBytes
            else -> queryFileDescriptorSize(context, item.uri)
        }

        resolvedName?.let { imageFields.add(MetadataField("File Name", it)) }
        formatMimeLabel(resolvedMime)?.let { imageFields.add(MetadataField("Format", it)) }
        if (w > 0 && h > 0) {
            val resStr = "$w × $h"
            imageFields.add(MetadataField("Resolution", resStr))
            heroHighlights.add(resStr)
        }
        if (durationMs > 0L) {
            val totalSec = durationMs / 1000L
            val durStr = String.format(Locale.US, "%02d:%02d", totalSec / 60L, totalSec % 60L)
            imageFields.add(MetadataField("Duration", durStr))
            heroHighlights.add(durStr)
        } else {
            fallbackDetails?.duration?.takeIf { it.isNotBlank() }?.let {
                imageFields.add(MetadataField("Duration", it))
                heroHighlights.add(it)
            }
        }
        formatBytes(sizeBytes)?.let { imageFields.add(MetadataField("File Size", it)) }

        val formattedDate = formatEpochMillis(mediaStoreMeta.dateTakenMs)
            ?: formatEpochSeconds(item.dateAdded)
            ?: captureDate
            ?: fallbackDetails?.dateTaken?.takeIf { it.isNotBlank() && it !in placeholderFallbackValues }
        formattedDate?.let { imageFields.add(MetadataField("Captured", it)) }

        val resolvedPath = mediaStoreMeta.relativePath?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: item.uri.path?.takeIf { item.uri.scheme == "file" && it.isNotBlank() }
            ?: fallbackDetails?.path?.takeIf { it.isNotBlank() && it !in placeholderFallbackValues }
        resolvedPath?.let { imageFields.add(MetadataField("Location Path", it)) }

        if (fps != null && fps > 0.0) {
            val fpsStr = String.format(Locale.US, "%.0f fps", fps)
            captureFields.add(MetadataField("Frame Rate", fpsStr))
            heroHighlights.add(fpsStr)
        }
        if (bitrate > 0L) {
            val mbps = bitrate / 1_000_000.0
            val brStr = if (mbps >= 1.0) {
                String.format(Locale.US, "%.1f Mbps", mbps)
            } else {
                String.format(Locale.US, "%.0f kbps", bitrate / 1_000.0)
            }
            captureFields.add(MetadataField("Bitrate", brStr))
        }

        if (gps != null) {
            locationFields.add(MetadataField("Coordinates", formatCoordinatesDecimal(gps.latitude, gps.longitude)))
            locationFields.add(MetadataField("DMS", formatCoordinatesDms(gps.latitude, gps.longitude)))
        }

        return RichPhotoMetadata(
            title = resolvedName,
            formatBadge = shortFormatBadge(resolvedMime, true),
            isVideo = true,
            heroHighlights = heroHighlights.take(4),
            imageFields = imageFields,
            cameraFields = cameraFields,
            captureFields = captureFields,
            gps = gps,
            locationFields = locationFields
        )
    }

    private data class MediaStoreInfo(
        val displayName: String? = null,
        val mimeType: String? = null,
        val sizeBytes: Long = 0L,
        val width: Int = 0,
        val height: Int = 0,
        val dateTakenMs: Long = 0L,
        val relativePath: String? = null
    )

    private fun queryMediaStoreInfo(context: Context, uri: Uri): MediaStoreInfo {
        if (uri.scheme != "content") return MediaStoreInfo()
        val cols = mutableListOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATE_TAKEN
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cols.add(MediaStore.MediaColumns.RELATIVE_PATH)
        }
        return try {
            context.contentResolver.query(uri, cols.toTypedArray(), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use MediaStoreInfo()
                fun str(col: String): String? {
                    val idx = cursor.getColumnIndex(col)
                    return if (idx >= 0 && !cursor.isNull(idx)) cursor.getString(idx) else null
                }
                fun lng(col: String): Long {
                    val idx = cursor.getColumnIndex(col)
                    return if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else 0L
                }
                fun intVal(col: String): Int {
                    val idx = cursor.getColumnIndex(col)
                    return if (idx >= 0 && !cursor.isNull(idx)) cursor.getInt(idx) else 0
                }
                MediaStoreInfo(
                    displayName = str(MediaStore.MediaColumns.DISPLAY_NAME),
                    mimeType = str(MediaStore.MediaColumns.MIME_TYPE),
                    sizeBytes = lng(MediaStore.MediaColumns.SIZE),
                    width = intVal(MediaStore.MediaColumns.WIDTH),
                    height = intVal(MediaStore.MediaColumns.HEIGHT),
                    dateTakenMs = lng(MediaStore.MediaColumns.DATE_TAKEN),
                    relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        str(MediaStore.MediaColumns.RELATIVE_PATH)
                    } else null
                )
            } ?: MediaStoreInfo()
        } catch (_: Throwable) {
            MediaStoreInfo()
        }
    }

    private fun readExif(context: Context, uri: Uri): ExifInterface? {
        return try {
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                val f = File(uri.path!!)
                if (f.exists() && f.isFile) {
                    return ExifInterface(f.absolutePath)
                }
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readImageBounds(context: Context, uri: Uri): BitmapFactory.Options {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                BitmapFactory.decodeFile(uri.path, opts)
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            }
        } catch (_: Throwable) {}
        return opts
    }

    private fun queryFileDescriptorSize(context: Context, uri: Uri): Long {
        if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
            val f = File(uri.path!!)
            if (f.exists()) return f.length()
        }
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                pfd.statSize.coerceAtLeast(0L)
            } ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    private fun extractGpsCoordinates(exif: ExifInterface?): GpsCoordinates? {
        if (exif == null) return null
        val latLong = exif.latLong ?: return null
        val lat = latLong[0]
        val lon = latLong[1]
        if (lat.isNaN() || lon.isNaN()) return null
        if (abs(lat) < 1e-7 && abs(lon) < 1e-7) return null

        val rawAlt = exif.getAltitude(Double.NaN)
        val alt = if (!rawAlt.isNaN()) rawAlt else null
        val dateStamp = cleanTag(exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP))
        val timeStamp = cleanTag(exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP))
        val stamp = when {
            dateStamp != null && timeStamp != null -> "${dateStamp.replace(':', '-')} $timeStamp UTC"
            dateStamp != null -> dateStamp.replace(':', '-')
            else -> null
        }
        return GpsCoordinates(
            latitude = lat,
            longitude = lon,
            altitudeMeters = alt,
            gpsDateStamp = stamp
        )
    }

    fun parseIso6709Location(raw: String?): GpsCoordinates? {
        if (raw.isNullOrBlank()) return null
        val regex = Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)?")
        val match = regex.find(raw.trim()) ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lon = match.groupValues[2].toDoubleOrNull() ?: return null
        if (abs(lat) < 1e-7 && abs(lon) < 1e-7) return null
        val alt = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toDoubleOrNull()
        return GpsCoordinates(latitude = lat, longitude = lon, altitudeMeters = alt)
    }

    fun formatBytes(bytes: Long): String? {
        if (bytes <= 0L) return null
        return when {
            bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    fun formatAperture(fNumber: Double?, apertureValue: Double?): String? {
        val f = when {
            fNumber != null && fNumber > 0.0 -> fNumber
            apertureValue != null && !apertureValue.isNaN() && apertureValue > 0.0 -> 2.0.pow(apertureValue / 2.0)
            else -> null
        } ?: return null
        val formatted = if ((f * 10.0).roundToInt() % 10 == 0) {
            String.format(Locale.US, "f/%.0f", f)
        } else {
            String.format(Locale.US, "f/%.1f", f)
        }
        return formatted
    }

    fun formatExposureTime(exposureTimeSec: Double?, shutterApex: Double?): String? {
        val sec = when {
            exposureTimeSec != null && exposureTimeSec > 0.0 -> exposureTimeSec
            shutterApex != null && !shutterApex.isNaN() -> 2.0.pow(-shutterApex)
            else -> null
        } ?: return null
        if (sec <= 0.0 || sec.isNaN() || sec.isInfinite()) return null
        return if (sec < 0.5) {
            val denom = (1.0 / sec).roundToInt().coerceAtLeast(1)
            "1/$denom s"
        } else if (abs(sec - sec.roundToInt()) < 0.05) {
            "${sec.roundToInt()} s"
        } else {
            String.format(Locale.US, "%.1f s", sec)
        }
    }

    fun formatFocalLength(focalMm: Double?, focal35Mm: Int?): Pair<String, String>? {
        val hasPhysical = focalMm != null && focalMm > 0.0
        val has35 = focal35Mm != null && focal35Mm > 0
        if (!hasPhysical && !has35) return null

        val physicalText = if (hasPhysical) {
            if (abs(focalMm!! - focalMm.roundToInt()) < 0.05) {
                "${focalMm.roundToInt()} mm"
            } else {
                String.format(Locale.US, "%.1f mm", focalMm)
            }
        } else null

        val full = when {
            physicalText != null && has35 && focal35Mm != focalMm?.roundToInt() ->
                "$physicalText (${focal35Mm} mm eq.)"
            physicalText != null -> physicalText
            else -> "$focal35Mm mm (35mm eq.)"
        }
        val short = physicalText ?: "${focal35Mm} mm"
        return full to short
    }

    fun formatExposureBias(biasEv: Double?): String? {
        if (biasEv == null || biasEv.isNaN()) return null
        val rounded = (biasEv * 10.0).roundToInt() / 10.0
        return when {
            rounded > 0.0 -> String.format(Locale.US, "+%.1f EV", rounded)
            rounded < 0.0 -> String.format(Locale.US, "%.1f EV", rounded)
            else -> "0.0 EV"
        }
    }

    fun formatCoordinatesDecimal(lat: Double, lon: Double): String {
        val latDir = if (lat >= 0) "N" else "S"
        val lonDir = if (lon >= 0) "E" else "W"
        return String.format(Locale.US, "%.5f° %s, %.5f° %s", abs(lat), latDir, abs(lon), lonDir)
    }

    fun formatCoordinatesDms(lat: Double, lon: Double): String {
        fun toDms(value: Double, posDir: String, negDir: String): String {
            val dir = if (value >= 0) posDir else negDir
            val absVal = abs(value)
            val deg = absVal.toInt()
            val minFloat = (absVal - deg) * 60.0
            val min = minFloat.toInt()
            val sec = (minFloat - min) * 60.0
            return String.format(Locale.US, "%d°%02d'%04.1f\"%s", deg, min, sec, dir)
        }
        return "${toDms(lat, "N", "S")} · ${toDms(lon, "E", "W")}"
    }

    private fun parseIsoValue(exif: ExifInterface?): Int? {
        if (exif == null) return null
        val candidates = listOf(
            exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
            exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS),
            exif.getAttribute(ExifInterface.TAG_ISO_SPEED)
        )
        for (raw in candidates) {
            val parsed = raw?.trim()?.split(',', ' ')?.firstOrNull()?.toIntOrNull()
            if (parsed != null && parsed > 0) return parsed
        }
        return null
    }

    private fun extractExifCaptureTimestamp(exif: ExifInterface?): String? {
        if (exif == null) return null
        val raw = cleanTag(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            ?: cleanTag(exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED))
            ?: cleanTag(exif.getAttribute(ExifInterface.TAG_DATETIME))
            ?: return null
        return try {
            val parser = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            val parsed = parser.parse(raw)
            if (parsed != null) {
                val base = SimpleDateFormat("MMM d, yyyy · HH:mm:ss", Locale.getDefault()).format(parsed)
                val offset = cleanTag(exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
                if (offset != null) "$base ($offset)" else base
            } else {
                raw
            }
        } catch (_: Throwable) {
            raw
        }
    }

    private fun formatEpochMillis(millis: Long): String? {
        if (millis <= 0L) return null
        return SimpleDateFormat("MMM d, yyyy · HH:mm:ss", Locale.getDefault()).format(Date(millis))
    }

    private fun formatEpochSeconds(seconds: Long): String? {
        if (seconds <= 0L) return null
        return SimpleDateFormat("MMM d, yyyy · HH:mm:ss", Locale.getDefault()).format(Date(seconds * 1000L))
    }

    private fun formatMimeLabel(mime: String?): String? {
        if (mime.isNullOrBlank()) return null
        return when (mime.lowercase(Locale.US)) {
            "image/jpeg", "image/jpg" -> "JPEG ($mime)"
            "image/png" -> "PNG ($mime)"
            "image/webp" -> "WebP ($mime)"
            "image/heic", "image/heif" -> "HEIF ($mime)"
            "image/x-adobe-dng" -> "RAW DNG ($mime)"
            "video/mp4" -> "MP4 ($mime)"
            else -> mime
        }
    }

    private fun shortFormatBadge(mime: String?, isVideo: Boolean): String? {
        if (mime.isNullOrBlank()) return if (isVideo) "VIDEO" else null
        return when (mime.lowercase(Locale.US)) {
            "image/jpeg", "image/jpg" -> "JPEG"
            "image/png" -> "PNG"
            "image/webp" -> "WEBP"
            "image/heic", "image/heif" -> "HEIC"
            "image/x-adobe-dng" -> "RAW DNG"
            "video/mp4" -> "MP4"
            else -> mime.substringAfter('/', mime).uppercase(Locale.US)
        }
    }

    private fun formatLensSpecification(spec: String?): String? {
        if (spec.isNullOrBlank()) return null
        // Format is typically "minFocal maxFocal minFNumber maxFNumber"
        val nums = spec.split(',', ' ').mapNotNull { part ->
            val trimmed = part.trim()
            if (trimmed.contains('/')) {
                val lr = trimmed.split('/')
                val n = lr.getOrNull(0)?.toDoubleOrNull()
                val d = lr.getOrNull(1)?.toDoubleOrNull()
                if (n != null && d != null && d != 0.0) n / d else null
            } else {
                trimmed.toDoubleOrNull()
            }
        }
        if (nums.size < 4) return spec
        val minF = nums[0]
        val maxF = nums[1]
        val minA = nums[2]
        val maxA = nums[3]
        if (minF <= 0.0 || minA <= 0.0) return null
        val focalPart = if (abs(minF - maxF) < 0.05) {
            String.format(Locale.US, "%.1fmm", minF)
        } else {
            String.format(Locale.US, "%.1f–%.1fmm", minF, maxF)
        }
        val apPart = if (abs(minA - maxA) < 0.05) {
            String.format(Locale.US, "f/%.1f", minA)
        } else {
            String.format(Locale.US, "f/%.1f–%.1f", minA, maxA)
        }
        return "$focalPart $apPart"
    }

    private fun formatFlashMode(flashValue: Int?): String? {
        if (flashValue == null || flashValue < 0) return null
        val fired = (flashValue and 0x1) != 0
        val mode = (flashValue shr 3) and 0x3
        return when {
            fired && mode == 3 -> "Fired (Auto)"
            fired -> "Fired"
            mode == 2 -> "Off (Compulsory)"
            mode == 3 -> "Did not fire (Auto)"
            else -> "Did not fire"
        }
    }

    private fun formatWhiteBalance(wb: Int?): String? = when (wb) {
        0 -> "Auto"
        1 -> "Manual"
        else -> null
    }

    private fun formatMeteringMode(mode: Int?): String? = when (mode) {
        1 -> "Average"
        2 -> "Center-Weighted"
        3 -> "Spot"
        4 -> "Multi-Spot"
        5 -> "Multi-Segment"
        6 -> "Partial"
        else -> null
    }

    private fun formatExposureProgram(program: Int?): String? = when (program) {
        1 -> "Manual"
        2 -> "Program Auto"
        3 -> "Aperture Priority"
        4 -> "Shutter Priority"
        5 -> "Creative"
        6 -> "Action"
        7 -> "Portrait"
        8 -> "Landscape"
        else -> null
    }

    private fun formatSceneCaptureType(scene: Int?): String? = when (scene) {
        0 -> "Standard"
        1 -> "Landscape"
        2 -> "Portrait"
        3 -> "Night Scene"
        else -> null
    }

    private fun formatProcessingLevel(level: Int?): String? = when (level) {
        0 -> "Normal"
        1 -> "Soft"
        2 -> "Hard"
        else -> null
    }

    private fun formatColorSpace(colorSpace: Int?): String? = when (colorSpace) {
        1 -> "sRGB"
        2 -> "Adobe RGB"
        65535 -> "Uncalibrated / Wide Gamut"
        else -> null
    }

    private fun formatOrientationLabel(orientation: Int): String? = when (orientation) {
        ExifInterface.ORIENTATION_NORMAL -> "Normal (0°)"
        ExifInterface.ORIENTATION_ROTATE_90 -> "Rotated 90° CW"
        ExifInterface.ORIENTATION_ROTATE_180 -> "Rotated 180°"
        ExifInterface.ORIENTATION_ROTATE_270 -> "Rotated 270° CW"
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> "Flipped Horizontal"
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> "Flipped Vertical"
        ExifInterface.ORIENTATION_TRANSPOSE -> "Transposed"
        ExifInterface.ORIENTATION_TRANSVERSE -> "Transversed"
        else -> null
    }

    private fun formatExifVersion(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return if (raw.length == 4 && raw.all { it.isDigit() }) {
            "${raw.substring(0, 2).toInt()}.${raw.substring(2, 4)}"
        } else {
            raw
        }
    }

    private fun cleanTag(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.equals("null", ignoreCase = true) ||
            trimmed.equals("unknown", ignoreCase = true) ||
            trimmed == "0" ||
            trimmed == "0.0"
        ) {
            return null
        }
        return trimmed
    }
}
