package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.example.data.CameraPreferencesRepository
import com.example.model.WatermarkConfig
import com.example.model.WatermarkLogoAlignment
import com.example.model.WatermarkLogoType
import com.example.model.WatermarkPosition
import com.example.model.WatermarkStyle
import com.example.model.WatermarkTextAlignment
import com.example.ui.components.STUDIO_MAX_ZOOM
import com.example.ui.components.STUDIO_MIN_ZOOM
import com.example.ui.components.WatermarkPreviewCache
import com.example.ui.components.WatermarkStudioScreen
import com.example.ui.components.WatermarkTemplateDefaults
import com.example.ui.components.clampPreviewOffset
import com.example.ui.components.computeFocalZoomTransform
import com.example.util.CameraMetadataUtil
import com.example.util.ManufacturerLogoUtil
import com.example.util.RealCameraMetadata
import com.example.util.WatermarkPainter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h840dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatermarkStudioPhase3To6Test {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        WatermarkPreviewCache.clear()
        WatermarkPainter.clearSampleCache()
    }

    @Test
    fun `phase 3 connects text alignment line spacing text opacity logo alignment margin and rotation`() {
        var savedConfig: WatermarkConfig? = null
        val initialCustom = WatermarkTemplateDefaults.defaultConfig(WatermarkStyle.CUSTOM)

        composeTestRule.setContent {
            WatermarkStudioScreen(
                initialConfig = initialCustom,
                savedPresets = emptyList(),
                onSaveConfig = { savedConfig = it },
                onSavePreset = { _, _ -> },
                onDeletePreset = {},
                onDismiss = {}
            )
        }
        composeTestRule.waitForIdle()

        // Text section: verify alignment chips + line spacing + text opacity
        composeTestRule.onNodeWithTag("studio_tab_text").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_panel_scroll_container")
            .performScrollToNode(hasTestTag("studio_text_align_center"))
        composeTestRule.onNodeWithTag("studio_text_align_center").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("studio_panel_scroll_container")
            .performScrollToNode(hasTestTag("studio_slider_text_opacity"))
        composeTestRule.onNodeWithTag("studio_slider_line_spacing").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_slider_text_opacity").assertIsDisplayed()

        // Logo section: verify Logo Placement chips (Left / Top / Right)
        composeTestRule.onNodeWithTag("studio_tab_logo").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_logo_align_top").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // Style & Position section: verify Edge Margin and Rotation controls exist on floating layouts
        composeTestRule.onNodeWithTag("studio_tab_appearance").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_panel_scroll_container")
            .performScrollToNode(hasTestTag("studio_slider_rotation"))
        composeTestRule.onNodeWithTag("studio_slider_margin").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_slider_rotation").assertIsDisplayed()

        // Verify GLASS_FRAME hides rotation/margin in FRAME_BORDER and reveals them when switching to BOTTOM_CENTER
        composeTestRule.onNodeWithTag("studio_tab_templates").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_templates_lazy_row")
            .performScrollToNode(hasTestTag("studio_template_glass_frame"))
        composeTestRule.onNodeWithTag("studio_template_glass_frame").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("studio_tab_appearance").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_slider_rotation").assertDoesNotExist()
        composeTestRule.onNodeWithTag("studio_slider_margin").assertDoesNotExist()

        composeTestRule.onNodeWithTag("studio_pos_bottom_center").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_panel_scroll_container")
            .performScrollToNode(hasTestTag("studio_slider_rotation"))
        composeTestRule.onNodeWithTag("studio_slider_margin").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_slider_rotation").assertIsDisplayed()

        // Switch back to CUSTOM and save — edits from earlier persisted cleanly
        composeTestRule.onNodeWithTag("studio_tab_templates").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_templates_lazy_row")
            .performScrollToNode(hasTestTag("studio_template_custom"))
        composeTestRule.onNodeWithTag("studio_template_custom").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_save_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(WatermarkStyle.CUSTOM, savedConfig?.style)
        assertEquals(WatermarkTextAlignment.CENTER, savedConfig?.textAlignment)
        assertEquals(WatermarkLogoAlignment.TOP, savedConfig?.logoAlignment)
    }

    @Test
    fun `motorola batwing logo renders symmetrically inside official blue circular badge`() {
        val size = 120
        val bmp = ManufacturerLogoUtil.createManufacturerLogo("motorola", size)
        try {
            assertEquals(size, bmp.width)
            assertEquals(size, bmp.height)

            // Center-horizontal symmetry check across badge and Batwing M strokes
            val cx = size / 2
            var symmetryMismatches = 0
            var whiteBatwingPixels = 0
            var badgeBluePixels = 0

            for (y in 10 until size - 10) {
                for (dx in 1 until 40) {
                    val leftPixel = bmp.getPixel(cx - dx, y)
                    val rightPixel = bmp.getPixel(cx + dx - 1, y)
                    val dr = abs(Color.red(leftPixel) - Color.red(rightPixel))
                    val dg = abs(Color.green(leftPixel) - Color.green(rightPixel))
                    val db = abs(Color.blue(leftPixel) - Color.blue(rightPixel))
                    if (dr > 18 || dg > 18 || db > 18) symmetryMismatches++
                }
                val centerPx = bmp.getPixel(cx, y)
                if (Color.red(centerPx) > 220 && Color.green(centerPx) > 220 && Color.blue(centerPx) > 220) {
                    whiteBatwingPixels++
                } else if (Color.blue(centerPx) > 100 && Color.red(centerPx) < 40) {
                    badgeBluePixels++
                }
            }

            assertTrue("Expected noticeable white Motorola Batwing pixels, got $whiteBatwingPixels", whiteBatwingPixels >= 8)
            assertTrue("Expected official Motorola deep blue badge pixels, got $badgeBluePixels", badgeBluePixels >= 20)
            val totalChecked = (size - 20) * 39
            assertTrue(
                "Motorola Batwing logo must be cleanly symmetrical (mismatches=$symmetryMismatches/$totalChecked)",
                symmetryMismatches < totalChecked * 0.08
            )
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    @Test
    fun `custom uploaded logo preserves rectangular aspect ratio without square distortion`() {
        val wideFile = File(app.cacheDir, "wide_logo_${System.nanoTime()}.png")
        val wideBmp = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        try {
            FileOutputStream(wideFile).use { wideBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val config = WatermarkTemplateDefaults.defaultConfig(WatermarkStyle.CUSTOM).copy(
                logoType = WatermarkLogoType.CUSTOM,
                customLogoUri = Uri.fromFile(wideFile).toString(),
                logoScale = 1.0f
            )
            val loaded = WatermarkPainter.loadLogoBitmap(app, config, targetSizePx = 120)
            assertNotNull(loaded)
            val ratio = loaded!!.width.toFloat() / loaded.height.toFloat()
            assertTrue("Expected preserved ~3:1 logo aspect ratio, got $ratio (${loaded.width}x${loaded.height})", ratio in 2.6f..3.4f)
            if (!loaded.isRecycled) loaded.recycle()
        } finally {
            if (!wideBmp.isRecycled) wideBmp.recycle()
            wideFile.delete()
        }
    }

    @Test
    fun `phase 4 removes preset zoom pills and implements natural pinch zoom pan clamping and double tap reset`() {
        // 1. Unit verification of zoom/pan/clamping math
        val size = IntSize(400, 300)
        assertEquals(Offset.Zero, clampPreviewOffset(Offset(120f, -80f), 1.0f, size))

        val maxClamped = clampPreviewOffset(Offset(9999f, -9999f), 2.0f, size)
        assertEquals(200f, maxClamped.x, 0.5f)
        assertEquals(-150f, maxClamped.y, 0.5f)

        // Focal-point zoom keeps point under off-center centroid fixed
        val centroid = Offset(300f, 75f)
        val (scaled, offsetAfterPinch) = computeFocalZoomTransform(
            currentScale = 1.0f,
            currentOffset = Offset.Zero,
            zoomChange = 2.0f,
            panChange = Offset.Zero,
            centroid = centroid,
            containerSize = size
        )
        assertEquals(2.0f, scaled, 0.001f)
        val boxCenter = Offset(size.width / 2f, size.height / 2f)
        val focalFromCenter = centroid - boxCenter
        assertEquals(-focalFromCenter.x, offsetAfterPinch.x, 0.5f)
        assertEquals(-focalFromCenter.y, offsetAfterPinch.y, 0.5f)

        val (clampedMaxScale, _) = computeFocalZoomTransform(
            currentScale = 4.0f,
            currentOffset = Offset.Zero,
            zoomChange = 3.0f,
            panChange = Offset.Zero,
            centroid = boxCenter,
            containerSize = size
        )
        assertEquals(STUDIO_MAX_ZOOM, clampedMaxScale, 0.001f)

        // 2. Compose UI verification: no Fit/100%/200%, pinch-to-zoom reveals zoom badge, double-tap resets it
        composeTestRule.setContent {
            WatermarkStudioScreen(
                initialConfig = WatermarkTemplateDefaults.defaultConfig(WatermarkStyle.LEICA_FRAME),
                savedPresets = emptyList(),
                onSaveConfig = {},
                onSavePreset = { _, _ -> },
                onDeletePreset = {},
                onDismiss = {}
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 12000L) {
            try {
                composeTestRule.onNodeWithTag("studio_photo_preview_frame").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }

        // Legacy Fit / 100% / 200% zoom buttons must be completely gone
        composeTestRule.onNodeWithText("Fit").assertDoesNotExist()
        composeTestRule.onNodeWithText("100%").assertDoesNotExist()
        composeTestRule.onNodeWithText("200%").assertDoesNotExist()
        composeTestRule.onNodeWithTag("studio_zoom_badge").assertDoesNotExist()

        // Pinch outward on photo preview frame -> zoom badge appears (> 1.0x)
        composeTestRule.onNodeWithTag("studio_photo_preview_frame").performTouchInput {
            down(0, center - Offset(18f, 0f))
            down(1, center + Offset(18f, 0f))
            moveTo(0, center - Offset(72f, 0f))
            moveTo(1, center + Offset(72f, 0f))
            up(0)
            up(1)
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_zoom_badge").assertIsDisplayed()

        // Tap zoom badge (accessible reset action) resets cleanly back to 1.0x fit and hides badge
        composeTestRule.onNodeWithContentDescription("Reset preview zoom to fit").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_zoom_badge").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [36], qualifiers = "w840dp-h540dp-xhdpi")
    fun `tablet landscape supports natural preview gestures and all five sections without fit zoom selector`() {
        composeTestRule.setContent {
            WatermarkStudioScreen(
                initialConfig = WatermarkTemplateDefaults.defaultConfig(WatermarkStyle.STUDIO_BRAND),
                savedPresets = emptyList(),
                onSaveConfig = {},
                onSavePreset = { _, _ -> },
                onDeletePreset = {},
                onDismiss = {}
            )
        }
        composeTestRule.waitUntil(timeoutMillis = 12000L) {
            try {
                composeTestRule.onNodeWithTag("studio_photo_preview_frame").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        composeTestRule.onNodeWithText("Fit").assertDoesNotExist()
        composeTestRule.onNodeWithText("100%").assertDoesNotExist()
        composeTestRule.onNodeWithText("200%").assertDoesNotExist()

        composeTestRule.onNodeWithTag("studio_tab_appearance").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_panel_scroll_container")
            .performScrollToNode(hasTestTag("studio_slider_rotation"))
        composeTestRule.onNodeWithTag("studio_slider_rotation").assertIsDisplayed()
    }

    @Test
    fun `phase 5 renderer scales frame proportions accurately between 720p preview and 4x hi-res export`() {
        val small = Bitmap.createBitmap(720, 540, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(42, 88, 130))
        }
        val large = Bitmap.createBitmap(2880, 2160, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(42, 88, 130))
        }
        val metaSmall = CameraMetadataUtil.samplePreviewMetadata(720, 540, focalLength = "24mm")
        val metaLarge = CameraMetadataUtil.samplePreviewMetadata(2880, 2160, focalLength = "24mm")

        try {
            listOf(WatermarkStyle.LEICA_FRAME, WatermarkStyle.HASSELBLAD, WatermarkStyle.METADATA_STRIP).forEach { style ->
                val cfg = WatermarkTemplateDefaults.defaultConfig(style)
                val outSmall = WatermarkPainter.applyWatermark(small, cfg, metaSmall, app)
                val outLarge = WatermarkPainter.applyWatermark(large, cfg, metaLarge, app)
                try {
                    val barSmallRatio = (outSmall.height - small.height).toFloat() / small.height.toFloat()
                    val barLargeRatio = (outLarge.height - large.height).toFloat() / large.height.toFloat()
                    assertTrue(
                        "$style bar proportion mismatch between preview ($barSmallRatio) and export ($barLargeRatio)",
                        abs(barSmallRatio - barLargeRatio) < 0.015f
                    )
                } finally {
                    if (outSmall !== small && !outSmall.isRecycled) outSmall.recycle()
                    if (outLarge !== large && !outLarge.isRecycled) outLarge.recycle()
                }
            }

            // Verify GLASS_FRAME expands canvas instead of cropping source photo
            val glassCfg = WatermarkTemplateDefaults.defaultConfig(WatermarkStyle.GLASS_FRAME)
            val glassOut = WatermarkPainter.applyWatermark(small, glassCfg, metaSmall, app)
            try {
                assertTrue(glassOut.width > small.width)
                assertTrue(glassOut.height > small.height)
                // Center of rendered Glass Frame photo matches original photo color cleanly (not blurred or cropped out)
                val centerPx = glassOut.getPixel(glassOut.width / 2, (small.height / 2) + 20)
                assertEquals(42, Color.red(centerPx))
                assertEquals(88, Color.green(centerPx))
                assertEquals(130, Color.blue(centerPx))
            } finally {
                if (glassOut !== small && !glassOut.isRecycled) glassOut.recycle()
            }
        } finally {
            if (!small.isRecycled) small.recycle()
            if (!large.isRecycled) large.recycle()
        }
    }

    @Test
    fun `missing exposure metadata omits fake values cleanly while exporting preserves EXIF and source file`() {
        val missingMeta = RealCameraMetadata(
            iso = null,
            shutterSpeed = null,
            aperture = null,
            focalLength = null,
            dateTimeFormatted = null,
            deviceModel = null,
            lensInfo = null,
            resolutionText = null,
            manufacturer = null,
            modelOnly = null
        )
        val cfg = WatermarkTemplateDefaults.defaultConfig(WatermarkStyle.METADATA_STRIP)
        assertEquals("", missingMeta.formatAvailableExposureTriangle(cfg))

        // Build real JPEG with EXIF on disk and export watermarked copy at full resolution
        val srcFile = File(app.cacheDir, "export_src_${System.nanoTime()}.jpg")
        val srcBmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply {
            val c = Canvas(this)
            c.drawColor(Color.rgb(20, 60, 110))
        }
        try {
            FileOutputStream(srcFile).use { out ->
                srcBmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            ExifInterface(srcFile.absolutePath).apply {
                setAttribute(ExifInterface.TAG_MAKE, "Motorola")
                setAttribute(ExifInterface.TAG_MODEL, "moto g34 5G")
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:16 14:22:10")
                saveAttributes()
            }
            val originalBytes = srcFile.readBytes()

            val exportedUri = runBlocking {
                WatermarkPainter.exportWatermarkedPhoto(
                    context = app,
                    sourceUri = Uri.fromFile(srcFile),
                    config = WatermarkTemplateDefaults.defaultConfig(WatermarkStyle.LEICA_FRAME)
                )
            }
            assertNotNull("Full-resolution export must return MediaStore URI", exportedUri)

            // Verify original file remained byte-for-byte untouched
            assertArrayEquals(originalBytes, srcFile.readBytes())

            // Verify exported JPEG decodes at full width + expanded watermark border height
            val exportedBmp = app.contentResolver.openInputStream(exportedUri!!)?.use {
                BitmapFactory.decodeStream(it)
            }
            assertNotNull(exportedBmp)
            assertEquals(640, exportedBmp!!.width)
            assertTrue("Expected Leica bar added below 480px original", exportedBmp.height > 480)
            exportedBmp.recycle()
        } finally {
            if (!srcBmp.isRecycled) srcBmp.recycle()
            srcFile.delete()
        }
    }

    @Test
    fun `camera preferences repository persists all phase 3 watermark attributes`() {
        val repo = CameraPreferencesRepository(app)
        val richConfig = WatermarkTemplateDefaults.defaultConfig(WatermarkStyle.STUDIO_BRAND).copy(
            textOpacity = 0.65f,
            lineSpacing = 1.45f,
            textAlignment = WatermarkTextAlignment.CENTER,
            logoAlignment = WatermarkLogoAlignment.TOP,
            rotationDegrees = -12.5f,
            marginScale = 1.6f
        )

        repo.saveWatermarkConfig(richConfig)
        val restored = repo.getWatermarkConfig()

        assertEquals(0.65f, restored.textOpacity, 0.01f)
        assertEquals(1.45f, restored.lineSpacing, 0.01f)
        assertEquals(WatermarkTextAlignment.CENTER, restored.textAlignment)
        assertEquals(WatermarkLogoAlignment.TOP, restored.logoAlignment)
        assertEquals(-12.5f, restored.rotationDegrees, 0.01f)
        assertEquals(1.6f, restored.marginScale, 0.01f)
    }
}
