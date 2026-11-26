package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.data.CameraPreferencesRepository
import com.example.model.CameraMode
import com.example.model.CameraUiState
import com.example.model.NightCaptureEngine
import com.example.model.NightExposureMode
import com.example.model.PhotoResolution
import com.example.model.RawCaptureMode
import com.example.night.NightFrameStacker
import com.example.night.NightStackConfig
import com.example.ui.components.CameraBottomBar
import com.example.ui.components.CameraTopBar
import com.example.ui.components.NightControlsBar
import com.example.viewmodel.CameraViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h740dp-xhdpi")
class NightModeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun createViewModel(): CameraViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return CameraViewModel(app)
    }

    @Test
    fun `night frame stacker reduces low-light noise, lifts shadows, and preserves highlights`() {
        val w = 160
        val h = 120
        val baseNoiseStdTarget = 14
        val rng = java.util.Random(42L)

        // Generate 5 dark noisy frames with a bright streetlamp spot in the center-top
        val frames = List(5) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val isLamp = (x in 72..88) && (y in 20..36)
                    val baseR: Int
                    val baseG: Int
                    val baseB: Int
                    if (isLamp) {
                        baseR = 250
                        baseG = 244
                        baseB = 225
                    } else {
                        baseR = 24
                        baseG = 26
                        baseB = 34
                    }
                    val n = if (isLamp) 0 else ((rng.nextGaussian() * baseNoiseStdTarget).toInt())
                    val r = (baseR + n).coerceIn(0, 255)
                    val g = (baseG + n).coerceIn(0, 255)
                    val b = (baseB + n).coerceIn(0, 255)
                    pixels[y * w + x] = Color.rgb(r, g, b)
                }
            }
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
            bmp
        }

        // Compute noise std on frame 0 shadow region before stacking
        fun shadowStd(bmp: Bitmap): Double {
            val vals = mutableListOf<Double>()
            for (y in 60 until 105) {
                for (x in 20 until 140) {
                    val px = bmp.getPixel(x, y)
                    val lum = 0.299 * Color.red(px) + 0.587 * Color.green(px) + 0.114 * Color.blue(px)
                    vals.add(lum)
                }
            }
            val mean = vals.average()
            val variance = vals.sumOf { (it - mean) * (it - mean) } / vals.size
            return sqrt(variance)
        }

        fun shadowMean(bmp: Bitmap): Double {
            var sum = 0.0
            var count = 0
            for (y in 60 until 105) {
                for (x in 20 until 140) {
                    val px = bmp.getPixel(x, y)
                    sum += 0.299 * Color.red(px) + 0.587 * Color.green(px) + 0.114 * Color.blue(px)
                    count++
                }
            }
            return sum / count
        }

        val inputStd = shadowStd(frames[0])
        val inputShadowMean = shadowMean(frames[0])

        val result = NightFrameStacker.stackBitmaps(
            frames = frames,
            config = NightStackConfig(
                isMaxExposure = true,
                enableAlignment = true,
                enableDenoiseSharpen = true
            )
        )

        try {
            assertFalse(result.bitmap.isRecycled)
            assertEquals(w, result.bitmap.width)
            assertEquals(h, result.bitmap.height)
            assertEquals(5, result.alignedFrameCount)

            val stackedStd = shadowStd(result.bitmap)
            val stackedShadowMean = shadowMean(result.bitmap)

            // Stacked shadows should be significantly brighter than dark raw input (~26 luma)
            assertTrue(
                "Expected stacked shadow mean ($stackedShadowMean) > input shadow mean ($inputShadowMean)",
                stackedShadowMean > inputShadowMean + 12.0
            )
            // Stacked shadows should be substantially cleaner (lower noise variance)
            assertTrue(
                "Expected stacked noise std ($stackedStd) < input std ($inputStd)",
                stackedStd < inputStd * 0.65
            )

            // Lamp highlight core stays bright without clipping to black or muddying
            val lampCenter = result.bitmap.getPixel(80, 28)
            assertTrue("Expected bright lamp core preserved", Color.red(lampCenter) >= 225)
            // Nearby dark region around lamp shouldn't bloom uncontrollably
            val nearDark = result.bitmap.getPixel(40, 28)
            assertTrue("Expected dark background near lamp remains dark/balanced", Color.red(nearDark) < 160)
        } finally {
            frames.forEach { if (!it.isRecycled) it.recycle() }
            if (!result.bitmap.isRecycled) result.bitmap.recycle()
        }
    }

    @Test
    fun `night frame stacker aligns shifted burst frames accurately`() {
        val w = 320
        val h = 240

        fun renderPatternBitmap(shiftX: Int, shiftY: Int): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val sx = x - shiftX
                    val sy = y - shiftY
                    val inBox1 = sx in 70..130 && sy in 50..110
                    val inBox2 = sx in 180..240 && sy in 130..185
                    val lum = when {
                        inBox1 -> 210
                        inBox2 -> 170
                        else -> 22
                    }
                    pixels[y * w + x] = Color.rgb(lum, lum, (lum + 8).coerceAtMost(255))
                }
            }
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
            return bmp
        }

        val ref = renderPatternBitmap(0, 0)
        val shifted = renderPatternBitmap(6, -4)

        val result = NightFrameStacker.stackBitmaps(
            frames = listOf(ref, shifted),
            config = NightStackConfig(
                isMaxExposure = false,
                enableAlignment = true,
                enableDenoiseSharpen = false
            )
        )

        try {
            assertEquals(2, result.appliedShifts.size)
            val estimatedShift = result.appliedShifts[1]
            // Shift convention: ref(x, y) matches candidate(x + dx, y + dy) => (~6, -4)
            assertTrue(
                "Expected non-zero alignment shift close to (6, -4), got $estimatedShift",
                abs(estimatedShift.first - 6) <= 2 && abs(estimatedShift.second - (-4)) <= 2
            )
            // Verify box edges stayed aligned without ghosting
            val insideBox = result.bitmap.getPixel(100, 80)
            val outsideBox = result.bitmap.getPixel(65, 80)
            assertTrue("Expected box interior bright", Color.red(insideBox) > 180)
            assertTrue("Expected outside box dark without ghost bleed", Color.red(outsideBox) < 60)
        } finally {
            ref.recycle()
            shifted.recycle()
            if (!result.bitmap.isRecycled) result.bitmap.recycle()
        }
    }

    @Test
    fun `night frame stacker decodes and stacks JPEG burst frames cleanly`() {
        val w = 128
        val h = 96
        val jpegFrames = List(4) { idx ->
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.rgb(28 + idx, 30 + idx, 42 + idx))
            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                bmp.recycle()
                out.toByteArray()
            }
        }

        val result = NightFrameStacker.stackJpegBurst(
            jpegFrames = jpegFrames,
            config = NightStackConfig(isMaxExposure = false, maxLongestEdgePx = 512)
        )

        try {
            assertFalse(result.bitmap.isRecycled)
            assertEquals(w, result.bitmap.width)
            assertEquals(h, result.bitmap.height)
            assertEquals(4, result.alignedFrameCount)
        } finally {
            if (!result.bitmap.isRecycled) result.bitmap.recycle()
        }
    }

    @Test
    fun `night exposure mode preference persists across repository instances`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val repo1 = CameraPreferencesRepository(ctx)
        assertEquals(NightExposureMode.AUTO, repo1.getNightExposureMode())

        repo1.saveNightExposureMode(NightExposureMode.MAX)

        val repo2 = CameraPreferencesRepository(ctx)
        assertEquals(NightExposureMode.MAX, repo2.getNightExposureMode())
    }

    @Test
    fun `view model handles night mode selection, engine switching, and duration estimation`() {
        val vm = createViewModel()
        vm.setCameraMode(CameraMode.NIGHT)
        assertEquals(CameraMode.NIGHT, vm.uiState.value.cameraMode)
        assertEquals(NightExposureMode.AUTO, vm.uiState.value.nightExposureMode)

        val autoDuration = vm.uiState.value.nightEstimatedDurationSeconds
        vm.setNightExposureMode(NightExposureMode.MAX)
        assertEquals(NightExposureMode.MAX, vm.uiState.value.nightExposureMode)
        assertTrue(vm.uiState.value.nightEstimatedDurationSeconds > autoDuration)

        // Switching to OEM extension updates engine + latency estimate
        vm.onNightBindingChanged(isExtensionActive = true, estimatedLatencyMs = 2400L)
        assertTrue(vm.uiState.value.isNightExtensionSupported)
        assertEquals(NightCaptureEngine.HARDWARE_EXTENSION, vm.uiState.value.nightCaptureEngine)
        assertTrue(vm.uiState.value.nightEstimatedDurationSeconds >= 2.0f)

        // Falling back to Multi-Frame Stack updates engine + responds to low-light telemetry
        vm.onNightBindingChanged(isExtensionActive = false, estimatedLatencyMs = null)
        assertFalse(vm.uiState.value.isNightExtensionSupported)
        assertEquals(NightCaptureEngine.MULTI_FRAME_STACK, vm.uiState.value.nightCaptureEngine)

        vm.onLowLightConditionChanged(true)
        vm.onExposureTelemetry(exposureNs = 80_000_000L, iso = 2400)
        assertTrue(vm.uiState.value.nightEstimatedDurationSeconds >= 4.0f)

        // Switching away from Night Mode clears hardware extension state
        vm.setCameraMode(CameraMode.PHOTO)
        assertEquals(CameraMode.PHOTO, vm.uiState.value.cameraMode)
        assertFalse(vm.uiState.value.isNightExtensionSupported)
    }

    @Test
    fun `night mode tab, top status pill, hold steady indicator, and shutter progress ring render properly`() {
        var state by mutableStateOf(
            CameraUiState(
                cameraMode = CameraMode.PHOTO,
                isCameraReady = true,
                nightExposureMode = NightExposureMode.AUTO,
                nightEstimatedDurationSeconds = 2.0f,
                photoResolution = PhotoResolution.ULTRA_RES,
                uhrMegapixels = 50.0,
                isHdrEnabled = true,
                isRawSupportedOnCurrentLens = true,
                rawCaptureMode = RawCaptureMode.JPEG_AND_DNG
            )
        )

        composeTestRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                CameraTopBar(
                    uiState = state,
                    onCycleFlash = {},
                    onOpenSettings = {},
                    onCycleAspectRatio = {},
                    onToggleQuickSettings = {},
                    onToggleFilterSelector = {},
                    modifier = Modifier.align(Alignment.TopCenter)
                )
                if (state.cameraMode == CameraMode.NIGHT) {
                    NightControlsBar(
                        uiState = state,
                        onSelectExposureMode = { mode ->
                            state = state.copy(
                                nightExposureMode = mode,
                                nightEstimatedDurationSeconds = mode.baseDurationSeconds
                            )
                        },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 64.dp)
                    )
                }
                CameraBottomBar(
                    uiState = state,
                    onCaptureClick = {},
                    onModeSelect = { mode -> state = state.copy(cameraMode = mode) },
                    onToggleLens = {},
                    onZoomChange = {},
                    onThumbnailClick = {},
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        // All 5 mode tabs fit on 360dp screen; tapping NIGHT selects Night Mode
        composeTestRule.onNodeWithTag("mode_night_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertEquals(CameraMode.NIGHT, state.cameraMode)

        // Night status pill appears with "~2s", "4F", and Auto/Max toggle
        composeTestRule.onNodeWithTag("night_status_pill").assertIsDisplayed()
        composeTestRule.onNodeWithTag("night_duration_badge").assertIsDisplayed()
        composeTestRule.onNodeWithText("~2s").assertIsDisplayed()
        composeTestRule.onNodeWithText("4F").assertIsDisplayed()

        // UHR / RAW+ / HDR top-bar badges are hidden in Night Mode
        composeTestRule.onNodeWithTag("raw_format_toggle_button").assertDoesNotExist()
        composeTestRule.onNodeWithText("UHR 50MP").assertDoesNotExist()
        composeTestRule.onNodeWithText("HDR").assertDoesNotExist()

        // Tapping Max updates duration & frame count badge
        composeTestRule.onNodeWithTag("night_mode_max_chip").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertEquals(NightExposureMode.MAX, state.nightExposureMode)
        composeTestRule.onNodeWithText("~3.5s").assertIsDisplayed()
        composeTestRule.onNodeWithText("6F").assertIsDisplayed()

        // Active capture reveals Hold steady pill + glowing shutter progress ring
        state = state.copy(
            isCapturing = true,
            isNightCapturing = true,
            nightCaptureProgress = 0.45f,
            nightCaptureStatusText = "Hold steady…"
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("night_hold_steady_pill").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hold steady…").assertIsDisplayed()
        composeTestRule.onNodeWithTag("night_shutter_progress_ring").assertIsDisplayed()
    }
}
