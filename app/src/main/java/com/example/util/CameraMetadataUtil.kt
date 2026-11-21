package com.example.util

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import com.example.model.WatermarkConfig
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Robust, genuine camera metadata extractor and formatter.
 * Reads real EXIF information directly from captured photos and HAL characteristics.
 * Never invents, simulates, or hardcodes fake camera parameters.
 */
data class RealCameraMetadata(
    val iso: String? = null,
    val shutterSpeed: String? = null,
    val aperture: String? = null,
    val focalLength: String? = null,
    val dateTimeFormatted: String? = null,
    val deviceModel: String? = null,
    val lensInfo: String? = null,
    val resolutionText: String? = null,
    val manufacturer: String? = null,
    val modelOnly: String? = null
) {
    /**
     * Formats the exposure triangle: e.g. "4.7mm  f/1.8  1/250s  ISO 100".
     * If individual components are missing, only available genuine items are joined.
     * If none are available, returns "N/A" when EXIF specs are requested, or an empty string.
     */
    fun formatExposureTriangle(config: WatermarkConfig): String {
        if (!config.showExifSpecs && !config.showLensInfo) return ""
        val parts = mutableListOf<String>()

        if (config.showLensInfo && !focalLength.isNullOrBlank()) {
            parts.add(focalLength)
        }
        if (config.showExifSpecs) {
            if (!aperture.isNullOrBlank()) parts.add(aperture)
            if (!shutterSpeed.isNullOrBlank()) parts.add(shutterSpeed)
            if (!iso.isNullOrBlank()) parts.add(iso)
        }

        return when {
            parts.isNotEmpty() -> parts.joinToString("  ")
            config.showExifSpecs -> "N/A"
            else -> ""
        }
    }

    /**
     * Formats only genuinely available exposure fields for rendered watermarks,
     * returning an empty string when no values exist instead of rendering "N/A".
     */
    fun formatAvailableExposureTriangle(config: WatermarkConfig): String {
        if (!config.showExifSpecs && !config.showLensInfo) return ""
        val parts = mutableListOf<String>()
        if (config.showLensInfo && !focalLength.isNullOrBlank()) {
            parts.add(focalLength)
        }
        if (config.showExifSpecs) {
            if (!aperture.isNullOrBlank()) parts.add(aperture)
            if (!shutterSpeed.isNullOrBlank()) parts.add(shutterSpeed)
            if (!iso.isNullOrBlank()) parts.add(iso)
        }
        return parts.joinToString("  ")
    }

    /**
     * Formats secondary hardware information: Device model and/or resolution.
     */
    fun formatDeviceAndResolution(config: WatermarkConfig): String {
        val parts = mutableListOf<String>()
        if (config.showDeviceModel && !deviceModel.isNullOrBlank()) {
            parts.add(deviceModel)
        }
        if (config.showResolution && !resolutionText.isNullOrBlank()) {
            parts.add(resolutionText)
        }
        return parts.joinToString(" · ")
    }
}

object CameraMetadataUtil {

