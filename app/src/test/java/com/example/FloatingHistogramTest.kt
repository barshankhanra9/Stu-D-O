package com.example

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import com.example.model.CameraMode
import com.example.model.HistogramData
import com.example.model.HistogramDisplayMode
import com.example.ui.components.FloatingLiveHistogramOverlay
import com.example.ui.components.LiveHistogramView
import com.example.util.HistogramSampler
import com.example.viewmodel.CameraViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h740dp-xhdpi")
class FloatingHistogramTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `histogram sampler subsamples 4x4 YUV frames, computes RGB channels, and detects 2 percent clipping`() {
        val width = 64
        val height = 64
        val yBytes = ByteArray(width * height) { 128.toByte() }
        // 64x64 with step 4 => 16x16 = 256 samples.
        // Set 8 sampled pixels (8 / 256 = 3.125% > 2%) to pure black (0) and 8 sampled pixels to pure white (255).
        for (i in 0 until 8) {
            val row = i * 4
            yBytes[row * width + 0] = 0.toByte()
            yBytes[row * width + 4] = 255.toByte()
        }
        val uvWidth = width / 2
        val uvHeight = height / 2
        // Warm reddish tint: U=90 (centered -38), V=200 (centered +72) -> Red > Green > Blue
        val uBytes = ByteArray(uvWidth * uvHeight) { 90.toByte() }
        val vBytes = ByteArray(uvWidth * uvHeight) { 200.toByte() }

        val scratch = HistogramSampler.ScratchBuffers()
        val data = HistogramSampler.sampleFromYuvBuffers(
            width = width,
            height = height,
            yBuffer = ByteBuffer.wrap(yBytes),
            yRowStride = width,
            yPixelStride = 1,
            uBuffer = ByteBuffer.wrap(uBytes),
            uRowStride = uvWidth,
            uPixelStride = 1,
            vBuffer = ByteBuffer.wrap(vBytes),
            vRowStride = uvWidth,
            vPixelStride = 1,
            buffers = scratch,
            step = 4
        )

        assertTrue(data.hasData)
        assertEquals(32, data.lumaBins.size)
        assertEquals(32, data.redBins.size)
        assertEquals(32, data.greenBins.size)
        assertEquals(32, data.blueBins.size)
        assertEquals(8f / 256f, data.shadowClipFraction, 0.001f)
        assertEquals(8f / 256f, data.highlightClipFraction, 0.001f)
        assertTrue(data.isShadowClipping)
        assertTrue(data.isHighlightClipping)

        // Peak red bin index should be higher than peak blue bin index due to V=200, U=90
        val redPeakIdx = data.redBins.indices.maxByOrNull { data.redBins[it] } ?: 0
        val bluePeakIdx = data.blueBins.indices.maxByOrNull { data.blueBins[it] } ?: 0
        assertTrue("Expected red peak ($redPeakIdx) > blue peak ($bluePeakIdx)", redPeakIdx > bluePeakIdx)

