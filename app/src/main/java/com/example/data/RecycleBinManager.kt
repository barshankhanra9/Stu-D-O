package com.example.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.example.model.BinMediaItem
import com.example.model.MediaItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

sealed class RecycleResult {
    data class MovedToBin(val binItem: BinMediaItem) : RecycleResult()
    data class Restored(val restoredUri: Uri, val binId: String) : RecycleResult()
    data class PermanentlyDeleted(val binId: String) : RecycleResult()
    data class Error(val message: String) : RecycleResult()
}

/**
 * Manages StuDO's local Recycle Bin under app-private storage (`filesDir/studo_bin/`).
 * Media deleted from the Gallery / Viewer is copied cleanly to app-private storage before
 * deleting the MediaStore item, and Restore atomically writes it back to MediaStore once
 * and removes the bin entry so duplicates never appear.
 */
class RecycleBinManager(private val context: Context) {

    companion object {
        private const val TAG = "RecycleBinManager"
        private const val BIN_ROOT_DIR = "studo_bin"
        private const val BIN_MEDIA_DIR = "media"
        private const val MANIFEST_FILE = "manifest.json"
        private const val BUFFER_SIZE = 128 * 1024
    }

    private val lock = Any()
    private val inFlightBinOps = mutableSetOf<String>()

    private val binRootDir: File by lazy {
        File(context.filesDir, BIN_ROOT_DIR).apply { if (!exists()) mkdirs() }
    }

    private val mediaDir: File by lazy {
        File(binRootDir, BIN_MEDIA_DIR).apply { if (!exists()) mkdirs() }
    }

    private val manifestFile: File by lazy {
        File(binRootDir, MANIFEST_FILE)
    }

    fun getBinItems(): List<BinMediaItem> = synchronized(lock) {
        loadManifestLocked()
            .filter { File(it.binFilePath).exists() }
            .sortedByDescending { it.deletedAtMillis }
    }

    fun getBinCount(): Int = getBinItems().size

    fun moveToBin(mediaCaptureManager: MediaCaptureManager, item: MediaItem): RecycleResult {
        val binId = UUID.randomUUID().toString()
        val ext = resolveExtension(item)
        val safeName = item.displayName.ifBlank {
            if (item.isVideo) "StuDO_VID_${System.currentTimeMillis()}.$ext" else "StuDO_${System.currentTimeMillis()}.$ext"
        }
        val destFile = File(mediaDir, "${binId}.$ext")

        try {
            // 1. Copy bytes from MediaStore or local file URI into private Bin storage
            val copiedBytes = copyUriToFile(item.uri, destFile)
            if (copiedBytes <= 0L || !destFile.exists()) {
                try { destFile.delete() } catch (_: Throwable) {}
                return RecycleResult.Error("Unable to copy media to StuDO Bin")
            }

            // 2. Remove original entry from MediaStore / file system
            val deletedOriginal = deleteOriginalSource(mediaCaptureManager, item.uri)
            if (!deletedOriginal) {
                try { destFile.delete() } catch (_: Throwable) {}
                return RecycleResult.Error("Could not remove original item from Gallery")
            }

            // 3. Persist entry in manifest
            val binItem = BinMediaItem(
                id = binId,
                originalUriString = item.uri.toString(),
                binFilePath = destFile.absolutePath,
                displayName = safeName,
                mimeType = resolveMimeType(item, ext),
                isVideo = item.isVideo,
                sizeBytes = copiedBytes,
                width = item.width,
                height = item.height,
                durationMs = item.durationMs,
                dateAddedSeconds = item.dateAdded.takeIf { it > 0L } ?: (System.currentTimeMillis() / 1000L),
                deletedAtMillis = System.currentTimeMillis(),
                relativePath = if (item.isVideo) "Movies/StuDO" else "Pictures/StuDO"
            )
            synchronized(lock) {
                val items = loadManifestLocked().toMutableList()
                // Deduplicate by original URI if somehow retried
                items.removeAll { it.originalUriString == binItem.originalUriString && !File(it.binFilePath).exists() }
                items.add(0, binItem)
                saveManifestLocked(items)
            }
            return RecycleResult.MovedToBin(binItem)
        } catch (t: Throwable) {
            Log.e(TAG, "Error moving item to StuDO Bin", t)
            try { destFile.delete() } catch (_: Throwable) {}
            return RecycleResult.Error(t.localizedMessage ?: "Failed to move item to StuDO Bin")
        }
    }

