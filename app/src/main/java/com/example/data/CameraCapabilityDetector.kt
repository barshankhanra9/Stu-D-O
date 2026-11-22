package com.example.data

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Range
import android.util.Size
import com.example.model.VideoFpsOption
import com.example.model.VideoQualityOption
import java.util.Locale

data class PhysicalCameraSubSensor(
    val physicalId: String,
    val pixelArraySize: Size?,
    val activeArraySize: Rect?,
    val standardJpegSizes: List<Size>,
    val highResolutionJpegSizes: List<Size>,
    val maxResolutionJpegSizes: List<Size>,
    val standardRawSizes: List<Size>,
    val maxResolutionRawSizes: List<Size>,
    val isUhrSupported: Boolean,
    val availableFocalLengths: List<Float> = emptyList(),
    val isDepthOutputSupported: Boolean = false,
    val depth16Sizes: List<Size> = emptyList(),
    val depthPointCloudSizes: List<Size> = emptyList()
) {
    val primaryFocalLengthMm: Float?
        get() = availableFocalLengths.firstOrNull { it.isFinite() && it > 0f }
}

data class CameraSensorCapability(
    val cameraId: String,
    val facing: String,
    val hardwareLevel: String,
    val pixelArraySize: Size?,
    val activeArraySize: Rect? = null,
    val isUltraHighResolutionSupported: Boolean,
    val standardJpegSizes: List<Size>,
    val maxResolutionJpegSizes: List<Size>,
    val highResolutionJpegSizes: List<Size>,
    val standardRawSizes: List<Size>,
    val maxResolutionRawSizes: List<Size>,
    val isRawSupported: Boolean,
    val physicalCameraIds: List<String>,
    val physicalCameras: List<PhysicalCameraSubSensor> = emptyList(),
    val maxJpegMegapixels: Double,
    val standardMaxJpegMegapixels: Double,
    val recommendedUhrSize: Size?,
    val hasMaxResolutionMap: Boolean = false,
    val capabilitiesList: List<String> = emptyList(),
    val highestGenuineJpegSize: Size = standardJpegSizes.firstOrNull() ?: Size(0, 0),
    val isHighestGenuineStallStream: Boolean = false,
    val allGenuineJpegSizes: List<Size> = emptyList(),
    val proCapabilities: com.example.model.ProCameraCapabilities = com.example.model.ProCameraCapabilities(),
    val supportedVideoQualities: List<VideoQualityOption> = emptyList(),
    val supportedVideoFps: List<VideoFpsOption> = emptyList(),
    val supportedFpsPerQuality: Map<VideoQualityOption, List<VideoFpsOption>> = emptyMap(),
    val supportedVideoCodecs: List<com.example.model.VideoCodecOption> = emptyList(),
    val supportedVideoAspectRatios: List<com.example.model.CaptureAspectRatio> = emptyList(),
    val isVideoRecordingSupported: Boolean = false,
    val isVideoStabilizationSupported: Boolean = false,
    val isOisSupported: Boolean = false,
    val targetFpsRanges: List<Range<Int>> = emptyList(),
    val availableFocalLengths: List<Float> = emptyList(),
    val isDepthOutputSupported: Boolean = false,
    val depth16Sizes: List<Size> = emptyList(),
    val depthPointCloudSizes: List<Size> = emptyList()
) {
    val primaryFocalLengthMm: Float?
        get() = availableFocalLengths.firstOrNull { it.isFinite() && it > 0f }

    val maxGenuineJpegMegapixels: Double
        get() = (highestGenuineJpegSize.width.toDouble() * highestGenuineJpegSize.height.toDouble()) / 1_000_000.0

    fun getSupportedFpsForQuality(quality: VideoQualityOption): List<VideoFpsOption> {
        return supportedFpsPerQuality[quality] ?: supportedVideoFps
    }

    fun getDynamicResolutionOptions(): List<com.example.model.DynamicResolutionOption> {
        val options = mutableListOf<com.example.model.DynamicResolutionOption>()
        val allSizes = (highResolutionJpegSizes + standardJpegSizes).distinct()
            .sortedByDescending { it.width.toLong() * it.height.toLong() }

        if (allSizes.isEmpty()) {
            val fallback = pixelArraySize?.takeIf { it.width > 0 && it.height > 0 } ?: return emptyList()
            return listOf(
                com.example.model.DynamicResolutionOption(
                    resolution = com.example.model.PhotoResolution.MAX,
                    title = "Maximum Quality",
                    size = fallback,
                    description = "Hardware sensor native output"
                )
            )
        }

        // 1. ULTRA_RES: Only if hardware HAL advertises unbinned mode (API 31+ UHR)
        if (isUltraHighResolutionSupported && recommendedUhrSize != null &&
            maxResolutionJpegSizes.contains(recommendedUhrSize)) {
            val uhrMp = (recommendedUhrSize.width.toDouble() * recommendedUhrSize.height.toDouble()) / 1_000_000.0
            options.add(
                com.example.model.DynamicResolutionOption(
                    resolution = com.example.model.PhotoResolution.ULTRA_RES,
                    title = "Ultra High Resolution",
                    size = recommendedUhrSize,
                    isStallStream = false,
                    description = "Hardware unbinned sensor mode (${String.format(Locale.US, "%.1f", uhrMp)} MP)"
                )
            )
        }

        // 2. MAX (Maximum Quality): Highest genuine supported resolution
        val maxSize = allSizes.first()
        val isMaxStall = highResolutionJpegSizes.contains(maxSize) && !standardJpegSizes.contains(maxSize)
        val maxMp = (maxSize.width.toDouble() * maxSize.height.toDouble()) / 1_000_000.0
        val maxAspect = if (Math.abs((maxSize.width.toDouble() / maxSize.height) - (4.0 / 3.0)) < 0.05) "4:3 Native" else "${maxSize.width}:${maxSize.height}"
        val stallNote = if (isMaxStall) " (High-res stall stream)" else ""
        options.add(
            com.example.model.DynamicResolutionOption(
                resolution = com.example.model.PhotoResolution.MAX,
                title = "Maximum Quality",
                size = maxSize,
                isStallStream = isMaxStall,
                description = "$maxAspect active-array sensor output for maximum detail and clarity$stallNote"
            )
        )

        // 3. HIGH_12MP (High Quality): Distinct second tier (~50-85% area or distinct intermediate size)
        val distinctSizesBelowMax = allSizes.filter { it.width * it.height < maxSize.width * maxSize.height }
        val highSize = distinctSizesBelowMax.firstOrNull { size ->
            val areaRatio = (size.width.toDouble() * size.height) / (maxSize.width.toDouble() * maxSize.height)
            areaRatio in 0.5..0.85
        } ?: distinctSizesBelowMax.firstOrNull() ?: maxSize

        if (highSize != maxSize) {
            val highMp = (highSize.width.toDouble() * highSize.height.toDouble()) / 1_000_000.0
            options.add(
                com.example.model.DynamicResolutionOption(
                    resolution = com.example.model.PhotoResolution.HIGH_12MP,
                    title = "High Quality",
                    size = highSize,
                    isStallStream = highResolutionJpegSizes.contains(highSize),
                    description = "Balanced sharpness (${String.format(Locale.US, "%.1f", highMp)} MP) with standard capture latency"
                )
            )
        }

        // 4. STANDARD_8MP (Standard / Efficient): Distinct third tier (~2-4 MP or ~25-45% area)
        val standardSize = distinctSizesBelowMax.filter { it.width * it.height < highSize.width * highSize.height }
            .minByOrNull { size ->
                val mp = (size.width.toDouble() * size.height) / 1_000_000.0
                Math.abs(mp - 2.5)
            } ?: distinctSizesBelowMax.lastOrNull() ?: highSize

        if (standardSize != highSize && standardSize != maxSize) {
            val stdMp = (standardSize.width.toDouble() * standardSize.height.toDouble()) / 1_000_000.0
            options.add(
                com.example.model.DynamicResolutionOption(
                    resolution = com.example.model.PhotoResolution.STANDARD_8MP,
                    title = "Standard (Efficient)",
                    size = standardSize,
                    isStallStream = highResolutionJpegSizes.contains(standardSize),
                    description = "Compact ${String.format(Locale.US, "%.1f", stdMp)} MP output for fast processing and minimal storage"
                )
            )
        }

        return options
    }
}

