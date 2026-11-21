package com.example.oem

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Size

enum class OemManufacturer(val displayName: String, val brandAliases: List<String>) {
    MOTOROLA("Motorola", listOf("motorola", "moto", "lenovo")),
    SAMSUNG("Samsung", listOf("samsung")),
    XIAOMI("Xiaomi", listOf("xiaomi", "redmi", "poco")),
    ONEPLUS("OnePlus", listOf("oneplus", "oppo", "realme")),
    GENERIC("Universal Android", emptyList());

    companion object {
        fun detectCurrent(): OemManufacturer {
            val manufacturer = Build.MANUFACTURER.lowercase()
            val brand = Build.BRAND.lowercase()
            for (m in values()) {
                if (m == GENERIC) continue
                if (m.brandAliases.any { manufacturer.contains(it) || brand.contains(it) }) {
                    return m
                }
            }
            return GENERIC
        }
    }
}

data class OemFeatureDescriptor(
    val featureId: String,
    val featureName: String,
    val isAvailable: Boolean,
    val isVerified: Boolean,
    val description: String
)

data class OemEnhancementProfile(
    val manufacturer: OemManufacturer,
    val adapterName: String,
    val isCompatible: Boolean,
    val isVerified: Boolean,
    val features: List<OemFeatureDescriptor>,
    val hardwareIspEnhancements: List<String>,
    val highResCaptureAvailable: Boolean,
    val hardwareHdrAvailable: Boolean,
    val rawCaptureAvailable: Boolean,
    val limitationsNote: String
)

/**
 * Modular interface for OEM camera enhancements.
 * Integrations must remain isolated and never become dependencies of the universal engine.
 * All implementations strictly use documented, public Android Camera2 APIs.
 */
interface OemCameraAdapter {
    val manufacturer: OemManufacturer
    val adapterName: String

    /**
     * Checks if the current physical device is compatible with this adapter.
     */
    fun isDeviceCompatible(context: Context): Boolean

    /**
     * Probes genuine hardware capabilities of the specified camera sensor.
     */
    fun probeCapabilities(
        context: Context,
        characteristics: CameraCharacteristics,
        cameraId: String
    ): OemEnhancementProfile

    /**
     * Applies verified OEM-tuned capture request parameters (e.g. ISP high quality, noise reduction, HDR).
     */
    fun configureCaptureRequest(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
        enableHdr: Boolean
    )

    /**
     * Returns the highest verified output size for high-resolution photography, or null if unsupported.
     */
    fun getSupportedHighResolution(characteristics: CameraCharacteristics): Size?
}