    fun restoreFromBin(binId: String): RecycleResult {
        val targetItem: BinMediaItem
        synchronized(lock) {
            if (!inFlightBinOps.add(binId)) {
                return RecycleResult.Error("Restore already in progress")
            }
            val found = loadManifestLocked().firstOrNull { it.id == binId }
            if (found == null) {
                inFlightBinOps.remove(binId)
                return RecycleResult.Error("Item no longer exists in StuDO Bin")
            }
            targetItem = found
        }

        try {
            val binFile = File(targetItem.binFilePath)
            if (!binFile.exists() || binFile.length() <= 0L) {
                removeEntryAndFile(binId, targetItem.binFilePath)
                return RecycleResult.Error("Bin file is missing or corrupted")
            }

            val restoredUri = writeBinFileBackToMediaStore(targetItem, binFile)
                ?: return RecycleResult.Error("Unable to restore item to device Gallery")

            // Atomically remove manifest entry & private file once MediaStore write succeeded
            removeEntryAndFile(binId, targetItem.binFilePath)
            return RecycleResult.Restored(restoredUri, binId)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed restoring bin item $binId", t)
            return RecycleResult.Error(t.localizedMessage ?: "Failed to restore item from StuDO Bin")
        } finally {
            synchronized(lock) { inFlightBinOps.remove(binId) }
        }
    }

    fun permanentlyDelete(binId: String): RecycleResult {
        synchronized(lock) {
            if (!inFlightBinOps.add(binId)) {
                return RecycleResult.Error("Operation already in progress")
            }
        }
        return try {
            val path: String? = synchronized(lock) {
                val list = loadManifestLocked().toMutableList()
                val match = list.firstOrNull { it.id == binId }
                if (match != null) {
                    list.removeAll { it.id == binId }
                    saveManifestLocked(list)
                }
                match?.binFilePath
            }
            if (path != null) {
                try { File(path).delete() } catch (_: Throwable) {}
            }
            RecycleResult.PermanentlyDeleted(binId)
        } finally {
            synchronized(lock) { inFlightBinOps.remove(binId) }
        }
    }

    fun clearBin(): Int {
        return synchronized(lock) {
            val items = loadManifestLocked()
            var removedCount = 0
            items.forEach { item ->
                val file = File(item.binFilePath)
                if (file.exists()) {
                    try { file.delete() } catch (_: Throwable) {}
                }
                removedCount++
            }
            saveManifestLocked(emptyList())
            mediaDir.listFiles()?.forEach { orphan ->
                try { orphan.delete() } catch (_: Throwable) {}
            }
            removedCount
        }
    }

    private fun removeEntryAndFile(binId: String, filePath: String) {
        synchronized(lock) {
            val list = loadManifestLocked().toMutableList()
            list.removeAll { it.id == binId }
            saveManifestLocked(list)
        }
        try { File(filePath).delete() } catch (_: Throwable) {}
    }

