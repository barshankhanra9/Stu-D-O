package com.example.data

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Size
import android.util.SizeF
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import com.example.model.CameraLensOption
import com.example.model.HardwareLensType
import com.example.model.ProCameraCapabilities
import com.example.model.ProCameraSettings
import com.example.model.ProWhitebalanceMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

data class RearPhysicalSubDescriptor(
    val physicalId: String,
    val focalLengthMm: Float?,
    val sensorPhysicalSizeMm: SizeF?,
    val pixelArraySize: Size?,
    val minimumFocusDistance: Float
)

data class RearCameraHardwareDescriptor(
    val cameraId: String,
    val isLogicalMultiCamera: Boolean,
    val focalLengthMm: Float?,
    val sensorPhysicalSizeMm: SizeF?,
    val pixelArraySize: Size?,
    val minimumFocusDistance: Float,
    val minZoomRatio: Float,
    val maxZoomRatio: Float,
    val physicalSubCameras: List<RearPhysicalSubDescriptor> = emptyList(),
    val isBackwardCompatible: Boolean = true
)

data class RearLensDiscoveryResult(
    val lenses: List<CameraLensOption>,
    val overallMinZoom: Float,
    val overallMaxZoom: Float
)

data class ZoomRoutingDecision(
    val targetLens: CameraLensOption,
    val hardwareZoomRatio: Float,
    val displayZoomRatio: Float,
    val requiresCameraRebind: Boolean
)

object MultiCameraLensManager {

    private const val FULL_FRAME_DIAGONAL_MM = 43.266615f

    @OptIn(ExperimentalCamera2Interop::class)
    fun inspectRearCameras(
        cameraProvider: ProcessCameraProvider,
        primaryBackCameraInfo: CameraInfo?,
        cameraManager: CameraManager?
    ): List<RearCameraHardwareDescriptor> {
        val backInfos = try {
            cameraProvider.availableCameraInfos.filter { info ->
                try {
                    info.lensFacing == CameraSelector.LENS_FACING_BACK
                } catch (_: Throwable) {
                    false
                }
            }
        } catch (_: Throwable) {
            listOfNotNull(primaryBackCameraInfo)
        }

        val uniqueInfos = if (primaryBackCameraInfo != null && backInfos.none { it == primaryBackCameraInfo }) {
            listOf(primaryBackCameraInfo) + backInfos
        } else {
            backInfos
        }

        val seenCameraIds = HashSet<String>()
        val descriptors = mutableListOf<RearCameraHardwareDescriptor>()

        for (info in uniqueInfos) {
            val cam2Info = try {
                Camera2CameraInfo.from(info)
            } catch (_: Throwable) {
                null
            } ?: continue

            val camId = try {
                cam2Info.cameraId
            } catch (_: Throwable) {
                null
            } ?: continue

            if (!seenCameraIds.add(camId)) continue

            val chars = try {
                cameraManager?.getCameraCharacteristics(camId)
            } catch (_: Throwable) {
                null
            }

            val caps = getCharacteristicCompat(cam2Info, chars, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val isBackwardCompatible = caps == null || caps.isEmpty() ||
                caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)
            if (!isBackwardCompatible) continue

            val isLogicalMultiCam = caps?.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
            ) == true

            val focalLengths = getCharacteristicCompat(
                cam2Info,
                chars,
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            )
            val primaryFocal = focalLengths?.firstOrNull { it.isFinite() && it > 0f }

            val sensorSize = getCharacteristicCompat(
                cam2Info,
                chars,
                CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
            )
            val pixelArray = getCharacteristicCompat(
                cam2Info,
                chars,
                CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE
            )
            val minFocusDist = getCharacteristicCompat(
                cam2Info,
                chars,
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
            ) ?: 0f

            val zoomState = info.zoomState.value
            val minZoom = (zoomState?.minZoomRatio ?: 1.0f).takeIf { it.isFinite() && it > 0f } ?: 1.0f
            val maxZoom = (zoomState?.maxZoomRatio ?: 1.0f)
                .takeIf { it.isFinite() && it >= minZoom } ?: minZoom

            val subDescriptors = mutableListOf<RearPhysicalSubDescriptor>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && chars != null && cameraManager != null) {
                val physIds = try {
                    chars.physicalCameraIds ?: emptySet()
                } catch (_: Throwable) {
                    emptySet()
                }
                for (physId in physIds) {
                    try {
                        val pChars = cameraManager.getCameraCharacteristics(physId)
                        val pFocal = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            ?.firstOrNull { it.isFinite() && it > 0f }
                        val pSensor = pChars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                        val pPixels = pChars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                        val pMinFocus = pChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                        subDescriptors.add(
                            RearPhysicalSubDescriptor(
                                physicalId = physId,
                                focalLengthMm = pFocal,
                                sensorPhysicalSizeMm = pSensor,
                                pixelArraySize = pPixels,
                                minimumFocusDistance = pMinFocus
                            )
                        )
                    } catch (_: Throwable) {
                    }
                }
            }

