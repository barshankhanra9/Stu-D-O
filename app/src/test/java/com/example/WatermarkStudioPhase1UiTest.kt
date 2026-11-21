package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.example.model.PostCaptureWatermarkTarget
import com.example.model.WatermarkConfig
import com.example.model.WatermarkStyle
import com.example.ui.components.WatermarkPreviewCache
import com.example.ui.components.WatermarkStudioScreen
import com.example.util.WatermarkPainter
import org.junit.Assert.assertEquals
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
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h840dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatermarkStudioPhase1UiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        WatermarkPreviewCache.clear()
        WatermarkPainter.clearSampleCache()
    }

    private fun createTestJpeg(width: Int, height: Int): File {
        val file = File(app.cacheDir, "studio_phase1_${width}x${height}_${System.nanoTime()}.jpg")
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(24, 96, 178))
        }
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        bmp.recycle()
        return file
    }

    @Test
    fun `top floating toolbar has Close Undo Save without sliders icon and actions stay connected`() {
        var dismissed = false
        var savedConfig: WatermarkConfig? = null

        composeTestRule.setContent {
            WatermarkStudioScreen(
                initialConfig = WatermarkConfig(isEnabled = true, style = WatermarkStyle.LEICA_FRAME),
                savedPresets = emptyList(),
                onSaveConfig = { savedConfig = it },
                onSavePreset = { _, _ -> },
                onDeletePreset = {},
                onDismiss = { dismissed = true }
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_top_toolbar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_close_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_undo_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_save_button").assertIsDisplayed()

        // Redundant sliders icon / reset button must not exist
        composeTestRule.onNodeWithTag("studio_reset_button").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Reset to Default").assertDoesNotExist()

        // Edit a template, then Undo restores previous config, then Save emits config
        composeTestRule.onNodeWithTag("studio_template_glass_frame").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_undo_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_save_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(WatermarkStyle.LEICA_FRAME, savedConfig?.style)

        composeTestRule.onNodeWithTag("studio_close_button").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun `all five editing sections open corresponding panels and support scrolling`() {
        var savedConfig: WatermarkConfig? = null

        composeTestRule.setContent {
            WatermarkStudioScreen(
                initialConfig = WatermarkConfig(isEnabled = true, style = WatermarkStyle.LEICA_FRAME),
                savedPresets = emptyList(),
                onSaveConfig = { savedConfig = it },
                onSavePreset = { _, _ -> },
                onDeletePreset = {},
                onDismiss = {}
            )
        }

        composeTestRule.waitForIdle()

        // 1. Templates
        composeTestRule.onNodeWithTag("studio_tab_templates").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("studio_templates_lazy_row").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_save_preset_button").assertIsDisplayed()

        // 2. Text
        composeTestRule.onNodeWithTag("studio_tab_text").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("studio_text_primary_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_text_primary_input").performTextReplacement("Custom Studio Title")
        composeTestRule.onNodeWithTag("studio_panel_scroll_container")
            .performScrollToNode(hasTestTag("studio_slider_letter_spacing"))
        composeTestRule.onNodeWithTag("studio_slider_letter_spacing").assertIsDisplayed()

        // 3. Logo
        composeTestRule.onNodeWithTag("studio_tab_logo").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("studio_logo_app_icon").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_panel_scroll_container")
            .performScrollToNode(hasTestTag("studio_slider_logo_spacing"))
        composeTestRule.onNodeWithTag("studio_slider_logo_spacing").assertIsDisplayed()

        // 4. Style & Position (LEICA_FRAME uses template-anchored frame layout and hides floating positions)
        composeTestRule.onNodeWithTag("studio_tab_appearance").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Style & Position").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_fixed_layout_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_pos_bottom_right").assertDoesNotExist()
        composeTestRule.onNodeWithTag("studio_panel_scroll_container")
            .performScrollToNode(hasTestTag("studio_slider_padding"))
        composeTestRule.onNodeWithTag("studio_slider_padding").assertIsDisplayed()

        // 5. Info
        composeTestRule.onNodeWithTag("studio_tab_metadata").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("studio_toggle_meta_date").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_panel_scroll_container")
            .performScrollToNode(hasTestTag("studio_toggle_meta_resolution"))
        composeTestRule.onNodeWithTag("studio_toggle_meta_resolution").assertIsDisplayed()

        composeTestRule.onNodeWithTag("studio_save_button").performClick()
        assertEquals("Custom Studio Title", savedConfig?.customText)
    }

    @Test
    fun `sliding bottom panel supports expanded partial and closed states while keeping preview visible`() {
        composeTestRule.setContent {
            WatermarkStudioScreen(
                initialConfig = WatermarkConfig(isEnabled = true),
                savedPresets = emptyList(),
                onSaveConfig = {},
                onSavePreset = { _, _ -> },
                onDeletePreset = {},
                onDismiss = {}
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 15000L) {
            try {
                composeTestRule.onNodeWithTag("studio_photo_preview_frame").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }

        // Initial state: Partial
        val partialPanelBounds = composeTestRule.onNodeWithTag("studio_bottom_panel").fetchSemanticsNode().boundsInRoot
        val partialPreviewBounds = composeTestRule.onNodeWithTag("studio_photo_preview_stage").fetchSemanticsNode().boundsInRoot
        composeTestRule.onNodeWithTag("studio_panel_scroll_container").assertIsDisplayed()
        assertTrue(
            "Preview $partialPreviewBounds must sit above partial panel $partialPanelBounds",
            partialPreviewBounds.bottom <= partialPanelBounds.top + 2f
        )

        // Tap drag handle -> transitions to Expanded
        composeTestRule.onNodeWithTag("studio_panel_drag_handle").performClick()
        composeTestRule.waitForIdle()
        val expandedPanelBounds = composeTestRule.onNodeWithTag("studio_bottom_panel").fetchSemanticsNode().boundsInRoot
        val expandedPreviewBounds = composeTestRule.onNodeWithTag("studio_photo_preview_stage").fetchSemanticsNode().boundsInRoot
        assertTrue("Expanded panel must be taller than partial", expandedPanelBounds.height > partialPanelBounds.height)
        assertTrue("Preview must remain visible above expanded panel", expandedPreviewBounds.height > 80f)
        assertTrue("Preview must not overlap behind expanded panel", expandedPreviewBounds.bottom <= expandedPanelBounds.top + 2f)

        // Drag down via drag handle -> closes or partially collapses
        composeTestRule.onNodeWithTag("studio_panel_drag_handle").performTouchInput {
            down(center)
            moveBy(Offset(0f, 900f))
            up()
        }
        composeTestRule.waitForIdle()
        val afterDragBounds = composeTestRule.onNodeWithTag("studio_bottom_panel").fetchSemanticsNode().boundsInRoot
        assertTrue("Dragging down must reduce panel height", afterDragBounds.height < expandedPanelBounds.height)

        // Ensure Closed state via collapse toggle if currently Partial
        if (afterDragBounds.height > 260f) {
            composeTestRule.onNodeWithTag("studio_panel_state_toggle").performClick()
            composeTestRule.waitForIdle()
        }
        composeTestRule.onNodeWithTag("studio_panel_scroll_container").assertDoesNotExist()
        val closedPreviewBounds = composeTestRule.onNodeWithTag("studio_photo_preview_stage").fetchSemanticsNode().boundsInRoot
        assertTrue("Closed state must maximize preview area", closedPreviewBounds.height > partialPreviewBounds.height)

        // Selecting a section while Closed opens its panel to Partial
        composeTestRule.onNodeWithTag("studio_tab_text").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_panel_scroll_container").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_text_primary_input").assertIsDisplayed()
    }

    @Test
    fun `photo preview preserves original portrait aspect ratio without stretching or cropping`() {
        val portraitFile = createTestJpeg(width = 120, height = 240)
        val target = PostCaptureWatermarkTarget(
            galleryUri = Uri.fromFile(portraitFile),
            originalId = "portrait_orig",
            originalFilePath = portraitFile.absolutePath,
            width = 120,
            height = 240,
            associatedConfig = WatermarkConfig(isEnabled = false)
        )

        composeTestRule.setContent {
            WatermarkStudioScreen(
                initialConfig = WatermarkConfig(isEnabled = false),
                savedPresets = emptyList(),
                onSaveConfig = {},
                onSavePreset = { _, _ -> },
                onDeletePreset = {},
                onDismiss = {},
                postCaptureTarget = target
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5000L) {
            try {
                composeTestRule.onNodeWithTag("studio_photo_preview_frame").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }

        val frameBounds = composeTestRule.onNodeWithTag("studio_photo_preview_frame").fetchSemanticsNode().boundsInRoot
        val renderedAspect = frameBounds.width / frameBounds.height
        val expectedAspect = 120f / 240f
        assertTrue(
            "Expected portrait aspect ratio ~0.5, got $renderedAspect (${frameBounds.width}x${frameBounds.height})",
            abs(renderedAspect - expectedAspect) < 0.04f
        )

        portraitFile.delete()
    }

    @Test
    @Config(sdk = [36], qualifiers = "w840dp-h540dp-xhdpi")
    fun `tablet landscape layout renders toolbar preview and bottom panel cleanly`() {
        composeTestRule.setContent {
            WatermarkStudioScreen(
                initialConfig = WatermarkConfig(isEnabled = true),
                savedPresets = emptyList(),
                onSaveConfig = {},
                onSavePreset = { _, _ -> },
                onDeletePreset = {},
                onDismiss = {}
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5000L) {
            try {
                composeTestRule.onNodeWithTag("studio_photo_preview_frame").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }

        composeTestRule.onNodeWithTag("studio_top_toolbar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_bottom_panel").assertIsDisplayed()
        val previewBounds = composeTestRule.onNodeWithTag("studio_photo_preview_stage").fetchSemanticsNode().boundsInRoot
        val panelBounds = composeTestRule.onNodeWithTag("studio_bottom_panel").fetchSemanticsNode().boundsInRoot
        assertTrue("Preview must remain clearly visible on tablet landscape", previewBounds.height > 100f)
        assertTrue("Panel must fit cleanly inside tablet landscape viewport", panelBounds.width > 0f && panelBounds.height > 0f)
    }

    @Test
    fun `switching sections and panel states keeps rendered preview stable without regenerating`() {
        composeTestRule.setContent {
            WatermarkStudioScreen(
                initialConfig = WatermarkConfig(isEnabled = true, style = WatermarkStyle.LEICA_FRAME),
                savedPresets = emptyList(),
                onSaveConfig = {},
                onSavePreset = { _, _ -> },
                onDeletePreset = {},
                onDismiss = {}
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 15000L) {
            try {
                composeTestRule.onNodeWithTag("studio_photo_preview_frame").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }

        val sections = listOf(
            "studio_tab_text",
            "studio_tab_logo",
            "studio_tab_appearance",
            "studio_tab_metadata",
            "studio_tab_templates"
        )

        sections.forEach { tabTag ->
            composeTestRule.onNodeWithTag(tabTag).performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Rendering Studio Preview...").assertDoesNotExist()
            composeTestRule.onNodeWithTag("studio_photo_preview_image").assertIsDisplayed()
        }

        // Expand -> Partial -> Close -> Reopen via section tap
        composeTestRule.onNodeWithTag("studio_panel_drag_handle").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Rendering Studio Preview...").assertDoesNotExist()
        composeTestRule.onNodeWithTag("studio_photo_preview_image").assertIsDisplayed()

        composeTestRule.onNodeWithTag("studio_panel_state_toggle").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_panel_state_toggle").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Rendering Studio Preview...").assertDoesNotExist()
        composeTestRule.onNodeWithTag("studio_photo_preview_image").assertIsDisplayed()

        composeTestRule.onNodeWithTag("studio_tab_text").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Rendering Studio Preview...").assertDoesNotExist()
        composeTestRule.onNodeWithTag("studio_photo_preview_image").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_undo_button").assertIsNotEnabled()
    }
}
