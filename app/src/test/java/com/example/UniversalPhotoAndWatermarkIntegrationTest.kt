package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.example.editor.EditorTab
import com.example.editor.PhotoAdjustmentState
import com.example.editor.PhotoEditSnapshot
import com.example.editor.PhotoEditorScreen
import com.example.editor.PhotoFilterType
import com.example.editor.PhotoProcessingEngine
import com.example.editor.TextOverlayItem
import com.example.model.CameraUiState
import com.example.model.EditorWatermarkTarget
import com.example.model.PendingEditorWatermarkResult
import com.example.model.WatermarkConfig
import com.example.model.WatermarkLogoType
import com.example.model.WatermarkStyle
import com.example.ui.components.CameraTopBar
import com.example.ui.components.WatermarkStudioScreen
import com.example.ui.theme.StuDOTheme
import com.example.util.CameraMetadataUtil
import com.example.util.RealCameraMetadata
import com.example.util.WatermarkPainter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xhdpi")
class UniversalPhotoAndWatermarkIntegrationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun createTempJpegFile(
        name: String,
        width: Int = 240,
        height: Int = 180,
        exifMake: String? = null,
        exifModel: String? = null,
        exifIso: String? = null,
        exifFNumber: String? = null
    ): File {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File(context.cacheDir, name)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(AndroidColor.rgb(42, 86, 132))
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        bmp.recycle()

        if (exifMake != null || exifModel != null || exifIso != null || exifFNumber != null) {
            val exif = ExifInterface(file.absolutePath)
            exifMake?.let { exif.setAttribute(ExifInterface.TAG_MAKE, it) }
            exifModel?.let { exif.setAttribute(ExifInterface.TAG_MODEL, it) }
            exifIso?.let { exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it) }
            exifFNumber?.let { exif.setAttribute(ExifInterface.TAG_F_NUMBER, it) }
            exif.saveAttributes()
        }
        return file
    }

    @Test
    fun importedPhotoMetadata_neverFabricatesDeviceMakeOrExif() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val noExifFile = createTempJpegFile("test_imported_no_exif.jpg", width = 240, height = 180)
        val uri = Uri.fromFile(noExifFile)

        val importedMeta = CameraMetadataUtil.fromUri(
            context = context,
            uri = uri,
            width = 240,
            height = 180,
            allowDeviceFallback = false
        )

        assertNull(importedMeta.manufacturer)
        assertNull(importedMeta.modelOnly)
        assertNull(importedMeta.deviceModel)
        assertNull(importedMeta.iso)
        assertNull(importedMeta.shutterSpeed)
        assertNull(importedMeta.aperture)
        assertNull(importedMeta.focalLength)
        assertEquals("0.0 MP (240×180)", importedMeta.resolutionText)

        val fallbackMeta = CameraMetadataUtil.fromUri(
            context = context,
            uri = uri,
            width = 240,
            height = 180,
            allowDeviceFallback = true
        )
        assertNotNull(fallbackMeta.deviceModel)
        noExifFile.delete()
    }

    @Test
    fun importedPhotoMetadata_preservesRealExternalCameraExifWhenPresent() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val exifFile = createTempJpegFile(
            name = "test_imported_with_exif.jpg",
            width = 320,
            height = 240,
            exifMake = "Fujifilm",
            exifModel = "X-T5",
            exifIso = "400",
            exifFNumber = "2.8"
        )
        val uri = Uri.fromFile(exifFile)

        val meta = CameraMetadataUtil.fromUri(
            context = context,
            uri = uri,
            width = 320,
            height = 240,
            allowDeviceFallback = false
        )

        assertEquals("Fujifilm", meta.manufacturer)
        assertEquals("X-T5", meta.modelOnly)
        assertEquals("Fujifilm X-T5", meta.deviceModel)
        assertEquals("ISO 400", meta.iso)
        assertEquals("f/2.8", meta.aperture)
        assertEquals("0.1 MP (320×240)", meta.resolutionText)
        exifFile.delete()
    }

    @Test
    fun watermarkPainter_doesNotRenderFabricatedOemLogoWhenManufacturerMissing() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val emptyMeta = RealCameraMetadata(
            resolutionText = "1920 × 1080",
            manufacturer = null,
            modelOnly = null,
            deviceModel = null
        )

        val logo = WatermarkPainter.loadLogoBitmap(
            context = context,
            config = WatermarkConfig(logoType = WatermarkLogoType.DEVICE_MANUFACTURER),
            targetSizePx = 120,
            metadata = emptyMeta
        )

        assertNull(logo)
    }

    @Test
    fun processingEngine_rendersEditorPreviewAndTempFileWithEditsAndWatermark() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val srcFile = createTempJpegFile("test_editor_wm_pipeline.jpg", width = 320, height = 240)
            val srcUri = Uri.fromFile(srcFile)

            val gradedBase = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
                eraseColor(AndroidColor.rgb(60, 120, 180))
            }
            val wmConfig = WatermarkConfig(
                isEnabled = true,
                style = WatermarkStyle.LEICA_FRAME,
                customText = "Editor Watermark",
                logoType = WatermarkLogoType.APP_ICON
            )
            val snapshot = PhotoEditSnapshot(
                adjustments = PhotoAdjustmentState(exposure = 0.4f, contrast = 0.15f),
                filter = PhotoFilterType.CINEMATIC_TEAL,
                textItems = listOf(TextOverlayItem(text = "Check")),
                watermarkConfig = wmConfig
            )

            val previewBmp = PhotoProcessingEngine.renderEditorPreviewWithWatermark(
                context = context,
                gradedBase = gradedBase,
                snapshot = snapshot,
                metadata = RealCameraMetadata(resolutionText = "320 × 240")
            )
            assertNotNull(previewBmp)
            // LEICA_FRAME adds a white border/frame height
            assertTrue((previewBmp?.height ?: 0) >= gradedBase.height)

            val tempRes = PhotoProcessingEngine.renderEditedBitmapToTempFile(
                context = context,
                sourceUri = srcUri,
                snapshot = snapshot,
                isImportedPhoto = true
            )
            assertNotNull(tempRes)
            assertTrue(tempRes!!.file.exists() && tempRes.file.length() > 0L)
            assertTrue(tempRes.width > 0 && tempRes.height > 0)

            previewBmp?.recycle()
            gradedBase.recycle()
            tempRes.file.delete()
            srcFile.delete()
        }
    }

    @Test
    fun cameraTopBar_longPressBrandOpensLiquidGlassSourceSelector_respectingRecordingState() {
        var galleryOpened = false
        var filesOpened = false
        var hapticTriggered = false
        var uiState by mutableStateOf(CameraUiState())

        composeRule.setContent {
            StuDOTheme {
                CameraTopBar(
                    uiState = uiState,
                    onCycleFlash = {},
                    onOpenSettings = {},
                    onCycleAspectRatio = {},
                    onToggleQuickSettings = {},
                    onToggleFilterSelector = {},
                    onOpenGallery = { galleryOpened = true },
                    onOpenFiles = { filesOpened = true },
                    onTriggerHaptic = { hapticTriggered = true }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("top_left_brand_capsule").performTouchInput { longClick() }
        composeRule.waitForIdle()

        assertTrue(hapticTriggered)
        composeRule.onNodeWithTag("camera_source_selector_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("source_selector_gallery_option").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue(galleryOpened)

        composeRule.onNodeWithTag("top_left_brand_capsule").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("source_selector_files_option").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue(filesOpened)

        // While recording video, long-press should NOT open the import source selector
        uiState = uiState.copy(isRecordingVideo = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("top_left_brand_capsule").performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithTag("camera_source_selector_dialog").fetchSemanticsNodes().size)
    }

    @Test
    fun photoEditorScreen_watermarkNavAndPendingResultPreserveEditingSessionAndSourceBadge() {
        val imgFile = createTempJpegFile("test_editor_ui.jpg", width = 200, height = 150)
        val imgUri = Uri.fromFile(imgFile)

        var capturedSnapshotForStudio: PhotoEditSnapshot? = null
        var pendingResult by mutableStateOf<PendingEditorWatermarkResult?>(null)

        composeRule.setContent {
            StuDOTheme {
                PhotoEditorScreen(
                    sourceUri = imgUri,
                    onSaveSuccess = {},
                    onDismiss = {},
                    onOpenWatermarkStudio = { snap -> capturedSnapshotForStudio = snap },
                    isImportedPhoto = true,
                    pendingWatermarkResult = pendingResult,
                    onConsumeWatermarkResult = { pendingResult = null }
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5000) {
            ShadowLooper.idleMainLooper()
            composeRule.onAllNodesWithTag("editor_photo_preview_frame").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        // Displays authentic imported source tag + dimensions
        composeRule.onNodeWithTag("editor_metadata_info_button").assertIsDisplayed()
        composeRule.onNodeWithText("IMPORTED · 200×150", substring = true).assertIsDisplayed()

        // Make a non-destructive edit (Rotate 90 CW in Crop tab)
        composeRule.onNodeWithTag("editor_nav_crop").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("+90°").performClick()
        composeRule.waitForIdle()

        // Tap Watermark beside Badges -> opens Watermark Studio with current edits preserved
        composeRule.onNodeWithTag("editor_nav_watermark").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertNotNull(capturedSnapshotForStudio)
        assertEquals(90, capturedSnapshotForStudio!!.crop.rotationDegrees)

        // Return watermark result from Watermark Studio -> applies cleanly while preserving Undo & prior crop
        val returnedConfig = WatermarkConfig(
            isEnabled = true,
            style = WatermarkStyle.STUDIO_BRAND,
            customText = "Session Mark"
        )
        pendingResult = PendingEditorWatermarkResult(eventId = 101L, config = returnedConfig)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("editor_undo_button").assertIsEnabled()

        // Tap Watermark again -> snapshot now contains both crop=90 and the returned watermarkConfig
        composeRule.onNodeWithTag("editor_nav_watermark").performClick()
        composeRule.waitForIdle()
        assertEquals(90, capturedSnapshotForStudio!!.crop.rotationDegrees)
        assertEquals(returnedConfig, capturedSnapshotForStudio!!.watermarkConfig)

        imgFile.delete()
    }

    @Test
    fun photoEditorScreen_corruptOrMissingFileShowsGracefulErrorAndCloses() {
        var dismissed = false
        val missingUri = Uri.fromFile(File("/nonexistent/path/missing_photo_12345.jpg"))

        composeRule.setContent {
            StuDOTheme {
                PhotoEditorScreen(
                    sourceUri = missingUri,
                    onSaveSuccess = {},
                    onDismiss = { dismissed = true },
                    isImportedPhoto = true
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5000) {
            ShadowLooper.idleMainLooper()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onAllNodesWithTag("editor_load_error_card").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("editor_load_error_close_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue(dismissed)
    }

    @Test
    fun watermarkStudioScreen_editorTargetPreservesExistingStudioUiAndSavesBackToEditor() {
        val tempFile = createTempJpegFile("test_studio_editor_target.jpg", width = 300, height = 200)
        val initialCfg = WatermarkConfig(
            isEnabled = true,
            style = WatermarkStyle.LEICA_FRAME,
            customText = "Editor Session"
        )
        val target = EditorWatermarkTarget(
            sessionId = "session_test_1",
            sourceUri = Uri.fromFile(tempFile),
            previewFilePath = tempFile.absolutePath,
            width = 300,
            height = 200,
            initialConfig = initialCfg,
            isImportedPhoto = true,
            metadata = RealCameraMetadata(resolutionText = "300 × 200")
        )
        var savedEditorConfig: WatermarkConfig? = null

        composeRule.setContent {
            StuDOTheme {
                WatermarkStudioScreen(
                    initialConfig = WatermarkConfig(),
                    savedPresets = emptyList(),
                    onSaveConfig = {},
                    onSavePreset = { _, _ -> },
                    onDeletePreset = {},
                    onDismiss = {},
                    editorWatermarkTarget = target,
                    onSaveEditorWatermark = { savedEditorConfig = it }
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5000) {
            ShadowLooper.idleMainLooper()
            composeRule.onAllNodesWithTag("studio_save_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        // Existing Watermark Studio tabs remain intact
        composeRule.onNodeWithTag("studio_tab_templates").assertIsDisplayed()
        composeRule.onNodeWithTag("studio_tab_text").assertIsDisplayed()
        composeRule.onNodeWithTag("studio_tab_logo").assertIsDisplayed()
        composeRule.onNodeWithTag("studio_tab_appearance").assertIsDisplayed()
        composeRule.onNodeWithTag("studio_tab_metadata").assertIsDisplayed()
        composeRule.onNodeWithText("Imported Photo (300×200)").assertIsDisplayed()

        // Save returns config directly to editor callback rather than post-capture dialog
        composeRule.onNodeWithTag("studio_save_button").assertIsEnabled().performClick()
        composeRule.waitForIdle()
        assertNotNull(savedEditorConfig)
        assertEquals("Editor Session", savedEditorConfig!!.customText)
        assertFalse(composeRule.onAllNodesWithTag("studio_save_update_existing_button").fetchSemanticsNodes().isNotEmpty())

        tempFile.delete()
    }
}
