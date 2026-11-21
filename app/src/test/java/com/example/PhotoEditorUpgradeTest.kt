package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.editor.AdjustmentTool
import com.example.editor.BadgeCategory
import com.example.editor.CropAspectRatio
import com.example.editor.CropOverlayView
import com.example.editor.CropState
import com.example.editor.DragHandle
import com.example.editor.EditorFontFamily
import com.example.editor.EditorPanelDetent
import com.example.editor.EditorTab
import com.example.editor.FilterCategory
import com.example.editor.PhotoAdjustmentState
import com.example.editor.PhotoEditorScreen
import com.example.editor.PhotoFilterType
import com.example.editor.PhotoProcessingEngine
import com.example.editor.StickerType
import com.example.editor.computeAspectRatioCropRect
import com.example.editor.computeUpdatedCropPixelRect
import com.example.editor.snapEditorDetent
import com.example.ui.theme.StuDOTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xhdpi")
class PhotoEditorUpgradeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun createTestGradientBitmap(w: Int = 64, h: Int = 64): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = ((x * 255) / (w - 1)).coerceIn(0, 255)
                val g = ((y * 255) / (h - 1)).coerceIn(0, 255)
                val b = 120
                bmp.setPixel(x, y, AndroidColor.argb(255, r, g, b))
            }
        }
        return bmp
    }

    private fun averageLuminance(bmp: Bitmap): Double {
        var sum = 0.0
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                val c = bmp.getPixel(x, y)
                sum += 0.2126 * AndroidColor.red(c) + 0.7152 * AndroidColor.green(c) + 0.0722 * AndroidColor.blue(c)
            }
        }
        return sum / (bmp.width * bmp.height)
    }

    @Test
    fun all_17_tune_adjustments_produce_accurate_tonal_color_and_detail_changes() {
        val base = createTestGradientBitmap(48, 48)
        val baseLuma = averageLuminance(base)

        // Exposure & Brightness
        val expUp = PhotoProcessingEngine.processEditedBitmap(base, PhotoAdjustmentState(exposure = 1.0f), PhotoFilterType.ORIGINAL)
        val expDown = PhotoProcessingEngine.processEditedBitmap(base, PhotoAdjustmentState(exposure = -1.0f), PhotoFilterType.ORIGINAL)
        assertTrue(averageLuminance(expUp) > baseLuma + 20.0)
        assertTrue(averageLuminance(expDown) < baseLuma - 20.0)

        val brightUp = PhotoProcessingEngine.processEditedBitmap(base, PhotoAdjustmentState(brightness = 0.6f), PhotoFilterType.ORIGINAL)
        assertTrue(averageLuminance(brightUp) > baseLuma + 10.0)

        // Contrast & Blacks & Whites
        val contrastUp = PhotoProcessingEngine.processEditedBitmap(base, PhotoAdjustmentState(contrast = 0.8f), PhotoFilterType.ORIGINAL)
        assertTrue(AndroidColor.red(contrastUp.getPixel(44, 24)) >= AndroidColor.red(base.getPixel(44, 24)))

        val whitesUp = PhotoProcessingEngine.processEditedBitmap(base, PhotoAdjustmentState(whites = 0.7f), PhotoFilterType.ORIGINAL)
        val blacksDown = PhotoProcessingEngine.processEditedBitmap(base, PhotoAdjustmentState(blacks = -0.7f), PhotoFilterType.ORIGINAL)
        assertTrue(averageLuminance(whitesUp) > baseLuma)
        assertTrue(averageLuminance(blacksDown) < baseLuma)

        // Temperature & Tint
        val grey = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.rgb(128, 128, 128)) }
        val warm = PhotoProcessingEngine.processEditedBitmap(grey, PhotoAdjustmentState(temperature = 0.8f), PhotoFilterType.ORIGINAL)
        val warmPx = warm.getPixel(12, 12)
        assertTrue("Warm temp must boost red over blue", AndroidColor.red(warmPx) > AndroidColor.blue(warmPx) + 15)

        val cool = PhotoProcessingEngine.processEditedBitmap(grey, PhotoAdjustmentState(temperature = -0.8f), PhotoFilterType.ORIGINAL)
        val coolPx = cool.getPixel(12, 12)
        assertTrue("Cool temp must boost blue over red", AndroidColor.blue(coolPx) > AndroidColor.red(coolPx) + 15)

        val magenta = PhotoProcessingEngine.processEditedBitmap(grey, PhotoAdjustmentState(tint = 0.8f), PhotoFilterType.ORIGINAL)
        val magPx = magenta.getPixel(12, 12)
        assertTrue("Positive tint shifts toward magenta", AndroidColor.red(magPx) > AndroidColor.green(magPx) + 10)

        // Saturation (-1.0 = monochrome)
        val desat = PhotoProcessingEngine.processEditedBitmap(base, PhotoAdjustmentState(saturation = -1.0f), PhotoFilterType.ORIGINAL)
        val desatPx = desat.getPixel(36, 10)
        assertTrue(abs(AndroidColor.red(desatPx) - AndroidColor.green(desatPx)) <= 2)
        assertTrue(abs(AndroidColor.green(desatPx) - AndroidColor.blue(desatPx)) <= 2)

        // Skin-tone protected Vibrance vs Saturation
        val skinChip = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.rgb(210, 150, 115)) }
        val mutedBlue = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.rgb(100, 115, 145)) }
        val vibSkin = PhotoProcessingEngine.processEditedBitmap(skinChip, PhotoAdjustmentState(vibrance = 0.8f), PhotoFilterType.ORIGINAL).getPixel(8, 8)
        val satSkin = PhotoProcessingEngine.processEditedBitmap(skinChip, PhotoAdjustmentState(saturation = 0.8f), PhotoFilterType.ORIGINAL).getPixel(8, 8)
        val vibBlue = PhotoProcessingEngine.processEditedBitmap(mutedBlue, PhotoAdjustmentState(vibrance = 0.8f), PhotoFilterType.ORIGINAL).getPixel(8, 8)
        val skinVibDelta = abs(AndroidColor.red(vibSkin) - AndroidColor.blue(vibSkin))
        val skinSatDelta = abs(AndroidColor.red(satSkin) - AndroidColor.blue(satSkin))
        assertTrue("Vibrance should protect skin tones compared to raw saturation", skinVibDelta < skinSatDelta)
        assertTrue("Vibrance boosts muted cool tones", AndroidColor.blue(vibBlue) - AndroidColor.red(vibBlue) > 45)

        // Highlights & Shadows & Dehaze
        val highlightTile = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.rgb(235, 235, 235)) }
        val recHigh = PhotoProcessingEngine.processEditedBitmap(highlightTile, PhotoAdjustmentState(highlights = -0.8f), PhotoFilterType.ORIGINAL)
        assertTrue("Highlight recovery must darken near-white tones", AndroidColor.red(recHigh.getPixel(8, 8)) < 235)

        val shadowTile = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.rgb(35, 35, 35)) }
        val liftShadow = PhotoProcessingEngine.processEditedBitmap(shadowTile, PhotoAdjustmentState(shadows = 0.8f), PhotoFilterType.ORIGINAL)
        assertTrue("Shadow recovery must lift dark regions", AndroidColor.red(liftShadow.getPixel(8, 8)) > 35)

        val hazyTile = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.rgb(95, 100, 105)) }
        val dehazed = PhotoProcessingEngine.processEditedBitmap(hazyTile, PhotoAdjustmentState(dehaze = 0.7f), PhotoFilterType.ORIGINAL)
        assertTrue("Dehaze must strip veil haze and deepen shadow density", AndroidColor.red(dehazed.getPixel(8, 8)) < 95)

        // Spatial Detail: Clarity, Texture, Sharpness (deterministic step edge via setPixel)
        val stepEdge = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                val v = if (x < 16) 170 else 80
                stepEdge.setPixel(x, y, AndroidColor.rgb(v, v, v))
            }
        }
        val clarityOut = PhotoProcessingEngine.processEditedBitmap(stepEdge, PhotoAdjustmentState(clarity = 0.8f), PhotoFilterType.ORIGINAL)
        val textureOut = PhotoProcessingEngine.processEditedBitmap(stepEdge, PhotoAdjustmentState(texture = 0.8f), PhotoFilterType.ORIGINAL)
        val sharpOut = PhotoProcessingEngine.processEditedBitmap(stepEdge, PhotoAdjustmentState(sharpness = 0.8f), PhotoFilterType.ORIGINAL)
        assertTrue("Clarity must enhance bright edge side (>170), got ${AndroidColor.red(clarityOut.getPixel(15, 16))}", AndroidColor.red(clarityOut.getPixel(15, 16)) > 170)
        assertTrue("Texture must enhance dark edge side (<80), got ${AndroidColor.red(textureOut.getPixel(16, 16))}", AndroidColor.red(textureOut.getPixel(16, 16)) < 80)
        assertTrue("Sharpness must boost high-contrast edge (>170), got ${AndroidColor.red(sharpOut.getPixel(15, 16))}", AndroidColor.red(sharpOut.getPixel(15, 16)) > 170)

        // Vignette darkens corners vs center
        val vignetted = PhotoProcessingEngine.processEditedBitmap(grey, PhotoAdjustmentState(vignette = 0.9f), PhotoFilterType.ORIGINAL)
        assertTrue(AndroidColor.red(vignetted.getPixel(12, 12)) > AndroidColor.red(vignetted.getPixel(0, 0)) + 25)

        // Grain introduces organic variance on uniform surface
        val grained = PhotoProcessingEngine.processEditedBitmap(grey, PhotoAdjustmentState(grain = 0.8f), PhotoFilterType.ORIGINAL)
        val distinctValues = (0 until 24).map { x -> AndroidColor.red(grained.getPixel(x, 12)) }.toSet()
        assertTrue("Film grain should produce distinct spatial luminance samples", distinctValues.size > 4)

        // All 17 enum entries format values cleanly
        assertEquals(17, AdjustmentTool.entries.size)
        AdjustmentTool.entries.forEach { tool ->
            assertTrue(tool.formatValue(tool.defaultVal).isNotBlank())
        }
    }

    @Test
    fun all_15_filter_categories_and_intensity_blending_behave_accurately() = runBlocking {
        // Verify every FilterCategory except ALL has presets
        FilterCategory.entries.filter { it != FilterCategory.ALL }.forEach { category ->
            assertTrue(
                "Category $category must contain at least 1 preset",
                PhotoFilterType.entries.any { it.category == category }
            )
        }

        val sample = createTestGradientBitmap(64, 64)
        val zeroIntensity = PhotoProcessingEngine.processEditedBitmap(
            sample,
            PhotoAdjustmentState(),
            PhotoFilterType.LEICA_BW,
            filterIntensity = 0f
        )
        assertEquals(sample.getPixel(20, 20), zeroIntensity.getPixel(20, 20))

        val fullBw = PhotoProcessingEngine.processEditedBitmap(
            sample,
            PhotoAdjustmentState(),
            PhotoFilterType.LEICA_BW,
            filterIntensity = 1.0f
        )
        val bwPx = fullBw.getPixel(50, 10)
        assertTrue("Monochrome filter must equalize RGB channels", abs(AndroidColor.red(bwPx) - AndroidColor.blue(bwPx)) <= 2)

        val thumbs = PhotoProcessingEngine.generateFilterThumbnails(sample, thumbSizePx = 48)
        assertEquals(PhotoFilterType.entries.size, thumbs.size)
        thumbs.values.forEach { bmp ->
            assertEquals(48, bmp.width)
            assertEquals(48, bmp.height)
            bmp.recycle()
        }
    }

    @Test
    fun crop_aspect_ratio_math_is_exact_across_landscape_and_portrait_images() {
        val aspectCases = listOf(
            CropAspectRatio.SQUARE to 1f,
            CropAspectRatio.RATIO_4_3 to (4f / 3f),
            CropAspectRatio.RATIO_16_9 to (16f / 9f),
            CropAspectRatio.RATIO_3_2 to (3f / 2f),
            CropAspectRatio.RATIO_9_16 to (9f / 16f)
        )

        val sizes = listOf(4000f to 3000f, 3000f to 4000f, 1080f to 1920f)
        for ((w, h) in sizes) {
            for ((preset, expectedRatio) in aspectCases) {
                val rect = computeAspectRatioCropRect(preset, w, h)
                assertTrue(rect.left in 0f..1f && rect.top in 0f..1f)
                assertTrue(rect.right in 0f..1f && rect.bottom in 0f..1f)
                val pxW = (rect.right - rect.left) * w
                val pxH = (rect.bottom - rect.top) * h
                assertEquals(
                    "Ratio mismatch for $preset on ${w}x${h}",
                    expectedRatio,
                    pxW / pxH,
                    0.005f
                )
            }
        }

        // Pixel-space locked corner drag maintains aspect ratio strictly within bounds
        val dragged = computeUpdatedCropPixelRect(
            handle = DragHandle.BOTTOM_RIGHT,
            l0 = 100f,
            t0 = 100f,
            r0 = 500f,
            b0 = 500f,
            dragDelta = Offset(120f, 40f),
            totalWidth = 800f,
            totalHeight = 600f,
            minSizePx = 56f,
            lockedRatio = 1.0f
        )
        val outW = (dragged.right - dragged.left) * 800f
        val outH = (dragged.bottom - dragged.top) * 600f
        assertEquals(1.0f, outW / outH, 0.005f)
        assertTrue(dragged.right <= 1f && dragged.bottom <= 1f)
    }

    @Test
    fun crop_overlay_view_supports_continuous_non_stepped_drag_gesture() {
        var latestRect by mutableStateOf(CropState().cropRect)
        val recordedRights = mutableListOf<Float>()
        var dragEndCount = 0

        composeRule.setContent {
            StuDOTheme {
                Box(modifier = Modifier.size(width = 360.dp, height = 270.dp)) {
                    CropOverlayView(
                        cropState = CropState(cropRect = latestRect, aspectRatio = CropAspectRatio.FREE),
                        onCropRectChanged = {
                            latestRect = it
                            recordedRights.add(it.right)
                        },
                        onCropDragEnded = { dragEndCount++ }
                    )
                }
            }
        }

        // Continuous multi-step drag from Bottom-Right corner inward
        composeRule.onNodeWithTag("crop_overlay_canvas").performTouchInput {
            down(Offset(width * 0.99f, height * 0.99f))
            moveBy(Offset(-width * 0.10f, -height * 0.10f))
            moveBy(Offset(-width * 0.10f, -height * 0.10f))
            moveBy(Offset(-width * 0.10f, -height * 0.10f))
            moveBy(Offset(-width * 0.08f, -height * 0.08f))
            up()
        }

        composeRule.waitForIdle()
        assertEquals(1, dragEndCount)
        assertTrue("Continuous drag should stream >= 3 intermediate updates, got ${recordedRights.size}", recordedRights.size >= 3)
        assertTrue("Crop right edge should continuously shrink, got ${latestRect.right}", latestRect.right < 0.78f)
        assertTrue("Crop bottom edge should continuously shrink, got ${latestRect.bottom}", latestRect.bottom < 0.78f)
    }

    @Test
    fun bottom_sheet_detent_snapping_maps_correctly() {
        assertEquals(EditorPanelDetent.COLLAPSED, snapEditorDetent(130f, 120f, 340f, 560f))
        assertEquals(EditorPanelDetent.PARTIAL, snapEditorDetent(310f, 120f, 340f, 560f))
        assertEquals(EditorPanelDetent.EXPANDED, snapEditorDetent(490f, 120f, 340f, 560f))
    }

    @Test
    fun liquid_glass_photo_editor_ui_supports_tabs_resize_inline_text_badges_and_undo_redo() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val tempFile = File(app.cacheDir, "editor_ui_test.jpg")
        val bmp = createTestGradientBitmap(120, 90)
        FileOutputStream(tempFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()

        var dismissed = false
        composeRule.setContent {
            StuDOTheme {
                PhotoEditorScreen(
                    sourceUri = Uri.fromFile(tempFile),
                    onSaveSuccess = {},
                    onDismiss = { dismissed = true }
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Tune").fetchSemanticsNodes().isNotEmpty()
        }

        // 1. Verify Top Toolbar buttons & initial disabled Undo/Redo states
        composeRule.onNodeWithTag("editor_top_toolbar").assertIsDisplayed()
        composeRule.onNodeWithTag("editor_close_button").assertIsDisplayed()
        composeRule.onNodeWithTag("editor_undo_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("editor_redo_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("editor_save_button").assertIsDisplayed()

        // 2. Verify all 6 Bottom Navigation Tabs exist
        EditorTab.entries.forEach { tab ->
            composeRule.onNodeWithTag("editor_nav_${tab.name.lowercase()}").assertExists()
        }

        // 3. Test Draggable Bottom Sheet resize toggle & drag handle
        composeRule.onNodeWithTag("editor_panel_detent_toggle").performClick() // PARTIAL -> EXPANDED
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("editor_panel_drag_handle").performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("editor_panel_drag_handle").performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        // 4. Test Non-Blocking Inline Text Tool & Live Updates + Undo/Redo
        composeRule.onNodeWithTag("editor_nav_text").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("text_add_button").performClick()
        composeRule.waitForIdle()

        // Inline input is immediately present without blocking modal AlertDialog
        composeRule.onNodeWithTag("text_inline_input").assertIsDisplayed()
        composeRule.onNodeWithTag("text_inline_input").performTextInput(" Golden")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("editor_undo_button").assertIsEnabled()

        // Verify typography bundled font options & color controls
        composeRule.onNodeWithTag("text_font_${EditorFontFamily.ELEGANT_SERIF.name.lowercase()}").assertExists()
        composeRule.onNodeWithTag("text_bold_toggle").performClick()
        composeRule.waitForIdle()

        // Undo and Redo accurately
        composeRule.onNodeWithTag("editor_undo_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("editor_redo_button").assertIsEnabled()
        composeRule.onNodeWithTag("editor_redo_button").performClick()
        composeRule.waitForIdle()

        // 5. Test Expanded Categorized Badges & Favorites Saving
        composeRule.onNodeWithTag("editor_nav_stickers").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("badge_category_${BadgeCategory.LOCATION.name.lowercase()}").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("badge_preset_${StickerType.LOCATION_PIN.name.lowercase()}").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("badge_save_favorite_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("badge_subtab_library").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("badge_category_${BadgeCategory.FAVORITES.name.lowercase()}").performClick()
        composeRule.waitForIdle()
        assertTrue(
            "Expected saved favorite preset card in Favorites library",
            composeRule.onAllNodesWithText("TOKYO · SHIBUYA").fetchSemanticsNodes().size >= 2
        )

        // 6. Close confirmation dialog triggers when edits exist
        composeRule.onNodeWithTag("editor_close_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("editor_discard_confirm_button").performClick()
        composeRule.waitForIdle()
        assertTrue(dismissed)

        tempFile.delete()
    }

    @Test
    fun oriented_crop_rotate_flip_and_draw_brushes_render_accurately() {
        val base = createTestGradientBitmap(80, 40)

        // 90° rotation swaps portrait/landscape dimensions accurately + applies normalized crop
        val rotatedCropped = PhotoProcessingEngine.createOrientedBitmap(
            source = base,
            crop = CropState(
                rotationDegrees = 90,
                isFlippedHorizontal = true,
                cropRect = android.graphics.RectF(0.25f, 0.25f, 0.75f, 0.75f)
            ),
            applyCropRect = true,
            recycleSource = false
        )
        assertEquals(20, rotatedCropped.width)
        assertEquals(40, rotatedCropped.height)

        // All 5 brush styles + eraser render onto canvas cleanly without crashing
        val canvasBmp = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        canvasBmp.eraseColor(AndroidColor.DKGRAY)
        val drawPaths = com.example.editor.DrawBrushType.entries.mapIndexed { idx, brush ->
            com.example.editor.DrawPathItem(
                points = listOf(Offset(0.1f, 0.15f + idx * 0.14f), Offset(0.85f, 0.18f + idx * 0.14f)),
                color = Color.Yellow,
                strokeWidth = 6f,
                opacity = 0.85f,
                brushType = brush
            )
        } + com.example.editor.DrawPathItem(
            points = listOf(Offset(0.2f, 0.2f), Offset(0.7f, 0.7f)),
            color = Color.Transparent,
            strokeWidth = 8f,
            isEraser = true
        )
        PhotoProcessingEngine.renderDrawingPaths(canvasBmp, drawPaths)

        val context = ApplicationProvider.getApplicationContext<Application>()
        val canvas = Canvas(canvasBmp)
        val densityFactor = canvasBmp.width / 600f
        PhotoProcessingEngine.renderTextOverlay(
            context = context,
            canvas = canvas,
            textItem = com.example.editor.TextOverlayItem(
                text = "Golden Hour",
                hasOutline = true,
                hasShadow = true,
                fontFamily = EditorFontFamily.ELEGANT_SERIF
            ),
            canvasWidth = canvasBmp.width.toFloat(),
            canvasHeight = canvasBmp.height.toFloat(),
            densityFactor = densityFactor
        )
        PhotoProcessingEngine.renderStickerBadge(
            canvas = canvas,
            sticker = com.example.editor.StickerOverlayItem(type = StickerType.LOCATION_PIN),
            density = densityFactor,
            metadata = null,
            context = context
        )

        canvasBmp.recycle()
        rotatedCropped.recycle()
        base.recycle()
    }
}
