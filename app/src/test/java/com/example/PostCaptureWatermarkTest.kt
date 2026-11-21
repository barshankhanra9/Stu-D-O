package com.example

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.data.CameraPreferencesRepository
import com.example.data.PrivateOriginalStorageManager
import com.example.data.ResolvedOriginalResult
import com.example.model.CameraFilter
import com.example.model.CameraUiState
import com.example.model.MediaItem
import com.example.model.PhotoDetails
import com.example.model.PostCaptureWatermarkTarget
import com.example.model.WatermarkCacheLimit
import com.example.model.WatermarkConfig
import com.example.model.WatermarkLogoType
import com.example.model.WatermarkPosition
import com.example.model.WatermarkStyle
import com.example.ui.components.CameraSettingsDialog
import com.example.ui.components.PhotoPreviewDialog
import com.example.ui.components.WatermarkPreviewCache
import com.example.ui.components.WatermarkStudioScreen
import com.example.util.CameraMetadataUtil
import com.example.util.MemoryMappedBufferUtil
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
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h840dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PostCaptureWatermarkTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        WatermarkPreviewCache.clear()
        WatermarkPainter.clearSampleCache()
        PrivateOriginalStorageManager(app).clearAllOriginals()
    }

    private fun createSolidJpegFile(
        file: File,
        width: Int = 240,
        height: Int = 180,
        color: Int = Color.rgb(30, 110, 210),
        addExif: Boolean = true
    ): File {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
            val canvas = android.graphics.Canvas(this)
            val accentPaint = android.graphics.Paint().apply {
                this.color = Color.rgb(220, 180, 90)
            }
            canvas.drawRect(0f, height * 0.55f, width.toFloat(), height.toFloat(), accentPaint)
        }
        val compressed = FileOutputStream(file).use { out ->
            val ok = bmp.compress(Bitmap.CompressFormat.JPEG, 96, out)
            out.flush()
            ok
        }
        bmp.recycle()
        assertTrue("Expected JPEG fixture compression to succeed", compressed)
        assertTrue("Expected non-empty JPEG fixture", file.length() > 0L)
        if (addExif) {
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, "400")
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.005")
            exif.setAttribute(ExifInterface.TAG_F_NUMBER, "1.8")
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "471/100")
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:15 10:30:00")
            exif.saveAttributes()
        }
        return file
    }

    private class FakeMediaContentProvider(private val rootDir: File) : ContentProvider() {
        private val nextId = AtomicInteger(100)
        private val filesByUri = LinkedHashMap<String, File>()

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor = MatrixCursor(projection ?: arrayOf(MediaStore.MediaColumns._ID))

        override fun getType(uri: Uri): String = "image/jpeg"

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val id = nextId.incrementAndGet()
            val targetFile = File(rootDir, "gallery_$id.jpg")
            if (!targetFile.exists()) targetFile.createNewFile()
            val insertedUri = Uri.parse("content://media/external/images/media/$id")
            filesByUri[insertedUri.toString()] = targetFile
            return insertedUri
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = filesByUri.getOrPut(uri.toString()) {
                val id = uri.lastPathSegment ?: nextId.incrementAndGet().toString()
                File(rootDir, "gallery_$id.jpg").apply { if (!exists()) createNewFile() }
            }
            val parsedMode = ParcelFileDescriptor.parseMode(mode)
            return ParcelFileDescriptor.open(file, parsedMode)
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val removed = filesByUri.remove(uri.toString())
            return if (removed != null && removed.delete()) 1 else 0
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = if (filesByUri.containsKey(uri.toString())) 1 else 0

        fun fileFor(uri: Uri): File? = filesByUri[uri.toString()]
    }

    @Test
    fun `preferences persist post-capture watermark toggle and cache limit configuration`() {
        val repo = CameraPreferencesRepository(app)
        assertFalse(repo.isPostCaptureWatermarkEditingEnabled())
        assertFalse(repo.isWatermarkCacheLimitConfigured())
        assertEquals(WatermarkCacheLimit.GB_1, repo.getWatermarkCacheLimit())

        repo.savePostCaptureWatermarkEditingEnabled(true)
        repo.saveWatermarkCacheLimit(WatermarkCacheLimit.GB_5)
        repo.markWatermarkCacheLimitConfigured()

        val reloadedRepo = CameraPreferencesRepository(app)
        assertTrue(reloadedRepo.isPostCaptureWatermarkEditingEnabled())
        assertTrue(reloadedRepo.isWatermarkCacheLimitConfigured())
        assertEquals(WatermarkCacheLimit.GB_5, reloadedRepo.getWatermarkCacheLimit())
        assertEquals(5L * 1024L * 1024L * 1024L, reloadedRepo.getWatermarkCacheLimit().limitBytes)

        assertEquals(
            listOf("250 MB", "500 MB", "1 GB", "2 GB", "5 GB", "10 GB"),
            WatermarkCacheLimit.entries.map { it.displayName }
        )
    }

    @Test
    fun `capture processing saves clean private original only when watermark and post-capture retain are active`() {
        val storage = PrivateOriginalStorageManager(app)
        assertEquals(0, storage.getRetainedOriginalsCount())
        val nomedia = File(File(app.filesDir, "private_originals"), ".nomedia")
        assertTrue("Private directory must contain .nomedia", nomedia.exists())

        val sourceFile = createSolidJpegFile(File(app.cacheDir, "capture_src_${System.nanoTime()}.jpg"))
        val rawJpegBytes = sourceFile.readBytes()
        val wmConfig = WatermarkConfig(
            isEnabled = true,
            style = WatermarkStyle.LEICA_FRAME,
            customText = "TEST WATERMARK",
            position = WatermarkPosition.FRAME_BORDER
        )

        // 1. Watermark ON + Post-Capture Retain OFF -> saves gallery photo without private original
        val outOffFile = File(app.cacheDir, "capture_off_${System.nanoTime()}.jpg")
        var retainedOffId: String? = null
        val bytesOff = MemoryMappedBufferUtil.processAndSerializeJpeg(
            context = app,
            targetUri = Uri.fromFile(outOffFile),
            sourceBuffer = ByteBuffer.wrap(rawJpegBytes),
            filter = CameraFilter.NATURAL,
            mirrorSelfie = false,
            watermarkConfig = wmConfig,
            isSaveLocation = false,
            activeFocalLengthMm = 4.71f,
            privateOriginalStorage = storage,
            retainCleanOriginal = false,
            onOriginalRetained = { retainedOffId = it.originalId }
        )
        assertTrue(bytesOff > 0L)
        assertEquals(null, retainedOffId)
        assertEquals(0, storage.getRetainedOriginalsCount())

        // 2. Watermark OFF + retainCleanOriginal=false -> no private original
        val outWmDisabled = File(app.cacheDir, "capture_wm_disabled_${System.nanoTime()}.jpg")
        val bytesWmOff = MemoryMappedBufferUtil.processAndSerializeJpeg(
            context = app,
            targetUri = Uri.fromFile(outWmDisabled),
            sourceBuffer = ByteBuffer.wrap(rawJpegBytes),
            filter = CameraFilter.NATURAL,
            mirrorSelfie = false,
            watermarkConfig = wmConfig.copy(isEnabled = false),
            isSaveLocation = false,
            activeFocalLengthMm = 4.71f,
            privateOriginalStorage = storage,
            retainCleanOriginal = false
        )
        assertTrue(bytesWmOff > 0L)
        assertEquals(0, storage.getRetainedOriginalsCount())

        // 3. Watermark ON + retainCleanOriginal=true -> private unwatermarked original saved and linked
        val outWmOn = File(app.cacheDir, "capture_wm_on_${System.nanoTime()}.jpg")
        var retainedId: String? = null
        val bytesOn = MemoryMappedBufferUtil.processAndSerializeJpeg(
            context = app,
            targetUri = Uri.fromFile(outWmOn),
            sourceBuffer = ByteBuffer.wrap(rawJpegBytes),
            filter = CameraFilter.NATURAL,
            mirrorSelfie = false,
            watermarkConfig = wmConfig,
            isSaveLocation = false,
            activeFocalLengthMm = 4.71f,
            privateOriginalStorage = storage,
            retainCleanOriginal = true,
            onOriginalRetained = { retainedId = it.originalId }
        )
        assertTrue(bytesOn > 0L)
        assertNotNull(retainedId)
        assertEquals(1, storage.getRetainedOriginalsCount())
        storage.linkGalleryOutput(Uri.fromFile(outWmOn), retainedId!!, wmConfig)
        storage.unmarkOriginalActive(retainedId!!)

        // Leica frame adds bottom border so gallery bitmap height > original clean height (240x180)
        val galleryBmp = BitmapFactory.decodeFile(outWmOn.absolutePath)
        assertTrue("Gallery watermarked bitmap should include frame border", galleryBmp.height > 180)
        galleryBmp.recycle()

        // Verify persisted clean original via fresh storage instance (simulates app restart)
        val restartedStorage = PrivateOriginalStorageManager(app)
        val resolved = restartedStorage.resolveForGalleryUri(Uri.fromFile(outWmOn))
        assertTrue(resolved is ResolvedOriginalResult.Available)
        val avail = resolved as ResolvedOriginalResult.Available
        assertArrayEquals("Clean private original bytes must match unwatermarked JPEG", rawJpegBytes, avail.file.readBytes())
        assertEquals("4.7mm", avail.metadata.focalLength)
        assertEquals("ISO 400", avail.metadata.iso)
        assertEquals(WatermarkStyle.LEICA_FRAME, avail.watermarkConfig.style)

        sourceFile.delete()
        outOffFile.delete()
        outWmDisabled.delete()
        outWmOn.delete()
    }

    @Test
    fun `update existing, save as new copy, and create unwatermarked copy keep clean original untouched and linked`() {
        val cleanSource = createSolidJpegFile(
            file = File(app.cacheDir, "orig_source_${System.nanoTime()}.jpg"),
            width = 240,
            height = 180,
            color = Color.rgb(30, 110, 210),
            addExif = true
        )
        val initialMeta = CameraMetadataUtil.fromFile(cleanSource, 240, 180)
        assertEquals("4.7mm", initialMeta.focalLength)
        assertEquals("ISO 400", initialMeta.iso)

        runBlocking<Unit> {
        val fakeRoot = File(app.cacheDir, "fake_mediastore_${System.nanoTime()}").apply { mkdirs() }
        val provider = FakeMediaContentProvider(fakeRoot).apply {
            attachInfo(app, ProviderInfo().apply { authority = MediaStore.AUTHORITY })
        }
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)

        val storage = PrivateOriginalStorageManager(app)
        val initialConfig = WatermarkConfig(
            isEnabled = true,
            style = WatermarkStyle.LEICA_FRAME,
            customText = "INITIAL",
            position = WatermarkPosition.FRAME_BORDER
        )
        val retained = storage.saveOriginalFromFile(cleanSource, initialMeta, isSaveLocation = false)!!
        storage.unmarkOriginalActive(retained.originalId)
        val originalPrivateFile = File(File(app.filesDir, "private_originals"), retained.fileName)
        val originalBytesSnapshot = originalPrivateFile.readBytes()

        val initialGalleryUri = provider.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues())
        val initialGalleryFile = provider.fileFor(initialGalleryUri)!!
        // Populate initial watermarked JPEG at initialGalleryUri
        assertTrue(
            MemoryMappedBufferUtil.processAndSerializeJpeg(
                context = app,
                targetUri = initialGalleryUri,
                sourceBuffer = ByteBuffer.wrap(originalBytesSnapshot),
                filter = CameraFilter.NATURAL,
                mirrorSelfie = false,
                watermarkConfig = initialConfig,
                isSaveLocation = false
            ) > 0L
        )
        storage.linkGalleryOutput(initialGalleryUri, retained.originalId, initialConfig)
        val sizeAfterInitial = initialGalleryFile.length()
        assertTrue(sizeAfterInitial > 0L)

        // 1. Update Existing Photo with HASSELBLAD dark frame & updated text
        val updatedConfig = initialConfig.copy(
            style = WatermarkStyle.CYBERPUNK,
            position = WatermarkPosition.BOTTOM_RIGHT,
            customText = "CYBER EDIT"
        )
        val updateResult = storage.updateExistingGalleryPhoto(
            galleryUri = initialGalleryUri,
            originalId = retained.originalId,
            config = updatedConfig,
            isSaveLocation = false
        )
        assertTrue(updateResult is com.example.data.PostCaptureExportResult.Success)
        // Same dimensions as clean original because CYBERPUNK is an overlay, not a frame border!
        // Crucially guarantees NO stacked/leftover LEICA frame border from first capture!
        val updatedBmp = BitmapFactory.decodeFile(initialGalleryFile.absolutePath)
        assertEquals(240, updatedBmp.width)
        assertEquals(180, updatedBmp.height)
        updatedBmp.recycle()
        assertArrayEquals("Clean private original must remain byte-for-byte untouched", originalBytesSnapshot, originalPrivateFile.readBytes())

        // Updated config persists on link
        val resolvedAfterUpdate = storage.resolveForGalleryUri(initialGalleryUri) as ResolvedOriginalResult.Available
        assertEquals(WatermarkStyle.CYBERPUNK, resolvedAfterUpdate.watermarkConfig.style)
        assertEquals("CYBER EDIT", resolvedAfterUpdate.watermarkConfig.customText)

        // 2. Save as New Copy with HASSELBLAD frame border -> links new URI to SAME originalId
        val copyConfig = initialConfig.copy(
            style = WatermarkStyle.HASSELBLAD,
            position = WatermarkPosition.FRAME_BORDER,
            customText = "SECOND COPY"
        )
        val newCopyResult = storage.saveNewGalleryCopyFromOriginal(
            originalId = retained.originalId,
            config = copyConfig,
            isSaveLocation = false
        )
        assertTrue(newCopyResult is com.example.data.PostCaptureExportResult.Success)
        val copyUri = (newCopyResult as com.example.data.PostCaptureExportResult.Success).galleryUri
        val copyFile = provider.fileFor(copyUri)!!
        val copyBmp = BitmapFactory.decodeFile(copyFile.absolutePath)
        assertTrue("Hasselblad frame increases height beyond 180", copyBmp.height > 180)
        copyBmp.recycle()

        // Only 1 private original retained for both gallery photos, and both resolve independently!
        assertEquals(1, storage.getRetainedOriginalsCount())
        val resolvedCopy = storage.resolveForGalleryUri(copyUri) as ResolvedOriginalResult.Available
        assertEquals(retained.originalId, resolvedCopy.entry.originalId)
        assertEquals(WatermarkStyle.HASSELBLAD, resolvedCopy.watermarkConfig.style)
        val resolvedFirstAgain = storage.resolveForGalleryUri(initialGalleryUri) as ResolvedOriginalResult.Available
        assertEquals(WatermarkStyle.CYBERPUNK, resolvedFirstAgain.watermarkConfig.style)

        // 3. Create Unwatermarked Copy -> matches clean original dimensions & pixels
        val cleanCopyResult = storage.saveNewGalleryCopyFromOriginal(
            originalId = retained.originalId,
            config = copyConfig.copy(isEnabled = false),
            isSaveLocation = false
        )
        assertTrue(cleanCopyResult is com.example.data.PostCaptureExportResult.Success)
        val unwatermarkedUri = (cleanCopyResult as com.example.data.PostCaptureExportResult.Success).galleryUri
        val unwatermarkedFile = provider.fileFor(unwatermarkedUri)!!
        val unwatermarkedBmp = BitmapFactory.decodeFile(unwatermarkedFile.absolutePath)
        assertEquals(240, unwatermarkedBmp.width)
        assertEquals(180, unwatermarkedBmp.height)
        unwatermarkedBmp.recycle()

        // Verify authentic focal length & EXIF preserved and never invented
        assertEquals("4.7mm", resolvedCopy.metadata.focalLength)
        assertEquals("ISO 400", resolvedCopy.metadata.iso)

        cleanSource.delete()
        fakeRoot.deleteRecursively()
        }
    }

    @Test
    fun `cache eviction removes oldest non-active originals first and never deletes active or missing originals safely`() {
        val storage = PrivateOriginalStorageManager(app)
        val src1 = createSolidJpegFile(File(app.cacheDir, "evict1_${System.nanoTime()}.jpg"), 300, 300, Color.RED, false)
        val src2 = createSolidJpegFile(File(app.cacheDir, "evict2_${System.nanoTime()}.jpg"), 300, 300, Color.GREEN, false)
        val src3 = createSolidJpegFile(File(app.cacheDir, "evict3_${System.nanoTime()}.jpg"), 300, 300, Color.BLUE, false)

        val e1 = storage.saveOriginalFromFile(src1, RealCameraMetadata(), isSaveLocation = false)!!
        storage.unmarkOriginalActive(e1.originalId)
        Thread.sleep(15)
        val e2 = storage.saveOriginalFromFile(src2, RealCameraMetadata(), isSaveLocation = false)!!
        storage.unmarkOriginalActive(e2.originalId)
        Thread.sleep(15)
        val e3 = storage.saveOriginalFromFile(src3, RealCameraMetadata(), isSaveLocation = false)!!
        // Keep e3 protected/active as if currently open in studio

        val gallery1 = Uri.fromFile(File(app.cacheDir, "gal_evict1.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) })
        storage.linkGalleryOutput(gallery1, e1.originalId, WatermarkConfig(isEnabled = true))

        assertEquals(3, storage.getRetainedOriginalsCount())
        val totalBefore = storage.getUsedStorageBytes()
        assertTrue(totalBefore > e3.fileSizeBytes)

        // Set tiny byte limit smaller than 3 files but enough for 1 file -> evicts e1 and e2, keeps e3 active!
        storage.markOriginalActive(e1.originalId) // Simulate e1 open in editor right now!
        val evictionProtectE1 = storage.enforceCacheLimitBytes(e1.fileSizeBytes + e3.fileSizeBytes)
        // e1 is active-protected, so e2 (next oldest) is evicted instead of e1!
        assertEquals(1, evictionProtectE1.evictedCount)
        assertNotNull(storage.getAllOriginals().find { it.originalId == e1.originalId })
        assertEquals(null, storage.getAllOriginals().find { it.originalId == e2.originalId })

        // Unmark e1 & simulate file corruption/deletion externally -> safely returns Unavailable
        storage.unmarkOriginalActive(e1.originalId)
        storage.unmarkOriginalActive(e3.originalId)
        val priv1 = File(File(app.filesDir, "private_originals"), e1.fileName)
        priv1.delete()

        val resolvedMissing = storage.resolveForGalleryUri(gallery1)
        assertTrue(resolvedMissing is ResolvedOriginalResult.Unavailable)
        assertTrue(File(gallery1.path!!).exists())

        // Clear all private originals clears all tracked entries (including missing-file entry e1 and active-released e3) and leaves 0 retained
        val cleared = storage.clearAllOriginals()
        assertTrue(cleared.evictedCount >= 1)
        assertEquals(0, storage.getRetainedOriginalsCount())
        assertEquals(0L, storage.getUsedStorageBytes())

        src1.delete()
        src2.delete()
        src3.delete()
        File(gallery1.path!!).delete()
    }

    @Test
    fun `compose UI validates settings toggle, cache limit modal, storage management page, and studio post-capture save options`() {
        var capturedEnabled: Boolean? = null
        var capturedLimit: WatermarkCacheLimit? = null
        var updatedLimit: WatermarkCacheLimit? = null
        var clearedCalled = false

        val uiState = CameraUiState(
            isSettingsOpen = true,
            watermarkConfig = WatermarkConfig(isEnabled = true),
            isPostCaptureWatermarkEditingEnabled = false,
            isWatermarkCacheLimitConfigured = false,
            watermarkCacheLimit = WatermarkCacheLimit.GB_1,
            privateOriginalStorageStats = com.example.model.PrivateOriginalStorageStats(
                maxBytes = WatermarkCacheLimit.GB_1.limitBytes,
                usedBytes = 12L * 1024L * 1024L,
                originalCount = 2
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
                onSelectPhotoResolution = {},
                onSelectRawCaptureMode = {},
                onToggleHdr = {},
                onToggleWatermark = {},
                onSetPostCaptureWatermarkEditing = { en, lim ->
                    capturedEnabled = en
                    capturedLimit = lim
                },
                onSetWatermarkCacheLimit = { updatedLimit = it },
                onClearAllPrivateOriginals = { clearedCalled = true },
                onSelectVideoQuality = {},
                onSelectVideoCodec = {},
                onToggleSelfieMirror = {},
                onToggleSaveLocation = {},
                onToggleHapticFeedback = {},
                onToggleKeepScreenAwake = {},
                onSelectVolumeKeyAction = {},
                onVersionTapped = {}
            )
        }

        // Scroll down to WatermarkCard controls inside settings LazyColumn
        composeTestRule.onNodeWithTag("settings_lazy_column")
            .performScrollToNode(hasTestTag("settings_toggle_post_capture_watermark"))
        // First-enable toggle prompts cache limit selection with explanation
        composeTestRule.onNodeWithTag("settings_toggle_post_capture_watermark").performClick()
        composeTestRule.onNodeWithText("Enable Post-Capture Watermark Editing").assertIsDisplayed()
        composeTestRule.onNodeWithTag("cache_limit_option_gb_2").performClick()
        composeTestRule.onNodeWithTag("post_capture_setup_confirm_button").performClick()

        assertEquals(true, capturedEnabled)
        assertEquals(WatermarkCacheLimit.GB_2, capturedLimit)

        // Open storage management page and test clear confirmation
        composeTestRule.onNodeWithTag("settings_lazy_column")
            .performScrollToNode(hasTestTag("open_watermark_storage_button"))
        composeTestRule.onNodeWithTag("open_watermark_storage_button").performClick()
        composeTestRule.onAllNodesWithText("Private Originals Storage").assertCountEquals(2)
        composeTestRule.onNodeWithTag("watermark_storage_management_close_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("Maximum allocated storage").assertIsDisplayed()
        composeTestRule.onNodeWithText("Currently used storage").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retained original photos").assertIsDisplayed()
        composeTestRule.onNodeWithTag("cache_limit_option_gb_5").performClick()
        assertEquals(WatermarkCacheLimit.GB_5, updatedLimit)

        composeTestRule.onNodeWithTag("clear_private_originals_button").performClick()
        composeTestRule.onNodeWithTag("clear_private_originals_confirm_button").performClick()
        assertTrue(clearedCalled)
    }

    @Test
    fun `compose UI verifies PhotoPreviewDialog Edit Watermark button, unavailable alert, and WatermarkStudioScreen post-capture actions`() {
        val dummyFile = createSolidJpegFile(File(app.cacheDir, "ui_studio_clean_${System.nanoTime()}.jpg"), 120, 90)
        val dummyUri = Uri.fromFile(dummyFile)
        val items = listOf(
            MediaItem(
                id = 1L,
                uri = dummyUri,
                isVideo = false,
                displayName = "IMG_1.jpg",
                dateAdded = 1000L,
                mimeType = "image/jpeg",
                sizeBytes = dummyFile.length(),
                width = 120,
                height = 90,
                durationMs = 0L
            )
        )
        var editWmRequested = false
        var dismissedUnavailable = false

        composeTestRule.setContent {
            PhotoPreviewDialog(
                items = items,
                currentIndex = 0,
                onIndexChange = {},
                onDismiss = {},
                onDelete = {},
                onGetDetails = {
                    PhotoDetails(
                        uri = dummyUri,
                        title = "IMG_1.jpg",
                        dateTaken = "Today",
                        dimensions = "120x90",
                        fileSize = "12 KB",
                        mimeType = "image/jpeg",
                        path = dummyFile.absolutePath
                    )
                },
                onEditWatermark = { editWmRequested = true },
                watermarkUnavailableMessage = "Clean unwatermarked original not available for this photo.",
                onDismissWatermarkUnavailable = { dismissedUnavailable = true }
            )
        }

        composeTestRule.onNodeWithTag("preview_edit_watermark_button").assertIsDisplayed().performClick()
        assertTrue(editWmRequested)

        composeTestRule.onNodeWithText("Clean Original Unavailable").assertIsDisplayed()
        composeTestRule.onNodeWithTag("watermark_unavailable_dismiss_button").performClick()
        assertTrue(dismissedUnavailable)

        dummyFile.delete()
    }

    @Test
    fun `compose UI verifies WatermarkStudioScreen post-capture toggle and 3-way save dialog`() {
        val cleanOrig = createSolidJpegFile(File(app.cacheDir, "studio_mode_${System.nanoTime()}.jpg"), 160, 120)
        var updatedExistingConfig: WatermarkConfig? = null

        val target = PostCaptureWatermarkTarget(
            galleryUri = Uri.fromFile(cleanOrig),
            originalId = "orig_ui_1",
            originalFilePath = cleanOrig.absolutePath,
            width = 160,
            height = 120,
            associatedConfig = WatermarkConfig(isEnabled = true, style = WatermarkStyle.LEICA_FRAME),
            focalLength = "4.7mm"
        )

        composeTestRule.setContent {
            WatermarkStudioScreen(
                initialConfig = WatermarkConfig(isEnabled = true),
                savedPresets = emptyList(),
                onSaveConfig = {},
                onSavePreset = { _, _ -> },
                onDeletePreset = {},
                onDismiss = {},
                postCaptureTarget = target,
                onPostCaptureUpdateExisting = { updatedExistingConfig = it }
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_post_capture_watermark_toggle").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("studio_save_button").performClick()

        composeTestRule.onNodeWithTag("studio_save_update_existing_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_save_new_copy_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_save_unwatermarked_copy_button").assertIsDisplayed()

        composeTestRule.onNodeWithTag("studio_save_update_existing_button").performClick()
        assertNotNull(updatedExistingConfig)
        assertFalse("Toggling watermark switch should flip isEnabled to false", updatedExistingConfig!!.isEnabled)

        cleanOrig.delete()
    }
}
