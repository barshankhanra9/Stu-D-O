package com.example

import android.util.Size
import android.util.SizeF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.MultiCameraLensManager
import com.example.data.RearCameraHardwareDescriptor
import com.example.data.RearPhysicalSubDescriptor
import com.example.model.CameraLensOption
import com.example.model.CameraMode
import com.example.model.CameraUiState
import com.example.model.HardwareLensType
import com.example.model.ProCameraCapabilities
import com.example.model.ProCameraSettings
import com.example.model.ProControlTab
import com.example.model.ProWhitebalanceMode
import com.example.ui.components.CurvedZoomControl
import com.example.ui.components.ProControlsBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h740dp-xhdpi")
class MultiCameraZoomSwitchTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `discovers logical multi-camera ultra-wide main crop-2x and optical-5x lenses`() {
        val logicalDescriptor = RearCameraHardwareDescriptor(
            cameraId = "0",
            isLogicalMultiCamera = true,
            focalLengthMm = 6.81f,
            sensorPhysicalSizeMm = SizeF(8.16f, 6.12f),
            pixelArraySize = Size(4080, 3072),
            minimumFocusDistance = 10.0f,
            minZoomRatio = 0.55f,
            maxZoomRatio = 30.0f,
            physicalSubCameras = listOf(
                RearPhysicalSubDescriptor(
                    physicalId = "2",
                    focalLengthMm = 2.0f,
                    sensorPhysicalSizeMm = SizeF(4.8f, 3.6f),
                    pixelArraySize = Size(4000, 3000),
                    minimumFocusDistance = 0f // fixed-focus ultra-wide
                ),
                RearPhysicalSubDescriptor(
                    physicalId = "3",
                    focalLengthMm = 18.0f,
                    sensorPhysicalSizeMm = SizeF(4.5f, 3.37f),
                    pixelArraySize = Size(4000, 3000),
                    minimumFocusDistance = 4.0f
                )
            )
        )

        val result = MultiCameraLensManager.discoverRearLenses(
            descriptors = listOf(logicalDescriptor),
            primaryCameraId = "0"
        )

