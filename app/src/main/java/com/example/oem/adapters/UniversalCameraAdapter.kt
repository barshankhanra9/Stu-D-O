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
 * Universal Android Camera Adapter.
 * Serves as the robust default implementation and safe fallback on all devices.
 * Uses strict standard Android Camera2 / CTS-certified APIs without vendor assumptions.
 */
class UniversalCameraAdapter : OemCameraAdapter {

    override val manufacturer: OemManufacturer = OemManufacturer.GENERIC
    override val adapterName: String = "Universal Android Engine"

    override fun isDeviceCompatible(context: Context): Boolean = true

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

        features.add(
            OemFeatureDescriptor(
                featureId = "std_uhr",
                featureName = "Standard Ultra-High-Resolution (API 31+)",
                isAvailable = hasUhr,
                isVerified = false,
                description = "Standard Android 12+ public remosaic sensor capability"
            )
        )

        features.add(
            OemFeatureDescriptor(
                featureId = "std_raw",
                featureName = "Standard RAW_SENSOR Capture",
                isAvailable = hasRaw,
                isVerified = false,
                description = "CTS-compliant DNG digital negative capture"
            )
        )

        val nrModes = characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf()
        val hasHighQualityNr = nrModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        if (hasHighQualityNr) ispHardware.add("High-Quality Denoise")

        val edgeModes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf()
        val hasHighQualityEdge = edgeModes.contains(CameraMetadata.EDGE_MODE_HIGH_QUALITY)
        if (hasHighQualityEdge) ispHardware.add("High-Quality Edge Enhancement")

        val sceneModes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES) ?: intArrayOf()
        val hasHdrScene = sceneModes.contains(CameraMetadata.CONTROL_SCENE_MODE_HDR)
        if (hasHdrScene) ispHardware.add("Hardware HDR Scene Mode")

        return OemEnhancementProfile(
            manufacturer = manufacturer,
            adapterName = adapterName,
            isCompatible = true,
            isVerified = false,
            features = features,
            hardwareIspEnhancements = ispHardware,
            highResCaptureAvailable = hasUhr,
            hardwareHdrAvailable = hasHdrScene,
            rawCaptureAvailable = hasRaw,
            limitationsNote = "Standard universal engine operating in pure CTS compliance across Android hardware. Requires physical shutter capture on device for hardware verification."
        )
    }

    override fun configureCaptureRequest(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
        enableHdr: Boolean
    ) {
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
