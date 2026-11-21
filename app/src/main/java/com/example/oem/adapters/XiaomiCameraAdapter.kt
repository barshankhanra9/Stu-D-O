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
 * Dedicated Xiaomi / Redmi / POCO OEM Adapter.
 * Prepares extension points for Xiaomi devices.
 * Uses CTS-certified Camera2 APIs for ISP pipeline configuration.
 */
class XiaomiCameraAdapter : OemCameraAdapter {

    override val manufacturer: OemManufacturer = OemManufacturer.XIAOMI
    override val adapterName: String = "Xiaomi OEM Adapter"

    override fun isDeviceCompatible(context: Context): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ||
                b.contains("xiaomi") || b.contains("redmi") || b.contains("poco")
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

        features.add(
            OemFeatureDescriptor(
                featureId = "xiaomi_uhr",
                featureName = "Xiaomi Ultra-High-Resolution Mode",
                isAvailable = hasUhr,
                isVerified = hasUhr,
                description = if (hasUhr) "Xiaomi HAL exposes standard unbinned stream"
                else "Xiaomi HAL restricts unbinned 50MP/108MP/200MP modes to MIUI/HyperOS Camera app"
            )
        )

        features.add(
            OemFeatureDescriptor(
                featureId = "xiaomi_raw",
                featureName = "Xiaomi RAW_SENSOR DNG",
                isAvailable = hasRaw,
                isVerified = hasRaw,
                description = "Direct hardware Bayer RAW DNG capture"
            )
        )

        val nrModes = characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf()
        if (nrModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
            ispHardware.add("Camera2 High-Quality Denoise")
        }

        val edgeModes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf()
        if (edgeModes.contains(CameraMetadata.EDGE_MODE_HIGH_QUALITY)) {
            ispHardware.add("Camera2 High-Quality Edge Enhancement")
        }

        val sceneModes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES) ?: intArrayOf()
        val hasHdr = sceneModes.contains(CameraMetadata.CONTROL_SCENE_MODE_HDR)
        if (hasHdr) ispHardware.add("Camera2 Hardware HDR Scene Mode")

        val compatible = isDeviceCompatible(context)
        return OemEnhancementProfile(
            manufacturer = manufacturer,
            adapterName = adapterName,
            isCompatible = compatible,
            isVerified = false,
            features = features.map { it.copy(isVerified = false) },
            hardwareIspEnhancements = ispHardware,
            highResCaptureAvailable = hasUhr,
            hardwareHdrAvailable = hasHdr,
            rawCaptureAvailable = hasRaw,
            limitationsNote = if (!hasUhr) "Xiaomi high-resolution modes on budget/mid-range series operate with 4:1 binning in public Camera2 HAL. StuDO captures the highest CTS-verified stream. Physical device testing required for full verification."
            else "Xiaomi hardware capabilities advertised. Physical device shutter testing required for verification."
        )
    }

    override fun configureCaptureRequest(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
        enableHdr: Boolean
    ) {
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
