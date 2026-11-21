package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import com.example.model.MediaItem
import com.example.model.PhotoDetails
import com.example.ui.components.PhotoPreviewDialog
import com.example.util.HighResPhotoSession
import com.example.util.HighResZoomMath
import com.example.util.RegionDecodeSource
import kotlinx.coroutines.runBlocking
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
import kotlin.math.max
import kotlin.math.min

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h840dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HighResPhotoZoomTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `computeBaseSampleSize and computeTargetSampleSize scale cleanly without redundant levels`() {
        assertEquals(1, HighResZoomMath.computeBaseSampleSize(1600, 1200))
        assertEquals(2, HighResZoomMath.computeBaseSampleSize(4000, 3000))
        assertEquals(4, HighResZoomMath.computeBaseSampleSize(8000, 6000))

        val fitScale = HighResZoomMath.computeFitScale(
            viewportWidth = 1080f,
            viewportHeight = 1920f,
            imageWidth = 4000,
            imageHeight = 3000
        )
        assertEquals(1080f / 4000f, fitScale, 0.0001f)

        // At 1.0x on a 1080px screen, baseSampleSize 2 (2000px wide) already exceeds screen pixels
        assertEquals(2, HighResZoomMath.computeTargetSampleSize(4000, 3000, fitScale, 1.0f))

        // At 2.0x+ zoom, targetSampleSize drops to 1 for full native pixel detail and stays at 1
        assertEquals(1, HighResZoomMath.computeTargetSampleSize(4000, 3000, fitScale, 2.0f))
        assertEquals(1, HighResZoomMath.computeTargetSampleSize(4000, 3000, fitScale, 2.5f))
        assertEquals(1, HighResZoomMath.computeTargetSampleSize(4000, 3000, fitScale, 4.0f))
    }

    @Test
    fun `computeMaxPanOffsets clamps panning strictly to fitted image edges`() {
        // 4:3 image fitted into 1000x2000 viewport -> fitted size is 1000x750
        val maxPan1x = HighResZoomMath.computeMaxPanOffsets(
            viewportWidth = 1000f,
            viewportHeight = 2000f,
            fittedWidth = 1000f,
            fittedHeight = 750f,
            scale = 1f
        )
        assertEquals(0f, maxPan1x.x, 0.01f)
        assertEquals(0f, maxPan1x.y, 0.01f)

        // At 2x zoom: scaled size is 2000x1500. X exceeds 1000 (+/-500), Y still fits inside 2000 (0)
        val maxPan2x = HighResZoomMath.computeMaxPanOffsets(
            viewportWidth = 1000f,
            viewportHeight = 2000f,
            fittedWidth = 1000f,
            fittedHeight = 750f,
            scale = 2f
        )
        assertEquals(500f, maxPan2x.x, 0.01f)
        assertEquals(0f, maxPan2x.y, 0.01f)

        // At 4x zoom: scaled size is 4000x3000 -> X maxPan 1500, Y maxPan 500
        val maxPan4x = HighResZoomMath.computeMaxPanOffsets(
            viewportWidth = 1000f,
            viewportHeight = 2000f,
            fittedWidth = 1000f,
            fittedHeight = 750f,
            scale = 4f
        )
        assertEquals(1500f, maxPan4x.x, 0.01f)
        assertEquals(500f, maxPan4x.y, 0.01f)
    }

    @Test
    fun `displayRectToRawRect and applyOrientationToBitmap align accurately for all 8 EXIF orientations`() {
        val uprightW = 240
        val uprightH = 160
        // Paint 4 asymmetric high-contrast quadrants in upright space
        val uprightRef = Bitmap.createBitmap(uprightW, uprightH, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            val paint = Paint()
            paint.color = Color.RED
            canvas.drawRect(0f, 0f, uprightW / 2f, uprightH / 2f, paint)
            paint.color = Color.GREEN
            canvas.drawRect(uprightW / 2f, 0f, uprightW.toFloat(), uprightH / 2f, paint)
            paint.color = Color.BLUE
            canvas.drawRect(0f, uprightH / 2f, uprightW / 2f, uprightH.toFloat(), paint)
            paint.color = Color.YELLOW
            canvas.drawRect(uprightW / 2f, uprightH / 2f, uprightW.toFloat(), uprightH.toFloat(), paint)
        }

        val allOrientations = listOf(
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270
        )

        val testDisplayRects = listOf(
            Rect(0, 0, 100, 70),       // Top-Left (RED)
            Rect(140, 0, 240, 70),     // Top-Right (GREEN)
            Rect(0, 90, 100, 160),     // Bottom-Left (BLUE)
            Rect(140, 90, 240, 160)    // Bottom-Right (YELLOW)
        )
        val expectedColors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)

        for (orientation in allOrientations) {
            val forwardMatrix = HighResZoomMath.buildOrientationMatrix(orientation)
            val inverseMatrix = Matrix()
            assertTrue("Matrix must be invertible for $orientation", forwardMatrix.invert(inverseMatrix))

            // Synthesize raw sensor bitmap such that applying forwardMatrix recovers uprightRef
            val rawBitmap = Bitmap.createBitmap(uprightRef, 0, 0, uprightW, uprightH, inverseMatrix, false)
            val rawW = rawBitmap.width
            val rawH = rawBitmap.height

            testDisplayRects.forEachIndexed { idx, dispRect ->
                val rawRect = HighResZoomMath.displayRectToRawRect(
                    displayRect = dispRect,
                    rawWidth = rawW,
                    rawHeight = rawH,
                    exifOrientation = orientation
                )
                assertEquals(
                    "Raw rect width/height invalid for orientation $orientation",
                    dispRect.width() * dispRect.height(),
                    rawRect.width() * rawRect.height()
                )

                val croppedRaw = Bitmap.createBitmap(
                    rawBitmap,
                    rawRect.left,
                    rawRect.top,
                    rawRect.width(),
                    rawRect.height()
                )
                val orientedTile = HighResZoomMath.applyOrientationToBitmap(croppedRaw, orientation)
                assertEquals(dispRect.width(), orientedTile.width)
                assertEquals(dispRect.height(), orientedTile.height)
                assertEquals(
                    "Pixel mismatch at center of rect $idx for EXIF orientation $orientation",
                    expectedColors[idx],
                    orientedTile.getPixel(orientedTile.width / 2, orientedTile.height / 2)
                )
                orientedTile.recycle()
            }
            rawBitmap.recycle()
        }

        uprightRef.recycle()
    }

    @Test
    fun `high res rotated JPEG decodes crisp native tiles and serves repeated requests from cache`() {
        // Create raw 2400x3200 JPEG tagged ORIENTATION_ROTATE_90 -> upright display is 3200x2400
        val rawW = 2400
        val rawH = 3200
        val rawBmp = Bitmap.createBitmap(rawW, rawH, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.rgb(30, 30, 30))

            // Place fine alternating black & white 2px bars at raw bottom-left, which rotates
            // clockwise 90 deg to upright Top-Left corner (x in 0..400, y in 0..400).
            val stripePaint = Paint()
            for (x in 0 until 400 step 4) {
                stripePaint.color = Color.WHITE
                canvas.drawRect(x.toFloat(), (rawH - 400).toFloat(), (x + 2).toFloat(), rawH.toFloat(), stripePaint)
                stripePaint.color = Color.BLACK
                canvas.drawRect((x + 2).toFloat(), (rawH - 400).toFloat(), (x + 4).toFloat(), rawH.toFloat(), stripePaint)
            }
        }

        val jpegFile = File(app.cacheDir, "highres_rotated_${System.nanoTime()}.jpg")
        FileOutputStream(jpegFile).use { out ->
            rawBmp.compress(Bitmap.CompressFormat.JPEG, 98, out)
        }
        rawBmp.recycle()

        ExifInterface(jpegFile.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        // 1. Verify default HighResPhotoSession initialization and region decoder wiring on 3200x2400 JPEG
        val defaultSession = runBlocking {
            HighResPhotoSession.open(app, Uri.fromFile(jpegFile))
        }
        assertNotNull("HighResPhotoSession should open high-res JPEG", defaultSession)
        defaultSession!!
        try {
            assertEquals(3200, defaultSession.metadata.displayWidth)
            assertEquals(2400, defaultSession.metadata.displayHeight)
            assertEquals(ExifInterface.ORIENTATION_ROTATE_90, defaultSession.metadata.exifOrientation)
            assertEquals(2, defaultSession.baseSampleSize)
            assertTrue("Base preview must stay memory-bounded <= 2048px", defaultSession.baseBitmap.width <= 2048)
            assertTrue("Region decoder must be enabled for 3200x2400 JPEG", defaultSession.supportsRegionDecoding)

            val promoted = runBlocking { defaultSession.ensureHighResFallback(app, Uri.fromFile(jpegFile)) }
            assertNotNull("High-res fallback decode should promote to full 3200x2400", promoted)
            assertEquals(3200, promoted!!.width)
            assertEquals(2400, promoted.height)
        } finally {
            defaultSession.close()
        }

        // 2. Verify full tile-decoding, EXIF raw->display mapping, 1:1 contrast, and cache reuse
        //    using real decoded JPEG bytes via BitmapFactory (avoids Robolectric's blank-zero BitmapRegionDecoder shadow)
        val rawDecodedJpeg = android.graphics.BitmapFactory.decodeFile(jpegFile.absolutePath)
        assertNotNull(rawDecodedJpeg)
        var decodeCalls = 0
        val session = runBlocking {
            HighResPhotoSession.open(app, Uri.fromFile(jpegFile)) { _, _ ->
                val source = object : RegionDecodeSource {
                    override val isRecycled: Boolean get() = rawDecodedJpeg.isRecycled
                    override fun decodeRegion(rawRect: Rect, sampleSize: Int): Bitmap? {
                        decodeCalls++
                        val crop = Bitmap.createBitmap(
                            rawDecodedJpeg,
                            rawRect.left,
                            rawRect.top,
                            rawRect.width(),
                            rawRect.height()
                        )
                        return if (sampleSize <= 1) {
                            crop
                        } else {
                            val scaled = Bitmap.createScaledBitmap(
                                crop,
                                (crop.width / sampleSize).coerceAtLeast(1),
                                (crop.height / sampleSize).coerceAtLeast(1),
                                true
                            )
                            crop.recycle()
                            scaled
                        }
                    }

                    override fun recycle() {
                        if (!rawDecodedJpeg.isRecycled) rawDecodedJpeg.recycle()
                    }
                }
                source to null
            }
        }!!

        try {
            // Compute visible tiles when zoomed to 4.5x anchored on the Top-Left fine pattern
            val vpW = 1080f
            val vpH = 1920f
            val fitScale = HighResZoomMath.computeFitScale(vpW, vpH, session.metadata.displayWidth, session.metadata.displayHeight)
            val totalScale = fitScale * 4.5f
            val panToTopLeft = Offset(
                x = (session.metadata.displayWidth / 2f - 200f) * totalScale,
                y = (session.metadata.displayHeight / 2f - 200f) * totalScale
            )

            val targetSample = HighResZoomMath.computeTargetSampleSize(
                session.metadata.displayWidth,
                session.metadata.displayHeight,
                fitScale,
                4.5f
            )
            assertEquals(1, targetSample)

            val visibleTiles = HighResZoomMath.computeVisibleTiles(
                displayWidth = session.metadata.displayWidth,
                displayHeight = session.metadata.displayHeight,
                viewportWidth = vpW,
                viewportHeight = vpH,
                fitScale = fitScale,
                scale = 4.5f,
                offset = panToTopLeft,
                sampleSize = targetSample
            )
            assertTrue("Should request visible tiles at 4.5x zoom", visibleTiles.isNotEmpty())
            assertTrue("Should only decode visible subset rather than entire grid", visibleTiles.size < 16)

            val decodedFirst = runBlocking { session.loadMissingTiles(visibleTiles) }
            assertEquals(visibleTiles.size, decodedFirst.size)
            assertEquals(visibleTiles.size, decodeCalls)

            // Verify the top-left (col=0, row=0) tile actually contains sharp alternating luminance bars
            val topLeftTile = decodedFirst.firstOrNull { it.key.col == 0 && it.key.row == 0 }
            assertNotNull("Top-left tile must be among visible decoded tiles", topLeftTile)
            var minLuma = 255
            var maxLuma = 0
            for (y in 20 until 120) {
                val px = topLeftTile!!.bitmap.getPixel(50, y)
                val luma = (Color.red(px) + Color.green(px) + Color.blue(px)) / 3
                minLuma = min(minLuma, luma)
                maxLuma = max(maxLuma, luma)
            }
            assertTrue(
                "Decoded 1:1 region tile must retain sharp black/white contrast (spread=${maxLuma - minLuma})",
                (maxLuma - minLuma) > 180
            )

            // Second call for same visible region must be served 100% from cache (0 newly decoded)
            val decodedSecond = runBlocking { session.loadMissingTiles(visibleTiles) }
            assertTrue("Cached tiles must not be re-decoded", decodedSecond.isEmpty())
            assertEquals("Cache hit must not invoke decodeRegion again", visibleTiles.size, decodeCalls)
            assertFalse(session.getCachedTilesSnapshot(targetSample).isEmpty())
        } finally {
            session.close()
            jpegFile.delete()
        }
    }

    @Test
    fun `PhotoPreviewDialog supports double-tap, zoomed pan, pinch-to-zoom, and pager swipe cleanly`() {
        val file1 = File(app.cacheDir, "preview_zoom_1_${System.nanoTime()}.jpg")
        val file2 = File(app.cacheDir, "preview_zoom_2_${System.nanoTime()}.jpg")
        listOf(file1 to Color.DKGRAY, file2 to Color.BLUE).forEach { (file, bg) ->
            val bmp = Bitmap.createBitmap(2400, 1800, Bitmap.Config.ARGB_8888).apply {
                val canvas = Canvas(this)
                canvas.drawColor(bg)
                val p = Paint().apply { color = Color.WHITE; strokeWidth = 6f }
                for (i in 0 until 2400 step 40) {
                    canvas.drawLine(i.toFloat(), 0f, i.toFloat(), 1800f, p)
                }
            }
            FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 92, out) }
            bmp.recycle()
        }

        val items = listOf(
            MediaItem(1L, Uri.fromFile(file1), false, file1.name, 100L, "image/jpeg", file1.length(), 2400, 1800, 0L),
            MediaItem(2L, Uri.fromFile(file2), false, file2.name, 200L, "image/jpeg", file2.length(), 2400, 1800, 0L)
        )
        val currentIndexState = androidx.compose.runtime.mutableIntStateOf(0)

        try {
            composeTestRule.setContent {
                PhotoPreviewDialog(
                    items = items,
                    currentIndex = currentIndexState.intValue,
                    onIndexChange = { currentIndexState.intValue = it },
                    onDismiss = {},
                    onDelete = {},
                    onGetDetails = { item ->
                        PhotoDetails(
                            uri = item.uri,
                            title = item.displayName,
                            dateTaken = "Today",
                            dimensions = "2400 x 1800",
                            fileSize = "1.2 MB",
                            path = item.uri.path ?: "",
                            mimeType = item.mimeType
                        )
                    },
                    onEditPhoto = {},
                    onEditWatermark = {}
                )
            }

            composeTestRule.waitUntil(timeoutMillis = 10_000L) {
                try {
                    composeTestRule.onNodeWithTag("preview_zoomable_photo").assertIsDisplayed()
                    true
                } catch (_: AssertionError) {
                    false
                }
            }
            composeTestRule.waitForIdle()

            // Verify Liquid Glass Chrome controls remain intact
            composeTestRule.onNodeWithTag("preview_close_button").assertIsDisplayed()
            composeTestRule.onNodeWithTag("preview_edit_watermark_button").assertIsDisplayed()
            composeTestRule.onNodeWithTag("preview_edit_button").assertIsDisplayed()
            composeTestRule.onNodeWithTag("gallery_filmstrip_row").assertIsDisplayed()

            // Double-tap to zoom in -> single-finger pan -> double-tap back to 1.0x
            composeTestRule.onNodeWithTag("preview_zoomable_photo").performTouchInput {
                doubleClick(center)
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("preview_zoomable_photo").performTouchInput {
                swipeLeft(startX = right * 0.7f, endX = left + width * 0.3f)
            }
            composeTestRule.waitForIdle()
            // Zoomed pan must NOT advance pager to page 1
            assertEquals(0, currentIndexState.intValue)

            composeTestRule.onNodeWithTag("preview_zoomable_photo").performTouchInput {
                doubleClick(center)
            }
            composeTestRule.waitForIdle()

            // Pinch-to-zoom smoothly without crash
            composeTestRule.onNodeWithTag("preview_zoomable_photo").performTouchInput {
                pinch(
                    start0 = Offset(centerX - 40f, centerY),
                    end0 = Offset(centerX - 180f, centerY),
                    start1 = Offset(centerX + 40f, centerY),
                    end1 = Offset(centerX + 180f, centerY)
                )
            }
            composeTestRule.waitForIdle()

            // Reset to 1x via double-tap and verify horizontal swipe passes through to HorizontalPager
            composeTestRule.onNodeWithTag("preview_zoomable_photo").performTouchInput {
                doubleClick(center)
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("gallery_horizontal_pager").performTouchInput {
                swipeLeft()
            }
            composeTestRule.waitForIdle()
            assertEquals(1, currentIndexState.intValue)

            composeTestRule.onNodeWithTag("gallery_horizontal_pager").performTouchInput {
                swipeRight()
            }
            composeTestRule.waitForIdle()
            assertEquals(0, currentIndexState.intValue)
        } finally {
            file1.delete()
            file2.delete()
        }
    }
}
