package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import com.example.data.CameraPreferencesRepository
import com.example.data.CameraSensorCapability
import com.example.data.MediaCaptureManager
import com.example.editor.DrawPathItem
import com.example.editor.PhotoProcessingEngine
import com.example.model.CameraMode
import com.example.model.PhotoResolution
import com.example.model.VideoQualityOption
import com.example.model.WatermarkConfig
import com.example.model.WatermarkPreset
import com.example.model.WatermarkStyle
import com.example.util.MemoryMappedBufferUtil
import com.example.util.RealCameraMetadata
import com.example.util.VideoEncoderUtil
import com.example.util.WatermarkPainter
import com.example.viewmodel.CameraViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuditRegressionTest {

    @Test
    fun `serializeDirectBufferToUri truncates oversized existing destination file without trailing bytes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempFile = File(context.cacheDir, "audit_truncate_test_${System.nanoTime()}.bin")
        try {
            tempFile.writeBytes(ByteArray(256) { 0x7F.toByte() })
            assertEquals(256L, tempFile.length())

            val smallerPayload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
            val direct = ByteBuffer.allocateDirect(smallerPayload.size).apply {
                put(smallerPayload)
                flip()
            }

            val writtenBytes = MemoryMappedBufferUtil.serializeDirectBufferToUri(
                context,
                Uri.fromFile(tempFile),
                direct
            )
            assertEquals(smallerPayload.size.toLong(), writtenBytes)
            assertEquals(smallerPayload.size.toLong(), tempFile.length())
            assertEquals(smallerPayload.toList(), tempFile.readBytes().toList())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `preferences repository persists showManufacturer across watermark config and presets`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = CameraPreferencesRepository(context)

        val disabledConfig = WatermarkConfig(
            isEnabled = true,
            style = WatermarkStyle.LEICA_FRAME,
            showManufacturer = false,
            customText = "AUDIT"
        )
        repo.saveWatermarkConfig(disabledConfig)
        val restoredConfig = repo.getWatermarkConfig()
        assertFalse(restoredConfig.showManufacturer)
        assertEquals("AUDIT", restoredConfig.customText)

        val presets = listOf(
            WatermarkPreset(
                id = "preset-no-oem",
                name = "No Manufacturer",
                config = disabledConfig
            ),
            WatermarkPreset(
                id = "preset-with-oem",
                name = "With Manufacturer",
                config = disabledConfig.copy(showManufacturer = true)
            )
        )
        repo.saveWatermarkPresets(presets)
        val restoredPresets = repo.getWatermarkPresets()
        assertEquals(2, restoredPresets.size)
        assertFalse(restoredPresets[0].config.showManufacturer)
        assertTrue(restoredPresets[1].config.showManufacturer)
    }

    @Test
    fun `eraser drawing strokes preserve opaque underlying photo pixels in offscreen saveLayer`() {
        val width = 80
        val height = 80
        val baseBlue = Color.rgb(10, 40, 200)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(baseBlue)
        }

        val redStroke = DrawPathItem(
            points = listOf(Offset(0.1f, 0.5f), Offset(0.9f, 0.5f)),
            color = androidx.compose.ui.graphics.Color.Red,
            strokeWidth = 12f,
            isEraser = false
        )
        val eraserStroke = DrawPathItem(
            points = listOf(Offset(0.5f, 0.1f), Offset(0.5f, 0.9f)),
            color = androidx.compose.ui.graphics.Color.Transparent,
            strokeWidth = 16f,
            isEraser = true
        )

        PhotoProcessingEngine.renderDrawingPaths(bmp, listOf(redStroke, eraserStroke))

        // Intersection (center pixel) should have been erased back to opaque baseBlue (NOT transparent 0x00000000)
        val centerPixel = bmp.getPixel(40, 40)
        assertEquals(255, Color.alpha(centerPixel))
        assertEquals(baseBlue, centerPixel)

        // Left segment of redStroke outside eraser should still be red
        val leftStrokePixel = bmp.getPixel(16, 40)
        assertEquals(255, Color.alpha(leftStrokePixel))
        assertTrue(Color.red(leftStrokePixel) > Color.blue(leftStrokePixel))

        bmp.recycle()
    }

    @Test
    fun `watermark painter handles empty metadata cleanly across all styles without crashing`() {
        val emptyMeta = RealCameraMetadata()
        for (style in WatermarkStyle.values()) {
            val source = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.DKGRAY)
            }
            val cfg = WatermarkConfig(
                isEnabled = true,
                style = style,
                showDeviceModel = true,
                showDateTime = true,
                showLensInfo = true,
                showExifSpecs = true,
                showManufacturer = true
            )
            val stamped = WatermarkPainter.applyWatermark(source, cfg, emptyMeta, null)
            assertNotNull(stamped)
            assertFalse(stamped.isRecycled)
            assertTrue(stamped.width >= 320)
            assertTrue(stamped.height >= 240)
            if (stamped !== source && !source.isRecycled) source.recycle()
            if (!stamped.isRecycled) stamped.recycle()
        }
    }

    @Test
    fun `camera sensor capability defaults honestly without fabricating video or resolution support`() {
        val fakeCap = CameraSensorCapability(
            cameraId = "test_cam",
            facing = "BACK",
            hardwareLevel = "LIMITED",
            pixelArraySize = null,
            isUltraHighResolutionSupported = false,
            standardJpegSizes = emptyList(),
            maxResolutionJpegSizes = emptyList(),
            highResolutionJpegSizes = emptyList(),
            standardRawSizes = emptyList(),
            maxResolutionRawSizes = emptyList(),
            isRawSupported = false,
            physicalCameraIds = emptyList(),
            maxJpegMegapixels = 0.0,
            standardMaxJpegMegapixels = 0.0,
            recommendedUhrSize = null
        )

        assertFalse(fakeCap.isVideoRecordingSupported)
        assertTrue(fakeCap.supportedVideoQualities.isEmpty())
        assertTrue(fakeCap.supportedVideoFps.isEmpty())
        assertEquals(0, fakeCap.highestGenuineJpegSize.width)
        assertEquals(0, fakeCap.highestGenuineJpegSize.height)
        assertTrue(fakeCap.getDynamicResolutionOptions().isEmpty())
    }

    @Test
    fun `viewmodel isolates histogram updates, scopes rebind keys, handles hardware errors and clears cleanly`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = CameraViewModel(app)

        val initialUi = vm.uiState.value
        val sampleBins = listOf(0.1f, 0.5f, 1.0f, 0.3f)
        vm.updateHistogramBins(sampleBins)
        assertEquals(sampleBins, vm.histogramBins.value)
        assertEquals(initialUi, vm.uiState.value)

        // Photo resolution change in VIDEO mode must not bump viewfinderRebindKey
        vm.setCameraMode(CameraMode.VIDEO)
        val rebindInVideo = vm.uiState.value.viewfinderRebindKey
        vm.setPhotoResolution(PhotoResolution.HIGH_12MP)
        assertEquals(rebindInVideo, vm.uiState.value.viewfinderRebindKey)

        // Video quality change in PHOTO mode must not bump viewfinderRebindKey
        vm.setCameraMode(CameraMode.PHOTO)
        val rebindInPhoto = vm.uiState.value.viewfinderRebindKey
        vm.setVideoQuality(VideoQualityOption.QUALITY_720P)
        assertEquals(rebindInPhoto, vm.uiState.value.viewfinderRebindKey)

        // Switching to VIDEO mode resets stale histogram bins
        vm.updateHistogramBins(sampleBins)
        vm.setCameraMode(CameraMode.VIDEO)
        assertTrue(vm.histogramBins.value.isEmpty())

        // Location sync & toggle updates state safely
        vm.toggleSaveLocation(true)
        assertTrue(vm.uiState.value.isSaveLocationEnabled)
        vm.syncLocationPermission(false)
        assertFalse(vm.uiState.value.isSaveLocationEnabled)
        assertFalse(vm.uiState.value.hasLocationPermission)

        // Hardware error callback updates readiness & streaming flags cleanly
        vm.onStreamStateChanged(true)
        vm.onCameraHardwareError("Camera is currently in use by another app", isFatal = false)
        assertFalse(vm.uiState.value.isCameraReady)
        assertFalse(vm.uiState.value.isStreaming)
        assertEquals("Camera is currently in use by another app", vm.uiState.value.userMessage)

        // Clear via ViewModelStore triggers onCleared() and executor shutdown without throwing
        val store = ViewModelStore()
        store.put("audit_vm", vm)
        store.clear()
    }

    @Test
    fun `media capture manager storage check and video codec config helper behave safely`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ShadowStatFs.registerStats(context.filesDir, 1000, 100, 100)
        val manager = MediaCaptureManager(context)
        assertTrue(manager.hasSufficientStorage(minBytes = 1024L))
        assertFalse(manager.hasSufficientStorage(minBytes = 50L * 1024L * 1024L))
        manager.shutdown()

        VideoEncoderUtil.clearCacheForTesting()
        // In Robolectric JVM with empty MediaCodecList, unsupported mime must cleanly return false
        assertFalse(VideoEncoderUtil.isCodecSupportedForConfig("video/hevc", 3840, 2160, 60))
    }
}