data class ResolutionCaptureRecord(
    val selectedResolution: com.example.model.PhotoResolution,
    val selectedResolutionName: String,
    val configuredSize: Size?,
    val savedWidth: Int,
    val savedHeight: Int,
    val savedMegapixels: Double,
    val fileSizeBytes: Long,
    val isSuccess: Boolean,
    val capturePath: String,
    val fallbackDetails: String?,
    val timestamp: Long = System.currentTimeMillis()
)

data class CaptureTestResult(
    val isSuccess: Boolean,
    val details: String,
    val timestamp: Long = System.currentTimeMillis()
)

class CameraCapabilityDetector(private val context: Context) {

    private val captureTestResults = java.util.concurrent.ConcurrentHashMap<String, CaptureTestResult>()
    private val resolutionCaptureRecords = java.util.concurrent.CopyOnWriteArrayList<ResolutionCaptureRecord>()

    fun recordCaptureTestResult(cameraId: String, isSuccess: Boolean, details: String) {
        captureTestResults[cameraId] = CaptureTestResult(isSuccess, details)
    }

    fun getCaptureTestResult(cameraId: String): CaptureTestResult? = captureTestResults[cameraId]

    fun recordResolutionCapture(record: ResolutionCaptureRecord) {
        resolutionCaptureRecords.add(record)
    }

    fun recordResolutionCapture(
        cameraId: String,
        lensFacing: String,
        requestedResolution: com.example.model.PhotoResolution,
        targetDimensions: Size?,
        actualDimensions: Size,
        fileSizeBytes: Long,
        captureEngine: String,
        isFallback: Boolean
    ) {
        val mp = (actualDimensions.width.toDouble() * actualDimensions.height.toDouble()) / 1_000_000.0
        val fallbackNote = if (isFallback) "Target was ${targetDimensions?.width} × ${targetDimensions?.height}, actual output is ${actualDimensions.width} × ${actualDimensions.height}" else null
        recordResolutionCapture(
            ResolutionCaptureRecord(
                selectedResolution = requestedResolution,
                selectedResolutionName = requestedResolution.displayName,
                configuredSize = targetDimensions,
                savedWidth = actualDimensions.width,
                savedHeight = actualDimensions.height,
                savedMegapixels = mp,
                fileSizeBytes = fileSizeBytes,
                isSuccess = true,
                capturePath = "$captureEngine (Camera $cameraId, $lensFacing)",
                fallbackDetails = fallbackNote
            )
        )
    }

    fun getResolutionCaptureRecords(): List<ResolutionCaptureRecord> = resolutionCaptureRecords

    private val cameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    @Volatile
    private var cachedCameras: List<CameraSensorCapability>? = null

