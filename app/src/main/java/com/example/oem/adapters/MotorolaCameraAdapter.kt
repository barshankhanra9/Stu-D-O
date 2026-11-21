package com.example.oem.adapters

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Size
import com.example.oem.OemCameraAdapter
import com.example.oem.OemEnhancementProfile
import com.example.oem.OemFeatureDescriptor
import com.example.oem.OemManufacturer

/**
 * Dedicated Motorola OEM Adapter.
 * Integrates Motorola-specific capability detection and ISP optimizations
 * while strictly adhering to public, CTS-certified Android Camera2 standards.
 */
class MotorolaCameraAdapter : OemCameraAdapter {

    override val manufacturer: OemManufacturer = OemManufacturer.MOTOROLA
    override val adapterName: String = "Motorola OEM Adapter"

    override fun isDeviceCompatible(context: Context): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        return m.contains("motorola") || b.contains("moto") || m.contains("lenovo")
    }

    override fun probeCapabilities(
        context: Context,
        characteristics: CameraCharacteristics,
        cameraId: String
    ): OemEnhancementProfile {
        val features = mutableListOf<OemFeatureDescriptor>()
        val ispHardware = mutableListOf<String>()

        val availableCaps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val hasRaw = availableCaps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val hasUhr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            availableCaps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)
        } else false

        // Check active array vs maximum public stream to detect 4:1 binning
        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val standardMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val standardJpeg = standardMap?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
        val topStandardJpeg = standardJpeg.maxByOrNull { it.width.toLong() * it.height.toLong() }

        val isHardwareBinned = if (activeArray != null && topStandardJpeg != null) {
            val activeArea = activeArray.width().toLong() * activeArray.height().toLong()
            val jpegArea = topStandardJpeg.width.toLong() * topStandardJpeg.height.toLong()
            activeArea > jpegArea * 2
        } else false

        features.add(
            OemFeatureDescriptor(
                featureId = "moto_quad_bayer_uhr",
                featureName = "Public Ultra-High-Resolution Stream",
                isAvailable = hasUhr,
                isVerified = hasUhr,
                description = if (hasUhr) "Motorola HAL publishes standard unbinned high-res stream"
                else "Motorola HAL operates in 4:1 hardware-binned mode (${topStandardJpeg?.width} × ${topStandardJpeg?.height}). Stock 50MP mode relies on proprietary Qualcomm CHI vendor modules unexposed to public Android APIs."
            )
        )

        features.add(
            OemFeatureDescriptor(
                featureId = "moto_raw",
                featureName = "Motorola RAW_SENSOR (DNG)",
                isAvailable = hasRaw,
                isVerified = hasRaw,
                description = "Hardware direct digital negative capture"
            )
        )

        // ISP high quality modes
        val nrModes = characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf()
        val hasHighQualityNr = nrModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        if (hasHighQualityNr) ispHardware.add("Camera2 High-Quality Denoise")

        val edgeModes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf()
        val hasHighQualityEdge = edgeModes.contains(CameraMetadata.EDGE_MODE_HIGH_QUALITY)
        if (hasHighQualityEdge) ispHardware.add("Camera2 High-Quality Edge Enhancement")

        val colorModes = characteristics.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES) ?: intArrayOf()
        if (colorModes.contains(CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)) {
            ispHardware.add("Camera2 Chromatic Aberration Correction")
        }

        val sceneModes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES) ?: intArrayOf()
        val hasHdrScene = sceneModes.contains(CameraMetadata.CONTROL_SCENE_MODE_HDR)
        if (hasHdrScene) ispHardware.add("Camera2 Hardware HDR Scene Mode")

        val limitations = if (isHardwareBinned && !hasUhr) {
            "Motorola HAL on ${Build.MODEL} locks unbinned Quad-Bayer output to internal system applications. StuDO captures the highest genuine verified stream (${topStandardJpeg?.width} × ${topStandardJpeg?.height}) at 100% JPEG quality with high-quality ISP denoise and DNG RAW support. Physical device shutter testing required for verification."
        } else {
            "Motorola OEM hardware enhancements configured with full CTS compliance. Physical device shutter testing required for verification."
        }

        val compatible = isDeviceCompatible(context)
        return OemEnhancementProfile(
            manufacturer = manufacturer,
            adapterName = adapterName,
            isCompatible = compatible,
            isVerified = false,
            features = features.map { it.copy(isVerified = false) },
            hardwareIspEnhancements = ispHardware,
            highResCaptureAvailable = hasUhr,
            hardwareHdrAvailable = hasHdrScene,
            rawCaptureAvailable = hasRaw,
            limitationsNote = limitations
        )
    }

    override fun configureCaptureRequest(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
        enableHdr: Boolean
    ) {
        // Motorola ISP tuning with documented Camera2 parameters
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        val nrModes = characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf()
        if (nrModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        }

        val edgeModes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf()
        if (edgeModes.contains(CameraMetadata.EDGE_MODE_HIGH_QUALITY)) {
            builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
        }

        val colorModes = characteristics.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES) ?: intArrayOf()
        if (colorModes.contains(CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)) {
            builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
        }

        val hotPixelModes = characteristics.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES) ?: intArrayOf()
        if (hotPixelModes.contains(CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)) {
            builder.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)
        }

        if (enableHdr) {
            val sceneModes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES) ?: intArrayOf()
            if (sceneModes.contains(CameraMetadata.CONTROL_SCENE_MODE_HDR)) {
                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
                builder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_HDR)
            }
        }
    }

    override fun getSupportedHighResolution(characteristics: CameraCharacteristics): Size? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            val sizes = map?.getOutputSizes(ImageFormat.JPEG)
            if (!sizes.isNullOrEmpty()) {
                return sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
            }
        }
        val stdMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val highResSizes = stdMap?.getHighResolutionOutputSizes(ImageFormat.JPEG)
        if (!highResSizes.isNullOrEmpty()) {
            return highResSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
        }
        return null
    }
}