            descriptors.add(
                RearCameraHardwareDescriptor(
                    cameraId = camId,
                    isLogicalMultiCamera = isLogicalMultiCam,
                    focalLengthMm = primaryFocal,
                    sensorPhysicalSizeMm = sensorSize,
                    pixelArraySize = pixelArray,
                    minimumFocusDistance = minFocusDist,
                    minZoomRatio = minZoom,
                    maxZoomRatio = maxZoom,
                    physicalSubCameras = subDescriptors,
                    isBackwardCompatible = isBackwardCompatible
                )
            )
        }

        return descriptors
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun <T> getCharacteristicCompat(
        cam2Info: Camera2CameraInfo,
        chars: CameraCharacteristics?,
        key: CameraCharacteristics.Key<T>
    ): T? {
        val fromCam2 = try {
            cam2Info.getCameraCharacteristic(key)
        } catch (_: Throwable) {
            null
        }
        if (fromCam2 != null) return fromCam2
        return try {
            chars?.get(key)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Discovers all rear camera lenses from logical and separate physical CameraInfo descriptors.
     * Can be tested purely on JVM without Android hardware.
     */
    fun discoverRearLenses(
        descriptors: List<RearCameraHardwareDescriptor>,
        primaryCameraId: String? = null
    ): RearLensDiscoveryResult {
        if (descriptors.isEmpty()) {
            val fallback = CameraLensOption(
                id = "lens_main_0",
                label = "1",
                lensType = HardwareLensType.MAIN,
                cameraId = primaryCameraId ?: "0",
                displayMagnification = 1.0f,
                hardwareMinZoom = 1.0f,
                hardwareMaxZoom = 8.0f,
                baseHardwareScale = 1.0f,
                minDisplayZoom = 1.0f,
                maxDisplayZoom = 8.0f,
                isSeparatePhysicalCamera = false,
                isManualFocusSupported = true
            )
            return RearLensDiscoveryResult(
                lenses = listOf(fallback),
                overallMinZoom = 1.0f,
                overallMaxZoom = 8.0f
            )
        }

        val primary = descriptors.firstOrNull { it.cameraId == primaryCameraId }
            ?: descriptors.firstOrNull { it.isLogicalMultiCamera }
            ?: descriptors.first()

        val primaryFocal = primary.focalLengthMm
        val primaryEquivFocal = computeEquivFocalLength(primaryFocal, primary.sensorPhysicalSizeMm)

        val discovered = mutableListOf<CameraLensOption>()
        var hasUltraWide = false

        // 1. Inspect secondary physical CameraInfo entries exposed separately by OEM HAL
        val secondaryDescriptors = descriptors.filter { it.cameraId != primary.cameraId }
        val teleCandidates = mutableListOf<CameraLensOption>()
        var separateUwCandidate: CameraLensOption? = null
        var macroCandidate: CameraLensOption? = null

        for (desc in secondaryDescriptors) {
            val pixelCount = desc.pixelArraySize?.let { it.width.toLong() * it.height.toLong() } ?: 0L
            val ratio = computeMagnificationRatio(
                candidateFocal = desc.focalLengthMm,
                candidateSensorSize = desc.sensorPhysicalSizeMm,
                primaryFocal = primaryFocal,
                primarySensorSize = primary.sensorPhysicalSizeMm,
                primaryEquiv = primaryEquivFocal
            )

            val isLikelyDedicatedMacro = isDedicatedMacroSensor(
                pixelCount = pixelCount,
                minFocusDistance = desc.minimumFocusDistance,
                magnificationRatio = ratio,
                primaryMinZoom = primary.minZoomRatio
            )

            if (isLikelyDedicatedMacro && macroCandidate == null) {
                macroCandidate = CameraLensOption(
                    id = "lens_macro_${desc.cameraId}",
                    label = "Macro",
                    lensType = HardwareLensType.MACRO,
                    cameraId = desc.cameraId,
                    displayMagnification = 1.0f,
                    hardwareMinZoom = desc.minZoomRatio.coerceAtLeast(1.0f),
                    hardwareMaxZoom = desc.maxZoomRatio.coerceAtLeast(desc.minZoomRatio).coerceAtMost(4.0f),
                    baseHardwareScale = 1.0f,
                    minDisplayZoom = desc.minZoomRatio.coerceAtLeast(1.0f),
                    maxDisplayZoom = desc.maxZoomRatio.coerceAtLeast(desc.minZoomRatio).coerceAtMost(4.0f),
                    focalLengthMm = desc.focalLengthMm,
                    isSeparatePhysicalCamera = true,
                    isManualFocusSupported = desc.minimumFocusDistance > 0f,
                    minimumFocusDistance = desc.minimumFocusDistance
                )
                continue
            }

            if (ratio != null && ratio < 0.80f && separateUwCandidate == null) {
                val snappedUw = snapUltraWideMagnification(ratio)
                separateUwCandidate = CameraLensOption(
                    id = "lens_uw_${desc.cameraId}",
                    label = formatPresetLabel(snappedUw),
                    lensType = HardwareLensType.ULTRA_WIDE,
                    cameraId = desc.cameraId,
                    displayMagnification = snappedUw,
                    hardwareMinZoom = desc.minZoomRatio.coerceAtLeast(1.0f),
                    hardwareMaxZoom = desc.maxZoomRatio.coerceAtLeast(desc.minZoomRatio),
                    baseHardwareScale = snappedUw,
                    minDisplayZoom = desc.minZoomRatio.coerceAtLeast(1.0f) * snappedUw,
                    maxDisplayZoom = desc.maxZoomRatio.coerceAtLeast(desc.minZoomRatio) * snappedUw,
                    focalLengthMm = desc.focalLengthMm,
                    isSeparatePhysicalCamera = true,
                    isManualFocusSupported = desc.minimumFocusDistance > 0f,
                    minimumFocusDistance = desc.minimumFocusDistance
                )
                continue
            }

            if (ratio != null && ratio >= 1.55f) {
                val snappedTele = snapTeleMagnification(ratio)
                teleCandidates.add(
                    CameraLensOption(
                        id = "lens_tele_${desc.cameraId}_${snappedTele}",
                        label = formatPresetLabel(snappedTele),
                        lensType = HardwareLensType.TELEPHOTO,
                        cameraId = desc.cameraId,
                        displayMagnification = snappedTele,
                        hardwareMinZoom = desc.minZoomRatio.coerceAtLeast(1.0f),
                        hardwareMaxZoom = desc.maxZoomRatio.coerceAtLeast(desc.minZoomRatio),
                        baseHardwareScale = snappedTele,
                        minDisplayZoom = desc.minZoomRatio.coerceAtLeast(1.0f) * snappedTele,
                        maxDisplayZoom = desc.maxZoomRatio.coerceAtLeast(desc.minZoomRatio) * snappedTele,
                        focalLengthMm = desc.focalLengthMm,
                        isSeparatePhysicalCamera = true,
                        isManualFocusSupported = desc.minimumFocusDistance > 0f,
                        minimumFocusDistance = desc.minimumFocusDistance
                    )
                )
            }
        }

        // 2. Check logical multi-camera Ultra-Wide (minZoomRatio < 1.0f)
        if (primary.minZoomRatio < 0.92f) {
            val snappedLogicalUw = snapUltraWideMagnification(primary.minZoomRatio)
            val logicalUwSub = primary.physicalSubCameras.firstOrNull { sub ->
                val subRatio = computeMagnificationRatio(
                    candidateFocal = sub.focalLengthMm,
                    candidateSensorSize = sub.sensorPhysicalSizeMm,
                    primaryFocal = primaryFocal,
                    primarySensorSize = primary.sensorPhysicalSizeMm,
                    primaryEquiv = primaryEquivFocal
                )
                subRatio != null && subRatio < 0.80f
            }
            val uwFocal = logicalUwSub?.focalLengthMm ?: primaryFocal
            val uwMinFocus = logicalUwSub?.minimumFocusDistance ?: primary.minimumFocusDistance
            discovered.add(
                CameraLensOption(
                    id = "lens_uw_logical_${primary.cameraId}",
                    label = formatPresetLabel(snappedLogicalUw),
                    lensType = HardwareLensType.ULTRA_WIDE,
                    cameraId = primary.cameraId,
                    displayMagnification = snappedLogicalUw,
                    hardwareMinZoom = primary.minZoomRatio,
                    hardwareMaxZoom = primary.maxZoomRatio,
                    baseHardwareScale = 1.0f,
                    minDisplayZoom = primary.minZoomRatio,
                    maxDisplayZoom = primary.maxZoomRatio,
                    focalLengthMm = uwFocal,
                    isSeparatePhysicalCamera = false,
                    isManualFocusSupported = uwMinFocus > 0f,
                    minimumFocusDistance = uwMinFocus
                )
            )
            hasUltraWide = true
        } else if (separateUwCandidate != null) {
            discovered.add(separateUwCandidate)
            hasUltraWide = true
        }

        // 3. Main Wide (1x)
        val mainHwMin = if (hasUltraWide && primary.minZoomRatio >= 0.95f) 1.0f else primary.minZoomRatio.coerceAtMost(1.0f)
        discovered.add(
            CameraLensOption(
                id = "lens_main_${primary.cameraId}",
                label = "1",
                lensType = HardwareLensType.MAIN,
                cameraId = primary.cameraId,
                displayMagnification = 1.0f,
                hardwareMinZoom = mainHwMin,
                hardwareMaxZoom = primary.maxZoomRatio.coerceAtLeast(1.0f),
                baseHardwareScale = 1.0f,
                minDisplayZoom = mainHwMin,
                maxDisplayZoom = primary.maxZoomRatio.coerceAtLeast(1.0f),
                focalLengthMm = primaryFocal,
                isSeparatePhysicalCamera = false,
                isManualFocusSupported = primary.minimumFocusDistance > 0f,
                minimumFocusDistance = primary.minimumFocusDistance
            )
        )

        // 4. Telephoto lenses: combine separate physical telephotos + logical optical sub-cameras + 2x crop preset
        val allTeleList = teleCandidates.toMutableList()

        // Logical optical telephoto sub-sensors exposed through primary logical camera zoom
        for (sub in primary.physicalSubCameras) {
            val subRatio = computeMagnificationRatio(
                candidateFocal = sub.focalLengthMm,
                candidateSensorSize = sub.sensorPhysicalSizeMm,
                primaryFocal = primaryFocal,
                primarySensorSize = primary.sensorPhysicalSizeMm,
                primaryEquiv = primaryEquivFocal
            ) ?: continue

            if (subRatio >= 1.55f && primary.maxZoomRatio >= (subRatio * 0.92f)) {
                val snapped = snapTeleMagnification(subRatio)
                val alreadyExists = allTeleList.any { abs(it.displayMagnification - snapped) < 0.35f }
                if (!alreadyExists) {
                    allTeleList.add(
                        CameraLensOption(
                            id = "lens_tele_logical_${primary.cameraId}_${snapped}",
                            label = formatPresetLabel(snapped),
                            lensType = HardwareLensType.TELEPHOTO,
                            cameraId = primary.cameraId,
                            displayMagnification = snapped,
                            hardwareMinZoom = primary.minZoomRatio,
                            hardwareMaxZoom = primary.maxZoomRatio,
                            baseHardwareScale = 1.0f,
                            minDisplayZoom = primary.minZoomRatio,
                            maxDisplayZoom = primary.maxZoomRatio,
                            focalLengthMm = sub.focalLengthMm,
                            isSeparatePhysicalCamera = false,
                            isCropPreset = false,
                            isManualFocusSupported = sub.minimumFocusDistance > 0f,
                            minimumFocusDistance = sub.minimumFocusDistance
                        )
                    )
                }
            }
        }

        // Add 2x crop preset if main/logical camera supports >= 2.0f zoom and no ~2x lens exists yet
        val hasTwoX = allTeleList.any { abs(it.displayMagnification - 2.0f) < 0.35f }
        if (!hasTwoX && primary.maxZoomRatio >= 2.0f) {
            allTeleList.add(
                CameraLensOption(
                    id = "lens_crop_2x_${primary.cameraId}",
                    label = "2",
                    lensType = HardwareLensType.TELEPHOTO,
                    cameraId = primary.cameraId,
                    displayMagnification = 2.0f,
                    hardwareMinZoom = primary.minZoomRatio,
                    hardwareMaxZoom = primary.maxZoomRatio,
                    baseHardwareScale = 1.0f,
                    minDisplayZoom = primary.minZoomRatio,
                    maxDisplayZoom = primary.maxZoomRatio,
                    focalLengthMm = primaryFocal,
                    isSeparatePhysicalCamera = false,
                    isCropPreset = true,
                    isManualFocusSupported = primary.minimumFocusDistance > 0f,
                    minimumFocusDistance = primary.minimumFocusDistance
                )
            )
        }

        // If no optical telephoto (>= 2.4x) exists and primary camera reaches >= 5x, add 5x preset matching legacy curve
        val hasOpticalTele = allTeleList.any { !it.isCropPreset && it.displayMagnification >= 2.4f }
        val hasFiveXOrHigher = allTeleList.any { it.displayMagnification >= 4.0f }
        if (!hasOpticalTele && !hasFiveXOrHigher && primary.maxZoomRatio >= 5.0f) {
            allTeleList.add(
                CameraLensOption(
                    id = "lens_crop_5x_${primary.cameraId}",
                    label = "5",
                    lensType = HardwareLensType.TELEPHOTO,
                    cameraId = primary.cameraId,
                    displayMagnification = 5.0f,
                    hardwareMinZoom = primary.minZoomRatio,
                    hardwareMaxZoom = primary.maxZoomRatio,
                    baseHardwareScale = 1.0f,
                    minDisplayZoom = primary.minZoomRatio,
                    maxDisplayZoom = primary.maxZoomRatio,
                    focalLengthMm = primaryFocal,
                    isSeparatePhysicalCamera = false,
                    isCropPreset = true,
                    isManualFocusSupported = primary.minimumFocusDistance > 0f,
                    minimumFocusDistance = primary.minimumFocusDistance
                )
            )
        }

        val sortedTeles = allTeleList.sortedBy { it.displayMagnification }
        discovered.addAll(sortedTeles)

        // 5. Optional Macro physical camera
        if (macroCandidate != null) {
            discovered.add(macroCandidate)
        }

        val nonMacroLenses = discovered.filter { it.lensType != HardwareLensType.MACRO }
        val overallMin = nonMacroLenses.minOfOrNull { it.minDisplayZoom } ?: primary.minZoomRatio
        val overallMax = nonMacroLenses.maxOfOrNull { it.maxDisplayZoom } ?: primary.maxZoomRatio

        return RearLensDiscoveryResult(
            lenses = discovered,
            overallMinZoom = overallMin.coerceAtLeast(0.4f),
            overallMaxZoom = overallMax.coerceAtLeast(overallMin)
        )
    }

    /**
     * Resolves how a requested target display zoom should be executed across logical zoom and
     * separate physical cameras (with hysteresis to prevent rapid rebind chatter).
     */
    fun resolveZoomTarget(
        lenses: List<CameraLensOption>,
        currentActiveLens: CameraLensOption?,
        targetDisplayZoom: Float,
        isRecordingVideo: Boolean
    ): ZoomRoutingDecision {
        val active = currentActiveLens
            ?: lenses.firstOrNull { it.lensType == HardwareLensType.MAIN }
            ?: lenses.firstOrNull()
            ?: CameraLensOption(
                id = "lens_main_0",
                label = "1",
                lensType = HardwareLensType.MAIN,
                cameraId = "0",
                displayMagnification = 1.0f
            )

        // While recording video, Android CameraX cannot rebind physical CameraInfo without stopping recording.
        if (isRecordingVideo) {
            val clampedDisplay = targetDisplayZoom.coerceIn(active.minDisplayZoom, active.maxDisplayZoom)
            return ZoomRoutingDecision(
                targetLens = active,
                hardwareZoomRatio = active.toHardwareZoom(clampedDisplay),
                displayZoomRatio = clampedDisplay,
                requiresCameraRebind = false
            )
        }

        // Macro stays isolated inside Macro zoom range until user explicitly taps another lens pill.
        if (active.lensType == HardwareLensType.MACRO) {
            val clampedDisplay = targetDisplayZoom.coerceIn(active.minDisplayZoom, active.maxDisplayZoom)
            return ZoomRoutingDecision(
                targetLens = active,
                hardwareZoomRatio = active.toHardwareZoom(clampedDisplay),
                displayZoomRatio = clampedDisplay,
                requiresCameraRebind = false
            )
        }

        val nonMacro = lenses.filter { it.lensType != HardwareLensType.MACRO }
            .sortedBy { it.displayMagnification }
        if (nonMacro.isEmpty()) {
            val clamped = targetDisplayZoom.coerceIn(active.minDisplayZoom, active.maxDisplayZoom)
            return ZoomRoutingDecision(
                targetLens = active,
                hardwareZoomRatio = active.toHardwareZoom(clamped),
                displayZoomRatio = clamped,
                requiresCameraRebind = false
            )
        }

        val mainLens = nonMacro.firstOrNull { it.lensType == HardwareLensType.MAIN } ?: active
        val uwLens = nonMacro.firstOrNull { it.lensType == HardwareLensType.ULTRA_WIDE }
        val separateTeles = nonMacro.filter {
            it.lensType == HardwareLensType.TELEPHOTO && it.isSeparatePhysicalCamera
        }.sortedBy { it.displayMagnification }

        // Select which physical camera owns targetDisplayZoom
        val chosenCameraLens: CameraLensOption = run {
            // Check Ultra-Wide transition
            if (uwLens != null && uwLens.isSeparatePhysicalCamera) {
                val uwExitThreshold = 0.99f
                val uwEnterThreshold = 0.95f
                if (active.cameraId == uwLens.cameraId && targetDisplayZoom < uwExitThreshold) {
                    return@run uwLens
                }
                if (targetDisplayZoom < uwEnterThreshold) {
                    return@run uwLens
                }
            }

            // Check separate physical Telephoto transitions (highest matching tier wins)
            val matchingTele = separateTeles.lastOrNull { tele ->
                val threshold = if (active.cameraId == tele.cameraId) {
                    (tele.displayMagnification - 0.15f).coerceAtLeast(1.1f)
                } else {
                    tele.displayMagnification
                }
                targetDisplayZoom >= threshold
            }
            if (matchingTele != null) {
                return@run matchingTele
            }

            mainLens
        }

        // Pick the closest preset badge on the chosen camera for chip highlight parity
        val chosenPresetForBadge = nonMacro
            .filter { it.cameraId == chosenCameraLens.cameraId }
            .lastOrNull { preset ->
                if (preset.lensType == HardwareLensType.ULTRA_WIDE) {
                    targetDisplayZoom < 0.95f
                } else {
                    targetDisplayZoom + 0.04f >= preset.displayMagnification
                }
            } ?: chosenCameraLens

        val clampedDisplay = targetDisplayZoom.coerceIn(
            chosenPresetForBadge.minDisplayZoom,
            chosenPresetForBadge.maxDisplayZoom
        )
        val requiresRebind = chosenPresetForBadge.cameraId != active.cameraId

        return ZoomRoutingDecision(
            targetLens = chosenPresetForBadge,
            hardwareZoomRatio = chosenPresetForBadge.toHardwareZoom(clampedDisplay),
            displayZoomRatio = clampedDisplay,
            requiresCameraRebind = requiresRebind
        )
    }

    /**
     * Reconciles current Pro Mode manual controls against newly active lens hardware limits.
     * Gracefully disables manual focus when switching onto fixed-focus sensors (e.g. Ultra-Wide).
     */
    fun reconcileProSettings(
        currentSettings: ProCameraSettings,
        newCapabilities: ProCameraCapabilities
    ): ProCameraSettings {
        val reconciledManualFocus = if (newCapabilities.isManualFocusSupported) {
            currentSettings.isManualFocus
        } else {
            false
        }
        val reconciledFocusDist = if (reconciledManualFocus) {
            currentSettings.focusDistance?.coerceIn(
                0f,
                newCapabilities.maxFocusDistance.coerceAtLeast(0.01f)
            )
        } else {
            null
        }

        val reconciledIso = currentSettings.iso?.let { currentIso ->
            val clamped = currentIso.coerceIn(newCapabilities.minIso, newCapabilities.maxIso)
            newCapabilities.supportedIsos.minByOrNull { abs(it - clamped) } ?: clamped
        }

        val reconciledShutter = currentSettings.shutterSpeedNanos?.let { currentShutter ->
            currentShutter.coerceIn(
                newCapabilities.minShutterNanos,
                newCapabilities.maxShutterNanos.coerceAtLeast(newCapabilities.minShutterNanos)
            )
        }

        val reconciledEv = currentSettings.evCompensation.coerceIn(
            newCapabilities.minEv,
            newCapabilities.maxEv.coerceAtLeast(newCapabilities.minEv)
        )

        val reconciledWb = if (newCapabilities.supportedAwbModes.contains(currentSettings.whiteBalance)) {
            currentSettings.whiteBalance
        } else {
            ProWhitebalanceMode.AUTO
        }

        return currentSettings.copy(
            iso = reconciledIso,
            shutterSpeedNanos = reconciledShutter,
            evCompensation = reconciledEv,
            focusDistance = reconciledFocusDist,
            whiteBalance = reconciledWb,
            isManualFocus = reconciledManualFocus
        )
    }

    private fun isDedicatedMacroSensor(
        pixelCount: Long,
        minFocusDistance: Float,
        magnificationRatio: Float?,
        primaryMinZoom: Float
    ): Boolean {
        val hasCloseFocus = minFocusDistance >= 8.0f
        val isLowResAuxSensor = pixelCount in 1L..5_500_000L
        if (isLowResAuxSensor && (hasCloseFocus || (magnificationRatio != null && magnificationRatio >= 0.72f))) {
            return true
        }
        if (hasCloseFocus && magnificationRatio != null && magnificationRatio in 0.75f..1.50f) {
            return true
        }
        // If logical UW already exists (<0.92x), a separate small-focus close sensor isn't UW
        if (primaryMinZoom < 0.92f && isLowResAuxSensor && minFocusDistance > 0f) {
            return true
        }
        return false
    }

    private fun computeEquivFocalLength(focalLengthMm: Float?, sensorSizeMm: SizeF?): Float? {
        if (focalLengthMm == null || !focalLengthMm.isFinite() || focalLengthMm <= 0f) return null
        if (sensorSizeMm == null || sensorSizeMm.width <= 0f || sensorSizeMm.height <= 0f) return null
        val diag = hypot(sensorSizeMm.width, sensorSizeMm.height)
        if (!diag.isFinite() || diag <= 0f) return null
        return focalLengthMm * (FULL_FRAME_DIAGONAL_MM / diag)
    }

    private fun computeMagnificationRatio(
        candidateFocal: Float?,
        candidateSensorSize: SizeF?,
        primaryFocal: Float?,
        primarySensorSize: SizeF?,
        primaryEquiv: Float?
    ): Float? {
        val candidateEquiv = computeEquivFocalLength(candidateFocal, candidateSensorSize)
        if (candidateEquiv != null && primaryEquiv != null && primaryEquiv > 0f) {
            return candidateEquiv / primaryEquiv
        }
        if (candidateFocal != null && primaryFocal != null && primaryFocal > 0f) {
            return candidateFocal / primaryFocal
        }
        return null
    }

    private fun snapUltraWideMagnification(rawRatio: Float): Float {
        return when {
            rawRatio <= 0.54f -> 0.5f
            rawRatio <= 0.68f -> 0.6f
            rawRatio <= 0.75f -> 0.7f
            else -> ((rawRatio * 10f).roundToInt() / 10f).coerceIn(0.4f, 0.8f)
        }
    }

    private fun snapTeleMagnification(rawRatio: Float): Float {
        val intTargets = listOf(2f, 3f, 5f, 10f)
        for (target in intTargets) {
            if (abs(rawRatio - target) <= 0.35f) return target
        }
        return ((rawRatio * 10f).roundToInt() / 10f).coerceAtLeast(1.5f)
    }

    @SuppressLint("DefaultLocale")
    fun formatPresetLabel(zoom: Float): String {
        val rounded = (zoom * 10f).roundToInt() / 10f
        return if (abs(rounded - rounded.roundToInt()) < 0.05f) {
            rounded.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }
    }
}
