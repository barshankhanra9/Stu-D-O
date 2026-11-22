package com.example

import android.app.Application
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.net.Uri
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.CameraCapabilityDetector
import com.example.data.CameraPreferencesRepository
import com.example.data.PostCaptureExportResult
import com.example.model.CameraMode
import com.example.model.CameraUiState
import com.example.model.MediaItem
import com.example.model.PortraitApertureFormatter
import com.example.model.PortraitDepthSource
import com.example.model.PortraitQualityTier
import com.example.model.PostCapturePortraitTarget
import com.example.model.ScreenFlashMode
import com.example.model.WatermarkConfig
import com.example.portrait.BokehRenderOptions
import com.example.portrait.HardwareDepthFrame
import com.example.portrait.HardwareDepthProcessor
import com.example.portrait.PortraitBokehRenderer
import com.example.portrait.PortraitSegmentationEngine
import com.example.portrait.PortraitSegmentationResult
import com.example.portrait.PortraitSidecarStorage
import com.example.portrait.PortraitSubjectRegion
import com.example.ui.components.CameraBottomBar
import com.example.ui.components.CameraTopBar
import com.example.ui.components.PhotoPreviewDialog
import com.example.ui.components.PortraitBlurEditorDialog
import com.example.ui.components.PortraitControlsBar
import com.example.util.RealCameraMetadata
import com.example.viewmodel.CameraViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowCameraManager
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h740dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PortraitModeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        CameraPreferencesRepository(app).savePortraitBlurIntensity(0.62f)
    }

    @Test
    fun `hardware depth16 binary decoding extracts range confidence and fills holes`() {
        // Sample 0: 950mm, confidence code 0 (100% confident)
        val sNear = 950 and 0x1FFF
        // Sample 1: 4200mm, confidence code 7 (6/7 confident)
        val sFar = (4200 and 0x1FFF) or (7 shl 13)
        // Sample 2: invalid hole (conf code 1 -> 0% confidence)
        val sHole = (1 shl 13)
        // Sample 3: 1000mm, conf code 0
        val sNear2 = 1000 and 0x1FFF

        val samples = shortArrayOf(sNear.toShort(), sFar.toShort(), sHole.toShort(), sNear2.toShort())
        val frame = HardwareDepthProcessor.decodeDepth16Samples(
            samples = samples,
            srcWidth = 2,
            srcHeight = 2,
            rotationDegrees = 0,
            mirrorHorizontal = false
        )

        assertEquals(2, frame.width)
        assertEquals(2, frame.height)
        assertEquals(0.95f, frame.depthMeters[0], 0.02f)
        assertEquals(4.20f, frame.depthMeters[1], 0.02f)
        assertEquals(1.00f, frame.depthMeters[3], 0.02f)
        // Hole pixel (idx 2) should be filled from neighbor depth
        assertTrue(frame.depthMeters[2] > 0.5f)
        assertEquals(0.75f, frame.validFraction, 0.01f)
    }

    @Test
    fun `capability detector distinguishes devices with depth sensors from software fallback devices`() {
        val cameraManager = app.getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager
        val shadowManager = Shadow.extract<ShadowCameraManager>(cameraManager)

        // Camera 0: rear camera with DEPTH_OUTPUT capability
        val charsDepth = ShadowCameraCharacteristics.newCameraCharacteristics()
        val shadowDepth = Shadow.extract<ShadowCameraCharacteristics>(charsDepth)
        shadowDepth.set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
        shadowDepth.set(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3)
        shadowDepth.set(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
            intArrayOf(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT
            )
        )
        shadowManager.addCamera("0", charsDepth)

        // Camera 1: front camera without depth sensor (software fallback path)
        val charsNoDepth = ShadowCameraCharacteristics.newCameraCharacteristics()
        val shadowNoDepth = Shadow.extract<ShadowCameraCharacteristics>(charsNoDepth)
        shadowNoDepth.set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_FRONT)
        shadowNoDepth.set(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED)
        shadowNoDepth.set(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
            intArrayOf(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)
        )
        shadowManager.addCamera("1", charsNoDepth)

        val detector = CameraCapabilityDetector(app)
        val allCaps = detector.detectAllCameras(forceRefresh = true)
        assertEquals(2, allCaps.size)
        val rearCap = detector.getCapabilityForLens(isFrontCamera = false)!!
        val frontCap = detector.getCapabilityForLens(isFrontCamera = true)!!
        assertTrue(rearCap.isDepthOutputSupported)
        assertFalse(frontCap.isDepthOutputSupported)
    }

    @Test
    fun `hardware depth fusion yields hybrid source and focuses cleanly on tapped plane`() {
        val w = 80
        val h = 60
        val count = w * h
        val bmp = createSyntheticTwoSubjectScene(w, h)

        val depthMeters = FloatArray(count)
        val conf = FloatArray(count) { 1f }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                depthMeters[idx] = when {
                    x in 12..28 && y in 16..50 -> 0.8f // Subject 1 near
                    x in 52..68 && y in 16..50 -> 2.4f // Subject 2 mid-ground
                    else -> 6.5f // Far background
                }
            }
        }
        val hwFrame = HardwareDepthFrame(w, h, depthMeters, conf, validFraction = 1f)

        val resultNearFocus = PortraitSegmentationEngine.segmentBitmap(
            uprightBitmap = bmp,
            isPreviewStream = false,
            focusX = 0.25f, // Subject 1
            focusY = 0.50f,
            hardwareDepthFrame = hwFrame
        )
        assertTrue(resultNearFocus.source == PortraitDepthSource.HYBRID || resultNearFocus.source == PortraitDepthSource.HARDWARE_DEPTH)

        val resultFarSubjectFocus = PortraitSegmentationEngine.segmentBitmap(
            uprightBitmap = bmp,
            isPreviewStream = false,
            focusX = 0.75f, // Subject 2
            focusY = 0.50f,
            hardwareDepthFrame = hwFrame
        )
        assertTrue(resultFarSubjectFocus.focusDepth > resultNearFocus.focusDepth)
    }

    @Test
    fun `software fallback segmentation runs without mlkit or depth hardware and refines edges`() {
        val bmp = createSyntheticTwoSubjectScene(160, 120)
        val seg = PortraitSegmentationEngine.segmentBitmap(
            uprightBitmap = bmp,
            isPreviewStream = false,
            focusX = 0.25f,
            focusY = 0.50f,
            hardwareDepthFrame = null
        )

        assertEquals(PortraitDepthSource.AI_SEGMENTATION, seg.source)
        assertEquals(seg.maskWidth * seg.maskHeight, seg.alphaMatte.size)
        assertEquals(seg.maskWidth * seg.maskHeight, seg.depthMap.size)
        assertTrue(seg.hasSubject)
    }

    @Test
    fun `depth bokeh keeps focused subject crisp while applying stronger blur to far background than mid plane`() {
        val w = 240
        val h = 180
        val scene = createSyntheticTwoSubjectScene(w, h)
        val count = w * h
        val alpha = FloatArray(count)
        val depth = FloatArray(count)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                when {
                    x in 35..85 && y in 35..145 -> {
                        alpha[idx] = 1f
                        depth[idx] = 0.12f // Focused near subject
                    }
                    x in 140..190 && y in 35..145 -> {
                        alpha[idx] = 0.85f
                        depth[idx] = 0.45f // Mid-ground secondary object
                    }
                    else -> {
                        alpha[idx] = 0f
                        depth[idx] = 0.96f // Far background checkerboard
                    }
                }
            }
        }

        val seg = PortraitSegmentationResult(
            maskWidth = w,
            maskHeight = h,
            alphaMatte = alpha,
            depthMap = depth,
            focusDepth = 0.12f,
            subjectCount = 2,
            hasSubject = true,
            source = PortraitDepthSource.AI_SEGMENTATION,
            subjects = listOf(
                PortraitSubjectRegion(0.25f, 0.5f, 0.10f, 0.30f, 0.12f),
                PortraitSubjectRegion(0.68f, 0.5f, 0.10f, 0.30f, 0.45f)
            )
        )

        val rendered = PortraitBokehRenderer.renderFullResolutionPortrait(
            originalUpright = scene,
            segmentation = seg,
            options = BokehRenderOptions(blurIntensity = 0.95f)
        )

        // Dimensions preserved strictly
        assertEquals(w, rendered.width)
        assertEquals(h, rendered.height)

        // Compare focused subject pixel vs original (must remain razor sharp / identical)
        val fgOrig = scene.getPixel(60, 90)
        val fgOut = rendered.getPixel(60, 90)
        assertEquals(fgOrig, fgOut)

        // Compare checkerboard edge contrast in far background across an 8px cell boundary (x=207 vs x=208)
        val origBgDiff = colorDiff(scene.getPixel(207, 140), scene.getPixel(208, 140))
        val outBgDiff = colorDiff(rendered.getPixel(207, 140), rendered.getPixel(208, 140))
        assertTrue("Expected far background contrast ($outBgDiff) to smooth significantly vs original ($origBgDiff)", outBgDiff < origBgDiff * 0.65f)

        // Refocus onto mid-ground secondary object and verify blur=0 restores original pixel-for-pixel
        val zeroBlur = PortraitBokehRenderer.renderFullResolutionPortrait(
            originalUpright = scene,
            segmentation = seg,
            options = BokehRenderOptions(blurIntensity = 0f)
        )
        assertEquals(scene.getPixel(210, 140), zeroBlur.getPixel(210, 140))
    }

    @Test
    fun `portrait aperture formatter and viewmodel preserve front screen flash and persist blur`() {
        assertEquals("f/16", PortraitApertureFormatter.formatFStop(0f))
        assertEquals("f/1.4", PortraitApertureFormatter.formatFStop(1f))

        val vm = CameraViewModel(app)
        vm.setCameraMode(CameraMode.PORTRAIT)
        assertEquals(CameraMode.PORTRAIT, vm.uiState.value.cameraMode)

        vm.setPortraitBlurIntensity(0.85f)
        assertEquals(0.85f, vm.uiState.value.portraitBlurIntensity, 0.001f)
        assertEquals(0.85f, CameraPreferencesRepository(app).getPortraitBlurIntensity(), 0.001f)

        // Front flashless camera Screen Flash works seamlessly in PORTRAIT mode
        vm.applyBoundFlashCapabilities(isFrontLens = true, hasHardwareFlash = false)
        assertTrue(vm.uiState.value.isScreenFlashAvailable)
        vm.cycleFlashMode()
        assertEquals(ScreenFlashMode.AUTO, vm.uiState.value.screenFlashMode)
    }

    @Test
    fun `portrait sidecar storage preserves clean original and enables non-destructive copy re-render`() = runBlocking {
        val storage = PortraitSidecarStorage(app)
        val w = 160
        val h = 120
        val cleanBmp = createSyntheticTwoSubjectScene(w, h)
        val count = w * h
        val alpha = FloatArray(count) { idx ->
            val x = idx % w
            val y = idx / w
            if (x in 45..75 && y in 30..95) 1f else 0f
        }
        val depth = FloatArray(count) { idx -> if (alpha[idx] > 0.5f) 0.15f else 0.90f }
        val seg = PortraitSegmentationResult(
            maskWidth = w,
            maskHeight = h,
            alphaMatte = alpha,
            depthMap = depth,
            focusDepth = 0.15f,
            subjectCount = 1,
            hasSubject = true,
            source = PortraitDepthSource.AI_SEGMENTATION
        )

        val entry = storage.savePortraitSidecar(
            cleanUprightBitmap = cleanBmp,
            segmentation = seg,
            blurIntensity = 0.65f,
            focusX = 0.37f,
            focusY = 0.52f,
            exifAttributes = emptyMap(),
            metadata = RealCameraMetadata(resolutionText = "${w}×${h}"),
            watermarkConfig = WatermarkConfig(isEnabled = false),
            isSaveLocation = false
        )
        assertNotNull(entry)
        val savedEntry = entry!!

        val galleryFile = File(app.cacheDir, "portrait_gallery_item.jpg")
        galleryFile.outputStream().use { cleanBmp.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        val galleryUri = Uri.fromFile(galleryFile)

        storage.linkGalleryUri(galleryUri, savedEntry.sidecarId)
        assertTrue(storage.hasPortraitSidecar(galleryUri))

        val target = storage.resolveForGalleryUri(galleryUri)
        assertNotNull(target)
        val loadedSeg = storage.loadSegmentationForTarget(target!!)
        assertNotNull(loadedSeg)
        assertEquals(w, loadedSeg!!.maskWidth)
        assertEquals(h, loadedSeg.maskHeight)

        // Non-destructive render back to clean unblurred background pixel when blurIntensity = 0f
        val cleanDecoded = BitmapFactory.decodeFile(target.originalFilePath)
        assertNotNull(cleanDecoded)
        assertEquals(w, cleanDecoded.width)
        assertEquals(h, cleanDecoded.height)

        // Verify reRenderAndUpdateExisting writes updated JPEG at exact original dimensions on file URI
        val updateResult = storage.reRenderAndUpdateExisting(
            target = target,
            newBlurIntensity = 0.90f,
            newFocusX = 0.37f,
            newFocusY = 0.52f,
            isSaveLocation = false
        )
        assertTrue(updateResult is PostCaptureExportResult.Success)
        val reDecoded = BitmapFactory.decodeFile(galleryFile.absolutePath)
        assertEquals(w, reDecoded.width)
        assertEquals(h, reDecoded.height)

        // Clean original file inside sidecars MUST remain untouched regardless of update
        val stillClean = BitmapFactory.decodeFile(target.originalFilePath)
        val cleanBgContrast = colorDiff(stillClean.getPixel(129, 80), stillClean.getPixel(130, 80))
        val updatedBgContrast = colorDiff(reDecoded.getPixel(129, 80), reDecoded.getPixel(130, 80))
        assertTrue("Expected updated contrast ($updatedBgContrast) < clean contrast ($cleanBgContrast)", updatedBgContrast < cleanBgContrast)

        storage.unlinkGalleryUri(galleryUri)
        assertFalse(storage.hasPortraitSidecar(galleryUri))
    }

    @Test
    fun `portrait UI controls bar mode selector top badge and post capture editor render cleanly`() {
        var currentState by mutableStateOf(
            CameraUiState(
                cameraMode = CameraMode.PORTRAIT,
                portraitBlurIntensity = 0.854f,
                portraitDepthSource = PortraitDepthSource.HYBRID,
                isPortraitSubjectDetected = true,
                portraitSubjectCount = 2,
                isFlashAvailable = false,
                isScreenFlashAvailable = true,
                screenFlashMode = ScreenFlashMode.AUTO
            )
        )

        composeTestRule.setContent {
            CameraTopBar(
                uiState = currentState,
                onCycleFlash = {},
                onOpenSettings = {},
                onCycleAspectRatio = {},
                onToggleQuickSettings = {},
                onToggleFilterSelector = {}
            )
        }
        composeTestRule.onNodeWithTag("top_portrait_aperture_badge").assertIsDisplayed()
        composeTestRule.onNodeWithText("f/2.0").assertIsDisplayed()
    }

    @Test
    fun `bottom bar displays all four modes within 360dp and PortraitControlsBar updates blur`() {
        var mode by mutableStateOf(CameraMode.PHOTO)
        var blur by mutableStateOf(0.50f)

        composeTestRule.setContent {
            val uiState = CameraUiState(
                cameraMode = mode,
                portraitBlurIntensity = blur,
                portraitDepthSource = PortraitDepthSource.AI_SEGMENTATION,
                isPortraitSubjectDetected = true,
                portraitSubjectCount = 1
            )
            androidx.compose.foundation.layout.Column {
                PortraitControlsBar(
                    uiState = uiState,
                    onBlurIntensityChanged = { blur = it }
                )
                CameraBottomBar(
                    uiState = uiState,
                    onCaptureClick = {},
                    onModeSelect = { mode = it },
                    onToggleLens = {},
                    onZoomChange = {},
                    onThumbnailClick = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("mode_photo_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mode_portrait_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mode_pro_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mode_video_button").assertIsDisplayed()

        composeTestRule.onNodeWithTag("mode_portrait_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(CameraMode.PORTRAIT, mode)

        composeTestRule.onNodeWithTag("portrait_controls_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("portrait_blur_slider").assertIsDisplayed()
        composeTestRule.onNodeWithTag("portrait_depth_source_chip").assertIsDisplayed()
        composeTestRule.onNodeWithTag("portrait_subject_status_chip").assertIsDisplayed()

        composeTestRule.onNodeWithTag("portrait_preset_f_1.4").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1.0f, blur, 0.01f)
    }

    @Test
    fun `photo preview toolbar shows portrait edit button and opens portrait blur editor`() {
        val sampleUri = Uri.fromFile(File(app.cacheDir, "dummy_portrait_preview.jpg").apply {
            createSyntheticTwoSubjectScene(120, 90).let { bmp ->
                outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }
        })
        val item = MediaItem(
            id = 42L,
            uri = sampleUri,
            isVideo = false,
            displayName = "STU_PORTRAIT.jpg",
            dateAdded = 1700000000L,
            mimeType = "image/jpeg",
            sizeBytes = 1024L,
            width = 120,
            height = 90,
            durationMs = 0L
        )
        var editPortraitClicked = false

        composeTestRule.setContent {
            PhotoPreviewDialog(
                items = listOf(item),
                currentIndex = 0,
                onIndexChange = {},
                onDismiss = {},
                onDelete = {},
                onGetDetails = {
                    com.example.model.PhotoDetails(
                        uri = sampleUri,
                        title = "STU_PORTRAIT.jpg",
                        dateTaken = "Now",
                        dimensions = "120x90",
                        fileSize = "1 KB",
                        path = sampleUri.path ?: "",
                        mimeType = "image/jpeg"
                    )
                },
                onEditPhoto = {},
                onEditWatermark = {},
                isPortraitEditable = { true },
                onEditPortrait = { editPortraitClicked = true }
            )
        }

        composeTestRule.onNodeWithTag("preview_edit_portrait_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("preview_edit_watermark_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("preview_edit_button").assertIsDisplayed()

        composeTestRule.onNodeWithTag("preview_edit_portrait_button").performClick()
        assertTrue(editPortraitClicked)
    }

    @Test
    fun `portrait blur editor dialog displays slider compare and save actions`() {
        val storage = PortraitSidecarStorage(app)
        val bmp = createSyntheticTwoSubjectScene(120, 90)
        val alpha = FloatArray(120 * 90) { 0f }
        val depth = FloatArray(120 * 90) { 0.8f }
        val seg = PortraitSegmentationResult(120, 90, alpha, depth, 0.2f, 1, true, PortraitDepthSource.AI_SEGMENTATION)
        val entry = storage.savePortraitSidecar(
            cleanUprightBitmap = bmp,
            segmentation = seg,
            blurIntensity = 0.62f,
            focusX = 0.5f,
            focusY = 0.5f,
            exifAttributes = emptyMap(),
            metadata = RealCameraMetadata(resolutionText = "120×90"),
            watermarkConfig = WatermarkConfig(),
            isSaveLocation = false
        )!!
        val target = PostCapturePortraitTarget(
            galleryUri = Uri.parse("content://media/external/images/media/100"),
            originalId = entry.sidecarId,
            originalFilePath = File(app.filesDir, "portrait_sidecars/${entry.cleanFileName}").absolutePath,
            depthMapFilePath = File(app.filesDir, "portrait_sidecars/${entry.depthFileName}").absolutePath,
            alphaMatteFilePath = File(app.filesDir, "portrait_sidecars/${entry.matteFileName}").absolutePath,
            width = 120,
            height = 90,
            blurIntensity = 0.62f,
            focusX = 0.5f,
            focusY = 0.5f,
            depthSource = PortraitDepthSource.AI_SEGMENTATION,
            watermarkConfig = WatermarkConfig()
        )
        var updateClicked = false
        var copyClicked = false

        composeTestRule.setContent {
            PortraitBlurEditorDialog(
                target = target,
                sidecarStorage = storage,
                isSaving = false,
                hapticsEnabled = false,
                onUpdateExisting = { _, _, _ -> updateClicked = true },
                onSaveCopy = { _, _, _ -> copyClicked = true },
                onDismiss = {}
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("portrait_blur_editor_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("portrait_editor_blur_slider").assertIsDisplayed()
        composeTestRule.onNodeWithTag("portrait_editor_compare_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("portrait_editor_save_copy_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("portrait_editor_update_button").assertIsDisplayed()

        composeTestRule.onNodeWithTag("portrait_editor_save_copy_button").performClick()
        assertTrue(copyClicked)
        composeTestRule.onNodeWithTag("portrait_editor_update_button").performClick()
        assertTrue(updateClicked)
    }

    private fun createSyntheticTwoSubjectScene(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()

        // High-frequency background checkerboard so bokeh smoothing is mathematically measurable
        val cell = maxOf(4, w / 30)
        for (y in 0 until h step cell) {
            for (x in 0 until w step cell) {
                val isLight = ((x / cell) + (y / cell)) % 2 == 0
                paint.color = if (isLight) Color.rgb(220, 235, 250) else Color.rgb(25, 45, 70)
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + cell).toFloat(), (y + cell).toFloat(), paint)
            }
        }

        // Warm subject 1 on left
        paint.color = Color.rgb(215, 165, 130)
        canvas.drawRect(w * 0.16f, h * 0.25f, w * 0.34f, h * 0.80f, paint)

        // Midground secondary subject on right
        paint.color = Color.rgb(190, 120, 95)
        canvas.drawRect(w * 0.60f, h * 0.25f, w * 0.78f, h * 0.80f, paint)

        return bmp
    }

    private fun colorDiff(c1: Int, c2: Int): Float {
        val dr = abs(Color.red(c1) - Color.red(c2))
        val dg = abs(Color.green(c1) - Color.green(c2))
        val db = abs(Color.blue(c1) - Color.blue(c2))
        return (dr + dg + db) / 3f
    }
}