        // When <= 2% (e.g. 5 / 256 = 1.95%), clipping flags stay false
        val lowClipY = ByteArray(width * height) { 128.toByte() }
        for (i in 0 until 5) {
            lowClipY[(i * 4) * width] = 0.toByte()
            lowClipY[(i * 4) * width + 4] = 255.toByte()
        }
        val lowClipData = HistogramSampler.sampleFromYuvBuffers(
            width = width,
            height = height,
            yBuffer = ByteBuffer.wrap(lowClipY),
            yRowStride = width,
            yPixelStride = 1,
            uBuffer = null,
            uRowStride = 0,
            uPixelStride = 0,
            vBuffer = null,
            vRowStride = 0,
            vPixelStride = 0,
            buffers = scratch
        )
        assertFalse(lowClipData.isShadowClipping)
        assertFalse(lowClipData.isHighlightClipping)
    }

    @Test
    fun `viewmodel toggles and persists histogram mode, collapse state, and clears data cleanly`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = CameraViewModel(app)

        assertEquals(HistogramDisplayMode.LUMA, vm.uiState.value.histogramMode)
        assertFalse(vm.uiState.value.isHistogramCollapsed)

        vm.toggleHistogramMode()
        assertEquals(HistogramDisplayMode.RGB, vm.uiState.value.histogramMode)

        // Re-creating ViewModel restores persisted RGB mode
        val vmReloaded = CameraViewModel(app)
        assertEquals(HistogramDisplayMode.RGB, vmReloaded.uiState.value.histogramMode)

        vm.toggleHistogramCollapsed()
        assertTrue(vm.uiState.value.isHistogramCollapsed)

        val sample = HistogramData(
            lumaBins = List(32) { it / 31f },
            redBins = List(32) { 0.5f },
            greenBins = List(32) { 0.4f },
            blueBins = List(32) { 0.3f },
            shadowClipFraction = 0.05f,
            highlightClipFraction = 0.03f
        )
        vm.updateHistogramData(sample)
        assertEquals(sample, vm.histogramData.value)
        assertEquals(sample.lumaBins, vm.histogramBins.value)

        vm.setCameraMode(CameraMode.VIDEO)
        assertEquals(HistogramData.Empty, vm.histogramData.value)
        assertTrue(vm.histogramBins.value.isEmpty())
    }

    @Test
    fun `floating histogram toggles LUMA and RGB pill, double-taps to collapse, and updates clipping indicators`() {
        val sampleData = HistogramData(
            lumaBins = List(32) { i -> kotlin.math.sin(i / 31.0 * kotlin.math.PI).toFloat() },
            redBins = List(32) { i -> (i / 31f) },
            greenBins = List(32) { 0.5f },
            blueBins = List(32) { i -> 1f - (i / 31f) },
            shadowClipFraction = 0.01f,
            highlightClipFraction = 0.04f
        )
        val dataFlow = MutableStateFlow(sampleData)
        var mode by mutableStateOf(HistogramDisplayMode.LUMA)
        var collapsed by mutableStateOf(false)

        composeTestRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                FloatingLiveHistogramOverlay(
                    exposureIndex = 0,
                    histogramDataState = dataFlow,
                    histogramMode = mode,
                    isCollapsed = collapsed,
                    hapticsEnabled = false,
                    onToggleMode = {
                        mode = if (mode == HistogramDisplayMode.LUMA) HistogramDisplayMode.RGB else HistogramDisplayMode.LUMA
                    },
                    onToggleCollapsed = {
                        collapsed = !collapsed
                    }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("live_histogram_view").assertIsDisplayed()
        composeTestRule.onNodeWithTag("histogram_expanded_content").assertIsDisplayed()
        composeTestRule.onNodeWithTag("histogram_curve_canvas").assertIsDisplayed()
        composeTestRule.onNodeWithText("LUMA").assertIsDisplayed()
        composeTestRule.onNodeWithText("S").assertIsDisplayed()
        composeTestRule.onNodeWithText("M").assertIsDisplayed()
        composeTestRule.onNodeWithText("H").assertIsDisplayed()

        // Verify clipping semantics: shadow inactive (1%), highlight active (4% > 2%)
        composeTestRule.onNodeWithContentDescription("Shadow clipping indicator inactive").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Highlight clipping indicator active").assertIsDisplayed()

        // Update shadow clip above 2% -> shadow indicator turns active
        dataFlow.value = sampleData.copy(shadowClipFraction = 0.035f)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Shadow clipping indicator active").assertIsDisplayed()

        // Tap interactive pill button -> switches LUMA to RGB
        composeTestRule.onNodeWithTag("histogram_mode_pill").performClick()
        composeTestRule.waitForIdle()
        assertEquals(HistogramDisplayMode.RGB, mode)
        composeTestRule.onNodeWithText("RGB").assertIsDisplayed()

        // Double-tap histogram card -> collapses to compact mini-pill
        composeTestRule.onNodeWithTag("live_histogram_view").performTouchInput {
            doubleClick()
        }
        composeTestRule.waitForIdle()
        assertTrue(collapsed)
        composeTestRule.onNodeWithTag("histogram_mini_pill").assertIsDisplayed()

        // Double-tap mini-pill -> expands back to full histogram graph
        composeTestRule.onNodeWithTag("live_histogram_view").performTouchInput {
            doubleClick()
        }
        composeTestRule.waitForIdle()
        assertFalse(collapsed)
        composeTestRule.onNodeWithTag("histogram_expanded_content").assertIsDisplayed()
    }

    @Test
    fun `floating histogram drags and snaps smoothly from right edge to left edge`() {
        val dataFlow = MutableStateFlow(
            HistogramData(
                lumaBins = List(32) { 0.4f },
                redBins = List(32) { 0.4f },
                greenBins = List(32) { 0.4f },
                blueBins = List(32) { 0.4f }
            )
        )

        composeTestRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                FloatingLiveHistogramOverlay(
                    exposureIndex = 0,
                    histogramDataState = dataFlow,
                    histogramMode = HistogramDisplayMode.LUMA,
                    isCollapsed = false,
                    hapticsEnabled = false,
                    onToggleMode = {},
                    onToggleCollapsed = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        val initialBounds = composeTestRule.onNodeWithTag("live_histogram_view").getBoundsInRoot()
        val containerBounds = composeTestRule.onNodeWithTag("floating_histogram_bounds").getBoundsInRoot()

        // Starts snapped near the right edge
        assertTrue(
            "Expected initial left (${initialBounds.left}) on right half of container (${containerBounds.right})",
            initialBounds.left.value > containerBounds.right.value * 0.45f
        )

        // Swipe far left across the screen
        composeTestRule.onNodeWithTag("live_histogram_view").performTouchInput {
            swipeLeft(startX = centerX, endX = centerX - 600f, durationMillis = 180)
        }
        composeTestRule.mainClock.advanceTimeBy(800L)
        composeTestRule.waitForIdle()

        val snappedBounds = composeTestRule.onNodeWithTag("live_histogram_view").getBoundsInRoot()
        assertTrue(
            "Expected histogram to snap near left edge (${snappedBounds.left}) vs initial (${initialBounds.left})",
            snappedBounds.left.value < initialBounds.left.value * 0.4f
        )
    }

    @Test
    fun `standalone LiveHistogramView renders cleanly with legacy histogramBinsState`() {
        val legacyBins = MutableStateFlow(List(24) { i -> (i + 1) / 24f })
        composeTestRule.setContent {
            LiveHistogramView(
                exposureIndex = 0,
                histogramBinsState = legacyBins
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("live_histogram_view").assertIsDisplayed()
        composeTestRule.onNodeWithTag("histogram_curve_canvas").assertIsDisplayed()
    }
}