    private fun copyUriToFile(uri: Uri, destFile: File): Long {
        if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
            val src = File(uri.path!!)
            if (!src.exists()) return 0L
            src.inputStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val copied = input.copyTo(output, BUFFER_SIZE)
                    output.flush()
                    return copied
                }
            }
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                val copied = input.copyTo(output, BUFFER_SIZE)
                output.flush()
                return copied
            }
        }
        return 0L
    }

    private fun deleteOriginalSource(mediaCaptureManager: MediaCaptureManager, uri: Uri): Boolean {
        if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
            val f = File(uri.path!!)
            return !f.exists() || f.delete()
        }
        return try {
            mediaCaptureManager.deleteMedia(uri)
        } catch (_: SecurityException) {
            false
        }
    }

    private fun writeBinFileBackToMediaStore(item: BinMediaItem, binFile: File): Uri? {
        val collectionUri = if (item.isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val relativePath = item.relativePath.ifBlank {
            if (item.isVideo) "Movies/StuDO" else "Pictures/StuDO"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            if (item.width > 0) put(MediaStore.MediaColumns.WIDTH, item.width)
            if (item.height > 0) put(MediaStore.MediaColumns.HEIGHT, item.height)
            if (item.isVideo && item.durationMs > 0L) {
                put(MediaStore.Video.Media.DURATION, item.durationMs)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val targetUri = context.contentResolver.insert(collectionUri, values) ?: return null
        return try {
            val out = try {
                context.contentResolver.openOutputStream(targetUri, "w")
            } catch (_: Throwable) {
                null
            } ?: context.contentResolver.openOutputStream(targetUri)

            if (out == null) {
                try { context.contentResolver.delete(targetUri, null, null) } catch (_: Throwable) {}
                return null
            }

            var writtenBytes = 0L
            out.use { stream ->
                binFile.inputStream().use { input ->
                    writtenBytes = input.copyTo(stream, BUFFER_SIZE)
                    stream.flush()
                }
            }
            if (writtenBytes <= 0L) {
                try { context.contentResolver.delete(targetUri, null, null) } catch (_: Throwable) {}
                return null
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val clearPending = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(targetUri, clearPending, null, null)
            }
            targetUri
        } catch (t: Throwable) {
            Log.e(TAG, "Failed writing restored item to MediaStore", t)
            try { context.contentResolver.delete(targetUri, null, null) } catch (_: Throwable) {}
            null
        }
    }

    private fun resolveExtension(item: MediaItem): String {
        val fromName = item.displayName.substringAfterLast('.', "").lowercase().trim()
        if (fromName.isNotEmpty() && fromName.length <= 5) return fromName
        return when {
            item.isVideo || item.mimeType.startsWith("video/") -> "mp4"
            item.mimeType.equals("image/x-adobe-dng", ignoreCase = true) -> "dng"
            item.mimeType.equals("image/png", ignoreCase = true) -> "png"
            item.mimeType.equals("image/webp", ignoreCase = true) -> "webp"
            else -> "jpg"
        }
    }

    private fun resolveMimeType(item: MediaItem, ext: String): String {
        if (item.mimeType.isNotBlank()) return item.mimeType
        return when {
            item.isVideo || ext in setOf("mp4", "mov", "mkv", "3gp", "webm") -> "video/mp4"
            ext == "dng" -> "image/x-adobe-dng"
            ext == "png" -> "image/png"
            ext == "webp" -> "image/webp"
            else -> "image/jpeg"
        }
    }

    private fun loadManifestLocked(): List<BinMediaItem> {
        if (!manifestFile.exists()) return emptyList()
        return try {
            val raw = manifestFile.readText()
            if (raw.isBlank()) return emptyList()
            val arr = JSONArray(raw)
            val result = mutableListOf<BinMediaItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                result.add(
                    BinMediaItem(
                        id = obj.optString("id"),
                        originalUriString = obj.optString("originalUriString"),
                        binFilePath = obj.optString("binFilePath"),
                        displayName = obj.optString("displayName"),
                        mimeType = obj.optString("mimeType"),
                        isVideo = obj.optBoolean("isVideo", false),
                        sizeBytes = obj.optLong("sizeBytes", 0L),
                        width = obj.optInt("width", 0),
                        height = obj.optInt("height", 0),
                        durationMs = obj.optLong("durationMs", 0L),
                        dateAddedSeconds = obj.optLong("dateAddedSeconds", 0L),
                        deletedAtMillis = obj.optLong("deletedAtMillis", 0L),
                        relativePath = obj.optString("relativePath", "Pictures/StuDO")
                    )
                )
            }
            result
        } catch (t: Throwable) {
            Log.e(TAG, "Error loading StuDO Bin manifest", t)
            emptyList()
        }
    }

    private fun saveManifestLocked(items: List<BinMediaItem>) {
        try {
            val arr = JSONArray()
            items.forEach { item ->
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("originalUriString", item.originalUriString)
                    put("binFilePath", item.binFilePath)
                    put("displayName", item.displayName)
                    put("mimeType", item.mimeType)
                    put("isVideo", item.isVideo)
                    put("sizeBytes", item.sizeBytes)
                    put("width", item.width)
                    put("height", item.height)
                    put("durationMs", item.durationMs)
                    put("dateAddedSeconds", item.dateAddedSeconds)
                    put("deletedAtMillis", item.deletedAtMillis)
                    put("relativePath", item.relativePath)
                }
                arr.put(obj)
            }
            val tmp = File(binRootDir, "$MANIFEST_FILE.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(manifestFile)) {
                manifestFile.writeText(arr.toString())
                try { tmp.delete() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error saving StuDO Bin manifest", t)
        }
    }
}
