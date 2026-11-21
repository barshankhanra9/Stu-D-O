package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.model.MediaItem
import com.example.model.PhotoDetails
import com.example.ui.components.PhotoPreviewDialog
import com.example.util.PhotoMetadataExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoViewerImprovementsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun createTestJpeg(
        name: String,
        width: Int = 1600,
        height: Int = 1200,
        configureExif: (ExifInterface.() -> Unit)? = null
    ): File {
        val file = File(app.cacheDir, "${name}_${System.nanoTime()}.jpg")
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.rgb(22, 34, 52))
            val paint = Paint().apply { color = Color.rgb(245, 195, 68); strokeWidth = 4f }
            canvas.drawLine(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        bmp.recycle()
        if (configureExif != null) {
            ExifInterface(file.absolutePath).apply {
                configureExif()
                saveAttributes()
            }
        }
        return file
    }

    private fun createCleanPng(name: String, width: Int = 800, height: Int = 600): File {
        val file = File(app.cacheDir, "${name}_${System.nanoTime()}.png")
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.DKGRAY)
        }
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bmp.recycle()
        return file
    }

    @Test
    fun `PhotoMetadataExtractor extracts complete EXIF, camera, capture, GPS, and ignores missing placeholders`() {
        val jpegFile = createTestJpeg("exif_rich", width = 1200, height = 1600) {
            setAttribute(ExifInterface.TAG_MAKE, "Motorola")
            setAttribute(ExifInterface.TAG_MODEL, "moto g34 5G")
            setAttribute("LensMake", "Motorola")
            setAttribute("LensModel", "24mm f/1.8 Main")
            setAttribute(ExifInterface.TAG_F_NUMBER, "1.8")
            setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.008")
            setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, "200")
            setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "450/100")
            setAttribute("FocalLengthIn35mmFilm", "26")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:17 14:30:15")
            setAttribute("ExposureBiasValue", "-3/10")
            setAttribute(ExifInterface.TAG_WHITE_BALANCE, "0")
            setAttribute(ExifInterface.TAG_FLASH, "16")
            setAttribute("SceneCaptureType", "1")
            setAttribute("Software", "StuDO Camera")
            setAttribute("Artist", "Alex")
            // 37.7749 N, 122.4194 W
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "37/1,46/1,2964/100")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "122/1,25/1,984/100")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "42/1")
            setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, "0")
        }

        val pngFile = createCleanPng("no_exif", width = 640, height = 480)

        try {
            val richItem = MediaItem(
                id = 1L,
                uri = Uri.fromFile(jpegFile),
                isVideo = false,
                displayName = jpegFile.name,
                dateAdded = 0L,
                mimeType = "image/jpeg",
                sizeBytes = jpegFile.length(),
                width = 1200,
                height = 1600,
                durationMs = 0L
            )
            val richMeta = runBlocking {
                PhotoMetadataExtractor.extract(
                    context = app,
                    item = richItem,
                    fallbackDetails = PhotoDetails(
                        uri = richItem.uri,
                        title = richItem.displayName,
                        dateTaken = "Recent",
                        dimensions = "Standard resolution",
                        fileSize = "Standard",
                        path = "/sdcard/DCIM",
                        mimeType = "image/jpeg"
                    )
                )
            }

            assertEquals("JPEG", richMeta.formatBadge)
            assertTrue("Expected f/1.8 in ${richMeta.heroHighlights}", richMeta.heroHighlights.contains("f/1.8"))
            assertTrue("Expected 1/125 s in ${richMeta.heroHighlights}", richMeta.heroHighlights.contains("1/125 s"))
            assertTrue("Expected ISO 200 in ${richMeta.heroHighlights}", richMeta.heroHighlights.contains("ISO 200"))
            assertTrue("Expected 4.5 mm in ${richMeta.heroHighlights}", richMeta.heroHighlights.contains("4.5 mm"))

            val imageMap = richMeta.imageFields.associate { it.label to it.value }
            assertEquals(jpegFile.name, imageMap["File Name"])
            assertEquals("JPEG (image/jpeg)", imageMap["Format"])
            assertEquals("1200 × 1600 (1.9 MP)", imageMap["Resolution"])
            assertNotNull(imageMap["Captured"])

            val cameraMap = richMeta.cameraFields.associate { it.label to it.value }
            assertEquals("Motorola", cameraMap["Manufacturer"])
            assertEquals("moto g34 5G", cameraMap["Model"])
            assertEquals("Motorola 24mm f/1.8 Main", cameraMap["Lens"])
            assertEquals("StuDO Camera", cameraMap["Software"])

            val captureMap = richMeta.captureFields.associate { it.label to it.value }
            assertEquals("f/1.8", captureMap["Aperture"])
            assertEquals("1/125 s", captureMap["Shutter Speed"])
            assertEquals("ISO 200", captureMap["ISO"])
            assertEquals("4.5 mm (26 mm eq.)", captureMap["Focal Length"])
            assertEquals("-0.3 EV", captureMap["Exposure Bias"])
            assertEquals("Auto", captureMap["White Balance"])
            assertEquals("Off (Compulsory)", captureMap["Flash"])

            assertNotNull("GPS must be extracted when coordinates exist", richMeta.gps)
            assertEquals(37.7749, richMeta.gps!!.latitude, 0.002)
            assertEquals(-122.4194, richMeta.gps!!.longitude, 0.002)
            assertEquals(42.0, richMeta.gps!!.altitudeMeters!!, 0.5)
            val locMap = richMeta.locationFields.associate { it.label to it.value }
            assertNotNull(locMap["Coordinates"])
            assertNotNull(locMap["DMS"])
            assertEquals("42.0 m", locMap["Altitude"])

            val otherMap = richMeta.otherFields.associate { it.label to it.value }
            assertEquals("Landscape", otherMap["Scene Type"])
            assertEquals("Alex", otherMap["Artist"])

            // Clean PNG without EXIF must NEVER fabricate camera/capture/location fields or placeholder strings
            val cleanItem = MediaItem(
                id = 2L,
                uri = Uri.fromFile(pngFile),
                isVideo = false,
                displayName = pngFile.name,
                dateAdded = 0L,
                mimeType = "image/png",
                sizeBytes = pngFile.length(),
                width = 640,
                height = 480,
                durationMs = 0L
            )
            val cleanMeta = runBlocking {
                PhotoMetadataExtractor.extract(
                    context = app,
                    item = cleanItem,
                    fallbackDetails = PhotoDetails(
                        uri = cleanItem.uri,
                        title = cleanItem.displayName,
                        dateTaken = "Recent",
                        dimensions = "Standard resolution",
                        fileSize = "Standard",
                        path = "Unknown",
                        mimeType = "image/png"
                    )
                )
            }
            assertEquals("PNG", cleanMeta.formatBadge)
            assertTrue("Camera fields must be omitted when missing", cleanMeta.cameraFields.isEmpty())
            assertTrue("Capture fields must be omitted when missing", cleanMeta.captureFields.isEmpty())
            assertNull("GPS must be null when absent", cleanMeta.gps)
            assertTrue("Location fields must be omitted when missing", cleanMeta.locationFields.isEmpty())
            assertTrue("Additional EXIF fields must be omitted when absent", cleanMeta.otherFields.isEmpty())
            val allValues = cleanMeta.imageFields.map { it.value }
            assertFalse(allValues.contains("Recent"))
            assertFalse(allValues.contains("Standard resolution"))
            assertFalse(allValues.contains("Standard"))
            assertFalse(allValues.contains("Unknown"))
        } finally {
            jpegFile.delete()
            pngFile.delete()
        }
    }

    @Test
    fun `PhotoMetadataExtractor pure formatters and ISO-6709 GPS parsing work accurately`() {
        assertEquals("512 B", PhotoMetadataExtractor.formatBytes(512))
        assertEquals("2.5 KB", PhotoMetadataExtractor.formatBytes(2560))
        assertEquals("3.50 MB", PhotoMetadataExtractor.formatBytes(3670016))
        assertEquals("f/1.8", PhotoMetadataExtractor.formatAperture(1.8, null))
        assertEquals("f/2", PhotoMetadataExtractor.formatAperture(2.0, null))
        assertEquals("1/1000 s", PhotoMetadataExtractor.formatExposureTime(0.001, null))
        assertEquals("2 s", PhotoMetadataExtractor.formatExposureTime(2.0, null))
        assertEquals("4.5 mm (26 mm eq.)", PhotoMetadataExtractor.formatFocalLength(4.5, 26)?.first)
        assertEquals("+0.7 EV", PhotoMetadataExtractor.formatExposureBias(0.7))
        assertEquals("0.0 EV", PhotoMetadataExtractor.formatExposureBias(0.0))

        val isoGps = PhotoMetadataExtractor.parseIso6709Location("+35.6762+139.6503+040.5/")
        assertNotNull(isoGps)
        assertEquals(35.6762, isoGps!!.latitude, 0.0001)
        assertEquals(139.6503, isoGps.longitude, 0.0001)
        assertEquals(40.5, isoGps.altitudeMeters!!, 0.01)
        assertNull(PhotoMetadataExtractor.parseIso6709Location("+0.0000+0.0000/"))
    }

    @Test
    fun `Redesigned Top Toolbar fits without overlap or clipping on 360dp compact width and preserves all actions`() {
        val file1 = createTestJpeg("toolbar_1")
        var closeClicks = 0
        var editClicks = 0
        var watermarkClicks = 0
        var deletedItem: MediaItem? = null

        val items = List(12) { idx ->
            MediaItem(
                id = idx.toLong(),
                uri = Uri.fromFile(file1),
                isVideo = false,
                displayName = "IMG_${idx + 1}.jpg",
                dateAdded = 0L,
                mimeType = "image/jpeg",
                sizeBytes = file1.length(),
                width = 1600,
                height = 1200,
                durationMs = 0L
            )
        }

        try {
            composeTestRule.setContent {
                Box(modifier = Modifier.size(width = 360.dp, height = 780.dp)) {
                    PhotoPreviewDialog(
                        items = items,
                        currentIndex = 11,
                        onIndexChange = {},
                        onDismiss = { closeClicks++ },
                        onDelete = { deletedItem = it },
                        onGetDetails = { item ->
                            PhotoDetails(
                                uri = item.uri,
                                title = item.displayName,
                                dateTaken = "Recent",
                                dimensions = "1600 × 1200",
                                fileSize = "200 KB",
                                path = item.uri.path ?: "",
                                mimeType = "image/jpeg"
                            )
                        },
                        onEditPhoto = { editClicks++ },
                        onEditWatermark = { watermarkClicks++ }
                    )
                }
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("preview_top_toolbar").assertIsDisplayed()
            composeTestRule.onNodeWithTag("preview_top_left_capsule").assertIsDisplayed()
            composeTestRule.onNodeWithTag("preview_top_right_capsule").assertIsDisplayed()
            composeTestRule.onNodeWithText("12 / 12").assertIsDisplayed()

            val toolbarBounds = composeTestRule.onNodeWithTag("preview_top_toolbar").fetchSemanticsNode().boundsInRoot
            val leftBounds = composeTestRule.onNodeWithTag("preview_top_left_capsule").fetchSemanticsNode().boundsInRoot
            val rightBounds = composeTestRule.onNodeWithTag("preview_top_right_capsule").fetchSemanticsNode().boundsInRoot

            assertTrue(
                "Left capsule right (${leftBounds.right}) must not overlap right capsule left (${rightBounds.left})",
                leftBounds.right <= rightBounds.left + 0.5f
            )
            assertTrue(
                "Right capsule (${rightBounds.right}) must fit within toolbar bounds (${toolbarBounds.right})",
                rightBounds.right <= toolbarBounds.right + 0.5f
            )

            // Verify all action buttons are clickable and distinct
            composeTestRule.onNodeWithTag("preview_edit_watermark_button").performClick()
            composeTestRule.onNodeWithTag("preview_edit_button").performClick()
            composeTestRule.onNodeWithTag("preview_close_button").performClick()
            assertEquals(1, watermarkClicks)
            assertEquals(1, editClicks)
            assertEquals(1, closeClicks)

            // Verify Delete button opens confirmation & invokes onDelete
            composeTestRule.onNodeWithTag("preview_delete_button").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Move to StuDO Bin?").assertIsDisplayed()
            composeTestRule.onNodeWithText("Move to Bin").performClick()
            composeTestRule.waitForIdle()
            assertEquals(items[11].id, deletedItem?.id)
        } finally {
            file1.delete()
        }
    }

    @Test
    fun `Info button opens scrollable Liquid Glass Metadata Sheet with real EXIF & GPS map and omits missing data`() {
        val richFile = createTestJpeg("sheet_rich", width = 1600, height = 1200) {
            setAttribute(ExifInterface.TAG_MAKE, "Samsung")
            setAttribute(ExifInterface.TAG_MODEL, "Galaxy Tab A9+")
            setAttribute("LensModel", "Fixed f/2.0")
            setAttribute(ExifInterface.TAG_F_NUMBER, "2.0")
            setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.01")
            setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, "100")
            setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "350/100")
            setAttribute("Software", "Android Camera")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "51/1,30/1,2600/100")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "0/1,7/1,3900/100")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
        }
        val cleanFile = createCleanPng("sheet_clean", width = 500, height = 400)

        val items = listOf(
            MediaItem(1L, Uri.fromFile(richFile), false, richFile.name, 0L, "image/jpeg", richFile.length(), 1600, 1200, 0L),
            MediaItem(2L, Uri.fromFile(cleanFile), false, cleanFile.name, 0L, "image/png", cleanFile.length(), 500, 400, 0L)
        )
        val indexState = mutableIntStateOf(0)

        try {
            composeTestRule.setContent {
                PhotoPreviewDialog(
                    items = items,
                    currentIndex = indexState.intValue,
                    onIndexChange = { indexState.intValue = it },
                    onDismiss = {},
                    onDelete = {},
                    onGetDetails = { item ->
                        PhotoDetails(
                            uri = item.uri,
                            title = item.displayName,
                            dateTaken = "Recent",
                            dimensions = "Standard resolution",
                            fileSize = "Standard",
                            path = "",
                            mimeType = item.mimeType
                        )
                    }
                )
            }
            composeTestRule.waitForIdle()

            // Tap Info button on EXIF-rich photo
            composeTestRule.onNodeWithTag("preview_info_button").performClick()
            composeTestRule.waitUntil(timeoutMillis = 10_000L) {
                composeTestRule.onAllNodesWithText("Galaxy Tab A9+").fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("photo_details_sheet").assertIsDisplayed()
            composeTestRule.onNodeWithTag("details_hero_highlights").assertIsDisplayed()
            composeTestRule.onNodeWithTag("details_section_image").assertIsDisplayed()
            composeTestRule.onNodeWithTag("details_section_camera").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText("Samsung").assertIsDisplayed()
            composeTestRule.onNodeWithText("Galaxy Tab A9+").assertIsDisplayed()

            // Scroll to Capture Settings & Location GPS Map
            composeTestRule.onNodeWithTag("details_section_capture").performScrollTo().assertIsDisplayed()
            composeTestRule.onAllNodesWithText("ISO 100").assertCountEquals(2)
            composeTestRule.onNodeWithTag("details_section_location").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithTag("details_gps_map_preview").assertIsDisplayed()
            composeTestRule.onNodeWithTag("details_open_maps_button").assertIsDisplayed()

            // Close sheet cleanly via close button
            composeTestRule.onNodeWithTag("details_sheet_close_button").performClick()
            composeTestRule.waitForIdle()
            assertTrue(composeTestRule.onAllNodesWithText("Photo Details").fetchSemanticsNodes().isEmpty())

            // Switch to clean PNG without EXIF and verify missing sections are never invented
            indexState.intValue = 1
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("preview_info_button").performClick()
            composeTestRule.waitUntil(timeoutMillis = 10_000L) {
                composeTestRule.onAllNodesWithText("500 × 400", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("details_section_image").assertIsDisplayed()
            assertTrue(composeTestRule.onAllNodesWithText("Camera").fetchSemanticsNodes().isEmpty())
            assertTrue(composeTestRule.onAllNodesWithText("Capture Settings").fetchSemanticsNodes().isEmpty())
            assertTrue(composeTestRule.onAllNodesWithText("Location").fetchSemanticsNodes().isEmpty())
            assertTrue(composeTestRule.onAllNodesWithText("Open in Maps").fetchSemanticsNodes().isEmpty())
        } finally {
            richFile.delete()
            cleanFile.delete()
        }
    }

    @Test
    fun `Double-tap toggles smoothly between fit-to-screen and zoomed in, including after pinch-to-zoom`() {
        val file1 = createTestJpeg("dbltap_1", width = 2000, height = 1500)
        val file2 = createTestJpeg("dbltap_2", width = 2000, height = 1500)
        val items = listOf(
            MediaItem(1L, Uri.fromFile(file1), false, file1.name, 0L, "image/jpeg", file1.length(), 2000, 1500, 0L),
            MediaItem(2L, Uri.fromFile(file2), false, file2.name, 0L, "image/jpeg", file2.length(), 2000, 1500, 0L)
        )
        val indexState = mutableIntStateOf(0)

        try {
            composeTestRule.setContent {
                PhotoPreviewDialog(
                    items = items,
                    currentIndex = indexState.intValue,
                    onIndexChange = { indexState.intValue = it },
                    onDismiss = {},
                    onDelete = {},
                    onGetDetails = { item ->
                        PhotoDetails(item.uri, item.displayName, "", "2000 × 1500", "200 KB", "", "image/jpeg")
                    }
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

            // Initial state: Fit to screen (1.0x)
            composeTestRule.onNodeWithTag("preview_zoomable_photo").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Fit to screen")
            )

            // First double-tap (off-center): zooms in smoothly to 2.5x
            composeTestRule.onNodeWithTag("preview_zoomable_photo").performTouchInput {
                doubleClick(Offset(width * 0.75f, height * 0.35f))
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("preview_zoomable_photo").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoomed 2.5x")
            )

            // Zoomed single-finger pan stays zoomed and never triggers page change
            composeTestRule.onNodeWithTag("preview_zoomable_photo").performTouchInput {
                swipeLeft(startX = width * 0.7f, endX = width * 0.3f)
            }
            composeTestRule.waitForIdle()
            assertEquals(0, indexState.intValue)

            // Second double-tap: returns smoothly to fit-to-screen 1.0x!
            composeTestRule.onNodeWithTag("preview_zoomable_photo").performTouchInput {
                doubleClick(center)
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("preview_zoomable_photo").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Fit to screen")
            )

            // Horizontal swipe now cleanly advances pager back and forth
            composeTestRule.onNodeWithTag("gallery_horizontal_pager").performTouchInput {
                swipeLeft()
            }
            composeTestRule.waitForIdle()
            assertEquals(1, indexState.intValue)

            composeTestRule.onNodeWithTag("gallery_horizontal_pager").performTouchInput {
                swipeRight()
            }
            composeTestRule.waitForIdle()
            assertEquals(0, indexState.intValue)

            // Pinch-to-zoom in -> double-tap returns smoothly to fit-to-screen 1.0x
            composeTestRule.onNodeWithTag("preview_zoomable_photo").performTouchInput {
                pinch(
                    start0 = Offset(centerX - 40f, centerY),
                    end0 = Offset(centerX - 180f, centerY),
                    start1 = Offset(centerX + 40f, centerY),
                    end1 = Offset(centerX + 180f, centerY)
                )
            }
            composeTestRule.waitForIdle()
            val zoomedState = composeTestRule.onNodeWithTag("preview_zoomable_photo")
                .fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription]
            assertTrue("Expected zoomed state after pinch, got: $zoomedState", zoomedState.startsWith("Zoomed "))

            composeTestRule.onNodeWithTag("preview_zoomable_photo").performTouchInput {
                doubleClick(center)
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("preview_zoomable_photo").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Fit to screen")
            )
        } finally {
            file1.delete()
            file2.delete()
        }
    }

    @Test
    @Config(qualifiers = "w840dp-h540dp-xhdpi")
    fun `Redesigned Top Toolbar shows media badge and keeps balanced spacing on tablet width`() {
        val file1 = createTestJpeg("tablet_toolbar")
        val items = List(3) { idx ->
            MediaItem(
                id = (idx + 10).toLong(),
                uri = Uri.fromFile(file1),
                isVideo = false,
                displayName = "TAB_${idx + 1}.jpg",
                dateAdded = 0L,
                mimeType = "image/jpeg",
                sizeBytes = file1.length(),
                width = 1600,
                height = 1200,
                durationMs = 0L
            )
        }

        try {
            composeTestRule.setContent {
                PhotoPreviewDialog(
                    items = items,
                    currentIndex = 0,
                    onIndexChange = {},
                    onDismiss = {},
                    onDelete = {},
                    onGetDetails = { item ->
                        PhotoDetails(item.uri, item.displayName, "", "1600 × 1200", "200 KB", "", "image/jpeg")
                    },
                    onEditPhoto = {},
                    onEditWatermark = {}
                )
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("preview_top_toolbar").assertIsDisplayed()
            composeTestRule.onNodeWithText("1 / 3").assertIsDisplayed()
            composeTestRule.onNodeWithText("PHOTO").assertIsDisplayed()
            composeTestRule.onNodeWithTag("preview_edit_watermark_button").assertIsDisplayed()
            composeTestRule.onNodeWithTag("preview_edit_button").assertIsDisplayed()
            composeTestRule.onNodeWithTag("preview_info_button").assertIsDisplayed()
            composeTestRule.onNodeWithTag("preview_share_button").assertIsDisplayed()
            composeTestRule.onNodeWithTag("preview_delete_button").assertIsDisplayed()

            val toolbarBounds = composeTestRule.onNodeWithTag("preview_top_toolbar").fetchSemanticsNode().boundsInRoot
            val leftBounds = composeTestRule.onNodeWithTag("preview_top_left_capsule").fetchSemanticsNode().boundsInRoot
            val rightBounds = composeTestRule.onNodeWithTag("preview_top_right_capsule").fetchSemanticsNode().boundsInRoot
            assertTrue(leftBounds.right < rightBounds.left)
            assertTrue(rightBounds.right <= toolbarBounds.right + 0.5f)
        } finally {
            file1.delete()
        }
    }
}
