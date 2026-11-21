package com.example.data

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.model.CameraFilter
import com.example.model.CaptureAspectRatio
import com.example.model.RawCaptureMode
import com.example.model.WatermarkConfig
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import com.example.util.MemoryMappedBufferUtil

class UltraResCaptureManager(private val context: Context) {

    private val cameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val postProcessingExecutor = Executors.newFixedThreadPool(2)
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    fun shutdown() {
        mainHandler.removeCallbacksAndMessages(null)
        stopBackgroundThread()
        try { postProcessingExecutor.shutdownNow() } catch (_: Throwable) {}
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null || !backgroundThread!!.isAlive) {
            backgroundThread = HandlerThread("StuDO_UltraRes_Camera2").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        val thread = backgroundThread ?: return
        thread.quitSafely()
        backgroundThread = null
        backgroundHandler = null
    }

    @SuppressLint("MissingPermission")
    fun captureUltraHighResolution(
        cameraId: String,
        targetSize: Size,
        rawSize: Size?,
        rawMode: RawCaptureMode,
        filter: CameraFilter,
        mirrorSelfie: Boolean,
        watermarkConfig: WatermarkConfig?,
        oemAdapter: com.example.oem.OemCameraAdapter? = null,
        isHdrEnabled: Boolean = false,
        proSettings: com.example.model.ProCameraSettings? = null,
        isSaveLocation: Boolean = false,
        targetRotation: Int = android.view.Surface.ROTATION_0,
        activeFocalLengthMm: Float? = null,
        privateOriginalStorage: PrivateOriginalStorageManager? = null,
        retainCleanOriginal: Boolean = false,
        watermarkCacheLimitBytes: Long = 0L,
        aspectRatio: CaptureAspectRatio = CaptureAspectRatio.RATIO_4_3,
        fullScreenAspectRatio: Float = 9f / 20f,
        onEvictionNotice: ((CacheEvictionResult) -> Unit)? = null,
        onCaptureStarted: () -> Unit,
        onShutterReady: () -> Unit = {},
        onSuccess: (Uri, Uri?) -> Unit, // (jpegUri, rawDngUri)
        onError: (String) -> Unit
    ) {
        val captureId = java.util.UUID.randomUUID().toString()
        val expectJpeg = rawMode != RawCaptureMode.DNG_ONLY
        val expectRaw = (rawMode == RawCaptureMode.JPEG_AND_DNG || rawMode == RawCaptureMode.DNG_ONLY) && rawSize != null

        if (!expectJpeg && !expectRaw) {
            onError("No valid capture format configured (RAW size unavailable for sensor)")
            return
        }

        startBackgroundThread()
        val handler = backgroundHandler ?: Handler(context.mainLooper)

        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val characteristicFocalMm = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull { it.isFinite() && it > 0f }

            // Setup ImageReaders strictly according to expected outputs
            val jpegReader = if (expectJpeg) {
                ImageReader.newInstance(targetSize.width, targetSize.height, ImageFormat.JPEG, 2)
            } else null

            val rawReader = if (expectRaw) {
                ImageReader.newInstance(rawSize!!.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
            } else null

            val stateLock = Any()
            var savedJpegUri: Uri? = null
            var savedDngUri: Uri? = null
            var jpegReceived = false
            var rawReceived = false
            var jpegProcessed = false
            var rawProcessed = false
            var jpegError: String? = null
            var rawError: String? = null
            var captureResultReceived: TotalCaptureResult? = null
            var captureFocalLengthMm: Float? = null
            var pendingRawImage: Image? = null
            var isDispatched = false
            var shutterSignaled = false

            fun notifyShutterReadyLocked() {
                if (!shutterSignaled) {
                    shutterSignaled = true
                    ContextCompat.getMainExecutor(context).execute {
                        onShutterReady()
                    }
                }
            }

            var activeCamera: CameraDevice? = null
            var activeSession: CameraCaptureSession? = null
            var closingCameraPending = false
            val sessionExecutor = Executors.newSingleThreadExecutor()

            fun closeImageReadersLocked() {
                try { jpegReader?.close() } catch (_: Exception) {}
                try { rawReader?.close() } catch (_: Exception) {}
            }

            fun finalizeCameraCloseLocked() {
                if (!closingCameraPending) return
                closingCameraPending = false
                try { sessionExecutor.shutdown() } catch (_: Exception) {}
                notifyShutterReadyLocked()
                if (isDispatched) {
                    closeImageReadersLocked()
                    stopBackgroundThread()
                }
            }

            fun releaseCameraHardwareLocked() {
                try { activeSession?.close() } catch (_: Exception) {}
                activeSession = null
                val cam = activeCamera
                if (cam != null) {
                    activeCamera = null
                    closingCameraPending = true
                    mainHandler.postDelayed({
                        synchronized(stateLock) {
                            finalizeCameraCloseLocked()
                        }
                    }, 600L)
                    try {
                        cam.close()
                    } catch (_: Exception) {
                        finalizeCameraCloseLocked()
                    }
                } else if (!closingCameraPending) {
                    try { sessionExecutor.shutdown() } catch (_: Exception) {}
                    notifyShutterReadyLocked()
                }
            }

            fun cleanupResourcesLocked() {
                try { pendingRawImage?.close() } catch (_: Exception) {}
                pendingRawImage = null
                releaseCameraHardwareLocked()
                if (!closingCameraPending) {
                    closeImageReadersLocked()
                    stopBackgroundThread()
                }
            }

            fun dispatchErrorLocked(msg: String) {
                if (isDispatched) return
                isDispatched = true
                handler.removeCallbacksAndMessages(null)
                cleanupResourcesLocked()
                ContextCompat.getMainExecutor(context).execute {
                    onError(msg)
                }
            }

            fun checkCompletionLocked() {
                if (isDispatched) return
                val isJpegDone = !expectJpeg || jpegProcessed
                val isRawDone = !expectRaw || rawProcessed

                if (isJpegDone && isRawDone) {
                    isDispatched = true
                    val finalJpegUri = savedJpegUri
                    val finalDngUri = savedDngUri
                    val finalJpegErr = jpegError
                    val finalRawErr = rawError
                    handler.removeCallbacksAndMessages(null)
                    cleanupResourcesLocked()

                    ContextCompat.getMainExecutor(context).execute {
                        if (expectRaw && expectJpeg) {
                            if (finalJpegUri != null && finalDngUri != null) {
                                onSuccess(finalJpegUri, finalDngUri)
                            } else if (finalJpegUri != null) {
                                // JPEG succeeded but RAW failed
                                onSuccess(finalJpegUri, null)
                            } else if (finalDngUri != null) {
                                // RAW succeeded but JPEG failed
                                onSuccess(finalDngUri, finalDngUri)
                            } else {
                                onError("Capture failed: JPEG (${finalJpegErr ?: "error"}), RAW (${finalRawErr ?: "error"})")
                            }
                        } else if (expectRaw) {
                            if (finalDngUri != null) {
                                onSuccess(finalDngUri, finalDngUri)
                            } else {
                                onError("RAW DNG capture failed: ${finalRawErr ?: "Unable to write DNG"}")
                            }
                        } else {
                            if (finalJpegUri != null) {
                                onSuccess(finalJpegUri, null)
                            } else {
                                onError("Photo capture failed: ${finalJpegErr ?: "Unable to write JPEG"}")
                            }
                        }
                    }
                }
            }

            // Sensible timeout safety (10 seconds)
            val timeoutRunnable = Runnable {
                synchronized(stateLock) {
                    if (!isDispatched) {
                        android.util.Log.w("UltraResCapture", "Capture $captureId timed out after 10s")
                        isDispatched = true
                        notifyShutterReadyLocked()
                        cleanupResourcesLocked()
                        ContextCompat.getMainExecutor(context).execute {
                            if (savedJpegUri != null || savedDngUri != null) {
                                onSuccess(savedJpegUri ?: savedDngUri!!, savedDngUri)
                            } else {
                                onError("Capture timed out after 10 seconds. Camera pipeline reset.")
                            }
                        }
                    }
                }
            }
            handler.postDelayed(timeoutRunnable, 10000L)

            // Setup JPEG Listener only when JPEG expected
            jpegReader?.setOnImageAvailableListener({ reader ->
                val image = try {
                    reader.acquireLatestImage() ?: reader.acquireNextImage()
                } catch (_: Exception) {
                    try { reader.acquireNextImage() } catch (_: Exception) { null }
                }
                if (image != null) {
                    var copyErr: String? = null
                    val jpegBytes = try {
                        val buf = image.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        bytes
                    } catch (e: Exception) {
                        copyErr = e.localizedMessage
                        null
                    } finally {
                        try { image.close() } catch (_: Exception) {}
                    }

                    val resolvedFocalLengthMm: Float?
                    synchronized(stateLock) {
                        if (copyErr != null) jpegError = copyErr
                        jpegReceived = true
                        resolvedFocalLengthMm = captureFocalLengthMm ?: activeFocalLengthMm ?: characteristicFocalMm
                        if (!expectRaw || rawReceived) {
                            releaseCameraHardwareLocked()
                        }
                    }

                    if (jpegBytes != null && !postProcessingExecutor.isShutdown) {
                        postProcessingExecutor.execute {
                            var outUri: Uri? = null
                            var errStr: String? = null
                            try {
                                val buf = java.nio.ByteBuffer.wrap(jpegBytes)
                                outUri = saveJpegBufferToMediaStore(
                                    buffer = buf,
                                    filter = filter,
                                    mirrorSelfie = mirrorSelfie,
                                    watermarkConfig = watermarkConfig,
                                    isSaveLocation = isSaveLocation,
                                    activeFocalLengthMm = resolvedFocalLengthMm,
                                    privateOriginalStorage = privateOriginalStorage,
                                    retainCleanOriginal = retainCleanOriginal,
                                    watermarkCacheLimitBytes = watermarkCacheLimitBytes,
                                    aspectRatio = aspectRatio,
                                    fullScreenAspectRatio = fullScreenAspectRatio,
                                    onEvictionNotice = onEvictionNotice
                                )
                                if (outUri == null) {
                                    errStr = "Failed to write JPEG to MediaStore"
                                }
                            } catch (t: Throwable) {
                                android.util.Log.e("UltraResCapture", "Error saving JPEG", t)
                                errStr = t.localizedMessage ?: "Unknown JPEG write error"
                            } finally {
                                synchronized(stateLock) {
                                    if (outUri != null) savedJpegUri = outUri
                                    if (errStr != null) jpegError = errStr
                                    jpegProcessed = true
                                    checkCompletionLocked()
                                }
                            }
                        }
                    } else {
                        synchronized(stateLock) {
                            jpegProcessed = true
                            checkCompletionLocked()
                        }
                    }
                }
            }, handler)

            // Setup RAW Listener only when RAW expected
            rawReader?.setOnImageAvailableListener({ reader ->
                val image = try {
                    reader.acquireLatestImage() ?: reader.acquireNextImage()
                } catch (_: Exception) {
                    try { reader.acquireNextImage() } catch (_: Exception) { null }
                }
                if (image != null) {
                    synchronized(stateLock) {
                        val res = captureResultReceived
                        if (res != null) {
                            rawReceived = true
                            if (!expectJpeg || jpegReceived) {
                                releaseCameraHardwareLocked()
                            }
                            if (!postProcessingExecutor.isShutdown) {
                                postProcessingExecutor.execute {
                                    var outUri: Uri? = null
                                    var errStr: String? = null
                                    try {
                                        outUri = writeDngToMediaStore(characteristics, res, image, targetRotation, isSaveLocation)
                                        if (outUri == null) {
                                            errStr = "Failed to write DNG file to MediaStore"
                                        }
                                    } catch (t: Throwable) {
                                        android.util.Log.e("UltraResCapture", "Error writing RAW DNG", t)
                                        errStr = t.localizedMessage ?: "Unknown DNG write error"
                                    } finally {
                                        try { image.close() } catch (_: Exception) {}
                                        synchronized(stateLock) {
                                            if (outUri != null) savedDngUri = outUri
                                            if (errStr != null) rawError = errStr
                                            rawProcessed = true
                                            checkCompletionLocked()
                                        }
                                    }
                                }
                            } else {
                                try { image.close() } catch (_: Exception) {}
                                rawProcessed = true
                                checkCompletionLocked()
                            }
                        } else {
                            // Hold the image until onCaptureCompleted delivers TotalCaptureResult
                            pendingRawImage = image
                        }
                    }
                }
            }, handler)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    synchronized(stateLock) {
                        activeCamera = camera
                        if (isDispatched) {
                            releaseCameraHardwareLocked()
                            return
                        }
                    }

                    try {
                        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                        val hasUhrCapability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR) == true
                        } else false

                        val maxResMap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasUhrCapability) {
                            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                        } else null
                        val maxResJpegSizes = maxResMap?.getOutputSizes(ImageFormat.JPEG)?.toSet().orEmpty()
                        val maxResRawSizes = maxResMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toSet().orEmpty()
                        val jpegUsesMaxRes = expectJpeg && maxResJpegSizes.contains(targetSize)
                        val rawUsesMaxRes = expectRaw && rawSize != null && maxResRawSizes.contains(rawSize)

