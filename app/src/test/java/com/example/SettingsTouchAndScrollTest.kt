package com.example

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import com.example.model.CameraUiState
import com.example.model.GridOption
import com.example.model.RawCaptureMode
import com.example.model.WatermarkConfig
import com.example.model.WatermarkLogoType
import com.example.model.WatermarkStyle
import com.example.ui.components.CameraSettingsDialog
import com.example.ui.components.WatermarkPreviewCache
import com.example.util.WatermarkPainter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h740dp-xhdpi")
class SettingsTouchAndScrollTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        WatermarkPreviewCache.clear()
        WatermarkPainter.clearSampleCache()
    }

    private fun testSettingsState(): CameraUiState = CameraUiState(
        isSettingsOpen = true,
        isRawSupportedOnCurrentLens = true,
        isUhrSupportedOnCurrentLens = true,
        isVideoStabilizationSupported = true,
        watermarkConfig = WatermarkConfig(
            isEnabled = true,
            style = WatermarkStyle.GLASS_FRAME,
            logoType = WatermarkLogoType.DEVICE_MANUFACTURER
        )
    )

    @Test
    fun `first touch vertical scroll with gradual pre-slop movement scrolls from top to bottom and back`() {
        val state = testSettingsState()
        composeTestRule.setContent {
            CameraSettingsDialog(
                uiState = state,
                onDismiss = {},
                onToggleHistogram = {},
                onToggleLevel = {},
                onSelectGrid = {},
                onToggleShutterSound = {},
                onSelectTimer = {},
                onSelectAspectRatio = {},
                onSelectPhotoResolution = {},
                onSelectCapturePriority = {},
                onSelectRawCaptureMode = {},
                onToggleHdr = {},
                onToggleWatermark = {},
                onOpenWatermarkStudio = {},
                onSelectVideoQuality = {},
                onSelectVideoCodec = {},
                onSelectVideoFps = {},
                onToggleVideoStabilization = {},
                onToggleAudio = {},
                onToggleSelfieMirror = {},
                onToggleSaveLocation = {},
                onToggleHapticFeedback = {},
                onToggleKeepScreenAwake = {},
                onSelectVolumeKeyAction = {},
                onSelectOemEnhancementMode = {},
                onVersionTapped = {}
            )
        }

        val listNode = composeTestRule.onNodeWithTag("settings_lazy_column")

        // Simulate realistic finger touch starting with tiny pre-slop increments (< touchSlop)
        // on the first swipe, plus edge-margin horizontal offset (10dp in from left edge).
        listNode.performTouchInput {
            val startX = left + 20f
            val startY = bottom * 0.78f
            down(0, Offset(startX, startY))
            // Small pre-slop movements typical of 120Hz / phone digitizers
            moveTo(0, Offset(startX, startY - 4f))
            moveTo(0, Offset(startX, startY - 9f))
            moveTo(0, Offset(startX, startY - 15f))
            // Cross slop and drag steadily upward
            for (step in 1..10) {
                moveTo(0, Offset(startX, startY - 15f - step * 45f))
            }
            up(0)
        }
        composeTestRule.waitForIdle()

        // Rapid swipes all the way to the bottom of Settings
        repeat(5) {
            listNode.performTouchInput {
                swipeUp(startY = bottom * 0.85f, endY = top + 60f, durationMillis = 90L)
            }
            composeTestRule.waitForIdle()
        }

        composeTestRule.onNodeWithText("STU•D•O Pro Camera").assertIsDisplayed()
        composeTestRule.onNodeWithText("v2.0 Pro").assertIsDisplayed()

        // Repeated rapid swipes back to the top
        repeat(6) {
            listNode.performTouchInput {
                swipeDown(startY = top + 60f, endY = bottom * 0.85f, durationMillis = 90L)
            }
            composeTestRule.waitForIdle()
        }

        composeTestRule.onNodeWithText("PHOTO CAPTURE").assertIsDisplayed()
    }

    @Test
    fun `two finger vertical scroll reliably scrolls settings list`() {
        val state = testSettingsState()
        composeTestRule.setContent {
            CameraSettingsDialog(
                uiState = state,
                onDismiss = {},
                onToggleHistogram = {},
                onToggleLevel = {},
                onSelectGrid = {},
                onToggleShutterSound = {},
                onSelectTimer = {},
                onSelectAspectRatio = {},
                onSelectPhotoResolution = {},
                onSelectCapturePriority = {},
                onSelectRawCaptureMode = {},
                onToggleHdr = {},
                onToggleWatermark = {},
                onOpenWatermarkStudio = {},
                onSelectVideoQuality = {},
                onSelectVideoCodec = {},
                onSelectVideoFps = {},
                onToggleVideoStabilization = {},
                onToggleAudio = {},
                onToggleSelfieMirror = {},
                onToggleSaveLocation = {},
                onToggleHapticFeedback = {},
                onToggleKeepScreenAwake = {},
                onSelectVolumeKeyAction = {},
                onSelectOemEnhancementMode = {},
                onVersionTapped = {}
            )
        }

        val listNode = composeTestRule.onNodeWithTag("settings_lazy_column")

        // Perform multi-touch two-finger upward swipes through the list
        repeat(6) {
            listNode.performTouchInput {
                val y0 = bottom * 0.80f
                val x1 = centerX - 60f
                val x2 = centerX + 60f
                down(0, Offset(x1, y0))
                down(1, Offset(x2, y0))
                for (step in 1..8) {
                    val y = y0 - step * 55f
                    moveTo(0, Offset(x1, y))
                    moveTo(1, Offset(x2, y))
                }
                up(0)
                up(1)
            }
            composeTestRule.waitForIdle()
        }

        composeTestRule.onNodeWithText("GENERAL & HARDWARE").assertExists()
    }

    @Test
    fun `settings overlay blocks touches from leaking to viewfinder and restores viewfinder touch on close`() {
        var isOpen by mutableStateOf(true)
        var uiState by mutableStateOf(testSettingsState())
        var underlyingTaps by mutableIntStateOf(0)
        var underlyingZoomEvents by mutableIntStateOf(0)
        var histogramToggled by mutableIntStateOf(0)
        var gridSelected: GridOption? = null
        var rawSelected: RawCaptureMode? = null

        composeTestRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                // Simulated underlying CameraViewfinder touch surface
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoomChange, _ ->
                                if (zoomChange != 1.0f) underlyingZoomEvents++
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures {
                                underlyingTaps++
                            }
                        }
                        .testTag("underlying_viewfinder")
                )

                if (isOpen) {
                    CameraSettingsDialog(
                        uiState = uiState,
                        onDismiss = { isOpen = false },
                        onToggleHistogram = { histogramToggled++ },
                        onToggleLevel = {},
                        onSelectGrid = { gridSelected = it },
                        onToggleShutterSound = {},
                        onSelectTimer = {},
                        onSelectAspectRatio = {},
                        onSelectPhotoResolution = {},
                        onSelectCapturePriority = {},
                        onSelectRawCaptureMode = { rawSelected = it },
                        onToggleHdr = {},
                        onToggleWatermark = {
                            uiState = uiState.copy(
                                watermarkConfig = uiState.watermarkConfig.copy(
                                    isEnabled = !uiState.watermarkConfig.isEnabled
                                )
                            )
                        },
                        onOpenWatermarkStudio = {},
                        onSelectVideoQuality = {},
                        onSelectVideoCodec = {},
                        onSelectVideoFps = {},
                        onToggleVideoStabilization = {},
                        onToggleAudio = {},
                        onToggleSelfieMirror = {},
                        onToggleSaveLocation = {},
                        onToggleHapticFeedback = {},
                        onToggleKeepScreenAwake = {},
                        onSelectVolumeKeyAction = {},
                        onSelectOemEnhancementMode = {},
                        onVersionTapped = {}
                    )
                }
            }
        }

        // Tap header, edge margin, and scroll while Settings is open — zero events may leak to viewfinder
        val surface = composeTestRule.onNodeWithTag("settings_dialog_surface")
        surface.performTouchInput {
            down(0, Offset(centerX, top + 20f))
            up(0)
            down(0, Offset(left + 10f, centerY))
            moveTo(0, Offset(left + 10f, centerY - 120f))
            up(0)
            // Two-finger pinch attempt over settings header/backdrop
            down(0, Offset(centerX - 50f, top + 30f))
            down(1, Offset(centerX + 50f, top + 30f))
            moveTo(0, Offset(centerX - 100f, top + 30f))
            moveTo(1, Offset(centerX + 100f, top + 30f))
            up(0)
            up(1)
        }
        composeTestRule.waitForIdle()
        assertEquals(0, underlyingTaps)
        assertEquals(0, underlyingZoomEvents)

        val listNode = composeTestRule.onNodeWithTag("settings_lazy_column")

        // Verify controls inside Settings remain clickable while scrolling works
        listNode.performScrollToNode(hasText("JPEG + RAW"))
        composeTestRule.onNodeWithText("JPEG + RAW").performClick()
        assertEquals(RawCaptureMode.JPEG_AND_DNG, rawSelected)

        // Toggle expandable Watermark section off and back on inside Settings
        listNode.performScrollToNode(hasTestTag("settings_toggle_watermark"))
        composeTestRule.onNodeWithTag("settings_toggle_watermark").performClick()
        composeTestRule.waitForIdle()
        assertFalse(uiState.watermarkConfig.isEnabled)
        composeTestRule.onNodeWithTag("settings_toggle_watermark").performClick()
        composeTestRule.waitForIdle()
        assertTrue(uiState.watermarkConfig.isEnabled)

        // Scroll to bottom switches/chips via swipe gestures and toggle them
        repeat(5) {
            listNode.performTouchInput {
                swipeUp(startY = bottom * 0.85f, endY = top + 60f, durationMillis = 90L)
            }
            composeTestRule.waitForIdle()
        }
        listNode.performScrollToNode(hasTestTag("settings_toggle_histogram"))
        composeTestRule.onNodeWithTag("settings_toggle_histogram").performClick()
        listNode.performScrollToNode(hasText("3×3"))
        composeTestRule.onNodeWithText("3×3").performClick()
        assertEquals(1, histogramToggled)
        assertEquals(GridOption.THIRDS, gridSelected)

        // Close Settings via close button
        composeTestRule.onNodeWithTag("settings_close_button").performClick()
        composeTestRule.waitForIdle()
        assertFalse(isOpen)

        // Verify underlying viewfinder now receives tap and pinch gestures cleanly
        composeTestRule.onNodeWithTag("underlying_viewfinder").performTouchInput {
            down(0, center)
            up(0)
        }
        composeTestRule.waitForIdle()
        assertTrue(underlyingTaps >= 1)

        // Re-open Settings and confirm scrolling still responds immediately from top to bottom
        isOpen = true
        composeTestRule.waitForIdle()
        repeat(5) {
            composeTestRule.onNodeWithTag("settings_lazy_column").performTouchInput {
                swipeUp(startY = bottom * 0.85f, endY = top + 60f, durationMillis = 90L)
            }
            composeTestRule.waitForIdle()
        }
        composeTestRule.onNodeWithText("STU•D•O Pro Camera").assertIsDisplayed()
    }
}
