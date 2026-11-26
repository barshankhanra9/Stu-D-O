package com.example

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.model.CameraMode
import com.example.model.CameraUiState
import com.example.model.FocusPeakingColor
import com.example.model.FocusPeakingSensitivity
import com.example.model.ProAssistPopover
import com.example.model.ZebraThreshold
import com.example.ui.components.ProAssistOverlay
import com.example.ui.components.ProControlsBar
import com.example.util.ProAssistAnalyzer
import com.example.util.ProAssistOverlayFrame
import com.example.viewmodel.CameraViewModel
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h740dp-xhdpi")
class ProFocusPeakingAndZebraTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `analyzer detects Sobel focus peaking edges, respects sensitivity and color, and flags IRE zebra highlights in single pass`() {
        val analyzer = ProAssistAnalyzer(subsampleStep = 2)
        val width = 32
        val height = 24
        val yBytes = ByteArray(width * height) { 40.toByte() }

        // Create a sharp vertical edge at x = 16 (jump from 40 -> 180) and a blown-out block at x >= 24 (Y = 246)
        for (y in 0 until height) {
            for (x in 16 until 24) {
                yBytes[y * width + x] = 180.toByte()
            }
            for (x in 24 until width) {
                yBytes[y * width + x] = 246.toByte()
            }
        }

        val frame95 = analyzer.analyzeFromYPlane(
            width = width,
            height = height,
            yBuffer = ByteBuffer.wrap(yBytes),
            yRowStride = width,
            yPixelStride = 1,
            rotationDegrees = 0,
            isFrontCamera = false,
            enablePeaking = true,
            peakingColorArgb = FocusPeakingColor.GOLD.argb,
            peakingThreshold = FocusPeakingSensitivity.MEDIUM.gradientThreshold,
            enableZebra = true,
            zebraLumaThreshold = ZebraThreshold.IRE_95.lumaThreshold
        )

        assertNotNull(frame95)
        frame95!!
        assertEquals(16, frame95.frameWidth)
        assertEquals(12, frame95.frameHeight)
        assertTrue(frame95.hasPeakingEdges)
        assertTrue(frame95.hasZebraHighlights)

        // Verify Focus Peaking bitmap has Gold RGB pixels along the edge and transparent flat areas
        val peakBmp = frame95.peakingBitmap!!.asAndroidBitmap()
        val edgePixel = peakBmp.getPixel(8, 6)
        assertTrue("Expected non-zero alpha on sharp edge", (edgePixel ushr 24) > 0)
        assertEquals(FocusPeakingColor.GOLD.argb and 0x00FFFFFF, edgePixel and 0x00FFFFFF)
        assertEquals(0, peakBmp.getPixel(3, 6))

        // Verify Zebra mask flags Y=246 when threshold is 95% IRE (242), but NOT when threshold is 100% IRE (250)
        val zebraBmp95 = frame95.zebraMaskBitmap!!.asAndroidBitmap()
        assertEquals(-1, zebraBmp95.getPixel(13, 6))
        assertEquals(0, zebraBmp95.getPixel(3, 6))

        val frame100 = analyzer.analyzeFromYPlane(
            width = width,
            height = height,
            yBuffer = ByteBuffer.wrap(yBytes),
            yRowStride = width,
            yPixelStride = 1,
            rotationDegrees = 0,
            isFrontCamera = false,
            enablePeaking = false,
            peakingColorArgb = FocusPeakingColor.CYAN.argb,
            peakingThreshold = FocusPeakingSensitivity.MEDIUM.gradientThreshold,
            enableZebra = true,
            zebraLumaThreshold = ZebraThreshold.IRE_100.lumaThreshold
        )!!
        assertFalse("Y=246 should not trigger 100% IRE (250) zebra", frame100.hasZebraHighlights)
        assertNull(frame100.peakingBitmap)

