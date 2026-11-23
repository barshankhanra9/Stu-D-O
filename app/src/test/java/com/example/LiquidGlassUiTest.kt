package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import com.example.model.CameraFilter
import com.example.model.CameraMode
import com.example.model.CameraUiState
import com.example.model.CaptureAspectRatio
import com.example.model.RawCaptureMode
import com.example.model.VideoFpsOption
import com.example.model.VideoQualityOption
import com.example.ui.components.CameraBottomBar
import com.example.ui.components.CameraTopBar
import com.example.ui.components.CurvedZoomControl
import com.example.ui.components.computeDynamicZoomPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h740dp-xhdpi")
class LiquidGlassUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `top bar renders two separate glass capsules and preserves mode-specific controls and capability checks`() {
        var state by mutableStateOf(
            CameraUiState(
                cameraMode = CameraMode.PHOTO,
                rawCaptureMode = RawCaptureMode.JPEG_AND_DNG,
                isFlashAvailable = true,
                aspectRatio = CaptureAspectRatio.RATIO_4_3
            )
        )

        composeTestRule.setContent {
            CameraTopBar(
                uiState = state,
                onCycleFlash = {},
                onOpenSettings = {},
                onCycleAspectRatio = {},
                onToggleQuickSettings = {},
                onToggleFilterSelector = {}
            )
        }

