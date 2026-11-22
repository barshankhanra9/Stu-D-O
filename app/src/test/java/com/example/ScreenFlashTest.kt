package com.example

import android.app.Application
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.data.CameraPreferencesRepository
import com.example.model.CameraMode
import com.example.model.CameraUiState
import com.example.model.CaptureAspectRatio
import com.example.model.CaptureTimer
import com.example.model.FlashMode
import com.example.model.PhotoResolution
import com.example.model.RawCaptureMode
import com.example.model.ScreenFlashMode
import com.example.ui.CameraScreen
import com.example.ui.components.CameraTopBar
import com.example.viewmodel.CameraViewModel
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenFlashTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        CameraPreferencesRepository(app).saveScreenFlashMode(ScreenFlashMode.OFF)
    }

    @Test
    fun `preferences repository persists ScreenFlashMode and ViewModel restores it`() {
        val repo = CameraPreferencesRepository(app)
        assertEquals(ScreenFlashMode.OFF, repo.getScreenFlashMode())

        repo.saveScreenFlashMode(ScreenFlashMode.AUTO)
        assertEquals(ScreenFlashMode.AUTO, CameraPreferencesRepository(app).getScreenFlashMode())

        repo.saveScreenFlashMode(ScreenFlashMode.ON)
        val restoredRepo = CameraPreferencesRepository(app)
        assertEquals(ScreenFlashMode.ON, restoredRepo.getScreenFlashMode())

        val vm = CameraViewModel(app)
        assertEquals(ScreenFlashMode.ON, vm.uiState.value.screenFlashMode)
    }

    @Test
    fun `applyBoundFlashCapabilities enables screen flash only on flashless front camera`() {
        val vm = CameraViewModel(app)

        vm.applyBoundFlashCapabilities(isFrontLens = false, hasHardwareFlash = true)
        assertTrue(vm.uiState.value.isFlashAvailable)
        assertFalse(vm.uiState.value.isScreenFlashAvailable)

        vm.applyBoundFlashCapabilities(isFrontLens = false, hasHardwareFlash = false)
        assertFalse(vm.uiState.value.isFlashAvailable)
        assertFalse(vm.uiState.value.isScreenFlashAvailable)

        vm.applyBoundFlashCapabilities(isFrontLens = true, hasHardwareFlash = true)
        assertTrue(vm.uiState.value.isFlashAvailable)
        assertFalse(vm.uiState.value.isScreenFlashAvailable)

        vm.applyBoundFlashCapabilities(isFrontLens = true, hasHardwareFlash = false)
        assertFalse(vm.uiState.value.isFlashAvailable)
        assertTrue(vm.uiState.value.isScreenFlashAvailable)
    }

    @Test
    fun `cycleFlashMode cycles ScreenFlashMode OFF to AUTO to ON to OFF on flashless front lens and blocks VIDEO`() {
        val vm = CameraViewModel(app)
        vm.applyBoundFlashCapabilities(isFrontLens = true, hasHardwareFlash = false)

        assertEquals(ScreenFlashMode.OFF, vm.uiState.value.screenFlashMode)
        vm.cycleFlashMode()
        assertEquals(ScreenFlashMode.AUTO, vm.uiState.value.screenFlashMode)
        vm.cycleFlashMode()
        assertEquals(ScreenFlashMode.ON, vm.uiState.value.screenFlashMode)
        vm.cycleFlashMode()
        assertEquals(ScreenFlashMode.OFF, vm.uiState.value.screenFlashMode)

        // Switching to VIDEO suppresses screen flash cycling
        vm.setCameraMode(CameraMode.VIDEO)
        vm.cycleFlashMode()
        assertEquals(ScreenFlashMode.OFF, vm.uiState.value.screenFlashMode)
        assertEquals("Flash unavailable on this sensor", vm.uiState.value.userMessage)
    }

    @Test
    fun `shouldUseScreenFlashForCapture and resolveEffectiveFlashMode cover OFF, AUTO, ON, hardware flash, and VIDEO`() {
        val vm = CameraViewModel(app)
        val frontBase = CameraUiState(
            cameraMode = CameraMode.PHOTO,
            lensFacing = CameraSelector.LENS_FACING_FRONT,
            isFlashAvailable = false,
            isScreenFlashAvailable = true
        )

        // OFF
        assertFalse(vm.shouldUseScreenFlashForCapture(frontBase.copy(screenFlashMode = ScreenFlashMode.OFF, isScreenFlashLowLightDetected = true)))
        assertEquals(
            ImageCapture.FLASH_MODE_OFF,
            vm.resolveEffectiveFlashMode(frontBase.copy(screenFlashMode = ScreenFlashMode.OFF, isScreenFlashLowLightDetected = true))
        )

        // ON
        assertTrue(vm.shouldUseScreenFlashForCapture(frontBase.copy(screenFlashMode = ScreenFlashMode.ON, isScreenFlashLowLightDetected = false)))
        assertEquals(
            ImageCapture.FLASH_MODE_SCREEN,
            vm.resolveEffectiveFlashMode(frontBase.copy(screenFlashMode = ScreenFlashMode.ON, isScreenFlashLowLightDetected = false))
        )

        // AUTO + low-light false vs true
        assertFalse(vm.shouldUseScreenFlashForCapture(frontBase.copy(screenFlashMode = ScreenFlashMode.AUTO, isScreenFlashLowLightDetected = false)))
        assertEquals(
            ImageCapture.FLASH_MODE_OFF,
            vm.resolveEffectiveFlashMode(frontBase.copy(screenFlashMode = ScreenFlashMode.AUTO, isScreenFlashLowLightDetected = false))
        )
        assertTrue(vm.shouldUseScreenFlashForCapture(frontBase.copy(screenFlashMode = ScreenFlashMode.AUTO, isScreenFlashLowLightDetected = true)))
        assertEquals(
            ImageCapture.FLASH_MODE_SCREEN,
            vm.resolveEffectiveFlashMode(frontBase.copy(screenFlashMode = ScreenFlashMode.AUTO, isScreenFlashLowLightDetected = true))
        )

        // Hardware flash takes precedence
        val hwFlash = frontBase.copy(isFlashAvailable = true, flashMode = FlashMode.AUTO, screenFlashMode = ScreenFlashMode.ON)
        assertFalse(vm.shouldUseScreenFlashForCapture(hwFlash))
        assertEquals(ImageCapture.FLASH_MODE_AUTO, vm.resolveEffectiveFlashMode(hwFlash))

        // Video mode suppresses Screen Flash
        val videoFront = frontBase.copy(cameraMode = CameraMode.VIDEO, screenFlashMode = ScreenFlashMode.ON, isScreenFlashLowLightDetected = true)
        assertFalse(vm.shouldUseScreenFlashForCapture(videoFront))
        assertEquals(ImageCapture.FLASH_MODE_OFF, vm.resolveEffectiveFlashMode(videoFront))
    }

    @Test
    fun `screen flash overlay lifecycle activates on apply, completes listener on ready, and dismisses cleanly`() {
        val vm = CameraViewModel(app)
        vm.applyBoundFlashCapabilities(isFrontLens = true, hasHardwareFlash = false)
        vm.onLowLightConditionChanged(true)
        assertTrue(vm.uiState.value.isScreenFlashLowLightDetected)

        var completedCount = 0
        val listener = ImageCapture.ScreenFlashListener { completedCount++ }

        vm.onScreenFlashApplyRequested(System.currentTimeMillis() + 3000L, listener)
        assertTrue(vm.uiState.value.isScreenFlashOverlayVisible)
        assertEquals(0, completedCount)

        vm.onScreenFlashSurfaceReady()
        assertEquals(1, completedCount)

        vm.dismissScreenFlash()
        assertFalse(vm.uiState.value.isScreenFlashOverlayVisible)

        // Switching mode, toggling lens, opening settings, and hardware error all clear the overlay
        vm.onScreenFlashApplyRequested(System.currentTimeMillis() + 3000L) {}
        assertTrue(vm.uiState.value.isScreenFlashOverlayVisible)
        vm.setCameraMode(CameraMode.VIDEO)
        assertFalse(vm.uiState.value.isScreenFlashOverlayVisible)

        // Requesting in VIDEO mode completes immediately without showing overlay
        var videoCompleted = 0
        vm.onScreenFlashApplyRequested(System.currentTimeMillis() + 3000L) { videoCompleted++ }
        assertEquals(1, videoCompleted)
        assertFalse(vm.uiState.value.isScreenFlashOverlayVisible)

        vm.setCameraMode(CameraMode.PHOTO)
        vm.onScreenFlashApplyRequested(System.currentTimeMillis() + 3000L) {}
        assertTrue(vm.uiState.value.isScreenFlashOverlayVisible)
        vm.toggleSettings()
        assertFalse(vm.uiState.value.isScreenFlashOverlayVisible)
        vm.closeSettings()

        vm.onScreenFlashApplyRequested(System.currentTimeMillis() + 3000L) {}
        assertTrue(vm.uiState.value.isScreenFlashOverlayVisible)
        vm.onCameraHardwareError("Sensor interrupted", isFatal = false)
        assertFalse(vm.uiState.value.isScreenFlashOverlayVisible)

        vm.applyBoundFlashCapabilities(isFrontLens = true, hasHardwareFlash = false)
        vm.onScreenFlashApplyRequested(System.currentTimeMillis() + 3000L) {}
        assertTrue(vm.uiState.value.isScreenFlashOverlayVisible)
        vm.toggleLens()
        assertFalse(vm.uiState.value.isScreenFlashOverlayVisible)
    }

    @Test
    fun `CameraTopBar displays SCR badge for ScreenFlashMode without capsule overlap at 360dp`() {
        var state by mutableStateOf(
            CameraUiState(
                cameraMode = CameraMode.PRO,
                photoResolution = PhotoResolution.ULTRA_RES,
                uhrMegapixels = 50.0,
                rawCaptureMode = RawCaptureMode.JPEG_AND_DNG,
                isHdrEnabled = true,
                selectedTimer = CaptureTimer.CUSTOM,
                customTimerSeconds = 25,
                aspectRatio = CaptureAspectRatio.RATIO_4_3,
                lensFacing = CameraSelector.LENS_FACING_FRONT,
                isFlashAvailable = false,
                isScreenFlashAvailable = true,
                screenFlashMode = ScreenFlashMode.OFF
            )
        )
        var flashCycles = 0

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 360.dp, height = 780.dp)) {
                CameraTopBar(
                    uiState = state,
                    onCycleFlash = {
                        flashCycles++
                        val next = when (state.screenFlashMode) {
                            ScreenFlashMode.OFF -> ScreenFlashMode.AUTO
                            ScreenFlashMode.AUTO -> ScreenFlashMode.ON
                            ScreenFlashMode.ON -> ScreenFlashMode.OFF
                        }
                        state = state.copy(screenFlashMode = next)
                    },
                    onOpenSettings = {},
                    onCycleAspectRatio = {},
                    onToggleQuickSettings = {},
                    onToggleFilterSelector = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // OFF -> badge hidden, content description reflects OFF
        composeTestRule.onNodeWithTag("flash_toggle_button").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Screen Flash: Off").assertIsDisplayed()
        composeTestRule.onNodeWithTag("screen_flash_mode_badge", useUnmergedTree = true).assertDoesNotExist()

        // Tap -> AUTO with SCR badge visible
        composeTestRule.onNodeWithTag("flash_toggle_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, flashCycles)
        assertEquals(ScreenFlashMode.AUTO, state.screenFlashMode)
        composeTestRule.onNodeWithContentDescription("Screen Flash: Auto").assertIsDisplayed()
        composeTestRule.onNodeWithTag("screen_flash_mode_badge", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("SCR", useUnmergedTree = true).assertIsDisplayed()

        // Tap -> ON with SCR badge still visible and 360dp layout non-overlapping
        composeTestRule.onNodeWithTag("flash_toggle_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(2, flashCycles)
        assertEquals(ScreenFlashMode.ON, state.screenFlashMode)
        composeTestRule.onNodeWithContentDescription("Screen Flash: On").assertIsDisplayed()
        composeTestRule.onNodeWithTag("screen_flash_mode_badge", useUnmergedTree = true).assertIsDisplayed()

        val leftCapsule = composeTestRule.onNodeWithTag("top_left_brand_capsule").fetchSemanticsNode().boundsInRoot
        val rightCapsule = composeTestRule.onNodeWithTag("top_right_controls_capsule").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Left capsule (${leftCapsule.right}) must not overlap right capsule (${rightCapsule.left}) at 360dp",
            leftCapsule.right <= rightCapsule.left + 0.5f
        )

        val childTags = listOf(
            "top_filter_button",
            "top_tools_button",
            "aspect_ratio_button",
            "flash_toggle_button",
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
    fun `CameraScreen renders warm Screen Flash illumination overlay and completes ready signal`() {
        val vm = CameraViewModel(app)
        vm.onPermissionResult(isGranted = true)
        vm.applyBoundFlashCapabilities(isFrontLens = true, hasHardwareFlash = false)
        vm.setScreenFlashMode(ScreenFlashMode.ON)

        composeTestRule.setContent {
            CameraScreen(viewModel = vm)
        }
        composeTestRule.waitForIdle()

        assertTrue(
            composeTestRule.onAllNodesWithTag("screen_flash_illumination_overlay").fetchSemanticsNodes().isEmpty()
        )

        var readyCompleted = 0
        vm.onScreenFlashApplyRequested(System.currentTimeMillis() + 4000L) {
            readyCompleted++
        }

        composeTestRule.waitUntil(timeoutMillis = 3_000L) {
            readyCompleted == 1 &&
                composeTestRule.onAllNodesWithTag("screen_flash_illumination_overlay").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("screen_flash_illumination_overlay").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Screen Flash illumination active").assertIsDisplayed()

        vm.dismissScreenFlash()
        composeTestRule.waitUntil(timeoutMillis = 3_000L) {
            composeTestRule.onAllNodesWithTag("screen_flash_illumination_overlay").fetchSemanticsNodes().isEmpty()
        }
    }
}