        val labels = result.lenses.map { it.label }
        assertEquals(listOf("0.6", "1", "2", "5"), labels)
        assertTrue(result.lenses.none { it.isSeparatePhysicalCamera })
        assertEquals(0.55f, result.overallMinZoom, 0.01f)
        assertEquals(30.0f, result.overallMaxZoom, 0.01f)
        assertFalse(result.lenses.first { it.label == "0.6" }.isManualFocusSupported)
    }

    @Test
    fun `discovers separate physical rear cameras including ultra-wide tele and macro`() {
        val mainCam = RearCameraHardwareDescriptor(
            cameraId = "0",
            isLogicalMultiCamera = false,
            focalLengthMm = 5.4f,
            sensorPhysicalSizeMm = SizeF(6.4f, 4.8f),
            pixelArraySize = Size(4000, 3000),
            minimumFocusDistance = 10.0f,
            minZoomRatio = 1.0f,
            maxZoomRatio = 8.0f
        )
        val uwCam = RearCameraHardwareDescriptor(
            cameraId = "2",
            isLogicalMultiCamera = false,
            focalLengthMm = 1.8f,
            sensorPhysicalSizeMm = SizeF(4.0f, 3.0f),
            pixelArraySize = Size(3264, 2448),
            minimumFocusDistance = 0f,
            minZoomRatio = 1.0f,
            maxZoomRatio = 2.0f
        )
        val teleCam = RearCameraHardwareDescriptor(
            cameraId = "3",
            isLogicalMultiCamera = false,
            focalLengthMm = 11.2f,
            sensorPhysicalSizeMm = SizeF(4.4f, 3.3f),
            pixelArraySize = Size(4000, 3000),
            minimumFocusDistance = 5.0f,
            minZoomRatio = 1.0f,
            maxZoomRatio = 5.0f
        )
        val macroCam = RearCameraHardwareDescriptor(
            cameraId = "4",
            isLogicalMultiCamera = false,
            focalLengthMm = 1.8f,
            sensorPhysicalSizeMm = SizeF(2.24f, 1.68f),
            pixelArraySize = Size(1600, 1200),
            minimumFocusDistance = 25.0f, // 4 cm close focus
            minZoomRatio = 1.0f,
            maxZoomRatio = 2.0f
        )

        val result = MultiCameraLensManager.discoverRearLenses(
            descriptors = listOf(mainCam, uwCam, teleCam, macroCam),
            primaryCameraId = "0"
        )

        val labels = result.lenses.map { it.label }
        assertEquals(listOf("0.5", "1", "2", "3", "Macro"), labels)

        val uw = result.lenses.first { it.lensType == HardwareLensType.ULTRA_WIDE }
        assertEquals("2", uw.cameraId)
        assertTrue(uw.isSeparatePhysicalCamera)
        assertFalse(uw.isManualFocusSupported)

        val tele = result.lenses.first { it.label == "3" }
        assertEquals("3", tele.cameraId)
        assertTrue(tele.isSeparatePhysicalCamera)
        assertEquals(15.0f, tele.maxDisplayZoom, 0.1f)

        val macro = result.lenses.first { it.lensType == HardwareLensType.MACRO }
        assertEquals("4", macro.cameraId)
        assertTrue(macro.isSeparatePhysicalCamera)
        assertTrue(macro.isManualFocusSupported)
    }

    @Test
    fun `hybrid zoom routing switches seamlessly across physical lenses and respects video recording lock`() {
        val uw = CameraLensOption(
            id = "lens_uw_2",
            label = "0.6",
            lensType = HardwareLensType.ULTRA_WIDE,
            cameraId = "2",
            displayMagnification = 0.6f,
            hardwareMinZoom = 1.0f,
            hardwareMaxZoom = 2.0f,
            baseHardwareScale = 0.6f,
            minDisplayZoom = 0.6f,
            maxDisplayZoom = 1.2f,
            isSeparatePhysicalCamera = true,
            isManualFocusSupported = false
        )
        val main = CameraLensOption(
            id = "lens_main_0",
            label = "1",
            lensType = HardwareLensType.MAIN,
            cameraId = "0",
            displayMagnification = 1.0f,
            hardwareMinZoom = 1.0f,
            hardwareMaxZoom = 8.0f,
            baseHardwareScale = 1.0f,
            minDisplayZoom = 1.0f,
            maxDisplayZoom = 8.0f,
            isSeparatePhysicalCamera = false
        )
        val crop2x = main.copy(
            id = "lens_crop_2x_0",
            label = "2",
            lensType = HardwareLensType.TELEPHOTO,
            displayMagnification = 2.0f,
            isCropPreset = true
        )
        val tele3x = CameraLensOption(
            id = "lens_tele_3",
            label = "3",
            lensType = HardwareLensType.TELEPHOTO,
            cameraId = "3",
            displayMagnification = 3.0f,
            hardwareMinZoom = 1.0f,
            hardwareMaxZoom = 5.0f,
            baseHardwareScale = 3.0f,
            minDisplayZoom = 3.0f,
            maxDisplayZoom = 15.0f,
            isSeparatePhysicalCamera = true
        )
        val lenses = listOf(uw, main, crop2x, tele3x)

        // 1. Pinch/drag from Main 1x down to 0.6x -> rebinds to UW physical camera at hwZoom = 1.0f
        val toUw = MultiCameraLensManager.resolveZoomTarget(lenses, main, 0.6f, isRecordingVideo = false)
        assertTrue(toUw.requiresCameraRebind)
        assertEquals("2", toUw.targetLens.cameraId)
        assertEquals(1.0f, toUw.hardwareZoomRatio, 0.01f)

        // 2. Scrubbing to 2.2x on Main -> no rebind, highlights 2x crop badge, hwZoom = 2.2f
        val to2x = MultiCameraLensManager.resolveZoomTarget(lenses, main, 2.2f, isRecordingVideo = false)
        assertFalse(to2x.requiresCameraRebind)
        assertEquals("lens_crop_2x_0", to2x.targetLens.id)
        assertEquals(2.2f, to2x.hardwareZoomRatio, 0.01f)

        // 3. Crossing 3.0x threshold -> rebinds to Telephoto physical camera at hwZoom = 1.0f
        val toTele = MultiCameraLensManager.resolveZoomTarget(lenses, main, 3.0f, isRecordingVideo = false)
        assertTrue(toTele.requiresCameraRebind)
        assertEquals("3", toTele.targetLens.cameraId)
        assertEquals(1.0f, toTele.hardwareZoomRatio, 0.01f)

        // 4. Zooming further on Telephoto to 6.0x -> stays on camera 3 at hwZoom = 2.0f
        val zoomOnTele = MultiCameraLensManager.resolveZoomTarget(lenses, tele3x, 6.0f, isRecordingVideo = false)
        assertFalse(zoomOnTele.requiresCameraRebind)
        assertEquals("3", zoomOnTele.targetLens.cameraId)
        assertEquals(2.0f, zoomOnTele.hardwareZoomRatio, 0.01f)

        // 5. While recording video on Main, crossing 3.0f must NOT trigger camera rebind
        val lockedWhileRecording = MultiCameraLensManager.resolveZoomTarget(lenses, main, 4.0f, isRecordingVideo = true)
        assertFalse(lockedWhileRecording.requiresCameraRebind)
        assertEquals("0", lockedWhileRecording.targetLens.cameraId)
        assertEquals(4.0f, lockedWhileRecording.hardwareZoomRatio, 0.01f)
    }

    @Test
    fun `pro settings reconcile clamps iso shutter and disables manual focus on fixed focus lens`() {
        val currentSettings = ProCameraSettings(
            iso = 6400,
            shutterSpeedNanos = 2_000_000_000L,
            evCompensation = 6,
            focusDistance = 5.0f,
            whiteBalance = ProWhitebalanceMode.SHADE,
            isManualFocus = true
        )
        val uwFixedCaps = ProCameraCapabilities(
            isProSupported = true,
            minIso = 100,
            maxIso = 1600,
            supportedIsos = listOf(100, 200, 400, 800, 1600),
            minShutterNanos = 100_000L,
            maxShutterNanos = 500_000_000L,
            minFocusDistance = 0f,
            maxFocusDistance = 0f,
            isManualFocusSupported = false,
            minEv = -4,
            maxEv = 4,
            evStep = 0.5f,
            supportedAwbModes = listOf(ProWhitebalanceMode.AUTO, ProWhitebalanceMode.DAYLIGHT)
        )

        val reconciled = MultiCameraLensManager.reconcileProSettings(currentSettings, uwFixedCaps)
        assertFalse(reconciled.isManualFocus)
        assertNull(reconciled.focusDistance)
        assertEquals(1600, reconciled.iso)
        assertEquals(500_000_000L, reconciled.shutterSpeedNanos)
        assertEquals(4, reconciled.evCompensation)
        assertEquals(ProWhitebalanceMode.AUTO, reconciled.whiteBalance)
    }

    @Test
    fun `curved zoom control renders hardware lens pills including macro and triggers selection`() {
        val lenses = listOf(
            CameraLensOption("uw", "0.6", HardwareLensType.ULTRA_WIDE, "2", 0.6f),
            CameraLensOption("main", "1", HardwareLensType.MAIN, "0", 1.0f),
            CameraLensOption("tele", "3", HardwareLensType.TELEPHOTO, "3", 3.0f),
            CameraLensOption("macro", "Macro", HardwareLensType.MACRO, "4", 1.0f)
        )
        var selectedLens by mutableStateOf(lenses[1])
        var zoom by mutableFloatStateOf(1.0f)

        composeTestRule.setContent {
            CurvedZoomControl(
                zoomRatio = zoom,
                minZoomRatio = 0.6f,
                maxZoomRatio = 15.0f,
                hapticsEnabled = false,
                onZoomChange = { zoom = it },
                lenses = lenses,
                activeLensId = selectedLens.id,
                activeLensType = selectedLens.lensType,
                onLensSelected = { lens ->
                    selectedLens = lens
                    zoom = lens.displayMagnification
                }
            )
        }

        composeTestRule.onNodeWithTag("zoom_preset_0.6").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_preset_1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_preset_3").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_preset_Macro").assertIsDisplayed()

        composeTestRule.onNodeWithTag("zoom_preset_Macro").performClick()
        composeTestRule.waitForIdle()
        assertEquals("macro", selectedLens.id)
        composeTestRule.onNodeWithTag("zoom_live_readout").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Zoom Macro").assertIsDisplayed()
    }

    @Test
    fun `pro controls bar disables manual focus pill and shows fixed focus hint on fixed focus lens`() {
        val state = CameraUiState(
            cameraMode = CameraMode.PRO,
            activeProTab = ProControlTab.FOCUS,
            proSettings = ProCameraSettings(isManualFocus = false),
            proCapabilities = ProCameraCapabilities(
                isManualFocusSupported = false,
                maxFocusDistance = 0f
            )
        )

        composeTestRule.setContent {
            ProControlsBar(
                uiState = state,
                onTabSelected = {},
                onIsoChanged = {},
                onShutterChanged = {},
                onEvChanged = {},
                onFocusChanged = { _, _ -> },
                onWhiteBalanceChanged = {},
                onResetAll = {}
            )
        }

        composeTestRule.onNodeWithText("FIXED").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pro_focus_af_pill").assertIsEnabled()
        composeTestRule.onNodeWithTag("pro_focus_mf_pill").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("pro_focus_fixed_hint").assertIsDisplayed()
    }
}
