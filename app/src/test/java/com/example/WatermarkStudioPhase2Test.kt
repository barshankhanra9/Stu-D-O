package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.model.PostCaptureWatermarkTarget
import com.example.model.WatermarkConfig
import com.example.model.WatermarkFont
import com.example.model.WatermarkLogoType
import com.example.model.WatermarkPosition
import com.example.model.WatermarkPreset
import com.example.model.WatermarkStyle
import com.example.ui.components.WatermarkPreviewCache
import com.example.ui.components.WatermarkStudioScreen
import com.example.ui.components.WatermarkStudioStateHolder
import com.example.ui.components.WatermarkTemplateDefaults
import com.example.ui.components.capabilitiesForTemplate
import com.example.util.CameraMetadataUtil
import com.example.util.WatermarkPainter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
@Config(sdk = [36], qualifiers = "w400dp-h840dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatermarkStudioPhase2Test {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        WatermarkPreviewCache.clear()
        WatermarkPainter.clearSampleCache()
    }

    private fun createTestJpeg(width: Int = 240, height: Int = 180): File {
        val file = File(app.cacheDir, "studio_phase2_${width}x${height}_${System.nanoTime()}.jpg")
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(36, 112, 168))
        }
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        bmp.recycle()
        return file
    }

    @Test
    fun `all nine templates initialize with distinct independent configs and render cleanly`() {
        val initial = WatermarkTemplateDefaults.defaultConfig(WatermarkStyle.LEICA_FRAME)
        val holder = WatermarkStudioStateHolder(initial)

        val configsByStyle = WatermarkStyle.entries.associateWith { style -> holder.configFor(style) }
        val distinctTexts = configsByStyle.values.map { it.customText }.toSet()
        assertEquals(WatermarkStyle.entries.size, distinctTexts.size)

        val source = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.DKGRAY)
        }
        val metadata = CameraMetadataUtil.samplePreviewMetadata(320, 240, focalLength = "24mm")
        try {
            WatermarkStyle.entries.forEach { style ->
                val cfg = holder.configFor(style)
                assertEquals(style, cfg.style)
                val rendered = WatermarkPainter.applyWatermark(source, cfg, metadata, app)
                assertTrue("Rendered bitmap must be valid for $style", rendered.width >= 320 && rendered.height >= 240)
                if (rendered !== source && !rendered.isRecycled) {
                    rendered.recycle()
                }
            }
        } finally {
            if (!source.isRecycled) source.recycle()
        }
    }

    @Test
    fun `editing template A never contaminates template B and switching back restores template A edits`() {
        var savedConfig: WatermarkConfig? = null
        val initialLeica = WatermarkTemplateDefaults.defaultConfig(WatermarkStyle.LEICA_FRAME)

        composeTestRule.setContent {
            WatermarkStudioScreen(
                initialConfig = initialLeica,
                savedPresets = emptyList(),
                onSaveConfig = { savedConfig = it },
                onSavePreset = { _, _ -> },
                onDeletePreset = {},
                onDismiss = {}
            )
        }
        composeTestRule.waitForIdle()

        // Edit LEICA_FRAME text
        composeTestRule.onNodeWithTag("studio_tab_text").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_text_primary_input").performTextReplacement("Leica Session Alpha")
        composeTestRule.waitForIdle()

        // Edit LEICA_FRAME logo
        composeTestRule.onNodeWithTag("studio_tab_logo").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_logo_device_manufacturer").performClick()
        composeTestRule.waitForIdle()

        // Switch to HASSELBLAD — must show its own independent defaults & EDITED badge on Leica
        composeTestRule.onNodeWithTag("studio_tab_templates").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_templates_lazy_row")
            .performScrollToNode(hasTestTag("studio_template_leica_frame"))
        composeTestRule.onNodeWithTag("studio_template_edited_badge_leica_frame", useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_templates_lazy_row")
            .performScrollToNode(hasTestTag("studio_template_hasselblad"))
        composeTestRule.onNodeWithTag("studio_template_hasselblad").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("studio_tab_text").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("STU•D•O MASTER").assertIsDisplayed()
        composeTestRule.onNodeWithText("Leica Session Alpha").assertDoesNotExist()

        // Edit HASSELBLAD independently
        composeTestRule.onNodeWithTag("studio_text_primary_input").performTextReplacement("Hasselblad Custom")
        composeTestRule.waitForIdle()

        // Switch back to LEICA_FRAME — restores Leica's earlier edits
        composeTestRule.onNodeWithTag("studio_tab_templates").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_templates_lazy_row")
            .performScrollToNode(hasTestTag("studio_template_hasselblad"))
        composeTestRule.onNodeWithTag("studio_template_edited_badge_hasselblad", useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_templates_lazy_row")
            .performScrollToNode(hasTestTag("studio_template_leica_frame"))
        composeTestRule.onNodeWithTag("studio_template_leica_frame").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("studio_tab_text").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Leica Session Alpha").assertIsDisplayed()

        // Save emits restored Leica configuration
        composeTestRule.onNodeWithTag("studio_save_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(WatermarkStyle.LEICA_FRAME, savedConfig?.style)
        assertEquals("Leica Session Alpha", savedConfig?.customText)
        assertEquals(WatermarkLogoType.DEVICE_MANUFACTURER, savedConfig?.logoType)
    }

    @Test
    fun `text section exposes only supported controls and supports add remove for optional elements`() {
        val initial = WatermarkTemplateDefaults.defaultConfig(WatermarkStyle.LEICA_FRAME)

        composeTestRule.setContent {
            WatermarkStudioScreen(
                initialConfig = initial,
                savedPresets = emptyList(),
                onSaveConfig = {},
                onSavePreset = { _, _ -> },
                onDeletePreset = {},
                onDismiss = {}
            )
        }
        composeTestRule.waitForIdle()

        // LEICA_FRAME: supports removing and re-adding secondary subtitle
        composeTestRule.onNodeWithTag("studio_tab_text").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_text_secondary_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_text_remove_secondary").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_text_secondary_input").assertDoesNotExist()
        composeTestRule.onNodeWithTag("studio_text_add_secondary").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_text_secondary_input").assertIsDisplayed()

        // Switch to GLASS_FRAME: hides free-form text inputs, font chips, color swatches, letter spacing
        composeTestRule.onNodeWithTag("studio_tab_templates").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_templates_lazy_row")
            .performScrollToNode(hasTestTag("studio_template_glass_frame"))
        composeTestRule.onNodeWithTag("studio_template_glass_frame").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("studio_tab_text").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_text_primary_input").assertDoesNotExist()
        composeTestRule.onNodeWithTag("studio_text_secondary_input").assertDoesNotExist()
        composeTestRule.onNodeWithTag("studio_font_serif").assertDoesNotExist()
        composeTestRule.onNodeWithTag("studio_slider_letter_spacing").assertDoesNotExist()
        composeTestRule.onNodeWithTag("studio_slider_font_size").assertIsDisplayed()

        // Switch to RETRO_90S: supports optional stamp label add/remove, hides bold toggle & secondary text
        composeTestRule.onNodeWithTag("studio_tab_templates").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_templates_lazy_row")
            .performScrollToNode(hasTestTag("studio_template_retro_90s"))
        composeTestRule.onNodeWithTag("studio_template_retro_90s").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("studio_tab_text").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_text_primary_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_text_secondary_input").assertDoesNotExist()
        composeTestRule.onNodeWithTag("studio_toggle_bold").assertDoesNotExist()
        composeTestRule.onNodeWithTag("studio_text_remove_primary").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_text_primary_input").assertDoesNotExist()
        composeTestRule.onNodeWithTag("studio_text_add_primary").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_text_primary_input").assertIsDisplayed()
    }

    @Test
    fun `logo and style position sections hide irrelevant controls per template`() {
        val initial = WatermarkTemplateDefaults.defaultConfig(WatermarkStyle.CYBERPUNK)

        composeTestRule.setContent {
            WatermarkStudioScreen(
                initialConfig = initial,
                savedPresets = emptyList(),
                onSaveConfig = {},
                onSavePreset = { _, _ -> },
                onDeletePreset = {},
                onDismiss = {}
            )
        }
        composeTestRule.waitForIdle()

        // CYBERPUNK: Logo section shows explanatory hint without logo sliders; Appearance hides floating positions
        composeTestRule.onNodeWithTag("studio_tab_logo").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_logo_app_icon").assertDoesNotExist()
        composeTestRule.onNodeWithTag("studio_slider_logo_scale").assertDoesNotExist()
        composeTestRule.onNodeWithText("Cyberpunk HUD uses vector corner brackets", substring = true).assertIsDisplayed()

        composeTestRule.onNodeWithTag("studio_tab_appearance").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_fixed_layout_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_pos_bottom_right").assertDoesNotExist()
        composeTestRule.onNodeWithTag("studio_hud_accent_00e5ff").assertIsDisplayed()

        // Switch to GLASS_FRAME: supports manufacturer/custom/none logos (no app icon, no spacing slider) & positions + frost opacity
        composeTestRule.onNodeWithTag("studio_tab_templates").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_templates_lazy_row")
            .performScrollToNode(hasTestTag("studio_template_glass_frame"))
        composeTestRule.onNodeWithTag("studio_template_glass_frame").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("studio_tab_logo").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_logo_device_manufacturer").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_logo_app_icon").assertDoesNotExist()
        composeTestRule.onNodeWithTag("studio_slider_logo_spacing").assertDoesNotExist()

        composeTestRule.onNodeWithTag("studio_tab_appearance").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_pos_frame_border").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_pos_bottom_center").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_panel_scroll_container")
            .performScrollToNode(hasTestTag("studio_slider_bg_opacity"))
        composeTestRule.onNodeWithTag("studio_slider_bg_opacity").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_slider_border_thickness").assertDoesNotExist()
        composeTestRule.onNodeWithTag("studio_slider_padding").assertDoesNotExist()

        // Switch to CUSTOM: exposes full custom coords and border/corner/padding sliders
        composeTestRule.onNodeWithTag("studio_tab_templates").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_templates_lazy_row")
            .performScrollToNode(hasTestTag("studio_template_custom"))
        composeTestRule.onNodeWithTag("studio_template_custom").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("studio_tab_appearance").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_pos_custom_coords").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_panel_scroll_container")
            .performScrollToNode(hasTestTag("studio_slider_custom_x"))
        composeTestRule.onNodeWithTag("studio_slider_custom_x").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_panel_scroll_container")
            .performScrollToNode(hasTestTag("studio_slider_padding"))
        composeTestRule.onNodeWithTag("studio_slider_border_thickness").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_slider_corner_radius").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_slider_padding").assertIsDisplayed()
    }

    @Test
    fun `info section uses genuine available metadata prevents duplication and hides missing fields`() {
        val file = createTestJpeg()
        val target = PostCaptureWatermarkTarget(
            galleryUri = Uri.fromFile(file),
            originalId = "meta_test_1",
            originalFilePath = file.absolutePath,
            width = 240,
            height = 180,
            associatedConfig = WatermarkTemplateDefaults.defaultConfig(WatermarkStyle.GLASS_FRAME),
            iso = "ISO 200",
            shutterSpeed = "1/125s",
            aperture = "f/1.8",
            focalLength = null, // missing focal length must be hidden
            dateTimeFormatted = "2026-11-16 09:30",
            deviceModel = "Google Pixel 8 Pro",
            lensInfo = null,
            resolutionText = "4000×3000",
            manufacturer = "Google",
            modelOnly = "Pixel 8 Pro"
        )

        try {
            composeTestRule.setContent {
                WatermarkStudioScreen(
                    initialConfig = target.associatedConfig,
                    savedPresets = emptyList(),
                    onSaveConfig = {},
                    onSavePreset = { _, _ -> },
                    onDeletePreset = {},
                    onDismiss = {},
                    postCaptureTarget = target
                )
            }
            composeTestRule.waitUntil(timeoutMillis = 8000L) {
                try {
                    composeTestRule.onNodeWithTag("studio_photo_preview_frame").assertIsDisplayed()
                    true
                } catch (_: AssertionError) {
                    false
                }
            }

            composeTestRule.onNodeWithTag("studio_tab_metadata").performClick()
            composeTestRule.waitForIdle()

            // Manufacturer & model must not duplicate "Google" in Device Model row
            composeTestRule.onNodeWithTag("studio_panel_scroll_container")
                .performScrollToNode(hasTestTag("studio_toggle_meta_manufacturer"))
            composeTestRule.onNodeWithTag("studio_toggle_meta_manufacturer").assertIsDisplayed()
            composeTestRule.onNodeWithText("Google").assertIsDisplayed()
            composeTestRule.onNodeWithTag("studio_panel_scroll_container")
                .performScrollToNode(hasTestTag("studio_toggle_meta_device"))
            composeTestRule.onNodeWithTag("studio_toggle_meta_device").assertIsDisplayed()
            composeTestRule.onNodeWithText("Pixel 8 Pro").assertIsDisplayed()
            composeTestRule.onNodeWithText("Google Pixel 8 Pro").assertDoesNotExist()

            // Missing focal length & unsupported resolution on GLASS_FRAME must be hidden
            composeTestRule.onNodeWithTag("studio_toggle_meta_lens").assertDoesNotExist()
            composeTestRule.onNodeWithTag("studio_toggle_meta_resolution").assertDoesNotExist()
            composeTestRule.onNodeWithTag("studio_panel_scroll_container")
                .performScrollToNode(hasTestTag("studio_toggle_meta_exif"))
            composeTestRule.onNodeWithTag("studio_toggle_meta_exif").assertIsDisplayed()
        } finally {
            file.delete()
        }
    }

    @Test
    fun `info section displays empty callout when photo has no genuine exif metadata`() {
        val file = createTestJpeg()
        val target = PostCaptureWatermarkTarget(
            galleryUri = Uri.fromFile(file),
            originalId = "meta_empty_1",
            originalFilePath = file.absolutePath,
            width = 240,
            height = 180,
            associatedConfig = WatermarkTemplateDefaults.defaultConfig(WatermarkStyle.RETRO_90S),
            dateTimeFormatted = null,
            iso = null,
            shutterSpeed = null,
            aperture = null,
            focalLength = null,
            deviceModel = null,
            resolutionText = null,
            manufacturer = null,
            modelOnly = null
        )

        try {
            composeTestRule.setContent {
                WatermarkStudioScreen(
                    initialConfig = target.associatedConfig,
                    savedPresets = emptyList(),
                    onSaveConfig = {},
                    onSavePreset = { _, _ -> },
                    onDeletePreset = {},
                    onDismiss = {},
                    postCaptureTarget = target
                )
            }
            composeTestRule.waitUntil(timeoutMillis = 8000L) {
                try {
                    composeTestRule.onNodeWithTag("studio_photo_preview_frame").assertIsDisplayed()
                    true
                } catch (_: AssertionError) {
                    false
                }
            }

            composeTestRule.onNodeWithTag("studio_tab_metadata").performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("studio_toggle_meta_date").assertDoesNotExist()
            composeTestRule.onNodeWithText(
                "No supported EXIF metadata fields are available for this photo and template."
            ).assertIsDisplayed()
        } finally {
            file.delete()
        }
    }

    @Test
    fun `scoped reset confirms restores only selected template and preserves undo and other templates`() {
        var savedConfig: WatermarkConfig? = null
        val initial = WatermarkTemplateDefaults.defaultConfig(WatermarkStyle.LEICA_FRAME)

        composeTestRule.setContent {
            WatermarkStudioScreen(
                initialConfig = initial,
                savedPresets = emptyList(),
                onSaveConfig = { savedConfig = it },
                onSavePreset = { _, _ -> },
                onDeletePreset = {},
                onDismiss = {}
            )
        }
        composeTestRule.waitForIdle()

        // Pristine template -> reset button disabled
        composeTestRule.onNodeWithTag("studio_reset_template_button").assertIsNotEnabled()

        // Edit LEICA_FRAME
        composeTestRule.onNodeWithTag("studio_tab_text").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_text_primary_input").performTextReplacement("Modified Leica")
        composeTestRule.waitForIdle()

        // Switch to STUDIO_BRAND and edit it too
        composeTestRule.onNodeWithTag("studio_tab_templates").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_templates_lazy_row")
            .performScrollToNode(hasTestTag("studio_template_studio_brand"))
        composeTestRule.onNodeWithTag("studio_template_studio_brand").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_tab_text").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_text_primary_input").performTextReplacement("Modified Studio Brand")
        composeTestRule.waitForIdle()

        // Reset button is enabled -> tap opens confirmation dialog -> Cancel aborts safely
        composeTestRule.onNodeWithTag("studio_reset_template_button").assertIsEnabled().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_reset_confirm_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_reset_cancel_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Modified Studio Brand").assertIsDisplayed()

        // Tap Reset again -> Confirm resets ONLY STUDIO_BRAND
        composeTestRule.onNodeWithTag("studio_reset_template_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_reset_confirm_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("STU•D•O PHOTOGRAPHY").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_reset_template_button").assertIsNotEnabled()

        // Undo reverses the reset cleanly back to "Modified Studio Brand"
        composeTestRule.onNodeWithTag("studio_undo_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Modified Studio Brand").assertIsDisplayed()

        // Confirm reset again and verify LEICA_FRAME remained untouched!
        composeTestRule.onNodeWithTag("studio_reset_template_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_reset_confirm_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("studio_tab_templates").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_templates_lazy_row")
            .performScrollToNode(hasTestTag("studio_template_studio_brand"))
        composeTestRule.onNodeWithTag("studio_template_edited_badge_studio_brand", useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithTag("studio_templates_lazy_row")
            .performScrollToNode(hasTestTag("studio_template_leica_frame"))
        composeTestRule.onNodeWithTag("studio_template_edited_badge_leica_frame", useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_template_leica_frame").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_save_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(WatermarkStyle.LEICA_FRAME, savedConfig?.style)
        assertEquals("Modified Leica", savedConfig?.customText)
    }

    @Test
    fun `stateHolder preset application and undo remain cleanly scoped per template`() {
        val holder = WatermarkStudioStateHolder(
            WatermarkTemplateDefaults.defaultConfig(WatermarkStyle.LEICA_FRAME)
        )
        holder.setPrimaryText("Custom Leica")
        holder.selectTemplate(WatermarkStyle.CUSTOM)
        holder.updateActiveConfig {
            it.copy(
                font = WatermarkFont.SERIF,
                position = WatermarkPosition.TOP_LEFT
            )
        }

        val retroPreset = WatermarkPreset(
            id = "preset_retro_1",
            name = "Warm 90s",
            config = WatermarkTemplateDefaults.defaultConfig(WatermarkStyle.RETRO_90S).copy(
                customText = "1998 FILM"
            )
        )

        holder.applyPreset(retroPreset)
        assertEquals(WatermarkStyle.RETRO_90S, holder.selectedStyle)
        assertEquals("1998 FILM", holder.activeConfig.customText)

        // Prior templates remained isolated
        assertEquals("Custom Leica", holder.configFor(WatermarkStyle.LEICA_FRAME).customText)
        assertEquals(WatermarkFont.SERIF, holder.configFor(WatermarkStyle.CUSTOM).font)
        assertEquals(WatermarkPosition.TOP_LEFT, holder.configFor(WatermarkStyle.CUSTOM).position)

        // Undo reverts preset edit then template switch cleanly
        holder.undo()
        assertEquals("'90s CAM", holder.configFor(WatermarkStyle.RETRO_90S).customText)
        holder.undo()
        assertEquals(WatermarkStyle.CUSTOM, holder.selectedStyle)
        assertEquals("Custom Leica", holder.configFor(WatermarkStyle.LEICA_FRAME).customText)
    }

    @Test
    fun `template capability matrix aligns strictly with rendered features`() {
        val leicaCaps = capabilitiesForTemplate(WatermarkStyle.LEICA_FRAME)
        assertTrue(leicaCaps.supportedPositions.isEmpty())
        assertTrue(leicaCaps.supportsPadding)
        assertFalse(leicaCaps.supportsBorderThickness)

        val retroCaps = capabilitiesForTemplate(WatermarkStyle.RETRO_90S)
        assertFalse(retroCaps.supportsAnyLogo)
        assertTrue(retroCaps.canTogglePrimaryText)
        assertFalse(retroCaps.supportsBoldToggle)
        assertNotEquals(emptyList<WatermarkPosition>(), retroCaps.supportedPositions)

        val glassCaps = capabilitiesForTemplate(WatermarkStyle.GLASS_FRAME)
        assertFalse(glassCaps.supportsPrimaryText)
        assertTrue(glassCaps.supportsBackgroundOpacity)
        assertFalse(glassCaps.supportsBackgroundColor)
        assertFalse(glassCaps.supportsLogoSpacing)
        assertFalse(WatermarkLogoType.APP_ICON in glassCaps.supportedLogoTypes)
    }
}