                        val outputConfigs = mutableListOf<OutputConfiguration>()

                        jpegReader?.let { jr ->
                            val jpegOutputConfig = OutputConfiguration(jr.surface)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && jpegUsesMaxRes) {
                                jpegOutputConfig.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                            }
                            outputConfigs.add(jpegOutputConfig)
                        }

                        rawReader?.let { rr ->
                            val rawOutputConfig = OutputConfiguration(rr.surface)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && rawUsesMaxRes) {
                                rawOutputConfig.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                            }
                            outputConfigs.add(rawOutputConfig)
                        }

                        if (outputConfigs.isEmpty()) {
                            synchronized(stateLock) {
                                dispatchErrorLocked("No valid camera output stream configured")
                            }
                            return
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val sessionConfig = SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                outputConfigs,
                                sessionExecutor,
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        synchronized(stateLock) {
                                            activeSession = session
                                            if (isDispatched) {
                                                try { session.close() } catch (_: Exception) {}
                                                return
                                            }
                                        }

                                        try {
                                            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                            jpegReader?.let { captureBuilder.addTarget(it.surface) }
                                            rawReader?.let { captureBuilder.addTarget(it.surface) }

                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (jpegUsesMaxRes || rawUsesMaxRes)) {
                                                captureBuilder.set(
                                                    CaptureRequest.SENSOR_PIXEL_MODE,
                                                    CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
                                                )
                                            }

                                            // Apply Pro manual controls if supplied
                                            if (proSettings != null) {
                                                if (proSettings.iso != null) {
                                                    captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                                                    captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, proSettings.iso)
                                                }
                                                if (proSettings.shutterSpeedNanos != null) {
                                                    captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                                                    captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, proSettings.shutterSpeedNanos)
                                                }
                                                if (proSettings.iso == null && proSettings.shutterSpeedNanos == null) {
                                                    captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                                    if (proSettings.evCompensation != 0) {
                                                        captureBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, proSettings.evCompensation)
                                                    }
                                                }
                                                if (proSettings.isManualFocus && proSettings.focusDistance != null) {
                                                    captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                                                    captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, proSettings.focusDistance)
                                                } else {
                                                    captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                                }
                                                if (proSettings.whiteBalance != com.example.model.ProWhitebalanceMode.AUTO) {
                                                    captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, proSettings.whiteBalance.camera2Mode)
                                                } else {
                                                    captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                                }
                                            } else {
                                                captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                            }

                                            captureBuilder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                                            try {
                                                oemAdapter?.configureCaptureRequest(captureBuilder, characteristics, isHdrEnabled)
                                            } catch (_: Exception) {}

                                            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureStarted(
                                                    session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    timestamp: Long,
                                                    frameNumber: Long
                                                ) {
                                                    mainHandler.post { onCaptureStarted() }
                                                }

                                                override fun onCaptureCompleted(
                                                    session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    result: TotalCaptureResult
                                                ) {
                                                    synchronized(stateLock) {
                                                        captureResultReceived = result
                                                        result.get(android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH)
                                                            ?.takeIf { it.isFinite() && it > 0f }
                                                            ?.let { captureFocalLengthMm = it }
                                                        val rawImg = pendingRawImage
                                                        if (rawImg != null) {
                                                            pendingRawImage = null
                                                            rawReceived = true
                                                            if (!expectJpeg || jpegReceived) {
                                                                releaseCameraHardwareLocked()
                                                            }
                                                            if (!postProcessingExecutor.isShutdown) {
                                                                postProcessingExecutor.execute {
                                                                    var outUri: Uri? = null
                                                                    var errStr: String? = null
                                                                    try {
                                                                        outUri = writeDngToMediaStore(characteristics, result, rawImg, targetRotation, isSaveLocation)
                                                                        if (outUri == null) {
                                                                            errStr = "Failed to write DNG file to MediaStore"
                                                                        }
                                                                    } catch (t: Throwable) {
                                                                        android.util.Log.e("UltraResCapture", "Error writing RAW DNG", t)
                                                                        errStr = t.localizedMessage ?: "Unknown DNG write error"
                                                                    } finally {
                                                                        try { rawImg.close() } catch (_: Exception) {}
                                                                        synchronized(stateLock) {
                                                                            if (outUri != null) savedDngUri = outUri
                                                                            if (errStr != null) rawError = errStr
                                                                            rawProcessed = true
                                                                            checkCompletionLocked()
                                                                        }
                                                                    }
                                                                }
                                                            } else {
                                                                try { rawImg.close() } catch (_: Exception) {}
                                                                rawProcessed = true
                                                                checkCompletionLocked()
                                                            }
                                                        } else if (!expectRaw) {
                                                            if (jpegReceived) {
                                                                releaseCameraHardwareLocked()
                                                            } else {
                                                                notifyShutterReadyLocked()
                                                            }
                                                        }
                                                    }
                                                }

                                                override fun onCaptureFailed(
                                                    session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    failure: android.hardware.camera2.CaptureFailure
                                                ) {
                                                    synchronized(stateLock) {
                                                        dispatchErrorLocked("Camera capture failed: reason ${failure.reason}")
                                                    }
                                                }
                                            }, handler)
                                        } catch (e: Exception) {
                                            synchronized(stateLock) {
                                                dispatchErrorLocked("Failed to initiate capture request: ${e.localizedMessage}")
                                            }
                                        }
                                    }

                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        synchronized(stateLock) {
                                            dispatchErrorLocked("Hardware capture session configuration failed")
                                        }
                                    }
                                }
                            )
                            camera.createCaptureSession(sessionConfig)
                        } else {
                            @Suppress("DEPRECATION")
                            val surfaces = listOfNotNull(jpegReader?.surface, rawReader?.surface)
                            @Suppress("DEPRECATION")
                            camera.createCaptureSession(
                                surfaces,
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        synchronized(stateLock) {
                                            activeSession = session
                                            if (isDispatched) {
                                                try { session.close() } catch (_: Exception) {}
                                                return
                                            }
                                        }
                                        try {
                                            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                            jpegReader?.let { captureBuilder.addTarget(it.surface) }
                                            rawReader?.let { captureBuilder.addTarget(it.surface) }
                                            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureStarted(
                                                    session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    timestamp: Long,
                                                    frameNumber: Long
                                                ) {
                                                    mainHandler.post { onCaptureStarted() }
                                                }

                                                override fun onCaptureCompleted(
                                                    session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    result: TotalCaptureResult
                                                ) {
                                                    synchronized(stateLock) {
                                                        captureResultReceived = result
                                                        result.get(android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH)
                                                            ?.takeIf { it.isFinite() && it > 0f }
                                                            ?.let { captureFocalLengthMm = it }
                                                        val rawImg = pendingRawImage
                                                        if (rawImg != null) {
                                                            pendingRawImage = null
                                                            rawReceived = true
                                                            notifyShutterReadyLocked()
                                                            if (!postProcessingExecutor.isShutdown) {
                                                                postProcessingExecutor.execute {
                                                                    var outUri: Uri? = null
                                                                    var errStr: String? = null
                                                                    try {
                                                                        outUri = writeDngToMediaStore(characteristics, result, rawImg, targetRotation, isSaveLocation)
                                                                        if (outUri == null) errStr = "Failed to write DNG"
                                                                    } catch (t: Throwable) {
                                                                        errStr = t.localizedMessage
                                                                    } finally {
                                                                        try { rawImg.close() } catch (_: Exception) {}
                                                                        synchronized(stateLock) {
                                                                            if (outUri != null) savedDngUri = outUri
                                                                            if (errStr != null) rawError = errStr
                                                                            rawProcessed = true
                                                                            checkCompletionLocked()
                                                                        }
                                                                    }
                                                                }
                                                            } else {
                                                                try { rawImg.close() } catch (_: Exception) {}
                                                                rawProcessed = true
                                                                checkCompletionLocked()
                                                            }
                                                        } else if (!expectRaw) {
                                                            notifyShutterReadyLocked()
                                                            checkCompletionLocked()
                                                        }
                                                    }
                                                }

                                                override fun onCaptureFailed(
                                                    session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    failure: android.hardware.camera2.CaptureFailure
                                                ) {
                                                    synchronized(stateLock) {
                                                        dispatchErrorLocked("Capture failed: reason ${failure.reason}")
                                                    }
                                                }
                                            }, handler)
                                        } catch (e: Exception) {
                                            synchronized(stateLock) {
                                                dispatchErrorLocked("Legacy capture failed: ${e.localizedMessage}")
                                            }
                                        }
                                    }
                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        synchronized(stateLock) {
                                            dispatchErrorLocked("Legacy session configuration failed")
                                        }
                                    }
                                },
                                handler
                            )
                        }
                    } catch (e: Exception) {
                        synchronized(stateLock) {
                            dispatchErrorLocked("Failed to configure camera output streams: ${e.localizedMessage}")
                        }
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    synchronized(stateLock) {
                        if (activeCamera == null) activeCamera = camera
                        dispatchErrorLocked("Camera disconnected during capture")
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    synchronized(stateLock) {
                        if (activeCamera == null) activeCamera = camera
                        dispatchErrorLocked("Camera hardware error: code $error")
                    }
                }

                override fun onClosed(camera: CameraDevice) {
                    synchronized(stateLock) {
                        finalizeCameraCloseLocked()
                    }
                }
            }, handler)

        } catch (e: Exception) {
            stopBackgroundThread()
            onError("Capture system unavailable: ${e.localizedMessage}")
        }
    }

    private fun saveJpegBufferToMediaStore(
        buffer: java.nio.ByteBuffer,
        filter: CameraFilter,
        mirrorSelfie: Boolean,
        watermarkConfig: WatermarkConfig?,
        isSaveLocation: Boolean = false,
        activeFocalLengthMm: Float? = null,
        privateOriginalStorage: PrivateOriginalStorageManager? = null,
        retainCleanOriginal: Boolean = false,
        watermarkCacheLimitBytes: Long = 0L,
        aspectRatio: CaptureAspectRatio = CaptureAspectRatio.RATIO_4_3,
        fullScreenAspectRatio: Float = 9f / 20f,
        onEvictionNotice: ((CacheEvictionResult) -> Unit)? = null
    ): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val fileName = "StuDO_UHR_$timeStamp.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StuDO")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null
        var retainedEntry: PrivateOriginalEntry? = null

        try {
            val bytesWritten = MemoryMappedBufferUtil.processAndSerializeJpeg(
                context = context,
                targetUri = uri,
                sourceBuffer = buffer,
                filter = filter,
                mirrorSelfie = mirrorSelfie,
                watermarkConfig = watermarkConfig,
                isSaveLocation = isSaveLocation,
                activeFocalLengthMm = activeFocalLengthMm,
                privateOriginalStorage = privateOriginalStorage,
                retainCleanOriginal = retainCleanOriginal,
                onOriginalRetained = { entry -> retainedEntry = entry },
                aspectRatio = aspectRatio,
                fullScreenAspectRatio = fullScreenAspectRatio
            )

            if (bytesWritten <= 0) {
                retainedEntry?.let { privateOriginalStorage?.removeOriginalById(it.originalId) }
                try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                return null
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, updateValues, null, null)
            }

            val retained = retainedEntry
            if (retained != null && privateOriginalStorage != null && watermarkConfig != null) {
                try {
                    privateOriginalStorage.linkGalleryOutput(
                        galleryUri = uri,
                        originalId = retained.originalId,
                        watermarkConfig = watermarkConfig
                    )
                    if (watermarkCacheLimitBytes > 0L) {
                        val eviction = privateOriginalStorage.enforceCacheLimitBytes(
                            limitBytes = watermarkCacheLimitBytes,
                            protectedOriginalId = retained.originalId
                        )
                        if (eviction.evictedCount > 0) {
                            onEvictionNotice?.let { cb ->
                                mainHandler.post { cb(eviction) }
                            }
                        }
                    }
                } finally {
                    privateOriginalStorage.unmarkOriginalActive(retained.originalId)
                }
            }
            return uri
        } catch (t: Throwable) {
            android.util.Log.e("UltraResCapture", "Error writing JPEG to disk", t)
            retainedEntry?.let { privateOriginalStorage?.removeOriginalById(it.originalId) }
            try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            return null
        }
    }

    private fun computeExifOrientation(characteristics: CameraCharacteristics, targetRotation: Int): Int {
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
        val deviceDegrees = when (targetRotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        val jpegRotation = if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + deviceDegrees) % 360
        } else {
            (sensorOrientation - deviceDegrees + 360) % 360
        }
        return when (jpegRotation) {
            90 -> android.media.ExifInterface.ORIENTATION_ROTATE_90
            180 -> android.media.ExifInterface.ORIENTATION_ROTATE_180
            270 -> android.media.ExifInterface.ORIENTATION_ROTATE_270
            else -> android.media.ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun writeDngToMediaStore(
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult,
        rawImage: Image,
        targetRotation: Int = android.view.Surface.ROTATION_0,
        isSaveLocation: Boolean = false
    ): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val fileName = "StuDO_RAW_$timeStamp.dng"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StuDO")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null

        try {
            val dngCreator = DngCreator(characteristics, captureResult)
            try {
                dngCreator.setOrientation(computeExifOrientation(characteristics, targetRotation))
                if (isSaveLocation) {
                    val hasFine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    val hasCoarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (hasFine || hasCoarse) {
                        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                        val loc = lm?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                            ?: lm?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                        if (loc != null) dngCreator.setLocation(loc)
                    }
                }
            } catch (_: Throwable) {}
            val bytesWritten = MemoryMappedBufferUtil.serializeDngToUri(
                context = context,
                targetUri = uri,
                characteristics = characteristics,
                dngCreator = dngCreator,
                rawImage = rawImage
            )

            if (bytesWritten <= 0) {
                try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                return null
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, updateValues, null, null)
            }
            return uri
        } catch (t: Throwable) {
            android.util.Log.e("UltraResCapture", "Error writing RAW DNG", t)
            try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            return null
        }
    }
}
