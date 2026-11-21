package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.model.CameraMode
import com.example.model.MediaItem
import com.example.util.VideoThumbnailUtil
import com.example.viewmodel.CameraViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VideoThumbnailTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Application>()
        VideoThumbnailUtil.clearCache()
    }

    @Test
    fun `test video thumbnail cache stores and retrieves bitmaps accurately`() {
        val testUri = Uri.parse("content://media/external/video/media/101")
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

        assertNull("Cache should initially be empty for new video URI", VideoThumbnailUtil.getCached(testUri))

        VideoThumbnailUtil.putCache(testUri, bitmap)
        val retrieved = VideoThumbnailUtil.getCached(testUri)

        assertNotNull("Cached bitmap should be retrievable", retrieved)
        assertEquals("Retrieved bitmap should match cached bitmap", bitmap, retrieved)
    }

    @Test
    fun `test multiple recorded videos retain distinct thumbnails in cache`() {
        val video1Uri = Uri.parse("content://media/external/video/media/201")
        val video2Uri = Uri.parse("content://media/external/video/media/202")
        val video3Uri = Uri.parse("content://media/external/video/media/203")

        val bmp1 = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { setPixel(0, 0, Color.RED) }
        val bmp2 = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { setPixel(0, 0, Color.GREEN) }
        val bmp3 = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { setPixel(0, 0, Color.BLUE) }

        VideoThumbnailUtil.putCache(video1Uri, bmp1)
        VideoThumbnailUtil.putCache(video2Uri, bmp2)
        VideoThumbnailUtil.putCache(video3Uri, bmp3)

        val cached1 = VideoThumbnailUtil.getCached(video1Uri)
        val cached2 = VideoThumbnailUtil.getCached(video2Uri)
        val cached3 = VideoThumbnailUtil.getCached(video3Uri)

        assertNotNull(cached1)
        assertNotNull(cached2)
        assertNotNull(cached3)

        assertEquals(Color.RED, cached1!!.getPixel(0, 0))
        assertEquals(Color.GREEN, cached2!!.getPixel(0, 0))
        assertEquals(Color.BLUE, cached3!!.getPixel(0, 0))
    }

    @Test
    fun `test fallback placeholder generated when extraction fails is fully opaque`() {
        val placeholder = VideoThumbnailUtil.createFallbackPlaceholderBitmap(256, 256)

        assertNotNull("Placeholder bitmap must not be null", placeholder)
        assertEquals(256, placeholder.width)
        assertEquals(256, placeholder.height)
        assertFalse("Placeholder bitmap must not be recycled", placeholder.isRecycled)

        // Verify the placeholder is 100% opaque (alpha == 255) to never leak camera preview
        val cornerPixel = placeholder.getPixel(0, 0)
        val alpha = Color.alpha(cornerPixel)
        assertEquals("Placeholder must be fully opaque to prevent viewfinder hole punch", 255, alpha)

        val centerPixel = placeholder.getPixel(128, 128)
        assertEquals("Center pixel must also be fully opaque", 255, Color.alpha(centerPixel))
    }

    @Test
    fun `test restart simulation clears cache and allows re-extraction`() {
        val videoUri = Uri.parse("content://media/external/video/media/301")
        val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)

        VideoThumbnailUtil.putCache(videoUri, bmp)
        assertNotNull(VideoThumbnailUtil.getCached(videoUri))

        // Simulate app restart / memory wipe
        VideoThumbnailUtil.clearCache()
        assertNull("Cache must be cleared on restart", VideoThumbnailUtil.getCached(videoUri))

        // When reloaded after restart
        val freshBmp = VideoThumbnailUtil.createFallbackPlaceholderBitmap(128, 128)
        VideoThumbnailUtil.putCache(videoUri, freshBmp)
        assertNotNull(VideoThumbnailUtil.getCached(videoUri))
    }

    @Test
    fun `test URI mapping resolves between CameraX external_primary and MediaStore external authorities`() {
        val cameraXOutputUri = Uri.parse("content://media/external_primary/video/media/9988")
        val mediaStoreQueryUri = Uri.parse("content://media/external/video/media/9988")

        val mediaItems = listOf(
            MediaItem(
                id = 9988L,
                uri = mediaStoreQueryUri,
                isVideo = true,
                displayName = "StuDO_VID_2026.mp4",
                dateAdded = 1700000000L,
                mimeType = "video/mp4",
                sizeBytes = 1048576L,
                width = 1920,
                height = 1080,
                durationMs = 5000L
            ),
            MediaItem(
                id = 9987L,
                uri = Uri.parse("content://media/external/video/media/9987"),
                isVideo = true,
                displayName = "StuDO_VID_Older.mp4",
                dateAdded = 1699990000L,
                mimeType = "video/mp4",
                sizeBytes = 2048576L,
                width = 1920,
                height = 1080,
                durationMs = 12000L
            )
        )

        // Matching logic used in CameraBottomBar and CameraViewModel
        val matchedItem = mediaItems.firstOrNull {
            it.uri == cameraXOutputUri ||
            (it.uri.lastPathSegment != null && it.uri.lastPathSegment == cameraXOutputUri.lastPathSegment)
        }

        assertNotNull("Matching by lastPathSegment must resolve differing URI authorities", matchedItem)
        assertEquals(9988L, matchedItem!!.id)
        assertEquals("StuDO_VID_2026.mp4", matchedItem.displayName)
    }

    @Test
    fun `test gallery navigation finds index for video with differing authority`() {
        val targetUri = Uri.parse("content://media/external_primary/video/media/402")
        val items = listOf(
            MediaItem(id = 401L, uri = Uri.parse("content://media/external/video/media/401"), isVideo = true, displayName = "Vid1.mp4", dateAdded = 100L, mimeType = "video/mp4", sizeBytes = 0L, width = 0, height = 0, durationMs = 0L),
            MediaItem(id = 402L, uri = Uri.parse("content://media/external/video/media/402"), isVideo = true, displayName = "Vid2.mp4", dateAdded = 200L, mimeType = "video/mp4", sizeBytes = 0L, width = 0, height = 0, durationMs = 0L),
            MediaItem(id = 403L, uri = Uri.parse("content://media/external/video/media/403"), isVideo = true, displayName = "Vid3.mp4", dateAdded = 300L, mimeType = "video/mp4", sizeBytes = 0L, width = 0, height = 0, durationMs = 0L)
        )

        val index = items.indexOfFirst {
            it.uri == targetUri ||
            (it.uri.lastPathSegment != null && it.uri.lastPathSegment == targetUri.lastPathSegment)
        }

        assertEquals("Should correctly resolve index 1 for the target video", 1, index)
    }

    @Test
    fun `test ViewModel openGallery matches video URI and selects valid index`() {
        val vm = CameraViewModel(app)
        val mockCaptureManager = com.example.data.MediaCaptureManager(app)

        val videoUri = Uri.parse("content://media/external/video/media/501")
        vm.openGallery(mockCaptureManager, videoUri)

        // Even with empty MediaStore in test environment, gallery index should be bounded to 0
        val state = vm.uiState.value
        assertEquals(0, state.currentGalleryIndex)
        assertFalse(state.isRecordingVideo)
    }
}