    /**
     * Extracts genuine metadata from an ExifInterface instance.
     * When [allowDeviceFallback] is false (e.g., externally imported photos), missing EXIF Make/Model
     * remain null instead of falling back to the current device's Build.MANUFACTURER/Build.MODEL.
     */
    fun extract(
        exif: ExifInterface,
        width: Int = 0,
        height: Int = 0,
        allowDeviceFallback: Boolean = true
    ): RealCameraMetadata {
        // 1. ISO
        val isoVal = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
            ?: exif.getAttribute("PhotographicSensitivity")
        val isoStr = if (!isoVal.isNullOrBlank() && isoVal != "0") "ISO $isoVal" else null

        // 2. Shutter Speed
        val exposureTimeSec = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
        val shutterStr = if (exposureTimeSec > 0.0) {
            if (exposureTimeSec < 0.999) {
                val denom = Math.round(1.0 / exposureTimeSec)
                "1/${denom}s"
            } else {
                String.format(Locale.US, "%.1fs", exposureTimeSec)
            }
        } else {
            val expRaw = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
            if (!expRaw.isNullOrBlank()) "${expRaw}s" else null
        }

        // 3. Aperture (f-number)
        val fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
        val apertureStr = if (fNumber > 0.0) {
            if (fNumber == fNumber.toInt().toDouble()) {
                "f/${fNumber.toInt()}"
            } else {
                String.format(Locale.US, "f/%.1f", fNumber)
            }
        } else {
            val fRaw = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
            if (!fRaw.isNullOrBlank()) "f/$fRaw" else null
        }

        // 4. Physical Focal Length in mm (do not substitute 35mm-equivalent FocalLengthIn35mmFilm)
        val focalMm = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
        val focalStr = formatFocalLengthMm(focalMm)
        val exifLensModel = exif.getAttribute("LensModel")?.trim()?.takeIf { it.isNotBlank() }

        // 5. Date & Time
        val rawDate = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
        val dateFormatted = if (!rawDate.isNullOrBlank()) {
            formatExifDate(rawDate)
        } else null

        // 6. Device Model & Make
        val rawMake = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
        val rawModel = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()

        val cleanMake: String?
        val cleanModelOnly: String?
        val deviceStr: String?

        if (allowDeviceFallback) {
            val make = if (rawMake.isNotBlank()) rawMake else Build.MANUFACTURER
            val model = if (rawModel.isNotBlank()) rawModel else Build.MODEL
            val trimmedMake = make.trim()
            val strippedModel = if (model.startsWith(trimmedMake, ignoreCase = true)) {
                model.substring(trimmedMake.length).trim()
            } else {
                model.trim()
            }
            cleanMake = trimmedMake
            cleanModelOnly = strippedModel.ifBlank { model }
            deviceStr = if (strippedModel.isNotBlank()) {
                if (strippedModel.startsWith(trimmedMake, ignoreCase = true)) strippedModel else "$trimmedMake $strippedModel"
            } else {
                trimmedMake
            }
        } else {
            cleanMake = rawMake.takeIf { it.isNotBlank() }
            val strippedModel = if (rawModel.isNotBlank()) {
                if (cleanMake != null && rawModel.startsWith(cleanMake, ignoreCase = true)) {
                    rawModel.substring(cleanMake.length).trim().ifBlank { rawModel }
                } else {
                    rawModel
                }
            } else {
                null
            }
            cleanModelOnly = strippedModel
            deviceStr = when {
                cleanMake != null && strippedModel != null -> {
                    if (strippedModel.startsWith(cleanMake, ignoreCase = true)) strippedModel else "$cleanMake $strippedModel"
                }
                strippedModel != null -> strippedModel
                cleanMake != null -> cleanMake
                else -> null
            }
        }

        // 7. Resolution
        val imgW = if (width > 0) width else exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
        val imgH = if (height > 0) height else exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
        val resStr = if (imgW > 0 && imgH > 0) {
            val mp = (imgW.toLong() * imgH.toLong()) / 1_000_000.0
            String.format(Locale.US, "%.1f MP (%d×%d)", mp, imgW, imgH)
        } else null

        return RealCameraMetadata(
            iso = isoStr,
            shutterSpeed = shutterStr,
            aperture = apertureStr,
            focalLength = focalStr,
            dateTimeFormatted = dateFormatted,
            deviceModel = deviceStr,
            lensInfo = focalStr ?: exifLensModel,
            resolutionText = resStr,
            manufacturer = cleanMake,
            modelOnly = cleanModelOnly
        )
    }

    /**
     * Extracts genuine metadata from a ByteArray representing JPEG payload.
     */
    fun fromByteArray(
        bytes: ByteArray,
        width: Int = 0,
        height: Int = 0,
        allowDeviceFallback: Boolean = true
    ): RealCameraMetadata {
        return try {
            ByteArrayInputStream(bytes).use { stream ->
                val exif = ExifInterface(stream)
                extract(exif, width, height, allowDeviceFallback)
            }
        } catch (_: Exception) {
            fallbackMetadata(width, height, allowDeviceFallback)
        }
    }