    fun detectAllCameras(forceRefresh: Boolean = false): List<CameraSensorCapability> {
        if (!forceRefresh) {
            cachedCameras?.let { return it }
        }
        val capabilities = mutableListOf<CameraSensorCapability>()
        try {
            val cameraIds = cameraManager.cameraIdList
            for (id in cameraIds) {
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val cap = parseCharacteristics(id, characteristics)
                    capabilities.add(cap)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        val snapshot = capabilities.toList()
        if (snapshot.isNotEmpty()) {
            cachedCameras = snapshot
        }
        return snapshot
    }

    fun getCapabilityForLens(isFrontCamera: Boolean): CameraSensorCapability? {
        val targetFacing = if (isFrontCamera) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        val all = detectAllCameras()
        return all.firstOrNull {
            try {
                val chars = cameraManager.getCameraCharacteristics(it.cameraId)
                chars.get(CameraCharacteristics.LENS_FACING) == targetFacing
            } catch (_: Exception) {
                false
            }
        } ?: all.firstOrNull()
    }

    fun getFocalLengthForCameraId(cameraId: String): Float? {
        return try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull { it.isFinite() && it > 0f }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCharacteristics(id: String, chars: CameraCharacteristics): CameraSensorCapability {
        val facingInt = chars.get(CameraCharacteristics.LENS_FACING) ?: -1
        val facing = when (facingInt) {
            CameraCharacteristics.LENS_FACING_BACK -> "Back (World)"
            CameraCharacteristics.LENS_FACING_FRONT -> "Front (Selfie)"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
            else -> "Unknown"
        }

        val hwLevelInt = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
        val hwLevel = when (hwLevelInt) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN ($hwLevelInt)"
        }

        val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        // Parse capabilities list
        val availableCapabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val capNames = mutableListOf<String>()
        for (cap in availableCapabilities) {
            when (cap) {
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> capNames.add("BACKWARD_COMPATIBLE")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> capNames.add("MANUAL_SENSOR")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> capNames.add("MANUAL_POST_PROCESSING")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> capNames.add("RAW")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> capNames.add("PRIVATE_REPROCESSING")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> capNames.add("READ_SENSOR_SETTINGS")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> capNames.add("BURST_CAPTURE")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> capNames.add("YUV_REPROCESSING")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> capNames.add("DEPTH_OUTPUT")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> capNames.add("CONSTRAINED_HIGH_SPEED_VIDEO")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> capNames.add("MOTION_TRACKING")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> capNames.add("LOGICAL_MULTI_CAMERA")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> capNames.add("MONOCHROME")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> capNames.add("SECURE_IMAGE_DATA")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> capNames.add("SYSTEM_CAMERA")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> capNames.add("OFFLINE_PROCESSING")
                14 -> capNames.add("ULTRA_HIGH_RESOLUTION_SENSOR")
                15 -> capNames.add("REMOSAIC_REPROCESSING")
                else -> capNames.add("CAP_$cap")
            }
        }

        // Check ultra high resolution sensor capability (API 31+)
        val isUhrSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            availableCapabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)
        } else {
            false
        }

        // Standard Stream Configuration Map
        val standardMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val standardJpegSizes = standardMap?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
        val standardRawSizes = standardMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList() ?: emptyList()
        val highResJpegSizes = standardMap?.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
        val depth16Sizes = try {
            standardMap?.getOutputSizes(ImageFormat.DEPTH16)?.toList() ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        val depthPointCloudSizes = try {
            standardMap?.getOutputSizes(ImageFormat.DEPTH_POINT_CLOUD)?.toList() ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        val isDepthOutputSupported = availableCapabilities.contains(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT
        ) || depth16Sizes.isNotEmpty()

        // Maximum Resolution Stream Configuration Map (API 31+)
        val maxResJpegSizes = mutableListOf<Size>()
        val maxResRawSizes = mutableListOf<Size>()
        var hasMaxResMap = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val maxMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            if (maxMap != null) {
                hasMaxResMap = true
                maxMap.getOutputSizes(ImageFormat.JPEG)?.let { maxResJpegSizes.addAll(it) }
                maxMap.getOutputSizes(ImageFormat.RAW_SENSOR)?.let { maxResRawSizes.addAll(it) }
            }
        }

        val isRawSupported = standardRawSizes.isNotEmpty() || maxResRawSizes.isNotEmpty()

        // Physical IDs for logical cameras
        val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                chars.physicalCameraIds?.toList() ?: emptyList()
            } catch (_: Throwable) {
                emptyList()
            }
        } else {
            emptyList()
        }

        // Deep inspection of physical sub-cameras
        val physicalSubSensors = mutableListOf<PhysicalCameraSubSensor>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            for (physId in physicalIds) {
                try {
                    val physChars = cameraManager.getCameraCharacteristics(physId)
                    val pPixelArray = physChars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    val pActiveArray = physChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    val pStandardMap = physChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val pStandardJpeg = pStandardMap?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
                    val pStandardRaw = pStandardMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList() ?: emptyList()
                    val pHighResJpeg = pStandardMap?.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()

                    val pMaxResJpeg = mutableListOf<Size>()
                    val pMaxResRaw = mutableListOf<Size>()
                    var pUhrSupported = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val pCaps = physChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                        pUhrSupported = pCaps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)
                        val pMaxMap = physChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                        pMaxMap?.getOutputSizes(ImageFormat.JPEG)?.let { pMaxResJpeg.addAll(it) }
                        pMaxMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.let { pMaxResRaw.addAll(it) }
                    }

                    val pFocalLengths = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        ?.filter { it.isFinite() && it > 0f } ?: emptyList()
                    val pCapsAll = physChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                    val pDepth16Sizes = try {
                        pStandardMap?.getOutputSizes(ImageFormat.DEPTH16)?.toList() ?: emptyList()
                    } catch (_: Throwable) {
                        emptyList()
                    }
                    val pDepthPointSizes = try {
                        pStandardMap?.getOutputSizes(ImageFormat.DEPTH_POINT_CLOUD)?.toList() ?: emptyList()
                    } catch (_: Throwable) {
                        emptyList()
                    }
                    val pDepthSupported = pCapsAll.contains(
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT
                    ) || pDepth16Sizes.isNotEmpty()

