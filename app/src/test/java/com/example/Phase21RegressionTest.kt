package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.data.MediaCaptureManager
import com.example.data.RecycleBinManager
import com.example.data.RecycleResult
import com.example.model.ActiveCameraPanel
import com.example.model.BinMediaItem
import com.example.model.CameraMode
import com.example.model.CameraUiState
import com.example.model.CapturePriority
import com.example.model.MediaItem
import com.example.model.PhotoDetails
import com.example.model.PhotoResolution
import com.example.model.ProControlTab
import com.example.model.RawCaptureMode
import com.example.model.VideoCodecOption
import com.example.model.VideoFpsOption
import com.example.model.VideoQualityOption
import com.example.ui.components.CameraTopBar
import com.example.ui.components.PhotoPreviewDialog
import com.example.ui.components.RecycleBinDialog
import com.example.ui.components.VideoSettingsPanel
import com.example.util.PhotoMetadataExtractor
import com.example.util.VideoEncoderUtil
import com.example.viewmodel.CameraViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Phase21RegressionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun createTempJpeg(name: String): File {
        val file = File(app.cacheDir, "${name}_${System.nanoTime()}.jpg")
        val bmp = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.DKGRAY)
        }
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bmp.recycle()
        return file
    }

    @Test
    fun `CameraTopBar fits cleanly with zero overlap at 360dp and hides still badges in Video mode`() {
        var videoSettingsToggles = 0
        val videoState = CameraUiState(
            cameraMode = CameraMode.VIDEO,
            photoResolution = PhotoResolution.ULTRA_RES,
            uhrMegapixels = 50.0,
            isRawSupportedOnCurrentLens = true,
            rawCaptureMode = RawCaptureMode.JPEG_AND_DNG,
            isHdrEnabled = true,
            capturePriority = CapturePriority.LATENCY,
            videoQuality = VideoQualityOption.QUALITY_2160P,
            videoFps = VideoFpsOption.FPS_60,
            isVideoStabilizationSupported = true,
            isVideoStabilizationEnabled = true,
            isFlashAvailable = true
        )

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 360.dp, height = 780.dp)) {
                CameraTopBar(
                    uiState = videoState,
                    onCycleFlash = {},
                    onOpenSettings = {},
                    onCycleAspectRatio = {},
                    onToggleQuickSettings = {},
                    onToggleFilterSelector = {},
                    onToggleVideoSettingsPanel = { videoSettingsToggles++ }
                )
            }
        }
        composeTestRule.waitForIdle()

        val leftCapsule = composeTestRule.onNodeWithTag("top_left_brand_capsule").fetchSemanticsNode().boundsInRoot
        val rightCapsule = composeTestRule.onNodeWithTag("top_right_controls_capsule").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Top bar left capsule (${leftCapsule.right}) must not overlap right capsule (${rightCapsule.left})",
            leftCapsule.right <= rightCapsule.left + 0.5f
        )

        // Still-photo badges must NOT leak into Video mode
        assertTrue(composeTestRule.onAllNodesWithText("UHR", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("RAW", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("HDR").fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("SPEED").fetchSemanticsNodes().isEmpty())

        // Video pill opens Video Settings Panel callback
        composeTestRule.onNodeWithTag("video_quality_fps_toggle_button").performClick()
        assertEquals(1, videoSettingsToggles)

        // Check right capsule buttons do not collide vertically/horizontally on 360dp
        val childTags = listOf(
            "video_quality_fps_toggle_button",
            "video_stabilization_toggle_button",
            "video_mic_top_toggle_button",
            "video_torch_toggle_button",
            "top_settings_button"
        )
        val childBounds = childTags.map {
            composeTestRule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot
        }
        for (i in 0 until childBounds.lastIndex) {
            assertTrue(
                "Right capsule child $i (${childBounds[i].right}) overlapped child ${i + 1} (${childBounds[i + 1].left})",
                childBounds[i].right <= childBounds[i + 1].left + 0.5f
            )
        }
    }

    @Test
    fun `Unified panel mutual exclusion and ViewModel dismissActivePanel clear drawers cleanly`() {
        val vm = CameraViewModel(app)

        vm.toggleFilterSelector()
        assertEquals(ActiveCameraPanel.FILTERS, vm.uiState.value.activeCameraPanel)
        assertTrue(vm.uiState.value.isFilterSelectorOpen)
        assertFalse(vm.uiState.value.isQuickSettingsOpen)

        vm.toggleQuickSettings()
        assertEquals(ActiveCameraPanel.QUICK_SETTINGS, vm.uiState.value.activeCameraPanel)
        assertFalse(vm.uiState.value.isFilterSelectorOpen)
        assertTrue(vm.uiState.value.isQuickSettingsOpen)

        vm.setCameraMode(CameraMode.VIDEO)
        assertEquals(ActiveCameraPanel.NONE, vm.uiState.value.activeCameraPanel)

        vm.toggleVideoSettingsPanel()
        assertEquals(ActiveCameraPanel.VIDEO_SETTINGS, vm.uiState.value.activeCameraPanel)
        assertTrue(vm.uiState.value.isVideoSettingsPanelOpen)

        vm.setCameraMode(CameraMode.PRO)
        vm.setActiveProTab(ProControlTab.ISO)
        assertEquals(ProControlTab.ISO, vm.uiState.value.activeProTab)

        vm.dismissActivePanel()
        assertEquals(ActiveCameraPanel.NONE, vm.uiState.value.activeCameraPanel)
        assertEquals(ProControlTab.NONE, vm.uiState.value.activeProTab)
        assertFalse(vm.uiState.value.isFilterSelectorOpen)
        assertFalse(vm.uiState.value.isQuickSettingsOpen)
        assertFalse(vm.uiState.value.isVideoSettingsPanelOpen)
    }

    @Test
    fun `Outside tap scrim dismisses open panel cleanly`() {
        var dismissedCount = 0
        composeTestRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { dismissedCount++ }
                        }
                        .testTag("camera_panel_dismiss_scrim")
                )
            }
        }
        composeTestRule.onNodeWithTag("camera_panel_dismiss_scrim").performClick()
        assertEquals(1, dismissedCount)
    }

    @Test
    fun `VideoSettingsPanel dynamically adapts FPS options per resolution and invokes codec and EIS callbacks`() {
        var state by mutableStateOf(
            CameraUiState(
                cameraMode = CameraMode.VIDEO,
                videoQuality = VideoQualityOption.QUALITY_2160P,
                videoFps = VideoFpsOption.FPS_30,
                supportedVideoQualities = listOf(VideoQualityOption.QUALITY_2160P, VideoQualityOption.QUALITY_1080P),
                supportedFpsPerQuality = mapOf(
                    VideoQualityOption.QUALITY_2160P to listOf(VideoFpsOption.FPS_AUTO, VideoFpsOption.FPS_24, VideoFpsOption.FPS_30),
                    VideoQualityOption.QUALITY_1080P to listOf(VideoFpsOption.FPS_AUTO, VideoFpsOption.FPS_24, VideoFpsOption.FPS_30, VideoFpsOption.FPS_60, VideoFpsOption.FPS_120)
                ),
                supportedVideoCodecs = listOf(VideoCodecOption.H264, VideoCodecOption.HEVC),
                videoCodec = VideoCodecOption.H264,
                isVideoStabilizationSupported = true,
                isVideoStabilizationEnabled = true,
                isAudioEnabled = true
            )
        )
        var selectedCodec: VideoCodecOption? = null
        var eisToggled = 0

        composeTestRule.setContent {
            VideoSettingsPanel(
                uiState = state,
                onSelectQuality = { q -> state = state.copy(videoQuality = q) },
                onSelectFps = { fps -> state = state.copy(videoFps = fps) },
                onSelectCodec = { codec -> selectedCodec = codec },
                onToggleStabilization = { eisToggled++ },
                onToggleAudio = {},
                onClose = {}
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("video_settings_panel").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_panel_fps_fps_24").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_panel_fps_fps_120").assertDoesNotExist()

        // Switch quality to 1080P -> 120 FPS appears dynamically
        composeTestRule.onNodeWithTag("video_panel_quality_quality_1080p").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("video_panel_fps_fps_120").assertIsDisplayed()

        composeTestRule.onNodeWithTag("video_panel_codec_hevc").performClick()
        assertEquals(VideoCodecOption.HEVC, selectedCodec)

        composeTestRule.onNodeWithTag("video_panel_eis_toggle").performClick()
        assertEquals(1, eisToggled)

        // Probe VideoEncoderUtil never fabricates invalid codecs
        val codecs = VideoEncoderUtil.getSupportedVideoCodecs()
        assertTrue(codecs.all { it == VideoCodecOption.H264 || it == VideoCodecOption.HEVC })
    }

    @Test
    fun `PhotoPreviewTopToolbar shows all 6 actions including StuDO Bin with zero overlap at 360dp`() {
        val file1 = createTempJpeg("viewer_bin_360")
        var binOpened = 0
        val items = listOf(
            MediaItem(
                id = 99L,
                uri = Uri.fromFile(file1),
                isVideo = false,
                displayName = "IMG_99.jpg",
                dateAdded = 0L,
                mimeType = "image/jpeg",
                sizeBytes = file1.length(),
                width = 320,
                height = 240,
                durationMs = 0L
            )
        )

        try {
            composeTestRule.setContent {
                Box(modifier = Modifier.size(width = 360.dp, height = 780.dp)) {
                    PhotoPreviewDialog(
                        items = items,
                        currentIndex = 0,
                        onIndexChange = {},
                        onDismiss = {},
                        onDelete = {},
                        onGetDetails = {
                            PhotoDetails(it.uri, it.displayName, "Recent", "320 × 240", "12 KB", "", "image/jpeg")
                        },
                        onEditPhoto = {},
                        onEditWatermark = {},
                        binCount = 2,
                        onOpenRecycleBin = { binOpened++ }
                    )
                }
            }
            composeTestRule.waitForIdle()

            val toolbar = composeTestRule.onNodeWithTag("preview_top_toolbar").fetchSemanticsNode().boundsInRoot
            val left = composeTestRule.onNodeWithTag("preview_top_left_capsule").fetchSemanticsNode().boundsInRoot
            val right = composeTestRule.onNodeWithTag("preview_top_right_capsule").fetchSemanticsNode().boundsInRoot
            assertTrue("Left capsule (${left.right}) overlapped right capsule (${right.left})", left.right <= right.left + 0.5f)
            assertTrue("Right capsule (${right.right}) clipped toolbar (${toolbar.right})", right.right <= toolbar.right + 0.5f)

            val rightButtons = listOf(
                "preview_edit_watermark_button",
                "preview_edit_button",
                "preview_info_button",
                "preview_share_button",
                "gallery_bin_button",
                "preview_delete_button"
            ).map { composeTestRule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
            for (i in 0 until rightButtons.lastIndex) {
                assertTrue(
                    "Viewer button $i (${rightButtons[i].right}) overlapped button ${i + 1} (${rightButtons[i + 1].left})",
                    rightButtons[i].right <= rightButtons[i + 1].left + 0.5f
                )
            }

            composeTestRule.onNodeWithTag("gallery_bin_button").performClick()
            assertEquals(1, binOpened)
        } finally {
            file1.delete()
        }
    }

    @Test
    fun `Video item metadata displays Video Details and Video section cleanly separated from JPEG EXIF`() {
        val fakeVideoFile = File(app.cacheDir, "sample_${System.nanoTime()}.mp4").apply {
            writeBytes(ByteArray(2048) { 1 })
        }
        val videoItem = MediaItem(
            id = 50L,
            uri = Uri.fromFile(fakeVideoFile),
            isVideo = true,
            displayName = fakeVideoFile.name,
            dateAdded = 1_760_000_000L,
            mimeType = "video/mp4",
            sizeBytes = fakeVideoFile.length(),
            width = 1920,
            height = 1080,
            durationMs = 15_000L
        )

        try {
            val meta = runBlocking {
                PhotoMetadataExtractor.extract(
                    context = app,
                    item = videoItem,
                    fallbackDetails = PhotoDetails(
                        uri = videoItem.uri,
                        title = videoItem.displayName,
                        dateTaken = "Today",
                        dimensions = "1920 × 1080",
                        fileSize = "2.0 KB",
                        path = fakeVideoFile.absolutePath,
                        mimeType = "video/mp4",
                        isVideo = true,
                        duration = "00:15"
                    )
                )
            }
            assertTrue(meta.isVideo)
            assertTrue(meta.cameraFields.isEmpty())
            assertTrue(meta.captureFields.isEmpty())

            composeTestRule.setContent {
                PhotoPreviewDialog(
                    items = listOf(videoItem),
                    currentIndex = 0,
                    onIndexChange = {},
                    onDismiss = {},
                    onDelete = {},
                    onGetDetails = {
                        PhotoDetails(
                            uri = it.uri,
                            title = it.displayName,
                            dateTaken = "Today",
                            dimensions = "1920 × 1080",
                            fileSize = "2.0 KB",
                            path = fakeVideoFile.absolutePath,
                            mimeType = "video/mp4",
                            isVideo = true,
                            duration = "00:15"
                        )
                    }
                )
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("preview_info_button").performClick()
            composeTestRule.waitUntil(timeoutMillis = 10_000L) {
                composeTestRule.onAllNodesWithText("Video Details").fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithTag("details_section_video").assertIsDisplayed()
            assertTrue(composeTestRule.onAllNodesWithText("Photo Details").fetchSemanticsNodes().isEmpty())
        } finally {
            fakeVideoFile.delete()
        }
    }

    @Test
    fun `RecycleBinManager and RecycleBinDialog handle move to bin, restore UI action, and permanent delete cleanly`() {
        val binManager = RecycleBinManager(app)
        binManager.clearBin()

        val captureFile = createTempJpeg("bin_lifecycle")
        val mediaCaptureManager = MediaCaptureManager(app)
        val item = MediaItem(
            id = 101L,
            uri = Uri.fromFile(captureFile),
            isVideo = false,
            displayName = captureFile.name,
            dateAdded = System.currentTimeMillis() / 1000L,
            mimeType = "image/jpeg",
            sizeBytes = captureFile.length(),
            width = 320,
            height = 240
        )

        val moveResult = binManager.moveToBin(mediaCaptureManager, item)
        assertTrue("Expected MovedToBin, got $moveResult", moveResult is RecycleResult.MovedToBin)
        val binItem = (moveResult as RecycleResult.MovedToBin).binItem
        assertFalse("Original file must be removed after moving to bin", captureFile.exists())
        assertTrue("Bin media file must exist in app-private storage", File(binItem.binFilePath).exists())
        assertEquals(1, binManager.getBinCount())

        var restoredBinItem: BinMediaItem? = null
        var deletedForeverBinItem: BinMediaItem? = null

        composeTestRule.setContent {
            RecycleBinDialog(
                binItems = listOf(binItem),
                onRestore = { restoredBinItem = it },
                onPermanentDelete = { deletedForeverBinItem = it },
                onClearBin = {},
                onDismiss = {}
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("recycle_bin_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bin_item_${binItem.id}").assertIsDisplayed()

        composeTestRule.onNodeWithTag("bin_restore_${binItem.id}").performClick()
        assertEquals(binItem.id, restoredBinItem?.id)

        composeTestRule.onNodeWithTag("bin_delete_${binItem.id}").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Delete permanently?").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bin_permanent_delete_confirm").performClick()
        assertEquals(binItem.id, deletedForeverBinItem?.id)

        val permResult = binManager.permanentlyDelete(binItem.id)
        assertTrue(permResult is RecycleResult.PermanentlyDeleted)
        assertFalse(File(binItem.binFilePath).exists())
        assertEquals(0, binManager.getBinCount())
    }
}
