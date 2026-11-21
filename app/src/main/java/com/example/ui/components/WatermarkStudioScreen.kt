package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.WatermarkConfig
import com.example.model.WatermarkLogoType
import com.example.model.WatermarkPreset
import com.example.ui.theme.GlassObsidianBottom
import com.example.ui.theme.GlassObsidianTop
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDOBlack
import com.example.ui.theme.StuDOFocusYellow
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite
import com.example.util.WatermarkPainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal enum class StudioPanelState {
    CLOSED,
    PARTIAL,
    EXPANDED
}

private data class StudioPreviewKey(
    val config: WatermarkConfig,
    val photoKey: String,
    val activeFocalLength: String? = null
)

private const val STUDIO_PREVIEW_MAX_DIM = 720

@Composable
fun WatermarkStudioScreen(
    initialConfig: WatermarkConfig,
    savedPresets: List<WatermarkPreset>,
    onSaveConfig: (WatermarkConfig) -> Unit,
    onSavePreset: (String, WatermarkConfig) -> Unit,
    onDeletePreset: (String) -> Unit,
    onDismiss: () -> Unit,
    activeFocalLength: String? = null,
    postCaptureTarget: com.example.model.PostCaptureWatermarkTarget? = null,
    isPostCaptureSaving: Boolean = false,
    onPostCaptureUpdateExisting: (WatermarkConfig) -> Unit = {},
    onPostCaptureSaveNewCopy: (WatermarkConfig) -> Unit = {},
    onPostCaptureSaveUnwatermarkedCopy: () -> Unit = {},
    editorWatermarkTarget: com.example.model.EditorWatermarkTarget? = null,
    onSaveEditorWatermark: (WatermarkConfig) -> Unit = {},
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = !isPostCaptureSaving, onBack = onDismiss)
    val context = LocalContext.current
    val visualTier = rememberStudioVisualTier()
    val isPostCaptureMode = postCaptureTarget != null
    val isEditorSessionMode = editorWatermarkTarget != null
    val isPhotoSessionMode = isPostCaptureMode || isEditorSessionMode

    val sessionKey1: Any? = postCaptureTarget?.originalId ?: editorWatermarkTarget?.sessionId
    val sessionKey2: Any? = postCaptureTarget?.galleryUri ?: editorWatermarkTarget?.sourceUri

    // Single source of truth for independent template configurations and editing operations
    val stateHolder = rememberWatermarkStudioStateHolder(
        initialConfig = postCaptureTarget?.associatedConfig ?: editorWatermarkTarget?.initialConfig ?: initialConfig,
        key1 = sessionKey1,
        key2 = sessionKey2
    )
    val currentConfig = stateHolder.activeConfig

    // Panel UI state (completely separate from preview rendering state)
    var activeTab by remember { mutableStateOf(StudioTab.TEMPLATES) }
    var panelState by remember { mutableStateOf(StudioPanelState.PARTIAL) }

    // Dialog states
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }
    var showPostCaptureSaveDialog by remember { mutableStateOf(false) }
    var presetNameInput by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    // Photo preview & export states
    var loadedPhotoUri by remember(sessionKey1, sessionKey2) {
        mutableStateOf(postCaptureTarget?.galleryUri ?: editorWatermarkTarget?.sourceUri)
    }
    var loadedPhotoBitmap by remember(sessionKey1, sessionKey2) {
        mutableStateOf<Bitmap?>(null)
    }
    var loadedPhotoMetadata by remember(sessionKey1, sessionKey2) {
        mutableStateOf<com.example.util.RealCameraMetadata?>(editorWatermarkTarget?.metadata)
    }
    var isExporting by remember { mutableStateOf(false) }
    var exportFeedbackMessage by remember { mutableStateOf<String?>(null) }

    // Decode rendered editor snapshot bitmap once when launched from Photo Editor
    LaunchedEffect(editorWatermarkTarget?.sessionId, editorWatermarkTarget?.previewFilePath) {
        val target = editorWatermarkTarget ?: return@LaunchedEffect
        val decodedResult = withContext(Dispatchers.IO) {
            try {
                val previewFile = java.io.File(target.previewFilePath)
                if (!previewFile.exists() || !previewFile.isFile || previewFile.length() <= 0L) {
                    return@withContext null
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(previewFile.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    return@withContext null
                }
                var sampleSize = 1
                while ((bounds.outWidth / sampleSize) > STUDIO_PREVIEW_MAX_DIM ||
                    (bounds.outHeight / sampleSize) > STUDIO_PREVIEW_MAX_DIM
                ) {
                    sampleSize *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val decoded = BitmapFactory.decodeFile(previewFile.absolutePath, opts)
                    ?: return@withContext null
                decoded to target.metadata
            } catch (_: Throwable) {
                null
            }
        }
        if (decodedResult != null) {
            val (upright, meta) = decodedResult
            val oldBmp = loadedPhotoBitmap
            loadedPhotoBitmap = upright
            if (oldBmp != null && oldBmp !== upright && !oldBmp.isRecycled) {
                oldBmp.recycle()
            }
            loadedPhotoMetadata = meta
            loadedPhotoUri = target.sourceUri
        }
    }

    // Decode clean unwatermarked private original once in post-capture edit mode
    LaunchedEffect(postCaptureTarget?.originalId, postCaptureTarget?.originalFilePath) {
        val target = postCaptureTarget ?: return@LaunchedEffect
        val decodedResult = withContext(Dispatchers.IO) {
            try {
                val origFile = java.io.File(target.originalFilePath)
                if (!origFile.exists() || !origFile.isFile || origFile.length() <= 0L) {
                    return@withContext null
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(origFile.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    return@withContext null
                }
                var sampleSize = 1
                while ((bounds.outWidth / sampleSize) > STUDIO_PREVIEW_MAX_DIM ||
                    (bounds.outHeight / sampleSize) > STUDIO_PREVIEW_MAX_DIM
                ) {
                    sampleSize *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val decoded = BitmapFactory.decodeFile(origFile.absolutePath, opts)
                    ?: return@withContext null

                var orientation = android.media.ExifInterface.ORIENTATION_NORMAL
                try {
                    orientation = android.media.ExifInterface(origFile.absolutePath).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL
                    )
                } catch (_: Exception) {}

                val upright = applyExifOrientation(decoded, orientation)
                val authenticMeta = com.example.util.RealCameraMetadata(
                    iso = target.iso,
                    shutterSpeed = target.shutterSpeed,
                    aperture = target.aperture,
                    focalLength = target.focalLength,
                    dateTimeFormatted = target.dateTimeFormatted,
                    deviceModel = target.deviceModel,
                    lensInfo = target.lensInfo,
                    resolutionText = target.resolutionText,
                    manufacturer = target.manufacturer,
                    modelOnly = target.modelOnly
                )
                upright to authenticMeta
            } catch (_: Throwable) {
                null
            }
        }
        if (decodedResult != null) {
            val (upright, meta) = decodedResult
            val oldBmp = loadedPhotoBitmap
            loadedPhotoBitmap = upright
            if (oldBmp != null && oldBmp !== upright && !oldBmp.isRecycled) {
                oldBmp.recycle()
            }
            loadedPhotoMetadata = meta
            loadedPhotoUri = target.galleryUri
        }
    }

    // Gallery photo picker for lab testing
    val userPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            loadedPhotoUri = uri
            scope.launch {
                val decodedResult = withContext(Dispatchers.IO) {
                    try {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        context.contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it, null, bounds)
                        }
                        var sampleSize = 1
                        while ((bounds.outWidth / sampleSize) > STUDIO_PREVIEW_MAX_DIM ||
                            (bounds.outHeight / sampleSize) > STUDIO_PREVIEW_MAX_DIM
                        ) {
                            sampleSize *= 2
                        }
                        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        val decoded = context.contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it, null, opts)
                        } ?: return@withContext null

                        var orientation = android.media.ExifInterface.ORIENTATION_NORMAL
                        try {
                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                orientation = android.media.ExifInterface(stream).getAttributeInt(
                                    android.media.ExifInterface.TAG_ORIENTATION,
                                    android.media.ExifInterface.ORIENTATION_NORMAL
                                )
                            }
                        } catch (_: Exception) {}

                        val upright = applyExifOrientation(decoded, orientation)
                        val sideways = orientation == android.media.ExifInterface.ORIENTATION_ROTATE_90 ||
                            orientation == android.media.ExifInterface.ORIENTATION_ROTATE_270 ||
                            orientation == android.media.ExifInterface.ORIENTATION_TRANSPOSE ||
                            orientation == android.media.ExifInterface.ORIENTATION_TRANSVERSE
                        val fullW = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                            if (sideways) bounds.outHeight else bounds.outWidth
                        } else upright.width
                        val fullH = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                            if (sideways) bounds.outWidth else bounds.outHeight
                        } else upright.height

                        val meta = com.example.util.CameraMetadataUtil.fromUri(context, uri, fullW, fullH)
                        upright to meta
                    } catch (_: Throwable) {
                        null
                    }
                }
                if (decodedResult != null) {
                    val (upright, meta) = decodedResult
                    val oldBmp = loadedPhotoBitmap
                    loadedPhotoBitmap = upright
                    if (oldBmp != null && oldBmp !== upright && !oldBmp.isRecycled) {
                        oldBmp.recycle()
                    }
                    loadedPhotoMetadata = meta
                }
            }
        }
    }

    // Custom logo picker
    val logoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            stateHolder.updateActiveConfig {
                it.copy(
                    logoType = WatermarkLogoType.CUSTOM,
                    customLogoUri = uri.toString()
                )
            }
        }
    }

    // Genuine capture/sample metadata shared by both Info section and preview renderer
    val fallbackSampleMetadata = remember(activeFocalLength) {
        com.example.util.CameraMetadataUtil.samplePreviewMetadata(
            width = STUDIO_PREVIEW_MAX_DIM,
            height = 540,
            focalLength = activeFocalLength
        )
    }
    val activePreviewMetadata = loadedPhotoMetadata ?: fallbackSampleMetadata

    // LRU preview cache (keyed strictly by watermark config + photo, never by panel/tab UI state)
    val previewCache = remember { LruCache<StudioPreviewKey, ImageBitmap>(10) }
    var previewImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    val photoTag = loadedPhotoBitmap?.let {
        "${postCaptureTarget?.originalId ?: editorWatermarkTarget?.sessionId ?: loadedPhotoUri?.toString().orEmpty()}:${it.generationId}"
    } ?: when {
        isPostCaptureMode -> "POST_CAPTURE_LOADING_${postCaptureTarget?.originalId}"
        isEditorSessionMode -> "EDITOR_SESSION_LOADING_${editorWatermarkTarget?.sessionId}"
        else -> "SAMPLE"
    }
    val previewKey = StudioPreviewKey(
        config = currentConfig,
        photoKey = photoTag,
        activeFocalLength = postCaptureTarget?.focalLength ?: editorWatermarkTarget?.metadata?.focalLength ?: activeFocalLength
    )

    LaunchedEffect(previewKey, loadedPhotoBitmap, postCaptureTarget?.originalId, editorWatermarkTarget?.sessionId) {
        if (isPhotoSessionMode && loadedPhotoBitmap == null) {
            return@LaunchedEffect
        }
        previewCache.get(previewKey)?.let { cached ->
            previewImageBitmap = cached
            return@LaunchedEffect
        }

        if (previewImageBitmap != null) {
            delay(45L)
        }
        ensureActive()

        val imageBitmap = withContext(Dispatchers.Default) {
            ensureActive()
            val base = loadedPhotoBitmap ?: WatermarkPainter.getReadOnlySamplePhoto(STUDIO_PREVIEW_MAX_DIM, 540)
            val meta = loadedPhotoMetadata
                ?: com.example.util.CameraMetadataUtil.samplePreviewMetadata(
                    width = base.width,
                    height = base.height,
                    focalLength = activeFocalLength
                )
            val rendered = WatermarkPainter.applyWatermark(
                source = base,
                config = currentConfig,
                metadata = meta,
                context = context
            )
            ensureActive()
            rendered.asImageBitmap()
        }
        ensureActive()
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            previewCache.put(previewKey, imageBitmap)
            previewImageBitmap = imageBitmap
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            previewImageBitmap = null
            previewCache.evictAll()
            loadedPhotoBitmap?.recycle()
            loadedPhotoBitmap = null
        }
    }

    val studioSwitchColors = SwitchDefaults.colors(
        checkedThumbColor = Color.Black,
        checkedTrackColor = StuDOFocusYellow
    )
    val studioSliderColors = SliderDefaults.colors(
        thumbColor = StuDOFocusYellow,
        activeTrackColor = StuDOAmber
    )
    val activePreviewImageBitmap = previewImageBitmap

    CompositionLocalProvider(
        LocalStudioSwitchColors provides studioSwitchColors,
        LocalStudioSliderColors provides studioSliderColors
    ) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .background(StuDOBlack)
        ) {
            val density = LocalDensity.current
            val safeTopPadding = WindowInsets.safeDrawing.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()
            val navBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val isCompactHeight = maxHeight < 460.dp

            // Top floating toolbar height + safe area clearance
            val topToolbarClearance = safeTopPadding + 72.dp
            val availableBodyHeight = (maxHeight - topToolbarClearance - 88.dp).coerceAtLeast(120.dp)

            // Bottom panel detents (Closed = handle + section dock; Partial = default split; Expanded = tall form)
            val closedSheetHeight = (108.dp + navBottomPadding).coerceAtMost(availableBodyHeight)
            val partialSheetHeight = ((if (isCompactHeight) {
                (maxHeight * 0.44f).coerceIn(160.dp, 210.dp)
            } else {
                (maxHeight * 0.38f).coerceIn(230.dp, 330.dp)
            }) + navBottomPadding).coerceAtMost(availableBodyHeight)
            val expandedSheetHeight = ((if (isCompactHeight) {
                (maxHeight * 0.64f).coerceIn(200.dp, 260.dp)
            } else {
                (maxHeight * 0.60f).coerceIn(310.dp, 520.dp)
            }) + navBottomPadding).coerceAtMost(availableBodyHeight)

            val closedPx = with(density) { closedSheetHeight.toPx() }
            val partialPx = with(density) { partialSheetHeight.toPx() }
            val expandedPx = with(density) { expandedSheetHeight.toPx() }
            val topClearancePx = with(density) { topToolbarClearance.toPx().roundToInt() }
            val minPreviewPx = with(density) { 80.dp.toPx().roundToInt() }
            val sheetMaxWidthPx = with(density) { 680.dp.toPx().roundToInt() }

            val targetSheetPx = when (panelState) {
                StudioPanelState.CLOSED -> closedPx
                StudioPanelState.PARTIAL -> partialPx
                StudioPanelState.EXPANDED -> expandedPx
            }
            val sheetHeightPxAnim = remember { Animatable(partialPx) }
            LaunchedEffect(targetSheetPx, visualTier.isLowEndDevice) {
                val spec = if (visualTier.isLowEndDevice) {
                    tween<Float>(durationMillis = 180)
                } else {
                    spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                }
                sheetHeightPxAnim.animateTo(targetSheetPx, animationSpec = spec)
            }

            // Combined layout-phase positioning guarantees zero overlap and zero preview recompositions during sheet drag/spring animations
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    // 1. LARGE PHOTO PREVIEW AREA
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    ) {
                        StudioPhotoPreviewStage(
                            previewImageBitmap = activePreviewImageBitmap,
                            photoSourceKey = photoTag,
                            isEnabled = currentConfig.isEnabled,
                            styleDisplayName = currentConfig.style.displayName,
                            visualTier = visualTier,
                            isPostCaptureMode = isPhotoSessionMode,
                            postCaptureDimensionsText = postCaptureTarget?.let { "${it.width}×${it.height}" }
                                ?: editorWatermarkTarget?.let { "${it.width}×${it.height}" },
                            postCaptureLabelOverride = if (isEditorSessionMode) {
                                val dims = editorWatermarkTarget?.let { "${it.width}×${it.height}" }.orEmpty()
                                val prefix = if (editorWatermarkTarget?.isImportedPhoto == true) "Imported Photo" else "Edited Photo"
                                "$prefix ($dims)"
                            } else null,
                            postCaptureSubtitleOverride = if (isEditorSessionMode) {
                                "Rendering with active editor adjustments & overlays"
                            } else null,
                            hasLoadedPhoto = loadedPhotoUri != null,
                            isExporting = isExporting,
                            onToggleWatermarkEnabled = { enabled ->
                                stateHolder.setWatermarkEnabled(enabled)
                            },
                            onPickPhoto = {
                                userPhotoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onClearPhoto = {
                                loadedPhotoUri = null
                                val old = loadedPhotoBitmap
                                loadedPhotoBitmap = null
                                old?.recycle()
                                loadedPhotoMetadata = null
                            },
                            onExportCopy = {
                                if (!isExporting) {
                                    isExporting = true
                                    scope.launch {
                                        val savedUri = try {
                                            if (loadedPhotoUri != null) {
                                                WatermarkPainter.exportWatermarkedPhoto(
                                                    context = context,
                                                    sourceUri = loadedPhotoUri!!,
                                                    config = currentConfig
                                                )
                                            } else {
                                                withContext(Dispatchers.IO) {
                                                    val sampleBmp = WatermarkPainter.getCachedSamplePhoto(1920, 1080)
                                                    val sampleMeta = com.example.util.CameraMetadataUtil.samplePreviewMetadata(
                                                        width = 1920,
                                                        height = 1080,
                                                        focalLength = activeFocalLength
                                                    )
                                                    val stamped = WatermarkPainter.applyWatermark(sampleBmp, currentConfig, sampleMeta, context)
                                                    try {
                                                        WatermarkPainter.exportWatermarkedBitmap(
                                                            context = context,
                                                            bitmap = stamped,
                                                            config = currentConfig,
                                                            metadata = sampleMeta
                                                        )
                                                    } finally {
                                                        if (stamped !== sampleBmp && !stamped.isRecycled) stamped.recycle()
                                                        if (!sampleBmp.isRecycled) sampleBmp.recycle()
                                                    }
                                                }
                                            }
                                        } finally {
                                            isExporting = false
                                        }
                                        exportFeedbackMessage = if (savedUri != null) {
                                            "Watermarked photo saved as a separate copy to Gallery (Pictures/StuDO_Watermarked)!"
                                        } else {
                                            "Failed to export watermarked photo copy."
                                        }
                                    }
                                }
                            }
                        )
                    }

                    // 2. SLIDING BOTTOM EDITING PANEL — LAYOUT B
                    Box(
                        modifier = Modifier
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .widthIn(max = 680.dp)
                            .fillMaxWidth()
                            .studioGlassPanel(
                                visualTier = visualTier,
                                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                                cornerRadius = 26.dp,
                                topColor = GlassObsidianTop.copy(alpha = 0.84f),
                                bottomColor = GlassObsidianBottom.copy(alpha = 0.94f),
                                borderWidth = 1.dp,
                                coolHighlightAlpha = 0.30f,
                                warmHighlightAlpha = 0.18f,
                                specularSheenAlpha = 0.10f
                            )
                            .padding(bottom = navBottomPadding)
                            .testTag("studio_bottom_panel")
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Drag handle + State toggle
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .pointerInput(closedPx, partialPx, expandedPx) {
                                        detectVerticalDragGestures(
                                            onDragEnd = {
                                                val cur = sheetHeightPxAnim.value
                                                panelState = snapPanelState(cur, closedPx, partialPx, expandedPx)
                                            },
                                            onDragCancel = {
                                                val cur = sheetHeightPxAnim.value
                                                panelState = snapPanelState(cur, closedPx, partialPx, expandedPx)
                                            },
                                            onVerticalDrag = { change, dragAmount ->
                                                change.consume()
                                                val nextPx = (sheetHeightPxAnim.value - dragAmount).coerceIn(closedPx, expandedPx)
                                                scope.launch { sheetHeightPxAnim.snapTo(nextPx) }
                                            }
                                        )
                                    }
                                    .clickable {
                                        panelState = when (panelState) {
                                            StudioPanelState.CLOSED -> StudioPanelState.PARTIAL
                                            StudioPanelState.PARTIAL -> StudioPanelState.EXPANDED
                                            StudioPanelState.EXPANDED -> StudioPanelState.PARTIAL
                                        }
                                    }
                                    .padding(horizontal = 12.dp)
                                    .testTag("studio_panel_drag_handle"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val isTemplateDirty = stateHolder.isSelectedTemplateModified
                                IconButton(
                                    onClick = { showResetConfirmDialog = true },
                                    enabled = isTemplateDirty,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("studio_reset_template_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.RestartAlt,
                                        contentDescription = "Reset Selected Template",
                                        tint = if (isTemplateDirty) StuDOAmber else StuDOSilverMuted.copy(alpha = 0.32f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .width(40.dp)
                                        .height(4.dp)
                                        .clip(CircleShape)
                                        .background(StuDOSilver.copy(alpha = 0.42f))
                                )
                                IconButton(
                                    onClick = {
                                        panelState = when (panelState) {
                                            StudioPanelState.CLOSED -> StudioPanelState.PARTIAL
                                            StudioPanelState.PARTIAL -> StudioPanelState.CLOSED
                                            StudioPanelState.EXPANDED -> StudioPanelState.PARTIAL
                                        }
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("studio_panel_state_toggle")
                                ) {
                                    Icon(
                                        imageVector = if (panelState == StudioPanelState.CLOSED) {
                                            Icons.Default.KeyboardArrowUp
                                        } else {
                                            Icons.Default.KeyboardArrowDown
                                        },
                                        contentDescription = when (panelState) {
                                            StudioPanelState.CLOSED -> "Open editing panel"
                                            StudioPanelState.PARTIAL -> "Collapse editing panel"
                                            StudioPanelState.EXPANDED -> "Partially collapse editing panel"
                                        },
                                        tint = StuDOSilverMuted,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            // 5 Editing Sections Selector Dock
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp, vertical = 2.dp)
                                    .testTag("studio_section_bar"),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                STUDIO_TABS.forEach { tab ->
                                    val isSelected = tab == activeTab && panelState != StudioPanelState.CLOSED
                                    val isLastActiveClosed = tab == activeTab && panelState == StudioPanelState.CLOSED
                                    Row(
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(
                                                when {
                                                    isSelected -> StuDOAmber.copy(alpha = 0.16f)
                                                    isLastActiveClosed -> Color.White.copy(alpha = 0.08f)
                                                    else -> Color.White.copy(alpha = 0.04f)
                                                }
                                            )
                                            .border(
                                                width = if (isSelected) 1.2.dp else 0.8.dp,
                                                color = if (isSelected) StuDOAmber else Color.White.copy(alpha = 0.11f),
                                                shape = RoundedCornerShape(16.dp)
                                            )
                                            .clickable {
                                                activeTab = tab
                                                if (panelState == StudioPanelState.CLOSED) {
                                                    panelState = StudioPanelState.PARTIAL
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                            .testTag("studio_tab_${tab.name.lowercase()}"),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = tab.icon,
                                            contentDescription = null,
                                            tint = if (isSelected) StuDOFocusYellow else StuDOSilverMuted,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = tab.label,
                                            color = if (isSelected) StuDOFocusYellow else StuDOSilver,
                                            fontSize = 11.5.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            if (panelState != StudioPanelState.CLOSED) {
                                HorizontalDivider(
                                    color = Color.White.copy(alpha = 0.08f),
                                    thickness = 0.8.dp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                StudioEditingSectionHost(
                                    activeTab = activeTab,
                                    stateHolder = stateHolder,
                                    availableMetadata = activePreviewMetadata,
                                    savedPresets = savedPresets,
                                    onOpenSavePreset = {
                                        presetNameInput = "My ${currentConfig.style.displayName}"
                                        showSavePresetDialog = true
                                    },
                                    onDeletePreset = onDeletePreset,
                                    onPickCustomLogo = {
                                        logoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            ) { measurables, constraints ->
                val maxH = constraints.maxHeight
                val maxW = constraints.maxWidth
                val sheetPx = sheetHeightPxAnim.value.roundToInt().coerceIn(0, maxH)
                val availPreviewH = (maxH - topClearancePx - sheetPx)
                    .coerceAtLeast(minPreviewPx)
                    .coerceAtMost((maxH - sheetPx).coerceAtLeast(0))
                val previewPlaceable = measurables[0].measure(
                    constraints.copy(
                        minWidth = maxW,
                        maxWidth = maxW,
                        minHeight = availPreviewH,
                        maxHeight = availPreviewH
                    )
                )
                val panelMaxW = minOf(maxW, sheetMaxWidthPx)
                val panelPlaceable = measurables[1].measure(
                    constraints.copy(
                        minWidth = 0,
                        maxWidth = panelMaxW,
                        minHeight = sheetPx,
                        maxHeight = sheetPx
                    )
                )
                val previewY = (maxH - sheetPx - availPreviewH).coerceIn(0, topClearancePx)
                val panelY = (maxH - sheetPx).coerceAtLeast(0)
                val panelX = ((maxW - panelPlaceable.width) / 2).coerceAtLeast(0)
                layout(maxW, maxH) {
                    previewPlaceable.placeRelative(0, previewY)
                    panelPlaceable.placeRelative(panelX, panelY)
                }
            }

            // 3. TOP FLOATING TOOLBAR (Close, Undo, Save — no redundant sliders icon)
            StudioTopFloatingToolbar(
                visualTier = visualTier,
                isPostCaptureMode = isPhotoSessionMode,
                canUndo = stateHolder.canUndo,
                isSaveEnabled = !isPostCaptureSaving && (!isPhotoSessionMode || loadedPhotoBitmap != null),
                onClose = onDismiss,
                onUndo = { stateHolder.undo() },
                onSave = {
                    val activeSnapshot = stateHolder.activeConfig
                    when {
                        isEditorSessionMode -> onSaveEditorWatermark(activeSnapshot)
                        isPostCaptureMode -> showPostCaptureSaveDialog = true
                        else -> onSaveConfig(activeSnapshot)
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        // Save Preset Dialog
        if (showSavePresetDialog) {
            AlertDialog(
                onDismissRequest = { showSavePresetDialog = false },
                containerColor = Color(0xFF1A1C23),
                title = {
                    Text(
                        text = "Save Watermark Preset",
                        color = StuDOWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "Name your custom watermark style for instant one-tap reuse:",
                            color = StuDOSilverMuted,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = presetNameInput,
                            onValueChange = { presetNameInput = it },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = StuDOWhite,
                                unfocusedTextColor = StuDOWhite,
                                focusedBorderColor = StuDOAmber,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                cursorColor = StuDOFocusYellow
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("studio_preset_name_input")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (presetNameInput.isNotBlank()) {
                                onSavePreset(presetNameInput.trim(), currentConfig)
                                showSavePresetDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StuDOFocusYellow),
                        modifier = Modifier.testTag("studio_save_preset_confirm_button")
                    ) {
                        Text("Save Preset", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSavePresetDialog = false }) {
                        Text("Cancel", color = StuDOSilver)
                    }
                }
            )
        }

        // Per-Template Reset Confirmation Dialog (strictly scoped to selected template)
        if (showResetConfirmDialog) {
            val activeStyleName = stateHolder.selectedStyle.displayName
            AlertDialog(
                onDismissRequest = { showResetConfirmDialog = false },
                containerColor = Color(0xFF1A1C23),
                modifier = Modifier.testTag("studio_reset_confirm_dialog"),
                title = {
                    Text(
                        text = "Reset Template",
                        color = StuDOWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Text(
                        text = "Restore \"$activeStyleName\" to its original default settings? Customisations in your other templates will remain untouched.",
                        color = StuDOSilverMuted,
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            stateHolder.resetSelectedTemplate()
                            showResetConfirmDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StuDOFocusYellow),
                        modifier = Modifier.testTag("studio_reset_confirm_button")
                    ) {
                        Text("Reset Template", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showResetConfirmDialog = false },
                        modifier = Modifier.testTag("studio_reset_cancel_button")
                    ) {
                        Text("Cancel", color = StuDOSilver)
                    }
                }
            )
        }

        // Export Feedback Dialog
        exportFeedbackMessage?.let { msg ->
            AlertDialog(
                onDismissRequest = { exportFeedbackMessage = null },
                title = {
                    Text(
                        text = "Studio Export",
                        color = StuDOWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Text(
                        text = msg,
                        color = StuDOSilver,
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { exportFeedbackMessage = null },
                        colors = ButtonDefaults.buttonColors(containerColor = StuDOFocusYellow),
                        modifier = Modifier.testTag("studio_export_feedback_ok_button")
                    ) {
                        Text("OK", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Color(0xFF1A1C23)
            )
        }

        // Post-Capture Save Destination Dialog
        if (showPostCaptureSaveDialog && isPostCaptureMode) {
            AlertDialog(
                onDismissRequest = { if (!isPostCaptureSaving) showPostCaptureSaveDialog = false },
                containerColor = Color(0xFF1A1C23),
                title = {
                    Text(
                        text = "Save Watermark Changes",
                        color = StuDOWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Re-renders full resolution cleanly from the private unwatermarked original (never double-watermarks).",
                            color = StuDOSilverMuted,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        Button(
                            onClick = {
                                showPostCaptureSaveDialog = false
                                onPostCaptureUpdateExisting(currentConfig)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = StuDOFocusYellow),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("studio_save_update_existing_button")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Update Existing Photo",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Overwrites current gallery photo cleanly (no duplicate file)",
                                    color = Color.Black.copy(alpha = 0.72f),
                                    fontSize = 10.sp
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                showPostCaptureSaveDialog = false
                                onPostCaptureSaveNewCopy(currentConfig)
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StuDOWhite),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("studio_save_new_copy_button")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Save as New Copy",
                                    color = StuDOWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Keeps previous photo intact and links new copy to original",
                                    color = StuDOSilverMuted,
                                    fontSize = 10.sp
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                showPostCaptureSaveDialog = false
                                onPostCaptureSaveUnwatermarkedCopy()
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StuDOAmber),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("studio_save_unwatermarked_copy_button")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Create Unwatermarked Copy",
                                    color = StuDOAmber,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Exports a completely clean copy without any watermark",
                                    color = StuDOSilverMuted,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showPostCaptureSaveDialog = false }) {
                        Text("Cancel", color = StuDOSilver)
                    }
                }
            )
        }

        AnimatedVisibility(
            visible = isPostCaptureSaving,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(color = StuDOFocusYellow)
                    Text(
                        text = "Exporting full-resolution photo...",
                        color = StuDOWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

private fun snapPanelState(
    currentPx: Float,
    closedPx: Float,
    partialPx: Float,
    expandedPx: Float
): StudioPanelState {
    val dClosed = kotlin.math.abs(currentPx - closedPx)
    val dPartial = kotlin.math.abs(currentPx - partialPx)
    val dExpanded = kotlin.math.abs(currentPx - expandedPx)
    return when {
        dClosed <= dPartial && dClosed <= dExpanded -> StudioPanelState.CLOSED
        dPartial <= dExpanded -> StudioPanelState.PARTIAL
        else -> StudioPanelState.EXPANDED
    }
}

private fun applyExifOrientation(decoded: Bitmap, orientation: Int): Bitmap {
    val matrix = android.graphics.Matrix()
    when (orientation) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        android.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        android.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
    }
    return if (!matrix.isIdentity) {
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        rotated
    } else {
        decoded
    }
}
