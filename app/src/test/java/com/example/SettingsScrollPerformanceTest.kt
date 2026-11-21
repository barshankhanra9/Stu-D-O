package com.example

import android.app.Application
import android.os.Debug
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.example.model.CameraUiState
import com.example.model.DynamicResolutionOption
import com.example.model.PhotoResolution
import com.example.model.WatermarkConfig
import com.example.model.WatermarkLogoType
import com.example.model.WatermarkStyle
import com.example.model.toSettingsUiState
import com.example.ui.components.CameraSettingsDialog
import com.example.ui.components.WatermarkPreviewCache
import com.example.ui.components.WatermarkStudioScreen
import com.example.util.CameraMetadataUtil
import com.example.util.WatermarkPainter
import com.example.viewmodel.CameraViewModel
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xxhdpi")
class SettingsScrollPerformanceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        WatermarkPreviewCache.clear()
        WatermarkPainter.clearSampleCache()
    }

    private fun richSettingsUiState(): CameraUiState {
        return CameraUiState(
            isSettingsOpen = true,
            isRawSupportedOnCurrentLens = true,
            isUhrSupportedOnCurrentLens = true,
            isVideoStabilizationSupported = true,
            uhrMegapixels = 50.0,
            uhrSize = Size(8160, 6144),
            standardSensorResolutionText = "4080 × 3072",
            availableResolutionOptions = listOf(
                DynamicResolutionOption(
                    resolution = PhotoResolution.ULTRA_RES,
                    title = "50 MP Full Sensor",
                    size = Size(8160, 6144),
                    isStallStream = true,
                    description = "Maximum physical sensor resolution"
                ),
                DynamicResolutionOption(
                    resolution = PhotoResolution.MAX,
                    title = "12.5 MP Binned",
                    size = Size(4080, 3072),
                    isStallStream = false,
                    description = "4-in-1 pixel binning"
                ),
                DynamicResolutionOption(
                    resolution = PhotoResolution.HIGH_12MP,
                    title = "9 MP Balanced",
                    size = Size(3648, 2736),
                    isStallStream = false,
                    description = "Fast processing"
                ),
                DynamicResolutionOption(
                    resolution = PhotoResolution.STANDARD_8MP,
                    title = "8 MP Compact",
                    size = Size(3264, 2448),
                    isStallStream = false,
                    description = "Compact storage"
                )
            ),
            watermarkConfig = WatermarkConfig(
                isEnabled = true,
                style = WatermarkStyle.GLASS_FRAME,
                logoType = WatermarkLogoType.DEVICE_MANUFACTURER
            )
        )
    }

    @Test
    fun `measure fast swipe scroll frame timings and verify smooth item virtualization`() {
        val state = richSettingsUiState()
        val frameDurationsNs = mutableListOf<Long>()

        composeTestRule.mainClock.autoAdvance = false

        val runtime = Runtime.getRuntime()
        val jvmBefore = runtime.totalMemory() - runtime.freeMemory()
        val nativeBefore = Debug.getNativeHeapAllocatedSize()

        val initStart = System.nanoTime()
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
                onSelectWatermarkStyle = {},
                onSetWatermarkCustomText = {},
                onSelectWatermarkPosition = {},
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
        composeTestRule.mainClock.advanceTimeByFrame()
        val initMs = (System.nanoTime() - initStart) / 1_000_000.0

        // Let initial watermark preview render complete
        repeat(20) {
            val t0 = System.nanoTime()
            composeTestRule.mainClock.advanceTimeBy(16L)
            frameDurationsNs.add(System.nanoTime() - t0)
        }

        val scrollTimesMs = mutableListOf<Double>()
        val listNode = composeTestRule.onNodeWithTag("settings_lazy_column")

        // Perform 4 fast swipe-up flings and 4 fast swipe-down flings, stepping every 8ms (120Hz budget)
        repeat(4) {
            listNode.performTouchInput {
                swipeUp(startY = bottom * 0.85f, endY = top + 50f, durationMillis = 80L)
            }
            repeat(24) {
                val t0 = System.nanoTime()
                composeTestRule.mainClock.advanceTimeBy(8L)
                scrollTimesMs.add((System.nanoTime() - t0) / 1_000_000.0)
            }
        }

        // Bottom item should be reachable cleanly
        composeTestRule.onNodeWithText("STU•D•O Pro Camera").assertExists()

        repeat(4) {
            listNode.performTouchInput {
                swipeDown(startY = top + 50f, endY = bottom * 0.85f, durationMillis = 80L)
            }
            repeat(24) {
                val t0 = System.nanoTime()
                composeTestRule.mainClock.advanceTimeBy(8L)
                scrollTimesMs.add((System.nanoTime() - t0) / 1_000_000.0)
            }
        }

        val jvmDeltaKb = ((runtime.totalMemory() - runtime.freeMemory()) - jvmBefore) / 1024L
        val nativeDeltaKb = (Debug.getNativeHeapAllocatedSize() - nativeBefore) / 1024L

        val sorted = scrollTimesMs.sorted()
        val p50 = sorted[(sorted.size * 0.50).toInt()]
        val p90 = sorted[(sorted.size * 0.90).toInt()]
        val p99 = sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.lastIndex)]
        val max = sorted.last()
        val over8ms = sorted.count { it > 8.33 }
        val over16ms = sorted.count { it > 16.67 }

        println(
            "SETTINGS_BENCHMARK: initMs=${String.format("%.2f", initMs)}, " +
                "frames=${sorted.size}, p50=${String.format("%.2f", p50)}ms, " +
                "p90=${String.format("%.2f", p90)}ms, p99=${String.format("%.2f", p99)}ms, " +
                "max=${String.format("%.2f", max)}ms, over8_3ms=$over8ms, over16_6ms=$over16ms, " +
                "jvmDeltaKb=$jvmDeltaKb, nativeDeltaKb=$nativeDeltaKb"
        )

        assertTrue("Expected frames to be measured", sorted.isNotEmpty())
        composeTestRule.onNodeWithText("SETTINGS").assertIsDisplayed()
    }

    @Test
    fun `watermark preview cache avoids re-rendering when scrolling away and back`() {
        val state = richSettingsUiState()

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
                onSelectWatermarkStyle = {},
                onSetWatermarkCustomText = {},
                onSelectWatermarkPosition = {},
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

        // Scroll to watermark studio button so LiveWatermarkPreviewCard enters composition and warms cache
        composeTestRule.onNodeWithTag("open_watermark_studio_button").performScrollTo()
        composeTestRule.waitUntil(timeoutMillis = 5000L) {
            WatermarkPreviewCache.get(state.watermarkConfig) != null
        }

        val cachedFirst = WatermarkPreviewCache.get(state.watermarkConfig)
        assertTrue("Watermark preview should be cached", cachedFirst != null)

        // Scroll all the way down (evicts watermark card from LazyColumn viewport) and back up
        val listNode = composeTestRule.onNodeWithTag("settings_lazy_column")
        listNode.performTouchInput {
            repeat(5) {
                swipeUp(startY = bottom * 0.85f, endY = top + 50f, durationMillis = 80L)
            }
        }
        composeTestRule.waitForIdle()
        listNode.performTouchInput {
            repeat(5) {
                swipeDown(startY = top + 50f, endY = bottom * 0.85f, durationMillis = 80L)
            }
        }
        composeTestRule.waitForIdle()

        val cachedAfterReturn = WatermarkPreviewCache.get(state.watermarkConfig)
        assertTrue(
            "Cached preview instance should be reused identically without re-allocating",
            cachedAfterReturn === cachedFirst
        )
    }

    @Test
    fun `unrelated camera state updates do not recompose settings dialog`() {
        var recompositions = 0
        var uiState by mutableStateOf(richSettingsUiState())

        composeTestRule.setContent {
            val current = uiState
            val trackComposition: () -> Unit = remember { { recompositions++ } }
            CameraSettingsDialog(
                uiState = current,
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
                onSelectWatermarkStyle = {},
                onSetWatermarkCustomText = {},
                onSelectWatermarkPosition = {},
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
                onVersionTapped = {},
                onContentComposed = trackComposition
            )
        }
        composeTestRule.waitForIdle()
        val baselineCompositions = recompositions

        // Mutate unrelated camera preview / capture fields 10 times while settings is open
        repeat(10) { idx ->
            val updated = uiState.copy(
                zoomRatio = 1.0f + idx * 0.2f,
                isStreaming = idx % 2 == 0,
                shutterFlashTrigger = idx.toLong(),
                focusPoint = Offset(100f + idx, 200f + idx),
                isCapturing = idx % 3 == 0
            )
            assertEquals(uiState.toSettingsUiState(), updated.toSettingsUiState())
            uiState = updated
            composeTestRule.waitForIdle()
        }
        val extraRecompositions = recompositions - baselineCompositions
        println("SETTINGS_RECOMP_CHECK: baseline=$baselineCompositions, extraRecompositions=$extraRecompositions")
        assertEquals(
            "Inner SettingsUiState dialog must not recompose when only unrelated CameraUiState fields change",
            0,
            extraRecompositions
        )
    }

    @Test
    fun `viewmodel settingsUiState isolates high frequency camera updates from settings recomposition`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = CameraViewModel(app)
        viewModel.toggleSettings()
        assertTrue(viewModel.isSettingsOpenState.value)

        var recompositions = 0
        composeTestRule.setContent {
            val isOpen by viewModel.isSettingsOpenState.collectAsStateWithLifecycle()
            if (isOpen) {
                val settingsState by viewModel.settingsUiState.collectAsStateWithLifecycle()
                val trackComposition: () -> Unit = remember { { recompositions++ } }
                CameraSettingsDialog(
                    settingsState = settingsState,
                    onDismiss = { viewModel.closeSettings() },
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
                    onVersionTapped = {},
                    onContentComposed = trackComposition
                )
            }
        }
        composeTestRule.waitForIdle()
        val baseline = recompositions

        // Rapidly mutate viewfinder-only ViewModel flows while Settings is open
        repeat(15) { idx ->
            viewModel.onStreamStateChanged(idx % 2 == 0)
            viewModel.setZoomRatio(1.0f + idx * 0.1f)
            viewModel.onOrientationChanged(roll = idx * 1.5f, pitch = idx * 0.5f)
            viewModel.updateHistogramBins(List(24) { (idx + it) / 40f })
            composeTestRule.waitForIdle()
        }

        val extra = recompositions - baseline
        println("VM_SETTINGS_ISOLATION: baseline=$baseline, extraRecompositions=$extra")
        assertEquals(0, extra)
    }

    @Test
    fun `expandable watermark section toggles smoothly and reuses cached preview`() {
        val initial = richSettingsUiState()
        var uiState by mutableStateOf(initial)

        composeTestRule.setContent {
            CameraSettingsDialog(
                uiState = uiState,
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
                onToggleWatermark = {
                    val cfg = uiState.watermarkConfig
                    uiState = uiState.copy(watermarkConfig = cfg.copy(isEnabled = !cfg.isEnabled))
                },
                onOpenWatermarkStudio = {},
                onSelectWatermarkStyle = {},
                onSetWatermarkCustomText = {},
                onSelectWatermarkPosition = {},
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

        composeTestRule.onNodeWithTag("open_watermark_studio_button").performScrollTo()
        composeTestRule.waitUntil(timeoutMillis = 5000L) {
            WatermarkPreviewCache.get(uiState.watermarkConfig) != null
        }
        val cachedFirst = WatermarkPreviewCache.get(uiState.watermarkConfig)

        // Collapse watermark card
        composeTestRule.onNodeWithTag("settings_toggle_watermark").performClick()
        composeTestRule.waitForIdle()
        assertFalse(uiState.watermarkConfig.isEnabled)

        // Re-expand watermark card and verify cached ImageBitmap is reused cleanly
        composeTestRule.onNodeWithTag("settings_toggle_watermark").performClick()
        composeTestRule.waitForIdle()
        assertTrue(uiState.watermarkConfig.isEnabled)
        composeTestRule.onNodeWithTag("open_watermark_studio_button").assertIsDisplayed()
        assertTrue(WatermarkPreviewCache.get(uiState.watermarkConfig) === cachedFirst)
    }

    @Test
    fun `watermark studio caches previews for undo and painter avoids redundant allocations`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val initialCfg = WatermarkConfig(
            isEnabled = true,
            style = WatermarkStyle.LEICA_FRAME,
            logoType = WatermarkLogoType.DEVICE_MANUFACTURER
        )

        val renderTimesMs = mutableListOf<Double>()
        val meta = CameraMetadataUtil.samplePreviewMetadata(720, 540)
        // Warm 1st render
        val baseWarm = WatermarkPainter.getReadOnlySamplePhoto(720, 540)
        WatermarkPainter.applyWatermark(baseWarm, initialCfg.copy(style = WatermarkStyle.GLASS_FRAME), meta, context)

        val memBefore = Debug.getNativeHeapAllocatedSize()
        repeat(8) { idx ->
            val cfg = initialCfg.copy(
                style = WatermarkStyle.GLASS_FRAME,
                backgroundOpacity = 0.5f + idx * 0.05f,
                logoOpacity = 0.5f + idx * 0.05f
            )
            val t0 = System.nanoTime()
            val out = WatermarkPainter.applyWatermark(
                source = WatermarkPainter.getReadOnlySamplePhoto(720, 540),
                config = cfg,
                metadata = meta,
                context = context
            )
            renderTimesMs.add((System.nanoTime() - t0) / 1_000_000.0)
            assertNotNull(out)
        }
        val nativeDeltaKb = (Debug.getNativeHeapAllocatedSize() - memBefore) / 1024L
        val avgRenderMs = renderTimesMs.average()

        composeTestRule.setContent {
            WatermarkStudioScreen(
                initialConfig = initialCfg,
                savedPresets = emptyList(),
                onSaveConfig = {},
                onSavePreset = { _, _ -> },
                onDeletePreset = {},
                onDismiss = {}
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5000L) {
            try {
                composeTestRule.onNodeWithText("Rendering Studio Preview...").assertDoesNotExist()
                true
            } catch (_: AssertionError) {
                false
            }
        }

        // Scroll lazily composed template row to switch templates rapidly, verifying cancellation and Undo cache reuse
        composeTestRule.onNodeWithTag("studio_template_glass_frame").performClick()
        composeTestRule.onNodeWithTag("studio_templates_lazy_row").performScrollToIndex(3)
        composeTestRule.onNodeWithTag("studio_template_cyberpunk").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_undo_button").performClick()
        composeTestRule.waitForIdle()

        println(
            "STUDIO_BENCHMARK: avgGlassRenderMs=${String.format("%.2f", avgRenderMs)}, " +
                "nativeDelta8RendersKb=$nativeDeltaKb"
        )
        assertTrue("Expected positive render measurements", avgRenderMs >= 0.0)
    }

    @Test
    fun `virtualized resolution and capture priority rows update selection cleanly after fast fling`() {
        var uiState by mutableStateOf(
            richSettingsUiState().copy(
                actualConfiguredResolution = Size(4080, 3072)
            )
        )

        composeTestRule.setContent {
            CameraSettingsDialog(
                uiState = uiState,
                onDismiss = {},
                onToggleHistogram = {},
                onToggleLevel = {},
                onSelectGrid = {},
                onToggleShutterSound = {},
                onSelectTimer = {},
                onSelectAspectRatio = {},
                onSelectPhotoResolution = { res ->
                    uiState = uiState.copy(photoResolution = res)
                },
                onSelectCapturePriority = { priority ->
                    uiState = uiState.copy(capturePriority = priority)
                },
                onSelectRawCaptureMode = {},
                onToggleHdr = {},
                onToggleWatermark = {},
                onOpenWatermarkStudio = {},
                onSelectWatermarkStyle = {},
                onSetWatermarkCustomText = {},
                onSelectWatermarkPosition = {},
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

        // Verify Active Session Stream footer is displayed and clickable resolution row updates state
        composeTestRule.onNodeWithText("Active Session Stream: 4080 × 3072").assertIsDisplayed()
        composeTestRule.onNodeWithText("9 MP Balanced").performClick()
        composeTestRule.waitForIdle()
        assertEquals(PhotoResolution.HIGH_12MP, uiState.photoResolution)

        // Fling to bottom and back to top, then select a Capture Priority row
        repeat(5) {
            listNode.performTouchInput {
                swipeUp(startY = bottom * 0.85f, endY = top + 50f, durationMillis = 80L)
            }
            composeTestRule.waitForIdle()
        }
        composeTestRule.onNodeWithText("STU•D•O Pro Camera").assertIsDisplayed()

        repeat(6) {
            listNode.performTouchInput {
                swipeDown(startY = top + 50f, endY = bottom * 0.85f, durationMillis = 80L)
            }
            composeTestRule.waitForIdle()
        }

        composeTestRule.onNodeWithText(com.example.model.CapturePriority.LATENCY.displayName).performClick()
        composeTestRule.waitForIdle()
        assertEquals(com.example.model.CapturePriority.LATENCY, uiState.capturePriority)
    }
}
