package com.example.oem

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.example.model.OemEnhancementMode
import com.example.oem.adapters.MotorolaCameraAdapter
import com.example.oem.adapters.OnePlusCameraAdapter
import com.example.oem.adapters.SamsungCameraAdapter
import com.example.oem.adapters.UniversalCameraAdapter
import com.example.oem.adapters.XiaomiCameraAdapter

/**
 * Registry and dynamic dispatcher for OEM Camera Adapters.
 * Provides runtime capability discovery, device compatibility checks,
 * and guaranteed safe fallback to Universal Mode.
 */
class OemAdapterRegistry {

    private val universalAdapter = UniversalCameraAdapter()

    // Registered adapters in priority order
    private val registeredAdapters: List<OemCameraAdapter> = listOf(
        MotorolaCameraAdapter(),
        SamsungCameraAdapter(),
        XiaomiCameraAdapter(),
        OnePlusCameraAdapter(),
        universalAdapter
    )

    fun getAllAdapters(): List<OemCameraAdapter> = registeredAdapters

    /**
     * Resolves the active adapter according to user preference and physical device compatibility.
     * When in UNIVERSAL_ONLY mode, always returns UniversalCameraAdapter.
     * When in AUTOMATIC mode, inspects hardware manufacturer and returns the matching adapter,
     * falling back safely to UniversalCameraAdapter if no specific match is found.
     */
    fun resolveActiveAdapter(context: Context, mode: OemEnhancementMode): OemCameraAdapter {
        if (mode == OemEnhancementMode.UNIVERSAL_ONLY) {
            return universalAdapter
        }

        return try {
            registeredAdapters.firstOrNull { it != universalAdapter && it.isDeviceCompatible(context) }
                ?: universalAdapter
        } catch (_: Exception) {
            universalAdapter
        }
    }

    /**
     * Probes the enhancement profile for the specified camera using the active adapter.
     */
    fun probeActiveEnhancements(
        context: Context,
        cameraId: String,
        mode: OemEnhancementMode
    ): OemEnhancementProfile {
        val adapter = resolveActiveAdapter(context, mode)
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            adapter.probeCapabilities(context, characteristics, cameraId)
        } catch (_: Exception) {
            // Graceful fallback to Universal
            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                universalAdapter.probeCapabilities(context, characteristics, cameraId)
            } catch (_: Exception) {
                OemEnhancementProfile(
                    manufacturer = OemManufacturer.GENERIC,
                    adapterName = universalAdapter.adapterName,
                    isCompatible = true,
                    isVerified = true,
                    features = emptyList(),
                    hardwareIspEnhancements = listOf("Standard Universal ISP"),
                    highResCaptureAvailable = false,
                    hardwareHdrAvailable = false,
                    rawCaptureAvailable = false,
                    limitationsNote = "Standard universal engine operating in pure CTS compliance."
                )
            }
        }
    }
}