        // Verify sensitivity filtering: mild step (delta 30 => Sobel ~120) triggers HIGH (85) but not LOW (200)
        val mildBytes = ByteArray(width * height) { 80.toByte() }
        for (y in 0 until height) {
            for (x in 16 until width) {
                mildBytes[y * width + x] = 110.toByte()
            }
        }
        val highSensFrame = analyzer.analyzeFromYPlane(
            width = width,
            height = height,
            yBuffer = ByteBuffer.wrap(mildBytes),
            yRowStride = width,
            yPixelStride = 1,
            rotationDegrees = 0,
            isFrontCamera = false,
            enablePeaking = true,
            peakingColorArgb = FocusPeakingColor.CYAN.argb,
            peakingThreshold = FocusPeakingSensitivity.HIGH.gradientThreshold,
            enableZebra = false,
            zebraLumaThreshold = ZebraThreshold.IRE_95.lumaThreshold
        )!!
        val lowSensFrame = analyzer.analyzeFromYPlane(
            width = width,
            height = height,
            yBuffer = ByteBuffer.wrap(mildBytes),
            yRowStride = width,
            yPixelStride = 1,
            rotationDegrees = 0,
            isFrontCamera = false,
            enablePeaking = true,
            peakingColorArgb = FocusPeakingColor.CYAN.argb,
            peakingThreshold = FocusPeakingSensitivity.LOW.gradientThreshold,
            enableZebra = false,
            zebraLumaThreshold = ZebraThreshold.IRE_95.lumaThreshold
        )!!
        assertTrue(highSensFrame.hasPeakingEdges)
        assertFalse(lowSensFrame.hasPeakingEdges)
    }

    @Test
    fun `analyzer applies 90-degree rotation and front-camera horizontal mirroring accurately`() {
        val analyzer = ProAssistAnalyzer(subsampleStep = 2)
        val width = 20
        val height = 16
        val yBytes = ByteArray(width * height) { 30.toByte() }
        // Highlight sensor top-left corner (sx in 0..3, sy in 0..3)
        for (sy in 0..3) {
            for (sx in 0..3) {
                yBytes[sy * width + sx] = 255.toByte()
            }
        }

        // Rotation 90 CW moves sensor top-left to display top-right
        val backRotated = analyzer.analyzeFromYPlane(
            width = width,
            height = height,
            yBuffer = ByteBuffer.wrap(yBytes),
            yRowStride = width,
            yPixelStride = 1,
            rotationDegrees = 90,
            isFrontCamera = false,
            enablePeaking = false,
            peakingColorArgb = FocusPeakingColor.CYAN.argb,
            peakingThreshold = FocusPeakingSensitivity.MEDIUM.gradientThreshold,
            enableZebra = true,
            zebraLumaThreshold = ZebraThreshold.IRE_90.lumaThreshold
        )!!
        assertEquals(8, backRotated.frameWidth)
        assertEquals(10, backRotated.frameHeight)
        val backBmp = backRotated.zebraMaskBitmap!!.asAndroidBitmap()
        assertEquals(-1, backBmp.getPixel(backRotated.frameWidth - 1, 0))
        assertEquals(0, backBmp.getPixel(0, 0))

        // With front-camera mirroring, display top-right mirrors horizontally to display top-left
        val frontMirrored = analyzer.analyzeFromYPlane(
            width = width,
            height = height,
            yBuffer = ByteBuffer.wrap(yBytes),
            yRowStride = width,
            yPixelStride = 1,
            rotationDegrees = 90,
            isFrontCamera = true,
            enablePeaking = false,
            peakingColorArgb = FocusPeakingColor.CYAN.argb,
            peakingThreshold = FocusPeakingSensitivity.MEDIUM.gradientThreshold,
            enableZebra = true,
            zebraLumaThreshold = ZebraThreshold.IRE_90.lumaThreshold
        )!!
        val frontBmp = frontMirrored.zebraMaskBitmap!!.asAndroidBitmap()
        assertEquals(-1, frontBmp.getPixel(0, 0))
        assertEquals(0, frontBmp.getPixel(frontMirrored.frameWidth - 1, 0))
    }

    @Test
    fun `viewmodel persists assist customization and automatically disables overlays when leaving Pro mode`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = CameraViewModel(app)

        vm.setCameraMode(CameraMode.PRO)
        assertFalse(vm.uiState.value.isFocusPeakingEnabled)
        assertFalse(vm.uiState.value.isZebraStripesEnabled)

        vm.toggleFocusPeaking()
        vm.toggleZebraStripes()
        vm.setFocusPeakingColor(FocusPeakingColor.RED)
        vm.setFocusPeakingSensitivity(FocusPeakingSensitivity.HIGH)
        vm.setZebraThreshold(ZebraThreshold.IRE_100)
        vm.setActiveProAssistPopover(ProAssistPopover.FOCUS_PEAKING)

        assertTrue(vm.uiState.value.isProFocusPeakingOverlayVisible)
        assertTrue(vm.uiState.value.isProZebraOverlayVisible)
        assertEquals(FocusPeakingColor.RED, vm.uiState.value.focusPeakingColor)
        assertEquals(FocusPeakingSensitivity.HIGH, vm.uiState.value.focusPeakingSensitivity)
        assertEquals(ZebraThreshold.IRE_100, vm.uiState.value.zebraThreshold)
        assertEquals(ProAssistPopover.FOCUS_PEAKING, vm.uiState.value.activeProAssistPopover)

        // Switching out of Pro mode automatically disables/hides both overlays & closes popover
        vm.setCameraMode(CameraMode.PHOTO)
        assertFalse(vm.uiState.value.isFocusPeakingEnabled)
        assertFalse(vm.uiState.value.isZebraStripesEnabled)
        assertFalse(vm.uiState.value.isProFocusPeakingOverlayVisible)
        assertFalse(vm.uiState.value.isProZebraOverlayVisible)
        assertEquals(ProAssistPopover.NONE, vm.uiState.value.activeProAssistPopover)
        assertNull(vm.proAssistFrame.value)

        // Persisted customization options survive ViewModel recreation
        val reloadedVm = CameraViewModel(app)
        assertEquals(FocusPeakingColor.RED, reloadedVm.uiState.value.focusPeakingColor)
        assertEquals(FocusPeakingSensitivity.HIGH, reloadedVm.uiState.value.focusPeakingSensitivity)
        assertEquals(ZebraThreshold.IRE_100, reloadedVm.uiState.value.zebraThreshold)
    }

    @Test
    fun `pro controls bar renders PEAK and ZEBRA pills, opens customization popovers via chevron and long-press`() {
        var state by mutableStateOf(CameraUiState(cameraMode = CameraMode.PRO))

        composeTestRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                ProControlsBar(
                    uiState = state,
                    onTabSelected = {},
                    onIsoChanged = {},
                    onShutterChanged = {},
                    onEvChanged = {},
                    onFocusChanged = { _, _ -> },
                    onWhiteBalanceChanged = {},
                    onResetAll = {},
                    onToggleFocusPeaking = {
                        state = state.copy(isFocusPeakingEnabled = !state.isFocusPeakingEnabled)
                    },
                    onToggleZebraStripes = {
                        state = state.copy(isZebraStripesEnabled = !state.isZebraStripesEnabled)
                    },
                    onSelectAssistPopover = { popover ->
                        val next = if (state.activeProAssistPopover == popover) ProAssistPopover.NONE else popover
                        state = state.copy(activeProAssistPopover = next)
                    },
                    onFocusPeakingColorChanged = { state = state.copy(focusPeakingColor = it) },
                    onFocusPeakingSensitivityChanged = { state = state.copy(focusPeakingSensitivity = it) },
                    onZebraThresholdChanged = { state = state.copy(zebraThreshold = it) }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("pro_assist_toolbar").assertIsDisplayed()
        composeTestRule.onNodeWithText("PEAK").assertIsDisplayed()
        composeTestRule.onNodeWithText("ZEBRA").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pro_assist_popover").assertDoesNotExist()

        // Tap PEAK -> enables Focus Peaking
        composeTestRule.onNodeWithTag("pro_peak_pill").performClick()
        composeTestRule.waitForIdle()
        assertTrue(state.isFocusPeakingEnabled)

        // Tap PEAK chevron -> opens Focus Peaking popover and selects Gold + High
        composeTestRule.onNodeWithTag("pro_peak_chevron").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("pro_assist_popover").assertIsDisplayed()
        composeTestRule.onNodeWithTag("peaking_color_gold").performClick()
        composeTestRule.onNodeWithTag("peaking_sensitivity_high").performClick()
        composeTestRule.waitForIdle()
        assertEquals(FocusPeakingColor.GOLD, state.focusPeakingColor)
        assertEquals(FocusPeakingSensitivity.HIGH, state.focusPeakingSensitivity)

        // Long-press ZEBRA pill -> switches popover to Zebra IRE threshold customization
        composeTestRule.onNodeWithTag("pro_zebra_pill").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        assertEquals(ProAssistPopover.ZEBRA, state.activeProAssistPopover)
        composeTestRule.onNodeWithTag("zebra_threshold_ire_90").performClick()
        composeTestRule.waitForIdle()
        assertEquals(ZebraThreshold.IRE_90, state.zebraThreshold)
    }

    @Test
    fun `pro assist overlay renders peaking and zebra canvases across aspect ratios and hides when disabled`() {
        val analyzer = ProAssistAnalyzer(subsampleStep = 2)
        val width = 32
        val height = 24
        val yBytes = ByteArray(width * height) { 50.toByte() }
        for (y in 0 until height) {
            for (x in 16 until width) {
                yBytes[y * width + x] = 250.toByte()
            }
        }
        val frame = analyzer.analyzeFromYPlane(
            width = width,
            height = height,
            yBuffer = ByteBuffer.wrap(yBytes),
            yRowStride = width,
            yPixelStride = 1,
            rotationDegrees = 90,
            isFrontCamera = false,
            enablePeaking = true,
            peakingColorArgb = FocusPeakingColor.CYAN.argb,
            peakingThreshold = FocusPeakingSensitivity.MEDIUM.gradientThreshold,
            enableZebra = true,
            zebraLumaThreshold = ZebraThreshold.IRE_95.lumaThreshold
        )
        val frameFlow = MutableStateFlow<ProAssistOverlayFrame?>(frame)
        var showPeaking by mutableStateOf(true)
        var showZebra by mutableStateOf(true)
        var aspect by mutableStateOf(3f / 4f)

        composeTestRule.setContent {
            Box(
                modifier = Modifier
                    .width(300.dp)
                    .aspectRatio(aspect)
            ) {
                ProAssistOverlay(
                    frameState = frameFlow,
                    isPeakingVisible = showPeaking,
                    isZebraVisible = showZebra
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("pro_assist_overlay").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pro_peaking_overlay_canvas").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pro_zebra_overlay_canvas").assertIsDisplayed()

        // Test 16:9 and 1:1 aspect ratio alignment renders cleanly
        aspect = 9f / 16f
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("pro_peaking_overlay_canvas").assertIsDisplayed()

        aspect = 1f
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("pro_zebra_overlay_canvas").assertIsDisplayed()

        // Disabling both hides overlay completely
        showPeaking = false
        showZebra = false
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("pro_assist_overlay").assertDoesNotExist()
    }
}
