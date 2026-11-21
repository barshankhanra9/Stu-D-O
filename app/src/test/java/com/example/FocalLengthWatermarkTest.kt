package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ExifInterface
import androidx.camera.core.CameraSelector
import androidx.test.core.app.ApplicationProvider
import com.example.data.CameraCapabilityDetector
import com.example.model.WatermarkConfig
import com.example.util.CameraMetadataUtil
import com.example.util.RealCameraMetadata
import com.example.viewmodel.CameraViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowCameraManager
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FocalLengthWatermarkTest {

    private fun registerFakeCamera(
        shadowCameraManager: ShadowCameraManager,
        cameraId: String,
        lensFacing: Int,
        focalLengths: FloatArray?
    ) {
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics()
        val shadowChars = Shadows.shadowOf(chars)
        shadowChars.set(CameraCharacteristics.LENS_FACING, lensFacing)
        if (focalLengths != null) {
            shadowChars.set(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, focalLengths)
        }
        shadowChars.set(
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL,
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL
        )
        shadowChars.set(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
            intArrayOf(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)
        )
        shadowCameraManager.addCamera(cameraId, chars)
    }

    private fun createTempJpeg(): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("exif_test_", ".jpg", context.cacheDir)
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bmp.recycle()
        return file
    }

    @Test
    fun `CameraCapabilityDetector and CameraViewModel track Moto G34 physical focal lengths across front, rear, and multi-lens switches`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val cameraManager = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val shadowCameraManager = Shadows.shadowOf(cameraManager)

        // Simulate Moto G34 rear main (4.71mm), front (2.86mm), plus extra lens IDs (1.68mm ultrawide, 19mm tele)
        registerFakeCamera(shadowCameraManager, "0", CameraCharacteristics.LENS_FACING_BACK, floatArrayOf(4.71f))
        registerFakeCamera(shadowCameraManager, "1", CameraCharacteristics.LENS_FACING_FRONT, floatArrayOf(2.86f))
        registerFakeCamera(shadowCameraManager, "2", CameraCharacteristics.LENS_FACING_BACK, floatArrayOf(1.68f))
        registerFakeCamera(shadowCameraManager, "3", CameraCharacteristics.LENS_FACING_BACK, floatArrayOf(19.0f))

        val detector = CameraCapabilityDetector(app)
        val cameras = detector.detectAllCameras(forceRefresh = true)
        assertEquals(4, cameras.size)
        assertEquals(4.71f, detector.getCapabilityForLens(isFrontCamera = false)?.primaryFocalLengthMm)
        assertEquals(2.86f, detector.getCapabilityForLens(isFrontCamera = true)?.primaryFocalLengthMm)
        assertEquals(1.68f, detector.getFocalLengthForCameraId("2"))
        assertEquals(19.0f, detector.getFocalLengthForCameraId("3"))

        val vm = CameraViewModel(app)
        // Initial rear camera should resolve to 4.7mm (NOT hardcoded 24mm)
        assertEquals(CameraSelector.LENS_FACING_BACK, vm.uiState.value.lensFacing)
        assertEquals("4.7mm", vm.uiState.value.activeFocalLengthText)
        assertEquals("4.7mm", vm.activeFocalLengthTextState.value)

        // Switching to front camera updates focal length dynamically to 2.9mm
        vm.toggleLens()
        assertEquals(CameraSelector.LENS_FACING_FRONT, vm.uiState.value.lensFacing)
        assertEquals("2.9mm", vm.uiState.value.activeFocalLengthText)
        assertEquals("2.9mm", vm.activeFocalLengthTextState.value)

        // Switching back to rear restores 4.7mm
        vm.toggleLens()
        assertEquals(CameraSelector.LENS_FACING_BACK, vm.uiState.value.lensFacing)
        assertEquals("4.7mm", vm.uiState.value.activeFocalLengthText)

        // Multi-camera CaptureResult focal length change (e.g., ultrawide) updates dynamically
        vm.onActiveLensFocalLengthChanged(focalLengthMm = 1.68f, physicalCameraId = "2")
        assertEquals("1.7mm", vm.uiState.value.activeFocalLengthText)

        // Active physical camera ID change without CaptureResult.LENS_FOCAL_LENGTH falls back to physical camera's characteristic
        vm.onActiveLensFocalLengthChanged(focalLengthMm = null, physicalCameraId = "3")
        assertEquals("19mm", vm.uiState.value.activeFocalLengthText)
    }

    @Test
    fun `missing LENS_INFO_AVAILABLE_FOCAL_LENGTHS hides focal length instead of inventing 24mm`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val cameraManager = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val shadowCameraManager = Shadows.shadowOf(cameraManager)

        registerFakeCamera(shadowCameraManager, "0", CameraCharacteristics.LENS_FACING_BACK, null)

        val vm = CameraViewModel(app)
        assertNull(vm.uiState.value.activeFocalLengthMm)
        assertNull(vm.uiState.value.activeFocalLengthText)

        // Watermark exposure triangle omits focal length cleanly when unavailable
        val lensOnlyCfg = WatermarkConfig(showLensInfo = true, showExifSpecs = false)
        val noFocalMeta = RealCameraMetadata(aperture = "f/1.8", shutterSpeed = "1/120s", iso = "ISO 100")
        assertEquals("", noFocalMeta.formatExposureTriangle(lensOnlyCfg))

        val withSpecsCfg = WatermarkConfig(showLensInfo = true, showExifSpecs = true)
        assertEquals("f/1.8  1/120s  ISO 100", noFocalMeta.formatExposureTriangle(withSpecsCfg))

        // Sample preview metadata never invents 24mm
        val previewNoFocal = CameraMetadataUtil.samplePreviewMetadata(width = 720, height = 540)
        assertNull(previewNoFocal.focalLength)
        assertFalse(previewNoFocal.formatExposureTriangle(withSpecsCfg).contains("24mm"))
        assertEquals("", previewNoFocal.formatExposureTriangle(lensOnlyCfg))
    }

    @Test
    fun `CameraMetadataUtil reads physical TAG_FOCAL_LENGTH and never confuses FocalLengthIn35mmFilm`() {
        val jpgWithBoth = createTempJpeg()
        try {
            val exif = ExifInterface(jpgWithBoth.absolutePath)
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "471/100")
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, "24")
            exif.saveAttributes()

            val meta = CameraMetadataUtil.fromFile(jpgWithBoth)
            assertEquals("4.7mm", meta.focalLength)
            assertEquals("4.7mm", meta.lensInfo)
        } finally {
            jpgWithBoth.delete()
        }

        val jpgWithOnly35mmEquiv = createTempJpeg()
        try {
            val exif = ExifInterface(jpgWithOnly35mmEquiv.absolutePath)
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, "24")
            exif.saveAttributes()

            val meta = CameraMetadataUtil.fromFile(jpgWithOnly35mmEquiv)
            assertNull("Must not substitute 35mm equivalent focal length", meta.focalLength)
            assertNull(meta.lensInfo)

            val enriched = CameraMetadataUtil.withActiveFocalLengthIfMissing(meta, 4.71f)
            assertEquals("4.7mm", enriched.focalLength)

            // Existing EXIF physical focal length is never overwritten by active camera focal length
            val alreadyHasPhysical = meta.copy(focalLength = "2.9mm", lensInfo = "2.9mm")
            val preserved = CameraMetadataUtil.withActiveFocalLengthIfMissing(alreadyHasPhysical, 4.71f)
            assertEquals("2.9mm", preserved.focalLength)
        } finally {
            jpgWithOnly35mmEquiv.delete()
        }
    }
}