        // Two distinct floating glass capsules
        composeTestRule.onNodeWithTag("top_left_brand_capsule").assertIsDisplayed()
        composeTestRule.onNodeWithTag("top_right_controls_capsule").assertIsDisplayed()
        composeTestRule.onNodeWithTag("app_brand_text").assertIsDisplayed()
        composeTestRule.onNodeWithTag("raw_format_toggle_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("RAW+").assertIsDisplayed()

        // Photo controls present
        composeTestRule.onNodeWithTag("top_filter_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("top_tools_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("aspect_ratio_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("flash_toggle_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("top_settings_button").assertIsDisplayed()

        // Switch to VIDEO mode without EIS support -> EIS must NOT appear
        state = state.copy(
            cameraMode = CameraMode.VIDEO,
            videoQuality = VideoQualityOption.QUALITY_1080P,
            videoFps = VideoFpsOption.FPS_30,
            isVideoStabilizationSupported = false
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("video_quality_fps_toggle_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_mic_top_toggle_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_torch_toggle_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_stabilization_toggle_button").assertDoesNotExist()

        // When EIS IS supported -> EIS pill appears
        state = state.copy(isVideoStabilizationSupported = true)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("video_stabilization_toggle_button").assertIsDisplayed()
    }

    @Test
    fun `video recording transforms left capsule into live timer pill and shutter into Stop`() {
        var state by mutableStateOf(
            CameraUiState(
                cameraMode = CameraMode.VIDEO,
                isCameraReady = true,
                isVideoRecordingSupported = true,
                isRecordingVideo = true,
                recordingDurationSeconds = 14L
            )
        )
        var shutterClicks = 0

        composeTestRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                CameraTopBar(
                    uiState = state,
                    onCycleFlash = {},
                    onOpenSettings = {},
                    onCycleAspectRatio = {},
                    onToggleQuickSettings = {},
                    onToggleFilterSelector = {}
                )
                CameraBottomBar(
                    uiState = state,
                    onCaptureClick = { shutterClicks++ },
                    onModeSelect = {},
                    onToggleLens = {},
                    onZoomChange = {},
                    onThumbnailClick = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("video_recording_timer_badge").assertIsDisplayed()
        composeTestRule.onNodeWithText("00:14").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Stop Recording").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_pause_resume_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_recording_mic_toggle_button").assertIsDisplayed()

        // Tap Stop Recording works immediately
        composeTestRule.onNodeWithTag("camera_shutter_button").performClick()
        assertEquals(1, shutterClicks)

        // End recording restores normal mode selector and gallery thumbnail
        state = state.copy(isRecordingVideo = false, recordingDurationSeconds = 0L)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("video_recording_timer_badge").assertDoesNotExist()
        composeTestRule.onNodeWithTag("mode_photo_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("gallery_thumbnail_button").assertIsDisplayed()
    }

    @Test
    fun `elastic mode selector switches modes immediately on tap`() {
        var activeMode by mutableStateOf(CameraMode.PHOTO)
        composeTestRule.setContent {
            CameraBottomBar(
                uiState = CameraUiState(cameraMode = activeMode, isCameraReady = true),
                onCaptureClick = {},
                onModeSelect = { activeMode = it },
                onToggleLens = {},
                onZoomChange = {},
                onThumbnailClick = {}
            )
        }

        composeTestRule.onNodeWithTag("mode_pro_button").performClick()
        assertEquals(CameraMode.PRO, activeMode)

        composeTestRule.onNodeWithTag("mode_video_button").performClick()
        assertEquals(CameraMode.VIDEO, activeMode)

        composeTestRule.onNodeWithTag("mode_photo_button").performClick()
        assertEquals(CameraMode.PHOTO, activeMode)
    }

    @Test
    fun `dynamic zoom presets strictly honor camera min and max zoom bounds`() {
        val widePresets = computeDynamicZoomPresets(0.5f, 8.0f)
        assertEquals(listOf(0.5f, 1.0f, 2.0f, 5.0f), widePresets)

        val standardPresets = computeDynamicZoomPresets(1.0f, 4.0f)
        assertEquals(listOf(1.0f, 2.0f, 3.0f), standardPresets)
        assertFalse(standardPresets.any { it < 1.0f || it > 4.0f })

        val fixedPresets = computeDynamicZoomPresets(1.0f, 1.0f)
        assertEquals(listOf(1.0f), fixedPresets)
    }

    @Test
    fun `curved zoom arc opens on drag or readout tap, updates zoom dynamically, and collapses`() {
        var zoom by mutableFloatStateOf(1.0f)

        composeTestRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                CurvedZoomControl(
                    zoomRatio = zoom,
                    minZoomRatio = 0.5f,
                    maxZoomRatio = 8.0f,
                    hapticsEnabled = true,
                    onZoomChange = { zoom = it },
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                )
            }
        }

        // Idle compact strip visible; curved arc hidden
        composeTestRule.onNodeWithTag("zoom_chips_strip").assertIsDisplayed()
        composeTestRule.onNodeWithTag("curved_zoom_arc").assertDoesNotExist()

        // Tap live readout pill to open curved arc
        composeTestRule.onNodeWithTag("zoom_live_readout").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("curved_zoom_arc").assertIsDisplayed()
        composeTestRule.onNodeWithTag("curved_zoom_droplet").assertIsDisplayed()

        // Tap droplet to collapse
        composeTestRule.onNodeWithTag("curved_zoom_droplet").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("curved_zoom_arc").assertDoesNotExist()
        composeTestRule.onNodeWithTag("zoom_chips_strip").assertIsDisplayed()

        // Drag across compact strip -> updates zoom dynamically
        val strip = composeTestRule.onNodeWithTag("zoom_chips_strip")
        strip.performTouchInput {
            down(0, center)
            moveBy(0, Offset(40f, 0f))
            moveBy(0, Offset(80f, 0f))
            up(0)
        }
        composeTestRule.waitForIdle()
        assertTrue(zoom > 1.0f)
    }

    @Test
    fun `viewfinder with 16-9 aspect ratio renders framed design like 4-3 and 1-1 without crash`() {
        var state by mutableStateOf(
            CameraUiState(
                cameraMode = CameraMode.PHOTO,
                aspectRatio = CaptureAspectRatio.RATIO_16_9,
                isSettingsOpen = false
            )
        )

        composeTestRule.setContent {
            com.example.ui.components.CameraViewfinder(
                uiState = state,
                onCameraBound = { _, _, _, _ -> },
                onStreamStateChanged = {},
                onPinchZoom = {},
                onTapToFocus = { _, _, _ -> }
            )
        }

        composeTestRule.waitForIdle()
        // Viewfinder frame is rendered and displayed
        composeTestRule.onNodeWithTag("camera_viewfinder_frame").assertIsDisplayed()

        // 16:9 is framed like 4:3 and 1:1, so fullscreen feather gradient must NOT be present
        composeTestRule.onNodeWithTag("fullscreen_top_feather").assertDoesNotExist()

        // Switch to 4:3 -> renders cleanly
        state = state.copy(aspectRatio = CaptureAspectRatio.RATIO_4_3)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("camera_viewfinder_frame").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fullscreen_top_feather").assertDoesNotExist()

        // Switch to 1:1 -> renders cleanly
        state = state.copy(aspectRatio = CaptureAspectRatio.RATIO_1_1)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("camera_viewfinder_frame").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fullscreen_top_feather").assertDoesNotExist()

        // Switch to FULL -> renders fullscreen feather
        state = state.copy(aspectRatio = CaptureAspectRatio.RATIO_FULL)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("camera_viewfinder_frame").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fullscreen_top_feather").assertIsDisplayed()
    }

    @Test
    fun `aspect ratio button in top bar cycles from 4-3 to 16-9 and renders 16-9 label`() {
        var currentRatio by mutableStateOf(CaptureAspectRatio.RATIO_4_3)

        composeTestRule.setContent {
            CameraTopBar(
                uiState = CameraUiState(
                    cameraMode = CameraMode.PHOTO,
                    aspectRatio = currentRatio
                ),
                onCycleFlash = {},
                onOpenSettings = {},
                onCycleAspectRatio = {
                    currentRatio = when (currentRatio) {
                        CaptureAspectRatio.RATIO_4_3 -> CaptureAspectRatio.RATIO_16_9
                        CaptureAspectRatio.RATIO_16_9 -> CaptureAspectRatio.RATIO_1_1
                        CaptureAspectRatio.RATIO_1_1 -> CaptureAspectRatio.RATIO_FULL
                        CaptureAspectRatio.RATIO_FULL -> CaptureAspectRatio.RATIO_4_3
                    }
                },
                onToggleQuickSettings = {},
                onToggleFilterSelector = {},
                onCycleRawMode = {},
                onCycleVideoQualityAndFps = {},
                onToggleAudio = {},
                onToggleVideoStabilization = {}
            )
        }

        // Initially 4:3
        composeTestRule.onNodeWithText("4:3").assertIsDisplayed()

        // Tap to cycle to 16:9
        composeTestRule.onNodeWithTag("aspect_ratio_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(CaptureAspectRatio.RATIO_16_9, currentRatio)
        composeTestRule.onNodeWithText("16:9").assertIsDisplayed()
    }

    @Test
    fun `bottom bar verifies photo video shutter states, gallery click, and camera switch`() {
        var state by mutableStateOf(
            CameraUiState(
                cameraMode = CameraMode.PHOTO,
                isCameraReady = true,
                hasFrontCamera = true,
                hasBackCamera = true,
                lastCapturedUri = android.net.Uri.parse("content://media/external/images/media/1")
            )
        )
        var captureClicks = 0
        var lensSwitchClicks = 0
        var galleryClicks = 0

        composeTestRule.setContent {
            CameraBottomBar(
                uiState = state,
                onCaptureClick = { captureClicks++ },
                onModeSelect = { state = state.copy(cameraMode = it) },
                onToggleLens = { lensSwitchClicks++ },
                onZoomChange = {},
                onThumbnailClick = { galleryClicks++ }
            )
        }

        // Photo shutter, gallery, and camera-switch are responsive
        composeTestRule.onNodeWithContentDescription("Take Photo").assertIsDisplayed().performClick()
        assertEquals(1, captureClicks)

        composeTestRule.onNodeWithTag("gallery_thumbnail_button").assertIsDisplayed().performClick()
        assertEquals(1, galleryClicks)

        composeTestRule.onNodeWithTag("lens_switch_button").assertIsDisplayed().performClick()
        assertEquals(1, lensSwitchClicks)

        // Switch to Video mode -> Record Video shutter description
        state = state.copy(cameraMode = CameraMode.VIDEO, isVideoRecordingSupported = true)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Record Video").assertIsDisplayed().performClick()
        assertEquals(2, captureClicks)
    }

    @Test
    fun `pro controls bar, quick settings drawer, and filter selector render cleanly`() {
        var state by mutableStateOf(
            CameraUiState(
                cameraMode = CameraMode.PRO,
                isQuickSettingsOpen = true,
                isFilterSelectorOpen = true
            )
        )

        composeTestRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                com.example.ui.components.CameraQuickSettingsDrawer(
                    uiState = state,
                    onSelectTimer = { state = state.copy(selectedTimer = it) },
                    onSelectGrid = { state = state.copy(selectedGrid = it) },
                    onToggleLevel = { state = state.copy(isLevelIndicatorEnabled = !state.isLevelIndicatorEnabled) },
                    onToggleShutterSound = {},
                    onSelectCapturePriority = {}
                )
                com.example.ui.components.CameraFilterSelector(
                    selectedFilter = state.selectedFilter,
                    onSelectFilter = { state = state.copy(selectedFilter = it) },
                    onClose = {}
                )
                com.example.ui.components.ProControlsBar(
                    uiState = state,
                    onTabSelected = { state = state.copy(activeProTab = it) },
                    onIsoChanged = {},
                    onShutterChanged = {},
                    onEvChanged = {},
                    onFocusChanged = { _, _ -> },
                    onWhiteBalanceChanged = {},
                    onResetAll = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("camera_quick_settings_drawer").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camera_filter_selector").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pro_tab_iso").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertEquals(com.example.model.ProControlTab.ISO, state.activeProTab)
    }

    @Test
    fun `custom capture timer drawer, picker dialog, settings card, and top bar badge work end to end`() {
        var state by mutableStateOf(
            CameraUiState(
                cameraMode = CameraMode.PHOTO,
                selectedTimer = com.example.model.CaptureTimer.OFF,
                customTimerSeconds = 15,
                isQuickSettingsOpen = true
            )
        )

        composeTestRule.setContent {
            Column(modifier = Modifier.fillMaxSize()) {
                CameraTopBar(
                    uiState = state,
                    onCycleFlash = {},
                    onOpenSettings = {},
                    onCycleAspectRatio = {},
                    onToggleQuickSettings = {},
                    onToggleFilterSelector = {}
                )
                com.example.ui.components.CameraQuickSettingsDrawer(
                    uiState = state,
                    onSelectTimer = { state = state.copy(selectedTimer = it) },
                    onSelectGrid = {},
                    onToggleLevel = {},
                    onToggleShutterSound = {},
                    onSelectCapturePriority = {},
                    onSetCustomTimerSeconds = { secs ->
                        state = state.copy(
                            selectedTimer = com.example.model.CaptureTimer.CUSTOM,
                            customTimerSeconds = secs
                        )
                    }
                )
                com.example.ui.components.CaptureTimerCard(
                    selectedTimer = state.selectedTimer,
                    onSelectTimer = { state = state.copy(selectedTimer = it) },
                    customTimerSeconds = state.customTimerSeconds,
                    onSetCustomTimerSeconds = { secs ->
                        state = state.copy(
                            selectedTimer = com.example.model.CaptureTimer.CUSTOM,
                            customTimerSeconds = secs
                        )
                    }
                )
            }
        }

        // Initially custom adjusters are hidden
        composeTestRule.onNodeWithTag("drawer_custom_timer_adjuster").assertDoesNotExist()
        composeTestRule.onNodeWithTag("settings_custom_timer_adjuster").assertDoesNotExist()

        // Selecting CUSTOM expands inline custom timer controls in both drawer and settings card
        composeTestRule.onNodeWithTag("timer_option_custom").performClick()
        composeTestRule.waitForIdle()
        assertEquals(com.example.model.CaptureTimer.CUSTOM, state.selectedTimer)
        composeTestRule.onNodeWithTag("drawer_custom_timer_adjuster").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_custom_timer_adjuster").assertIsDisplayed()

        // Increment (+1s) and Decrement (-1s) update customTimerSeconds
        composeTestRule.onNodeWithTag("drawer_custom_timer_increment_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(16, state.customTimerSeconds)
        composeTestRule.onNodeWithTag("top_tools_button").assertIsDisplayed()

        composeTestRule.onNodeWithTag("drawer_custom_timer_decrement_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(15, state.customTimerSeconds)

        // Open exact-time picker dialog via edit pill
        composeTestRule.onNodeWithTag("drawer_custom_timer_edit_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("custom_timer_picker_dialog").assertIsDisplayed()

        // Out-of-bounds value (>300) disables Set Timer confirm button
        composeTestRule.onNodeWithTag("custom_timer_seconds_input")
            .performSemanticsAction(SemanticsActions.SetText) {
                it(AnnotatedString("500"))
            }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("custom_timer_dialog_confirm").assertIsNotEnabled()

        // Valid custom duration (e.g., 25s) enables confirm and updates state
        composeTestRule.onNodeWithTag("custom_timer_seconds_input")
            .performSemanticsAction(SemanticsActions.SetText) {
                it(AnnotatedString("25"))
            }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("custom_timer_dialog_confirm")
            .assertIsEnabled()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("custom_timer_picker_dialog").assertDoesNotExist()
        assertEquals(25, state.customTimerSeconds)
        assertEquals(com.example.model.CaptureTimer.CUSTOM, state.selectedTimer)
        assertEquals("25s", state.activeTimerLabel)
    }

    @Test
    fun `video aspect ratio selector in top panel shows only for supported multi-ratio devices and cycles`() {
        var state by mutableStateOf(
            CameraUiState(
                cameraMode = CameraMode.VIDEO,
                isVideoRecordingSupported = true,
                supportedVideoAspectRatios = listOf(CaptureAspectRatio.RATIO_16_9),
                videoAspectRatio = CaptureAspectRatio.RATIO_16_9
            )
        )

        composeTestRule.setContent {
            CameraTopBar(
                uiState = state,
                onCycleFlash = {},
                onOpenSettings = {},
                onCycleAspectRatio = {},
                onToggleQuickSettings = {},
                onToggleFilterSelector = {},
                onCycleVideoAspectRatio = {
                    val ratios = state.supportedVideoAspectRatios
                    if (ratios.size > 1) {
                        val next = ratios[(ratios.indexOf(state.videoAspectRatio) + 1) % ratios.size]
                        state = state.copy(videoAspectRatio = next)
                    }
                }
            )
        }

        // Only 1 supported video ratio -> selector hidden
        composeTestRule.onNodeWithTag("video_aspect_ratio_button").assertDoesNotExist()

        // Device supports both 16:9 and 4:3 video ratios -> selector appears and cycles
        state = state.copy(
            supportedVideoAspectRatios = listOf(
                CaptureAspectRatio.RATIO_16_9,
                CaptureAspectRatio.RATIO_4_3
            )
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("video_aspect_ratio_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("16:9").assertIsDisplayed()

        composeTestRule.onNodeWithTag("video_aspect_ratio_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(CaptureAspectRatio.RATIO_4_3, state.videoAspectRatio)
        composeTestRule.onNodeWithText("4:3").assertIsDisplayed()

        // Unsupported video recording -> selector hidden
        state = state.copy(isVideoRecordingSupported = false)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("video_aspect_ratio_button").assertDoesNotExist()
    }

    @Test
    fun `framing grids render cleanly in canvas and quick drawer selects new grid presets`() {
        var state by mutableStateOf(
            CameraUiState(
                isQuickSettingsOpen = true,
                selectedGrid = com.example.model.GridOption.OFF
            )
        )

        composeTestRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                com.example.ui.components.CameraGridView(
                    gridOption = state.selectedGrid
                )
                com.example.ui.components.CameraQuickSettingsDrawer(
                    uiState = state,
                    onSelectTimer = {},
                    onSelectGrid = { state = state.copy(selectedGrid = it) },
                    onToggleLevel = {},
                    onToggleShutterSound = {}
                )
            }
        }

        // Render and verify each GridOption composes cleanly
        com.example.model.GridOption.entries.forEach { grid ->
            composeTestRule.onNodeWithTag("grid_option_${grid.name.lowercase()}")
                .performScrollTo()
                .performClick()
            composeTestRule.waitForIdle()
            assertEquals(grid, state.selectedGrid)
        }
    }

    @Test
    fun `pro filter selector categories filter list, update active filter, and reset cleanly`() {
        var state by mutableStateOf(
            CameraUiState(
                isFilterSelectorOpen = true,
                selectedFilter = CameraFilter.NATURAL
            )
        )

        composeTestRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                com.example.ui.components.CameraFilterSelector(
                    selectedFilter = state.selectedFilter,
                    onSelectFilter = { state = state.copy(selectedFilter = it) },
                    onClose = {}
                )
            }
        }

        // Switch to MONO category -> TRI_X_400 visible & selectable
        composeTestRule.onNodeWithTag("filter_category_mono")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("filter_option_tri_x_400")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        assertEquals(CameraFilter.TRI_X_400, state.selectedFilter)

        // Reset pill appears when non-Natural filter active and restores NATURAL
        composeTestRule.onNodeWithTag("filter_reset_button")
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()
        assertEquals(CameraFilter.NATURAL, state.selectedFilter)
    }

    @Test
    fun `dense secondary panels in Portrait and Pro modes collapse zoom strip into compact curved-zoom-only pill`() {
        var state by mutableStateOf(
            CameraUiState(
                cameraMode = CameraMode.PHOTO,
                isCameraReady = true,
                zoomRatio = 1.0f,
                minZoomRatio = 0.5f,
                maxZoomRatio = 8.0f
            )
        )

        composeTestRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                CameraBottomBar(
                    uiState = state,
                    onCaptureClick = {},
                    onModeSelect = { state = state.copy(cameraMode = it) },
                    onToggleLens = {},
                    onZoomChange = { state = state.copy(zoomRatio = it) },
                    onThumbnailClick = {}
                )
            }
        }

        // Photo mode initially shows full preset strip + live readout
        composeTestRule.onNodeWithTag("zoom_presets_row").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_preset_2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_live_readout").assertIsDisplayed()

        // Switch to Portrait mode (aperture/depth panel) -> presets collapse into single compact pill
        composeTestRule.onNodeWithTag("mode_portrait_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(CameraMode.PORTRAIT, state.cameraMode)
        composeTestRule.onNodeWithTag("zoom_presets_row").assertDoesNotExist()
        composeTestRule.onNodeWithTag("zoom_live_readout").assertIsDisplayed()
        composeTestRule.onNodeWithText("1.0x").assertIsDisplayed()

        // Tapping compact pill in Portrait mode opens curved zoom arc directly (only curved zoom works)
        composeTestRule.onNodeWithTag("zoom_live_readout").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("curved_zoom_arc").assertIsDisplayed()

        // Collapsing curved arc keeps it as single compact pill (never re-expands presets)
        composeTestRule.onNodeWithTag("curved_zoom_droplet").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("curved_zoom_arc").assertDoesNotExist()
        composeTestRule.onNodeWithTag("zoom_presets_row").assertDoesNotExist()
        composeTestRule.onNodeWithTag("zoom_live_readout").assertIsDisplayed()

        // Dragging compact pill adjusts zoom via curved zoom
        composeTestRule.onNodeWithTag("zoom_chips_strip").performTouchInput {
            down(0, center)
            moveBy(0, Offset(60f, 0f))
            moveBy(0, Offset(60f, 0f))
            up(0)
        }
        composeTestRule.waitForIdle()
        assertTrue("Expected zoom > 1.0f after dragging compact pill, got ${state.zoomRatio}", state.zoomRatio > 1.0f)
        composeTestRule.onNodeWithTag("zoom_presets_row").assertDoesNotExist()

        // Switch to Pro mode -> stays collapsed into single compact pill
        composeTestRule.onNodeWithTag("mode_pro_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(CameraMode.PRO, state.cameraMode)
        composeTestRule.onNodeWithTag("zoom_presets_row").assertDoesNotExist()
        composeTestRule.onNodeWithTag("zoom_live_readout").assertIsDisplayed()

        // Switch to Video mode -> full preset strip restored
        composeTestRule.onNodeWithTag("mode_video_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(CameraMode.VIDEO, state.cameraMode)
        composeTestRule.onNodeWithTag("zoom_presets_row").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_live_readout").assertIsDisplayed()
    }

    @Test
    fun `photo and video zoom strip auto-collapses into small pill after inactivity and expands on tap`() {
        var zoom by mutableFloatStateOf(1.0f)

        composeTestRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                CurvedZoomControl(
                    zoomRatio = zoom,
                    minZoomRatio = 0.5f,
                    maxZoomRatio = 8.0f,
                    hapticsEnabled = false,
                    onZoomChange = { zoom = it },
                    forceCompactPill = false,
                    idleCollapseDelayMs = 1500L,
                    modeKey = CameraMode.PHOTO
                )
            }
        }

        // Initially expanded with presets
        composeTestRule.onNodeWithTag("zoom_presets_row").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_live_readout").assertIsDisplayed()

        // Advance time past idle delay without touching -> transforms to small pill
        composeTestRule.mainClock.advanceTimeBy(1600L)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("zoom_presets_row").assertDoesNotExist()
        composeTestRule.onNodeWithTag("zoom_live_readout").assertIsDisplayed()
        composeTestRule.onNodeWithText("1.0x").assertIsDisplayed()

        // Tapping the small pill in Photo/Video mode expands the full strip again
        composeTestRule.onNodeWithTag("zoom_live_readout").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("zoom_presets_row").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_preset_2").performClick()
        composeTestRule.waitForIdle()
        assertEquals(2.0f, zoom, 0.01f)

        // Pinning curved zoom arc prevents auto-collapse while arc is open
        composeTestRule.onNodeWithTag("zoom_live_readout").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("curved_zoom_arc").assertIsDisplayed()
        composeTestRule.mainClock.advanceTimeBy(2000L)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("curved_zoom_arc").assertIsDisplayed()
    }
}