    /**
     * Extracts genuine metadata from a Uri stored in MediaStore or SAF.
     */
    fun fromUri(
        context: Context,
        uri: Uri,
        width: Int = 0,
        height: Int = 0,
        allowDeviceFallback: Boolean = true
    ): RealCameraMetadata {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                extract(exif, width, height, allowDeviceFallback)
            } ?: fallbackMetadata(width, height, allowDeviceFallback)
        } catch (_: Exception) {
            fallbackMetadata(width, height, allowDeviceFallback)
        }
    }

    /**
     * Extracts genuine metadata from a local File.
     */
    fun fromFile(
        file: java.io.File,
        width: Int = 0,
        height: Int = 0,
        allowDeviceFallback: Boolean = true
    ): RealCameraMetadata {
        return try {
            val exif = ExifInterface(file.absolutePath)
            extract(exif, width, height, allowDeviceFallback)
        } catch (_: Exception) {
            fallbackMetadata(width, height, allowDeviceFallback)
        }
    }

    /**
     * Fallback for real photos when EXIF metadata is missing or unreadable.
     * Never fabricates fake exposure triangle or optical values (ISO, shutter speed, aperture, focal length).
     * When [allowDeviceFallback] is false, never fabricates host device Make/Model either.
     */
    fun fallbackMetadata(
        width: Int = 0,
        height: Int = 0,
        allowDeviceFallback: Boolean = true
    ): RealCameraMetadata {
        val resStr = if (width > 0 && height > 0) {
            val mp = (width.toLong() * height.toLong()) / 1_000_000.0
            String.format(Locale.US, "%.1f MP (%d×%d)", mp, width, height)
        } else null

        if (!allowDeviceFallback) {
            return RealCameraMetadata(resolutionText = resStr)
        }

        val mfr = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        val model = Build.MODEL
        val cleanModel = if (model.startsWith(mfr, ignoreCase = true)) {
            model.substring(mfr.length).trim()
        } else {
            model.trim()
        }

        return RealCameraMetadata(
            iso = null,
            shutterSpeed = null,
            aperture = null,
            focalLength = null,
            dateTimeFormatted = null,
            deviceModel = "$mfr $cleanModel",
            lensInfo = null,
            resolutionText = resStr,
            manufacturer = mfr,
            modelOnly = cleanModel.ifBlank { model }
        )
    }

    /**
     * Formats a genuine physical focal length in millimeters (e.g., 4.7f -> "4.7mm", 5.0f -> "5mm").
     * Returns null when the focal length is unavailable or invalid so callers can hide the field.
     */
    fun formatFocalLengthMm(focalMm: Double?): String? {
        if (focalMm == null || !focalMm.isFinite() || focalMm <= 0.0) return null
        val rounded = Math.round(focalMm)
        return if (Math.abs(focalMm - rounded.toDouble()) < 1e-4) {
            "${rounded}mm"
        } else {
            String.format(Locale.US, "%.1fmm", focalMm)
        }
    }

    fun formatFocalLengthMm(focalMm: Float?): String? =
        if (focalMm != null && focalMm.isFinite() && focalMm > 0f) {
            formatFocalLengthMm(focalMm.toDouble())
        } else {
            null
        }

    /**
     * Supplies the active lens's Camera2 physical focal length when photo EXIF lacks TAG_FOCAL_LENGTH,
     * preserving authentic capture EXIF whenever present and hiding the field if unavailable.
     */
    fun withActiveFocalLengthIfMissing(
        metadata: RealCameraMetadata,
        activeFocalLengthMm: Float?
    ): RealCameraMetadata {
        if (!metadata.focalLength.isNullOrBlank()) return metadata
        val formatted = formatFocalLengthMm(activeFocalLengthMm) ?: return metadata
        return metadata.copy(focalLength = formatted, lensInfo = formatted)
    }

    /**
     * Sample metadata for Watermark Studio preview cards.
     * Uses the active camera's genuine physical focal length when available, and hides focal length if unavailable.
     */
    fun samplePreviewMetadata(
        width: Int = 1200,
        height: Int = 800,
        focalLength: String? = null
    ): RealCameraMetadata {
        val mp = (width.toLong() * height.toLong()) / 1_000_000.0
        val mfr = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        val model = Build.MODEL
        val cleanModel = if (model.startsWith(mfr, ignoreCase = true)) {
            model.substring(mfr.length).trim()
        } else {
            model.trim()
        }
        val genuineFocal = focalLength?.takeIf { it.isNotBlank() }

        return RealCameraMetadata(
            iso = "ISO 100",
            shutterSpeed = "1/250s",
            aperture = "f/1.8",
            focalLength = genuineFocal,
            dateTimeFormatted = SimpleDateFormat("yyyy.MM.dd  HH:mm:ss", Locale.US).format(Date()),
            deviceModel = "$mfr $cleanModel",
            lensInfo = genuineFocal,
            resolutionText = String.format(Locale.US, "%.1f MP (%d×%d)", mp, width, height),
            manufacturer = mfr,
            modelOnly = cleanModel.ifBlank { model }
        )
    }

    private fun formatExifDate(raw: String): String {
        return try {
            // Standard EXIF date is "yyyy:MM:dd HH:mm:ss"
            val parser = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            val date = parser.parse(raw)
            if (date != null) {
                SimpleDateFormat("yyyy.MM.dd  HH:mm:ss", Locale.US).format(date)
            } else {
                raw.replace(":", ".").replace("-", ".")
            }
        } catch (_: Exception) {
            raw.replace(":", ".").replace("-", ".")
        }
    }
}