                    physicalSubSensors.add(
                        PhysicalCameraSubSensor(
                            physicalId = physId,
                            pixelArraySize = pPixelArray,
                            activeArraySize = pActiveArray,
                            standardJpegSizes = pStandardJpeg.sortedByDescending { it.width.toLong() * it.height.toLong() },
                            highResolutionJpegSizes = pHighResJpeg.sortedByDescending { it.width.toLong() * it.height.toLong() },
                            maxResolutionJpegSizes = pMaxResJpeg.sortedByDescending { it.width.toLong() * it.height.toLong() },
                            standardRawSizes = pStandardRaw.sortedByDescending { it.width.toLong() * it.height.toLong() },
                            maxResolutionRawSizes = pMaxResRaw.sortedByDescending { it.width.toLong() * it.height.toLong() },
                            isUhrSupported = pUhrSupported,
                            availableFocalLengths = pFocalLengths,
                            isDepthOutputSupported = pDepthSupported,
                            depth16Sizes = pDepth16Sizes.sortedByDescending { it.width.toLong() * it.height.toLong() },
                            depthPointCloudSizes = pDepthPointSizes.sortedByDescending { it.width.toLong() * it.height.toLong() }
                        )
                    )
                } catch (_: Exception) {}
            }
        }

        // Calculate megapixels and highest genuine resolution across ALL supported JPEG outputs
        // (including high-resolution stall stream outputs like 4080x3072 on Moto G34!)
        val allGenuineJpeg = (highResJpegSizes + standardJpegSizes).distinct()
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
        val highestGenuineJpeg = allGenuineJpeg.firstOrNull() ?: pixelArray ?: Size(0, 0)
        val isHighestStall = highResJpegSizes.contains(highestGenuineJpeg) && !standardJpegSizes.contains(highestGenuineJpeg)
        val highestMegapixels = (highestGenuineJpeg.width.toDouble() * highestGenuineJpeg.height.toDouble()) / 1_000_000.0

        val maxResJpeg = maxResJpegSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
        val maxMegapixels = if (maxResJpeg != null && isUhrSupported) {
            (maxResJpeg.width.toDouble() * maxResJpeg.height.toDouble()) / 1_000_000.0
        } else highestMegapixels

        val recommendedUhr = if (isUhrSupported) maxResJpeg ?: highResJpegSizes.maxByOrNull { it.width.toLong() * it.height.toLong() } else null

        // Pro Mode capabilities extraction from CameraCharacteristics
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val minIso = isoRange?.lower ?: 100
        val maxIso = isoRange?.upper ?: 3200
        val isoSteps = mutableListOf<Int>()
        var curIso = minIso
        while (curIso <= maxIso) {
            isoSteps.add(curIso)
            curIso = when {
                curIso < 200 -> 200
                curIso < 400 -> 400
                curIso < 800 -> 800
                curIso < 1600 -> 1600
                curIso < 3200 -> 3200
                curIso < 6400 -> 6400
                else -> curIso * 2
            }
        }
        if (isoSteps.isEmpty()) isoSteps.addAll(listOf(100, 200, 400, 800, 1600, 3200))

        val expTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minShutter = expTimeRange?.lower ?: 100_000L
        val maxShutter = expTimeRange?.upper ?: 1_000_000_000L

        val maxFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10f
        val isManualFocusSupported = maxFocusDist > 0f

        val aeRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val aeStepRational = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val aeStep = if (aeStepRational != null && aeStepRational.denominator != 0) {
            aeStepRational.numerator.toFloat() / aeStepRational.denominator.toFloat()
        } else 0.5f

        val availableAwb = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        val supportedAwb = com.example.model.ProWhitebalanceMode.values().filter { mode ->
            mode == com.example.model.ProWhitebalanceMode.AUTO || availableAwb.contains(mode.camera2Mode)
        }

        val proCaps = com.example.model.ProCameraCapabilities(
            isProSupported = true,
            minIso = minIso,
            maxIso = maxIso,
            supportedIsos = isoSteps.distinct(),
            minShutterNanos = minShutter,
            maxShutterNanos = maxShutter,
            minFocusDistance = 0f,
            maxFocusDistance = maxFocusDist,
            isManualFocusSupported = isManualFocusSupported,
            minEv = aeRange?.lower ?: -4,
            maxEv = aeRange?.upper ?: 4,
            evStep = aeStep,
            supportedAwbModes = if (supportedAwb.isNotEmpty()) supportedAwb else com.example.model.ProWhitebalanceMode.values().toList()
        )

        // Dynamic Video Capabilities Detection
        val stabModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        val isVideoStabSupported = stabModes?.contains(CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true

        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        val isOisSupported = oisModes?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true

        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val highSpeedFpsRanges = try {
            standardMap?.highSpeedVideoFpsRanges ?: emptyArray()
        } catch (_: Throwable) {
            emptyArray()
        }
        val is24Supported = fpsRanges.any { it.upper == 24 || it.lower == 24 }
        val is60Supported = fpsRanges.any { it.upper >= 60 }
        val is120Supported = fpsRanges.any { it.upper >= 120 } || highSpeedFpsRanges.any { it.upper >= 120 }
        val encoderSupport = com.example.util.VideoEncoderUtil.detectVideoEncoderSupport()
        val detectedVideoQualities = mutableListOf<VideoQualityOption>()
        val fpsMap = mutableMapOf<VideoQualityOption, List<VideoFpsOption>>()

        // Check MediaRecorder and SurfaceTexture output sizes first to avoid legacy CamcorderProfile false negatives & logcat spam
        val recorderSizes = standardMap?.getOutputSizes(android.media.MediaRecorder::class.java)
            ?: standardMap?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
        val highSpeedVideoSizes = try {
            standardMap?.highSpeedVideoSizes ?: emptyArray()
        } catch (_: Throwable) {
            emptyArray()
        }

        fun isCameraAndEncoderFpsSupported(width: Int, height: Int, fps: Int): Boolean {
            if (!encoderSupport.canEncodeVideo) return false

            // 1. Camera size match
            val matchingSize = recorderSizes?.firstOrNull { it.width == width && it.height == height }
                ?: recorderSizes?.firstOrNull { it.width >= width && it.height >= height }
                ?: return false

            // 2. Camera target FPS range match
            val cameraSupportsTargetFps = when (fps) {
                0 -> true
                24 -> fpsRanges.any { it.upper == 24 || it.lower == 24 }
                120 -> {
                    val hasAe120 = fpsRanges.any { it.upper >= 120 }
                    val hasHs120 = highSpeedVideoSizes.any { it.width >= width && it.height >= height } &&
                        highSpeedFpsRanges.any { it.upper >= 120 }
                    hasAe120 || hasHs120
                }
                else -> fpsRanges.any { it.upper >= fps }
            }
            if (!cameraSupportsTargetFps) {
                return false
            }

            // 3. Camera sensor minimum frame duration check for high-FPS (>30)
            if (fps > 30 && standardMap != null) {
                val minDur = standardMap.getOutputMinFrameDuration(android.media.MediaRecorder::class.java, matchingSize)
                val durNanos = if (minDur > 0) minDur else standardMap.getOutputMinFrameDuration(android.graphics.SurfaceTexture::class.java, matchingSize)
                if (durNanos > 0) {
                    val maxCamFps = 1_000_000_000.0 / durNanos
                    if (maxCamFps < (fps - 1.0)) {
                        return false
                    }
                }
            }

            // 4. Video encoder resolution, rate, bitrate, and configuration verification
            return com.example.util.VideoEncoderUtil.isResolutionAndFpsSupported(width, height, fps)
        }

        val qualitySpecs = listOf(
            Triple(VideoQualityOption.QUALITY_2160P, 3840, 2160),
            Triple(VideoQualityOption.QUALITY_1080P, 1920, 1080),
            Triple(VideoQualityOption.QUALITY_720P, 1280, 720),
            Triple(VideoQualityOption.QUALITY_480P, 640, 480)
        )

        for ((opt, w, h) in qualitySpecs) {
            val is30Ok = isCameraAndEncoderFpsSupported(w, h, 30)
            if (is30Ok) {
                detectedVideoQualities.add(opt)
                val fpsList = mutableListOf(VideoFpsOption.FPS_AUTO)
                if (is24Supported && isCameraAndEncoderFpsSupported(w, h, 24)) {
                    fpsList.add(VideoFpsOption.FPS_24)
                }
                fpsList.add(VideoFpsOption.FPS_30)
                if (is60Supported && isCameraAndEncoderFpsSupported(w, h, 60)) {
                    fpsList.add(VideoFpsOption.FPS_60)
                }
                if (is120Supported && isCameraAndEncoderFpsSupported(w, h, 120)) {
                    fpsList.add(VideoFpsOption.FPS_120)
                }
                fpsMap[opt] = fpsList
            }
        }

        // Never fabricate video qualities if hardware/software support is genuinely absent
        val isVideoSupported = encoderSupport.canEncodeVideo && detectedVideoQualities.isNotEmpty()
        val detectedCodecs = if (isVideoSupported) {
            val topOpt = detectedVideoQualities.first()
            val (cw, ch) = when (topOpt) {
                VideoQualityOption.QUALITY_2160P -> 3840 to 2160
                VideoQualityOption.QUALITY_1080P -> 1920 to 1080
                VideoQualityOption.QUALITY_720P -> 1280 to 720
                VideoQualityOption.QUALITY_480P -> 640 to 480
            }
            com.example.util.VideoEncoderUtil.getSupportedVideoCodecs(cw, ch, 30)
        } else {
            emptyList()
        }

        val detectedFps = mutableListOf<VideoFpsOption>()
        if (isVideoSupported) {
            detectedFps.add(VideoFpsOption.FPS_AUTO)
            if (fpsMap.values.any { it.contains(VideoFpsOption.FPS_24) }) {
                detectedFps.add(VideoFpsOption.FPS_24)
            }
            if (fpsMap.values.any { it.contains(VideoFpsOption.FPS_30) }) {
                detectedFps.add(VideoFpsOption.FPS_30)
            }
            if (fpsMap.values.any { it.contains(VideoFpsOption.FPS_60) }) {
                detectedFps.add(VideoFpsOption.FPS_60)
            }
            if (fpsMap.values.any { it.contains(VideoFpsOption.FPS_120) }) {
                detectedFps.add(VideoFpsOption.FPS_120)
            }
        }

        val availableFocalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.filter { it.isFinite() && it > 0f } ?: emptyList()

        val supportedVideoRatios = mutableListOf<com.example.model.CaptureAspectRatio>()
        if (isVideoSupported) {
            val privateSizes = standardMap?.getOutputSizes(ImageFormat.PRIVATE)?.toList() ?: emptyList()
            val surfaceSizes = standardMap?.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList() ?: emptyList()
            val videoSizePool = ((recorderSizes?.toList() ?: emptyList()) + privateSizes + surfaceSizes).distinct()

            val supports16_9Video = detectedVideoQualities.any {
                it == VideoQualityOption.QUALITY_2160P ||
                    it == VideoQualityOption.QUALITY_1080P ||
                    it == VideoQualityOption.QUALITY_720P
            } || videoSizePool.any { s ->
                s.width >= 640 && s.height >= 360 &&
                    kotlin.math.abs((s.width.toDouble() / s.height) - (16.0 / 9.0)) < 0.08 &&
                    com.example.util.VideoEncoderUtil.isResolutionAndFpsSupported(s.width, s.height, 30)
            }
            if (supports16_9Video) {
                supportedVideoRatios.add(com.example.model.CaptureAspectRatio.RATIO_16_9)
            }

            val supports4_3Video = videoSizePool.any { s ->
                s.width >= 640 && s.height >= 480 &&
                    kotlin.math.abs((s.width.toDouble() / s.height) - (4.0 / 3.0)) < 0.08 &&
                    com.example.util.VideoEncoderUtil.isResolutionAndFpsSupported(s.width, s.height, 30)
            }
            if (supports4_3Video) {
                supportedVideoRatios.add(com.example.model.CaptureAspectRatio.RATIO_4_3)
            }
        }

        return CameraSensorCapability(
            cameraId = id,
            facing = facing,
            hardwareLevel = hwLevel,
            pixelArraySize = pixelArray,
            activeArraySize = activeArray,
            isUltraHighResolutionSupported = isUhrSupported && maxResJpegSizes.isNotEmpty(),
            standardJpegSizes = standardJpegSizes.sortedByDescending { it.width.toLong() * it.height.toLong() },
            maxResolutionJpegSizes = maxResJpegSizes.sortedByDescending { it.width.toLong() * it.height.toLong() },
            highResolutionJpegSizes = highResJpegSizes.sortedByDescending { it.width.toLong() * it.height.toLong() },
            standardRawSizes = standardRawSizes.sortedByDescending { it.width.toLong() * it.height.toLong() },
            maxResolutionRawSizes = maxResRawSizes.sortedByDescending { it.width.toLong() * it.height.toLong() },
            isRawSupported = isRawSupported,
            physicalCameraIds = physicalIds,
            physicalCameras = physicalSubSensors,
            maxJpegMegapixels = maxMegapixels,
            standardMaxJpegMegapixels = highestMegapixels,
            recommendedUhrSize = recommendedUhr,
            hasMaxResolutionMap = hasMaxResMap,
            capabilitiesList = capNames,
            highestGenuineJpegSize = highestGenuineJpeg,
            isHighestGenuineStallStream = isHighestStall,
            allGenuineJpegSizes = allGenuineJpeg,
            proCapabilities = proCaps,
            supportedVideoQualities = detectedVideoQualities.distinct(),
            supportedVideoFps = detectedFps.distinct(),
            supportedFpsPerQuality = fpsMap,
            supportedVideoCodecs = detectedCodecs,
            supportedVideoAspectRatios = supportedVideoRatios.distinct(),
            isVideoRecordingSupported = isVideoSupported,
            isVideoStabilizationSupported = isVideoStabSupported,
            isOisSupported = isOisSupported,
            targetFpsRanges = fpsRanges.toList(),
            availableFocalLengths = availableFocalLengths,
            isDepthOutputSupported = isDepthOutputSupported || physicalSubSensors.any { it.isDepthOutputSupported },
            depth16Sizes = depth16Sizes.sortedByDescending { it.width.toLong() * it.height.toLong() },
            depthPointCloudSizes = depthPointCloudSizes.sortedByDescending { it.width.toLong() * it.height.toLong() }
        )
    }

    fun generateDiagnosticReport(oemMode: com.example.model.OemEnhancementMode = com.example.model.OemEnhancementMode.AUTOMATIC): String {
        val cameras = detectAllCameras()
        val oemRegistry = com.example.oem.OemAdapterRegistry()
        val activeOemAdapter = oemRegistry.resolveActiveAdapter(context, oemMode)

        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine("STU•D•O CAMERA CAPABILITY DIAGNOSTIC REPORT")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Total Accessible Cameras: ${cameras.size}")
        sb.appendLine("Active OEM Engine: ${activeOemAdapter.adapterName} [Mode: ${oemMode.displayName}]")
        sb.appendLine("Diagnostic Protocol: Direct Camera2 HAL3 Inspection")
        sb.appendLine("Note: All metrics read from standard public Android Camera2 APIs.")
        sb.appendLine("==================================================")
        sb.appendLine()

        for (cam in cameras) {
            sb.appendLine("--------------------------------------------------")
            sb.appendLine("CAMERA ID: ${cam.cameraId} [${cam.facing}]")
            sb.appendLine("Hardware Support Level: ${cam.hardwareLevel}")
            if (cam.availableFocalLengths.isNotEmpty()) {
                val focalStr = cam.availableFocalLengths.joinToString(", ") {
                    com.example.util.CameraMetadataUtil.formatFocalLengthMm(it) ?: "${it}mm"
                }
                sb.appendLine("Available Focal Lengths (LENS_INFO_AVAILABLE_FOCAL_LENGTHS): [$focalStr]")
            }

            // Pixel Array vs Active Array
            val pixelStr = if (cam.pixelArraySize != null) {
                val mp = (cam.pixelArraySize.width.toDouble() * cam.pixelArraySize.height.toDouble()) / 1_000_000.0
                "${cam.pixelArraySize.width} × ${cam.pixelArraySize.height} (${String.format(Locale.US, "%.2f", mp)} MP)"
            } else "Unknown"
            sb.appendLine("Sensor Pixel Array: $pixelStr")

            val activeStr = if (cam.activeArraySize != null) {
                val w = cam.activeArraySize.width()
                val h = cam.activeArraySize.height()
                val mp = (w.toDouble() * h.toDouble()) / 1_000_000.0
                "Rect(${cam.activeArraySize.left}, ${cam.activeArraySize.top}, ${cam.activeArraySize.right}, ${cam.activeArraySize.bottom}) -> $w × $h (${String.format(Locale.US, "%.2f", mp)} MP)"
            } else "Not Reported by HAL"
            sb.appendLine("Sensor Active Array: $activeStr")

            // Available Capabilities
            if (cam.capabilitiesList.isNotEmpty()) {
                sb.appendLine("Advertised Capabilities: [${cam.capabilitiesList.joinToString(", ")}]")
            }

            // UHR Capability & Max Resolution Map
            sb.appendLine("Ultra-High-Res Capability (REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR): ${if (cam.isUltraHighResolutionSupported) "YES (Advertised)" else "NO (Not Advertised by HAL)"}")
            sb.appendLine("Max-Resolution Stream Map (SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION): ${if (cam.hasMaxResolutionMap) "PRESENT" else "NULL (Not Exposed by HAL)"}")

            if (cam.recommendedUhrSize != null) {
                val uhrMp = (cam.recommendedUhrSize.width.toDouble() * cam.recommendedUhrSize.height.toDouble()) / 1_000_000.0
                sb.appendLine("Configured Maximum Resolution: ${cam.recommendedUhrSize.width} × ${cam.recommendedUhrSize.height} (${String.format(Locale.US, "%.2f", uhrMp)} MP)")
            }

            // RAW Support
            sb.appendLine("RAW_SENSOR Support: ${if (cam.isRawSupported) "YES (Supported)" else "NO"}")
            val depthSummary = when {
                cam.depth16Sizes.isNotEmpty() -> "YES (DEPTH16 ${cam.depth16Sizes.first().width}×${cam.depth16Sizes.first().height})"
                cam.isDepthOutputSupported -> "YES (DEPTH_OUTPUT capability / sub-sensor)"
                else -> "NO (AI + Multi-Cue Software Portrait Depth Fallback)"
            }
            sb.appendLine("Portrait Hardware Depth: $depthSummary")

            // Logical / Physical Camera Sub-Sensors
            if (cam.physicalCameraIds.isNotEmpty()) {
                sb.appendLine("Logical Multi-Camera Physical IDs: [${cam.physicalCameraIds.joinToString(", ")}]")
                for (sub in cam.physicalCameras) {
                    sb.appendLine("  -> Physical Sub-Camera [ID ${sub.physicalId}]:")
                    val pPixel = sub.pixelArraySize?.let { "${it.width} × ${it.height}" } ?: "Unknown"
                    val pActive = sub.activeArraySize?.let { "${it.width()} × ${it.height()}" } ?: "Unknown"
                    sb.appendLine("     Pixel Array: $pPixel | Active Array: $pActive")
                    val topStd = sub.standardJpegSizes.firstOrNull()?.let { "${it.width} × ${it.height}" } ?: "None"
                    sb.appendLine("     Standard JPEG Top Size: $topStd (Total sizes: ${sub.standardJpegSizes.size})")
                    if (sub.maxResolutionJpegSizes.isNotEmpty()) {
                        sb.appendLine("     Max-Res Stream Top Size: ${sub.maxResolutionJpegSizes.first().width} × ${sub.maxResolutionJpegSizes.first().height}")
                    }
                    if (sub.standardRawSizes.isNotEmpty()) {
                        sb.appendLine("     Standard RAW Top Size: ${sub.standardRawSizes.first().width} × ${sub.standardRawSizes.first().height}")
                    }
                }
            } else {
                sb.appendLine("Logical Multi-Camera Physical IDs: None (Independent Physical Camera Node)")
            }

            // Capture Verification Test Status
            sb.appendLine("Hardware Capture Verification Status:")
            val testResult = getCaptureTestResult(cam.cameraId)
            if (testResult != null) {
                if (testResult.isSuccess) {
                    sb.appendLine("  ✓ TESTED & VERIFIED ON PHYSICAL HARDWARE")
                    sb.appendLine("    Result: ${testResult.details}")
                } else {
                    sb.appendLine("  ✗ TESTED BUT FAILED ON HARDWARE")
                    sb.appendLine("    Reason: ${testResult.details}")
                }
            } else {
                sb.appendLine("  ○ NOT TESTED YET")
                sb.appendLine("    (Capability advertised by HAL; no physical shutter capture executed yet in this session)")
            }

            // Standard JPEG Stream Sizes
            sb.appendLine("Standard JPEG Output Sizes (${cam.standardJpegSizes.size}):")
            cam.standardJpegSizes.take(5).forEach {
                val mp = (it.width.toDouble() * it.height.toDouble()) / 1_000_000.0
                sb.appendLine("  • ${it.width} × ${it.height} (${String.format(Locale.US, "%.2f", mp)} MP)")
            }
            if (cam.standardJpegSizes.size > 5) {
                sb.appendLine("  ... (${cam.standardJpegSizes.size - 5} additional sizes)")
            }

            // High-Resolution Stall Stream Sizes
            if (cam.highResolutionJpegSizes.isNotEmpty()) {
                sb.appendLine("High-Resolution (Stall) Stream JPEG Sizes (${cam.highResolutionJpegSizes.size}):")
                cam.highResolutionJpegSizes.forEach {
                    val mp = (it.width.toDouble() * it.height.toDouble()) / 1_000_000.0
                    sb.appendLine("  ✦ ${it.width} × ${it.height} (${String.format(Locale.US, "%.2f", mp)} MP)")
                }
            }

            // Max-Resolution Stream Sizes (API 31+)
            if (cam.maxResolutionJpegSizes.isNotEmpty()) {
                sb.appendLine("Max-Resolution Stream JPEG Sizes (${cam.maxResolutionJpegSizes.size}):")
                cam.maxResolutionJpegSizes.forEach {
                    val mp = (it.width.toDouble() * it.height.toDouble()) / 1_000_000.0
                    sb.appendLine("  ★ ${it.width} × ${it.height} (${String.format(Locale.US, "%.2f", mp)} MP)")
                }
            }

            // Standard RAW Sizes
            if (cam.standardRawSizes.isNotEmpty()) {
                sb.appendLine("Standard RAW_SENSOR Sizes (${cam.standardRawSizes.size}):")
                cam.standardRawSizes.take(3).forEach {
                    val mp = (it.width.toDouble() * it.height.toDouble()) / 1_000_000.0
                    sb.appendLine("  [RAW] ${it.width} × ${it.height} (${String.format(Locale.US, "%.2f", mp)} MP)")
                }
            }

            // Max-Resolution RAW Sizes
            if (cam.maxResolutionRawSizes.isNotEmpty()) {
                sb.appendLine("Max-Resolution RAW_SENSOR Sizes (${cam.maxResolutionRawSizes.size}):")
                cam.maxResolutionRawSizes.forEach {
                    val mp = (it.width.toDouble() * it.height.toDouble()) / 1_000_000.0
                    sb.appendLine("  [MAX RAW] ${it.width} × ${it.height} (${String.format(Locale.US, "%.2f", mp)} MP)")
                }
            }

            // Technical Sensor Analysis
            if (cam.activeArraySize != null) {
                val activeArea = cam.activeArraySize.width().toLong() * cam.activeArraySize.height().toLong()
                val highestArea = cam.highestGenuineJpegSize.width.toLong() * cam.highestGenuineJpegSize.height.toLong()
                val highestMp = (cam.highestGenuineJpegSize.width.toDouble() * cam.highestGenuineJpegSize.height.toDouble()) / 1_000_000.0

                sb.appendLine("Highest Genuine Output: ${cam.highestGenuineJpegSize.width} × ${cam.highestGenuineJpegSize.height} (${String.format(Locale.US, "%.2f", highestMp)} MP)${if (cam.isHighestGenuineStallStream) " [High-Res Stall Stream]" else " [Standard Stream]"}")

                if (highestArea >= activeArea * 0.85) {
                    sb.appendLine("HAL Stream Architecture Note:")
                    sb.appendLine("  Sensor active array (${cam.activeArraySize.width()} × ${cam.activeArraySize.height()}) is fully accessible via ${if (cam.isHighestGenuineStallStream) "high-resolution stall stream" else "standard stream"}.")
                    sb.appendLine("  StuDO captures at full native resolution (${cam.highestGenuineJpegSize.width} × ${cam.highestGenuineJpegSize.height}) without artificial upscaling or center-cropping.")
                } else if (activeArea > highestArea * 2) {
                    sb.appendLine("HAL Stream Architecture Note:")
                    sb.appendLine("  Active array (${cam.activeArraySize.width()} × ${cam.activeArraySize.height()}) exceeds maximum public stream (${cam.highestGenuineJpegSize.width} × ${cam.highestGenuineJpegSize.height}).")
                    sb.appendLine("  The device HAL operates in hardware-binned mode and does not advertise full-sensor unbinned streams to public Android Camera2 stream configuration maps.")
                }
            }

            // Dynamic Resolution Tiers
            sb.appendLine("Dynamic Capture Resolution Tiers:")
            cam.getDynamicResolutionOptions().forEach { opt ->
                val stallTag = if (opt.isStallStream) " [Stall Stream]" else ""
                sb.appendLine("  • ${opt.title}: ${opt.dimensionsText} (${opt.megapixelsText})$stallTag -> ${opt.description}")
            }

            // OEM Enhancement Assessment
            val oemProfile = oemRegistry.probeActiveEnhancements(context, cam.cameraId, oemMode)
            if (oemProfile.hardwareIspEnhancements.isNotEmpty()) {
                sb.appendLine("OEM ISP Enhancements: [${oemProfile.hardwareIspEnhancements.joinToString(", ")}]")
            }
            if (oemProfile.limitationsNote.isNotBlank()) {
                sb.appendLine("OEM Engine Assessment: ${oemProfile.limitationsNote}")
            }

            sb.appendLine()
        }

        // PHYSICAL SHUTTER CAPTURE & VERIFICATION LOG
        sb.appendLine("==================================================")
        sb.appendLine("PHYSICAL SHUTTER CAPTURE & VERIFICATION LOG")
        sb.appendLine("==================================================")
        if (resolutionCaptureRecords.isEmpty()) {
            sb.appendLine("Status: ○ NO PHYSICAL SHUTTER CAPTURES RECORDED YET IN THIS SESSION")
            sb.appendLine("(Real capture records populate here upon shutter release for each resolution option)")
        } else {
            sb.appendLine("Total Verified Captures in Session: ${resolutionCaptureRecords.size}")
            resolutionCaptureRecords.forEachIndexed { idx, rec ->
                sb.appendLine("--------------------------------------------------")
                sb.appendLine("CAPTURE #${idx + 1}: ${rec.selectedResolutionName} [${rec.selectedResolution.name}]")
                sb.appendLine("  Configured Resolution: ${rec.configuredSize?.let { "${it.width} × ${it.height}" } ?: "Auto"}")
                sb.appendLine("  Actual Saved JPEG: ${rec.savedWidth} × ${rec.savedHeight} (${String.format(Locale.US, "%.2f", rec.savedMegapixels)} MP)")
                sb.appendLine("  File Size on Disk: ${rec.fileSizeBytes} bytes (${String.format(Locale.US, "%.2f MB", rec.fileSizeBytes / (1024.0 * 1024.0))})")
                sb.appendLine("  Capture Pipeline: ${rec.capturePath}")
                sb.appendLine("  Verification: ${if (rec.isSuccess) "✓ SUCCESS (Physically Verified)" else "✗ FAILED"}")
                if (rec.fallbackDetails != null) {
                    sb.appendLine("  Fallback Note: ${rec.fallbackDetails}")
                }
            }
        }

        sb.appendLine("==================================================")
        sb.appendLine("End of STU•D•O Camera Capability Diagnostic Report")
        sb.appendLine("==================================================")
        return sb.toString()
    }
}
