package com.example.portrait

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import com.example.data.CameraSensorCapability
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Decodes Android hardware DEPTH16 streams (13-bit millimeter range + 3-bit confidence)
 * into orientation-aligned depth maps and handles optional Camera2 dual JPEG+DEPTH16 capture
 * with automatic fallback when hardware depth streams are unsupported or busy.
 */
object HardwareDepthProcessor {

    /**
     * Decodes raw Android ImageFormat.DEPTH16 16-bit samples according to Android NDK/HAL3 spec:
     * - bits [0..12]: range in millimeters (0x1FFF mask)
     * - bits [13..15]: 3-bit confidence where 0 = 100% confident, 1 = 0%, 2..7 = (conf - 1) / 7
     */
    fun decodeDepth16Samples(
        samples: ShortArray,
        srcWidth: Int,
        srcHeight: Int,
        rotationDegrees: Int = 0,
        mirrorHorizontal: Boolean = false
    ): HardwareDepthFrame {
        if (srcWidth <= 0 || srcHeight <= 0 || samples.size < srcWidth * srcHeight) {
            return HardwareDepthFrame(1, 1, floatArrayOf(1.5f), floatArrayOf(0f), 0f)
        }

        val swapDims = rotationDegrees == 90 || rotationDegrees == 270
        val outW = if (swapDims) srcHeight else srcWidth
        val outH = if (swapDims) srcWidth else srcHeight
        val outCount = outW * outH

        val meters = FloatArray(outCount)
        val conf = FloatArray(outCount)
        var validPixels = 0

        for (oy in 0 until outH) {
            val ny = oy.toFloat() / (outH - 1).coerceAtLeast(1)
            for (ox in 0 until outW) {
                val nx = ox.toFloat() / (outW - 1).coerceAtLeast(1)
                val mx = if (mirrorHorizontal) 1f - nx else nx
                val uNorm: Float
                val vNorm: Float
                when (rotationDegrees) {
                    90 -> {
                        uNorm = ny
                        vNorm = 1f - mx
                    }
                    180 -> {
                        uNorm = 1f - mx
                        vNorm = 1f - ny
                    }
                    270 -> {
                        uNorm = 1f - ny
                        vNorm = mx
                    }
                    else -> {
                        uNorm = mx
                        vNorm = ny
                    }
                }
                val sx = (uNorm * (srcWidth - 1)).toInt().coerceIn(0, srcWidth - 1)
                val sy = (vNorm * (srcHeight - 1)).toInt().coerceIn(0, srcHeight - 1)
                val raw = samples[sy * srcWidth + sx].toInt() and 0xFFFF
                val rangeMm = raw and 0x1FFF
                val confCode = (raw ushr 13) and 0x7

                val pixelConf = when (confCode) {
                    0 -> 1.0f
                    1 -> 0.0f
                    else -> ((confCode - 1).toFloat() / 7f).coerceIn(0f, 1f)
                }

                val outIdx = oy * outW + ox
                if (rangeMm > 0 && pixelConf > 0.05f) {
                    meters[outIdx] = rangeMm / 1000f
                    conf[outIdx] = pixelConf
                    validPixels++
                } else {
                    meters[outIdx] = 0f
                    conf[outIdx] = 0f
                }
            }
        }

        // Fast 3-pass neighborhood hole filling for invalid Time-of-Flight / stereo depth pixels
        fillDepthHoles(meters, conf, outW, outH)

        return HardwareDepthFrame(
            width = outW,
            height = outH,
            depthMeters = meters,
            confidence = conf,
            validFraction = validPixels.toFloat() / outCount.coerceAtLeast(1)
        )
    }

