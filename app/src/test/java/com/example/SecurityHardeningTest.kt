package com.example

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.MediaCaptureManager
import com.example.data.PrivateOriginalStorageManager
import com.example.data.RecycleBinManager
import com.example.data.RecycleResult
import com.example.data.ResolvedOriginalResult
import com.example.editor.PhotoEditSnapshot
import com.example.editor.PhotoProcessingEngine
import com.example.model.MediaItem
import com.example.model.PortraitDepthSource
import com.example.model.PostCapturePortraitTarget
import com.example.model.WatermarkConfig
import com.example.model.WatermarkLogoType
import com.example.model.WatermarkStyle
import com.example.portrait.PortraitSidecarStorage
import com.example.util.RealCameraMetadata
import com.example.util.WatermarkPainter
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SecurityHardeningTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun createValidJpegFile(file: File, width: Int = 64, height: Int = 64): File {
        file.parentFile?.mkdirs()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.rgb(40, 80, 120))
        }
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        bmp.recycle()
        return file
    }

    @Test
    fun `manifest strips transitive network permissions and disables backup`() {
        val pm = app.packageManager
        val pkgInfo = pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        val requested = pkgInfo.requestedPermissions?.toSet().orEmpty()

        assertFalse(
            "INTERNET permission must not be present in offline app manifest",
            requested.contains(android.Manifest.permission.INTERNET)
        )
        assertFalse(
            "ACCESS_NETWORK_STATE permission must not be present in offline app manifest",
            requested.contains(android.Manifest.permission.ACCESS_NETWORK_STATE)
        )

        val appInfo = pm.getApplicationInfo(app.packageName, 0)
        assertEquals(
            "android:allowBackup must be disabled (FLAG_ALLOW_BACKUP == 0)",
            0,
            appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP
        )
    }

    @Test
    fun `RecycleBinManager blocks path traversal in manifest and creates nomedia markers`() {
        val outsideFile = File(app.filesDir, "outside_secret_${System.nanoTime()}.jpg")
        createValidJpegFile(outsideFile)

        val binRoot = File(app.filesDir, "studo_bin").apply { mkdirs() }
        val manifestFile = File(binRoot, "manifest.json")
        val maliciousId = "traversal-id-1"

        try {
            val arr = JSONArray().put(
                JSONObject().apply {
                    put("id", maliciousId)
                    put("originalUriString", "content://media/external/images/media/1")
                    put("binFilePath", outsideFile.absolutePath)
                    put("displayName", "escape.jpg")
                    put("mimeType", "image/jpeg")
                    put("isVideo", false)
                    put("sizeBytes", outsideFile.length())
                    put("width", 64)
                    put("height", 64)
                    put("durationMs", 0L)
                    put("dateAddedSeconds", 1700000000L)
                    put("deletedAtMillis", 1700000000000L)
                    put("relativePath", "Pictures/StuDO")
                }
            )
            manifestFile.writeText(arr.toString(), Charsets.UTF_8)

            val manager = RecycleBinManager(app)
            assertTrue("getBinItems must ignore escaped path outside studo_bin/media", manager.getBinItems().isEmpty())
            assertTrue("bin root must contain .nomedia", File(binRoot, ".nomedia").exists())
            assertTrue("bin media dir must contain .nomedia", File(binRoot, "media/.nomedia").exists())

            val restoreResult = manager.restoreFromBin(maliciousId)
            assertTrue("restoreFromBin must reject escaped path", restoreResult is RecycleResult.Error)
            assertTrue("outsideFile must not be deleted by restore failure", outsideFile.exists())

            // Re-seed manifest and verify permanentlyDelete also refuses to delete files outside studo_bin/media
            manifestFile.writeText(arr.toString(), Charsets.UTF_8)
            manager.permanentlyDelete(maliciousId)
            assertTrue("outsideFile must remain untouched on permanentlyDelete", outsideFile.exists())
        } finally {
            outsideFile.delete()
            binRoot.deleteRecursively()
        }
    }

    @Test
    fun `RecycleBinManager rejects moveToBin for remote or persistent private file URIs`() {
        val secretPrivateFile = File(app.filesDir, "private_data_${System.nanoTime()}.jpg")
        createValidJpegFile(secretPrivateFile)

        val manager = RecycleBinManager(app)
        val captureManager = MediaCaptureManager(app)
        try {
            val remoteItem = MediaItem(
                id = 1L,
                uri = Uri.parse("https://example.com/photo.jpg"),
                isVideo = false,
                displayName = "remote.jpg",
                dateAdded = 1700000000L,
                mimeType = "image/jpeg",
                sizeBytes = 100L,
                width = 64,
                height = 64
            )
            assertTrue(manager.moveToBin(captureManager, remoteItem) is RecycleResult.Error)

            val privateFileItem = MediaItem(
                id = 2L,
                uri = Uri.fromFile(secretPrivateFile),
                isVideo = false,
                displayName = "private.jpg",
                dateAdded = 1700000000L,
                mimeType = "image/jpeg",
                sizeBytes = secretPrivateFile.length(),
                width = 64,
                height = 64
            )
            assertTrue(manager.moveToBin(captureManager, privateFileItem) is RecycleResult.Error)
            assertTrue("Private filesDir source must not be deleted", secretPrivateFile.exists())
        } finally {
            captureManager.shutdown()
            secretPrivateFile.delete()
        }
    }

    @Test
    fun `PrivateOriginalStorageManager rejects path traversal filenames in persisted index`() {
        val outsideFile = File(app.filesDir, "outside_original_${System.nanoTime()}.jpg")
        createValidJpegFile(outsideFile)

        val storageDir = File(app.filesDir, "private_originals").apply { mkdirs() }
        val indexFile = File(storageDir, "originals_index.json")
        val galleryUri = Uri.parse("content://media/external/images/media/4242")

        try {
            val root = JSONObject().apply {
                put(
                    "originals",
                    JSONArray().put(
                        JSONObject().apply {
                            put("originalId", "orig-traversal")
                            put("fileName", "../${outsideFile.name}")
                            put("createdAtMs", 1700000000000L)
                            put("fileSizeBytes", outsideFile.length())
                            put("width", 64)
                            put("height", 64)
                        }
                    )
                )
                put(
                    "links",
                    JSONArray().put(
                        JSONObject().apply {
                            put("galleryUri", galleryUri.toString())
                            put("mediaStoreId", 4242L)
                            put("originalId", "orig-traversal")
                            put("updatedAtMs", 1700000000000L)
                        }
                    )
                )
            }
            indexFile.writeText(root.toString(), Charsets.UTF_8)

            val storage = PrivateOriginalStorageManager(app)
            val resolved = storage.resolveForGalleryUri(galleryUri)
            assertTrue(
                "Escaped ../ original filename must be rejected",
                resolved is ResolvedOriginalResult.Unavailable
            )
            storage.clearAllOriginals()
            assertTrue("Outside file must not be deleted during storage operations", outsideFile.exists())
        } finally {
            outsideFile.delete()
            storageDir.deleteRecursively()
        }
    }

    @Test
    fun `PortraitSidecarStorage rejects path traversal in index and target paths`() {
        val outsideClean = File(app.filesDir, "outside_portrait_${System.nanoTime()}.jpg")
        createValidJpegFile(outsideClean)

        val storageDir = File(app.filesDir, "portrait_sidecars").apply { mkdirs() }
        val indexFile = File(storageDir, "portrait_index.json")
        val galleryUri = Uri.parse("content://media/external/images/media/7777")

        try {
            val validMatte = File(storageDir, "matte_valid.png")
            val validDepth = File(storageDir, "depth_valid.png")
            PortraitSidecarStorage.encodeFloatFieldToPng(FloatArray(4) { 1f }, 2, 2, validMatte)
            PortraitSidecarStorage.encodeFloatFieldToPng(FloatArray(4) { 0.5f }, 2, 2, validDepth)

            val root = JSONObject().apply {
                put(
                    "entries",
                    JSONArray().put(
                        JSONObject().apply {
                            put("sidecarId", "sidecar-traversal")
                            put("cleanFileName", "../${outsideClean.name}")
                            put("matteFileName", validMatte.name)
                            put("depthFileName", validDepth.name)
                            put("maskWidth", 2)
                            put("maskHeight", 2)
                            put("width", 64)
                            put("height", 64)
                        }
                    )
                )
                put(
                    "links",
                    JSONArray().put(
                        JSONObject().apply {
                            put("galleryUri", galleryUri.toString())
                            put("mediaStoreId", 7777L)
                            put("sidecarId", "sidecar-traversal")
                            put("updatedAtMs", 1700000000000L)
                        }
                    )
                )
            }
            indexFile.writeText(root.toString(), Charsets.UTF_8)

            val sidecarStorage = PortraitSidecarStorage(app)
            assertFalse(sidecarStorage.hasPortraitSidecar(galleryUri))
            assertNull(sidecarStorage.resolveForGalleryUri(galleryUri))

            // Also verify direct PostCapturePortraitTarget with outside paths is rejected
            val craftedTarget = PostCapturePortraitTarget(
                galleryUri = galleryUri,
                originalId = "sidecar-traversal",
                originalFilePath = outsideClean.absolutePath,
                depthMapFilePath = validDepth.absolutePath,
                alphaMatteFilePath = validMatte.absolutePath,
                width = 64,
                height = 64,
                blurIntensity = 0.6f,
                focusX = 0.5f,
                focusY = 0.5f,
                depthSource = PortraitDepthSource.AI_SEGMENTATION,
                watermarkConfig = WatermarkConfig(isEnabled = false)
            )
            assertNull(sidecarStorage.loadSegmentationForTarget(craftedTarget))
            assertTrue("Outside file must remain intact", outsideClean.exists())
        } finally {
            outsideClean.delete()
            storageDir.deleteRecursively()
        }
    }

    @Test
    fun `WatermarkPainter safely rejects remote and app-private file logo URIs`() {
        val base = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }
        val secretPrivateLogo = File(app.filesDir, "secret_logo_${System.nanoTime()}.png")
        createValidJpegFile(secretPrivateLogo, 32, 32)
        try {
            val meta = RealCameraMetadata(deviceModel = "StuDO Test", resolutionText = "120x120")

            val remoteConfig = WatermarkConfig(
                isEnabled = true,
                style = WatermarkStyle.STUDIO_BRAND,
                logoType = WatermarkLogoType.CUSTOM,
                customLogoUri = "https://example.invalid/tracker.png"
            )
            val remoteOut = WatermarkPainter.applyWatermark(base, remoteConfig, meta, app, recycleSource = false)
            assertNotNull(remoteOut)
            if (remoteOut !== base) remoteOut.recycle()

            val privateConfig = WatermarkConfig(
                isEnabled = true,
                style = WatermarkStyle.STUDIO_BRAND,
                logoType = WatermarkLogoType.CUSTOM,
                customLogoUri = Uri.fromFile(secretPrivateLogo).toString()
            )
            val privateOut = WatermarkPainter.applyWatermark(base, privateConfig, meta, app, recycleSource = false)
            assertNotNull(privateOut)
            if (privateOut !== base) privateOut.recycle()
        } finally {
            base.recycle()
            secretPrivateLogo.delete()
        }
    }

    @Test
    fun `PhotoProcessingEngine exportFullResolutionEditedImage strips GPS EXIF when location preservation is disabled`() {
        runBlocking {
            val sourceFile = File(app.cacheDir, "exif_gps_src_${System.nanoTime()}.jpg")
            createValidJpegFile(sourceFile, 128, 96)
            val srcExif = ExifInterface(sourceFile.absolutePath)
            srcExif.setAttribute(ExifInterface.TAG_MAKE, "StuDOCam")
            srcExif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "37/1,46/1,30/1")
            srcExif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            srcExif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "122/1,25/1,10/1")
            srcExif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            srcExif.saveAttributes()

            var strippedOutUri: Uri? = null
            var keptOutUri: Uri? = null
            try {
                strippedOutUri = PhotoProcessingEngine.exportFullResolutionEditedImage(
                    context = app,
                    sourceUri = Uri.fromFile(sourceFile),
                    snapshot = PhotoEditSnapshot(),
                    isImportedPhoto = false,
                    preserveLocationMetadata = false
                )
                assertNotNull("Edited export should succeed when preserveLocationMetadata=false", strippedOutUri)
                app.contentResolver.openInputStream(strippedOutUri!!)?.use { stream ->
                    val outExif = ExifInterface(stream)
                    assertEquals("StuDOCam", outExif.getAttribute(ExifInterface.TAG_MAKE))
                    assertNull(
                        "GPS latitude must be omitted when preserveLocationMetadata=false",
                        outExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
                    )
                    assertNull(
                        "GPS longitude must be omitted when preserveLocationMetadata=false",
                        outExif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
                    )
                }

                keptOutUri = PhotoProcessingEngine.exportFullResolutionEditedImage(
                    context = app,
                    sourceUri = Uri.fromFile(sourceFile),
                    snapshot = PhotoEditSnapshot(),
                    isImportedPhoto = false,
                    preserveLocationMetadata = true
                )
                assertNotNull("Edited export should succeed when preserveLocationMetadata=true", keptOutUri)
                app.contentResolver.openInputStream(keptOutUri!!)?.use { stream ->
                    val outExif = ExifInterface(stream)
                    assertEquals("37/1,46/1,30/1", outExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
                    assertEquals("N", outExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF))
                }
            } finally {
                sourceFile.delete()
                strippedOutUri?.let { try { app.contentResolver.delete(it, null, null) } catch (_: Throwable) {} }
                keptOutUri?.let { try { app.contentResolver.delete(it, null, null) } catch (_: Throwable) {} }
            }
        }
    }
}