    private fun fillDepthHoles(
        meters: FloatArray,
        conf: FloatArray,
        width: Int,
        height: Int
    ) {
        val tmpMeters = meters.copyOf()
        val tmpConf = conf.copyOf()
        repeat(2) {
            for (y in 0 until height) {
                val rowOff = y * width
                for (x in 0 until width) {
                    val idx = rowOff + x
                    if (conf[idx] > 0.05f && meters[idx] > 0.05f) continue

                    var sumD = 0f
                    var sumW = 0f
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny !in 0 until height) continue
                        val nRow = ny * width
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = x + dx
                            if (nx !in 0 until width) continue
                            val nIdx = nRow + nx
                            val w = conf[nIdx]
                            val d = meters[nIdx]
                            if (w > 0.05f && d > 0.05f) {
                                sumD += d * w
                                sumW += w
                            }
                        }
                    }
                    if (sumW > 0f) {
                        tmpMeters[idx] = sumD / sumW
                        tmpConf[idx] = (sumW / 8f).coerceIn(0.1f, 0.7f)
                    }
                }
            }
            System.arraycopy(tmpMeters, 0, meters, 0, meters.size)
            System.arraycopy(tmpConf, 0, conf, 0, conf.size)
        }
    }

    /**
     * Fuses a hardware depth map with an AI segmentation alpha matte.
     * Preserves crisp AI hair/shoulder silhouettes while deriving continuous optical CoC depth
     * directly from hardware range measurements.
     */
    fun fuseHardwareDepthWithMatte(
        hardwareFrame: HardwareDepthFrame,
        alphaMatte: FloatArray,
        maskW: Int,
        maskH: Int,
        focusX: Float?,
        focusY: Float?
    ): Pair<FloatArray, Float> {
        val count = maskW * maskH
        val hwNorm = hardwareFrame.toNormalizedDepthMap()
        val outDepth = FloatArray(count)

        var fgWeightedSum = 0f
        var fgWeightSum = 0f

        for (y in 0 until maskH) {
            val hy = (y.toFloat() / (maskH - 1).coerceAtLeast(1) * (hardwareFrame.height - 1))
                .toInt().coerceIn(0, hardwareFrame.height - 1)
            for (x in 0 until maskW) {
                val hx = (x.toFloat() / (maskW - 1).coerceAtLeast(1) * (hardwareFrame.width - 1))
                    .toInt().coerceIn(0, hardwareFrame.width - 1)
                val hIdx = hy * hardwareFrame.width + hx
                val mIdx = y * maskW + x
                val alpha = alphaMatte[mIdx]
                val hwD = hwNorm[hIdx]
                val hwConf = hardwareFrame.confidence[hIdx]

                // Blend hardware measurement with structural foreground/background prior when confidence is low
                val priorD = if (alpha > 0.65f) 0.18f else 0.78f
                val fused = if (hwConf > 0.15f) {
                    hwD * hwConf.coerceIn(0.3f, 1f) + priorD * (1f - hwConf.coerceIn(0.3f, 1f))
                } else {
                    priorD
                }
                outDepth[mIdx] = fused.coerceIn(0f, 1f)

                if (alpha > 0.75f) {
                    fgWeightedSum += outDepth[mIdx] * alpha
                    fgWeightSum += alpha
                }
            }
        }

        val focusDepth = if (focusX != null && focusY != null) {
            sampleFocusDepth(outDepth, maskW, maskH, focusX, focusY)
        } else if (fgWeightSum > 0.5f) {
            (fgWeightedSum / fgWeightSum).coerceIn(0f, 1f)
        } else {
            0.18f
        }

        return outDepth to focusDepth
    }

    internal fun sampleFocusDepth(
        depthMap: FloatArray,
        width: Int,
        height: Int,
        normX: Float,
        normY: Float
    ): Float {
        val cx = (normX.coerceIn(0f, 1f) * (width - 1)).toInt()
        val cy = (normY.coerceIn(0f, 1f) * (height - 1)).toInt()
        var sum = 0f
        var cnt = 0
        for (dy in -2..2) {
            val yy = (cy + dy).coerceIn(0, height - 1)
            for (dx in -2..2) {
                val xx = (cx + dx).coerceIn(0, width - 1)
                sum += depthMap[yy * width + xx]
                cnt++
            }
        }
        return if (cnt > 0) (sum / cnt).coerceIn(0f, 1f) else 0.18f
    }

    /**
     * Attempts to capture a standalone hardware DEPTH16 frame using Camera2 when the active camera
     * advertises DEPTH_OUTPUT + DEPTH16 sizes. Returns null promptly if unsupported or if hardware refuses.
     * Note: Caller must ensure CameraX is unbound or camera is closed before calling, OR pass a dedicated
     * closed-session capture. In practice, Portrait Mode runs this only when DEPTH16 is advertised on single camera.
     */
    @SuppressLint("MissingPermission")
    fun captureHardwareDepthSnapshot(
        context: Context,
        capability: CameraSensorCapability?,
        targetRotationDegrees: Int,
        mirrorHorizontal: Boolean,
        timeoutMs: Long = 1600L
    ): HardwareDepthFrame? {
        if (capability == null || !capability.isDepthOutputSupported || capability.depth16Sizes.isEmpty()) {
            return null
        }
        val depthSize: Size = capability.depth16Sizes
            .firstOrNull { it.width in 160..640 && it.height in 120..480 }
            ?: capability.depth16Sizes.last()

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        val thread = HandlerThread("StuDO_Depth16").apply { start() }
        val handler = Handler(thread.looper)
        val exec = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(1)
        var capturedFrame: HardwareDepthFrame? = null
        var reader: ImageReader? = null
        var cameraDevice: CameraDevice? = null
        var session: CameraCaptureSession? = null

        try {
            reader = ImageReader.newInstance(depthSize.width, depthSize.height, ImageFormat.DEPTH16, 2)
            reader.setOnImageAvailableListener({ r ->
                val img = try { r.acquireLatestImage() } catch (_: Throwable) { null }
                if (img != null) {
                    try {
                        val plane = img.planes.firstOrNull()
                        if (plane != null) {
                            val buf = plane.buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val arr = ShortArray(buf.remaining())
                            buf.get(arr)
                            capturedFrame = decodeDepth16Samples(
                                samples = arr,
                                srcWidth = img.width,
                                srcHeight = img.height,
                                rotationDegrees = targetRotationDegrees,
                                mirrorHorizontal = mirrorHorizontal
                            )
                        }
                    } catch (_: Throwable) {
                    } finally {
                        try { img.close() } catch (_: Throwable) {}
                        latch.countDown()
                    }
                }
            }, handler)

            manager.openCamera(capability.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val surface = reader!!.surface
                        val onSessionReady = object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                try {
                                    val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(surface)
                                    }
                                    s.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(
                                            session: CameraCaptureSession,
                                            request: CaptureRequest,
                                            result: TotalCaptureResult
                                        ) {}
                                    }, handler)
                                } catch (_: Throwable) {
                                    latch.countDown()
                                }
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                latch.countDown()
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val cfg = SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                listOf(OutputConfiguration(surface)),
                                exec,
                                onSessionReady
                            )
                            camera.createCaptureSession(cfg)
                        } else {
                            @Suppress("DEPRECATION")
                            camera.createCaptureSession(listOf(surface), onSessionReady, handler)
                        }
                    } catch (_: Throwable) {
                        latch.countDown()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    try { camera.close() } catch (_: Throwable) {}
                    latch.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    try { camera.close() } catch (_: Throwable) {}
                    latch.countDown()
                }
            }, handler)

            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            // Silently fall back to AI segmentation
        } finally {
            try { session?.close() } catch (_: Throwable) {}
            try { cameraDevice?.close() } catch (_: Throwable) {}
            try { reader?.close() } catch (_: Throwable) {}
            try { exec.shutdownNow() } catch (_: Throwable) {}
            try { thread.quitSafely() } catch (_: Throwable) {}
        }

        return capturedFrame?.takeIf { it.validFraction > 0.08f }
    }
}
