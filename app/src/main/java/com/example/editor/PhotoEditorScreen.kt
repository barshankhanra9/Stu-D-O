package com.example.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.adaptiveLiquidGlassPanel
import com.example.ui.components.liquidGlassChipSurface
import com.example.ui.components.rememberLiquidGlassVisualTier
import com.example.ui.theme.GlassObsidianBottom
import com.example.ui.theme.GlassObsidianTop
import com.example.ui.theme.StuDOBlack
import com.example.ui.theme.StuDOFocusYellow
import com.example.ui.theme.StuDORed
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure detent snapping helper for the draggable bottom editing panel.
 */
internal fun snapEditorDetent(
    currentPx: Float,
    collapsedPx: Float,
    partialPx: Float,
    expandedPx: Float
): EditorPanelDetent {
    val dCollapsed = abs(currentPx - collapsedPx)
    val dPartial = abs(currentPx - partialPx)
    val dExpanded = abs(currentPx - expandedPx)
    return when {
        dCollapsed <= dPartial && dCollapsed <= dExpanded -> EditorPanelDetent.COLLAPSED
        dExpanded <= dPartial -> EditorPanelDetent.EXPANDED
        else -> EditorPanelDetent.PARTIAL
    }
}

/**
 * StuDO Photo Editor — Cinematic Dark Liquid Glass UI & Professional Non-Destructive Engine.
 */
@Composable
fun PhotoEditorScreen(
    sourceUri: Uri,
    onSaveSuccess: (Uri) -> Unit,
    onDismiss: () -> Unit,
    onOpenWatermarkStudio: (PhotoEditSnapshot) -> Unit = {},
    isImportedPhoto: Boolean = false,
    isSaveLocationEnabled: Boolean = true,
    pendingWatermarkResult: com.example.model.PendingEditorWatermarkResult? = null,
    onConsumeWatermarkResult: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val visualTier = rememberLiquidGlassVisualTier()

    // Image & metadata state
    var baseLoadedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var orientedBaseBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var processedPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var watermarkedPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val filterThumbnails = remember { mutableStateMapOf<PhotoFilterType, Bitmap>() }
    var photoMetadata by remember { mutableStateOf<com.example.util.RealCameraMetadata?>(null) }
    var sourceDimensionsText by remember { mutableStateOf<String?>(null) }
    var isLoadingPreview by remember { mutableStateOf(true) }
    var loadErrorMessage by remember { mutableStateOf<String?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }

    // Rich Metadata Bottom Sheet State
    var showDetailsSheet by remember { mutableStateOf(false) }
    var richMetadata by remember { mutableStateOf<com.example.util.RichPhotoMetadata?>(null) }
    var isLoadingDetails by remember { mutableStateOf(false) }

    // Non-destructive state + Undo/Redo stacks
    val undoStack = remember { mutableStateListOf<PhotoEditSnapshot>() }
    val redoStack = remember { mutableStateListOf<PhotoEditSnapshot>() }
    var currentSnapshot by remember { mutableStateOf(PhotoEditSnapshot()) }
    var pendingBaselineSnapshot by remember { mutableStateOf<PhotoEditSnapshot?>(null) }

    // UI Navigation & Panel State
    var activeTab by remember { mutableStateOf(EditorTab.ADJUST) }
    var panelDetent by remember { mutableStateOf(EditorPanelDetent.PARTIAL) }
    var selectedAdjustTool by remember { mutableStateOf(AdjustmentTool.EXPOSURE) }
    var isComparingOriginal by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    // Selection & Tool Sub-states
    var selectedTextId by remember { mutableStateOf<String?>(null) }
    var selectedBadgeId by remember { mutableStateOf<String?>(null) }
    val savedBadgePresets = remember { mutableStateListOf<SavedBadgePreset>() }
    val recentCustomColors = remember { mutableStateListOf<Color>() }

    // Draw tool parameters
    var drawBrushType by remember { mutableStateOf(DrawBrushType.PEN) }
    var drawColor by remember { mutableStateOf(Color.White) }
    var drawStrokeWidth by remember { mutableFloatStateOf(8f) }
    var drawOpacity by remember { mutableFloatStateOf(1.0f) }
    var isDrawEraser by remember { mutableStateOf(false) }

    fun pushSnapshot(newSnapshot: PhotoEditSnapshot) {
        if (newSnapshot != currentSnapshot) {
            undoStack.add(currentSnapshot)
            redoStack.clear()
            currentSnapshot = newSnapshot
            pendingBaselineSnapshot = null
        }
    }

    fun beginContinuousEdit() {
        if (pendingBaselineSnapshot == null) {
            pendingBaselineSnapshot = currentSnapshot
        }
    }

    fun commitContinuousEdit() {
        val base = pendingBaselineSnapshot ?: return
        if (base != currentSnapshot) {
            undoStack.add(base)
            redoStack.clear()
        }
        pendingBaselineSnapshot = null
    }

    fun performUndo() {
        pendingBaselineSnapshot = null
        if (undoStack.isNotEmpty()) {
            val prev = undoStack.removeAt(undoStack.lastIndex)
            redoStack.add(currentSnapshot)
            currentSnapshot = prev
        }
    }

    fun performRedo() {
        pendingBaselineSnapshot = null
        if (redoStack.isNotEmpty()) {
            val next = redoStack.removeAt(redoStack.lastIndex)
            undoStack.add(currentSnapshot)
            currentSnapshot = next
        }
    }

    fun performResetAll() {
        if (currentSnapshot != PhotoEditSnapshot()) {
            pushSnapshot(PhotoEditSnapshot())
        }
    }

    fun recordRecentColor(color: Color) {
        val argb = color.toArgb()
        if (EDITOR_PRESET_COLORS.none { it.toArgb() == argb }) {
            recentCustomColors.removeAll { it.toArgb() == argb }
            recentCustomColors.add(0, color)
            if (recentCustomColors.size > 6) {
                recentCustomColors.removeAt(recentCustomColors.lastIndex)
            }
        }
    }

    // Consume watermark configuration returned from Watermark Studio
    LaunchedEffect(pendingWatermarkResult?.eventId) {
        val result = pendingWatermarkResult ?: return@LaunchedEffect
        pushSnapshot(currentSnapshot.copy(watermarkConfig = result.config))
        onConsumeWatermarkResult(result.eventId)
    }

    // 1. Decode upright preview bitmap + authentic EXIF + filter thumbnails
    LaunchedEffect(sourceUri, isImportedPhoto) {
        isLoadingPreview = true
        loadErrorMessage = null
        richMetadata = null
        val loaded = PhotoProcessingEngine.loadPreviewBitmap(context, sourceUri, maxDimPx = 1280)
        if (loaded == null || loaded.isRecycled) {
            baseLoadedBitmap = null
            photoMetadata = null
            sourceDimensionsText = null
            loadErrorMessage = "Unable to load this photo. It may have been removed or uses an unsupported format."
            isLoadingPreview = false
            return@LaunchedEffect
        }
        baseLoadedBitmap = loaded
        val (fullW, fullH, meta) = withContext(Dispatchers.IO) {
            var outW = loaded.width
            var outH = loaded.height
            try {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream, null, bounds)
                }
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    var orientation = android.media.ExifInterface.ORIENTATION_NORMAL
                    try {
                        context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                            orientation = android.media.ExifInterface(stream).getAttributeInt(
                                android.media.ExifInterface.TAG_ORIENTATION,
                                android.media.ExifInterface.ORIENTATION_NORMAL
                            )
                        }
                    } catch (_: Exception) {}
                    val swapped = com.example.util.HighResZoomMath.isOrientationSwapped(orientation)
                    outW = if (swapped) bounds.outHeight else bounds.outWidth
                    outH = if (swapped) bounds.outWidth else bounds.outHeight
                }
            } catch (_: Throwable) {}
            val extracted = com.example.util.CameraMetadataUtil.fromUri(
                context = context,
                uri = sourceUri,
                width = outW,
                height = outH,
                allowDeviceFallback = !isImportedPhoto
            )
            Triple(outW, outH, extracted)
        }
        sourceDimensionsText = "${fullW}×${fullH}"
        photoMetadata = meta
        isLoadingPreview = false
        val thumbs = PhotoProcessingEngine.generateFilterThumbnails(loaded, thumbSizePx = 112)
        filterThumbnails.values.forEach { if (!it.isRecycled) it.recycle() }
        filterThumbnails.clear()
        filterThumbnails.putAll(thumbs)
    }

    // 2. Oriented & cropped base bitmap (cached across tone/filter/overlay changes)
    val applyCropInPreview = activeTab != EditorTab.CROP
    LaunchedEffect(baseLoadedBitmap, currentSnapshot.crop, applyCropInPreview) {
        val src = baseLoadedBitmap ?: return@LaunchedEffect
        val oriented = withContext(Dispatchers.Default) {
            PhotoProcessingEngine.createOrientedBitmap(
                source = src,
                crop = currentSnapshot.crop,
                applyCropRect = applyCropInPreview,
                recycleSource = false
            )
        }
        val old = orientedBaseBitmap
        orientedBaseBitmap = oriented
        if (old != null && old !== src && old !== oriented && old !== processedPreviewBitmap && !old.isRecycled) {
            old.recycle()
        }
    }

    // 3. Unified non-destructive Tone + Color + Detail + Filter + Intensity processing
    LaunchedEffect(
        orientedBaseBitmap,
        currentSnapshot.adjustments,
        currentSnapshot.filter,
        currentSnapshot.filterIntensity
    ) {
        val oriented = orientedBaseBitmap ?: return@LaunchedEffect
        val hasGrading = !currentSnapshot.adjustments.isDefault() ||
            (currentSnapshot.filter != PhotoFilterType.ORIGINAL && currentSnapshot.filterIntensity > 0.005f)

        if (!hasGrading) {
            val old = processedPreviewBitmap
            processedPreviewBitmap = null
            if (old != null && old !== oriented && !old.isRecycled) old.recycle()
            return@LaunchedEffect
        }

        // Brief coalesce on rapid slider sweeps so latest-wins stays smooth at 60fps
        delay(12L)
        ensureActive()

        val rendered = withContext(Dispatchers.Default) {
            PhotoProcessingEngine.processEditedBitmap(
                source = oriented,
                adjustments = currentSnapshot.adjustments,
                filter = currentSnapshot.filter,
                filterIntensity = currentSnapshot.filterIntensity,
                recycleSource = false
            )
        }
        ensureActive()
        val old = processedPreviewBitmap
        processedPreviewBitmap = rendered
        if (old != null && old !== oriented && old !== rendered && !old.isRecycled) {
            old.recycle()
        }
    }

    // 4. Render WYSIWYG Watermark Studio preview when watermark is enabled in current snapshot
    val activeWatermarkConfig = currentSnapshot.watermarkConfig?.takeIf { it.isEnabled }
    val shouldBakeWatermarkInPreview = activeWatermarkConfig != null &&
        !isComparingOriginal &&
        activeTab != EditorTab.CROP

    LaunchedEffect(
        shouldBakeWatermarkInPreview,
        activeWatermarkConfig,
        processedPreviewBitmap,
        orientedBaseBitmap,
        currentSnapshot.drawingPaths,
        currentSnapshot.textItems,
        currentSnapshot.stickerItems,
        photoMetadata
    ) {
        if (!shouldBakeWatermarkInPreview) {
            val old = watermarkedPreviewBitmap
            watermarkedPreviewBitmap = null
            if (old != null && old !== orientedBaseBitmap && old !== processedPreviewBitmap && !old.isRecycled) {
                old.recycle()
            }
            return@LaunchedEffect
        }
        val gradedBase = processedPreviewBitmap ?: orientedBaseBitmap ?: return@LaunchedEffect
        delay(16L)
        ensureActive()
        val rendered = withContext(Dispatchers.Default) {
            PhotoProcessingEngine.renderEditorPreviewWithWatermark(
                context = context,
                gradedBase = gradedBase,
                snapshot = currentSnapshot,
                metadata = photoMetadata ?: com.example.util.RealCameraMetadata()
            )
        }
        ensureActive()
        val old = watermarkedPreviewBitmap
        watermarkedPreviewBitmap = rendered
        if (old != null && old !== rendered && old !== gradedBase && !old.isRecycled) {
            old.recycle()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            filterThumbnails.values.forEach { if (!it.isRecycled) it.recycle() }
            filterThumbnails.clear()
            watermarkedPreviewBitmap?.let { if (!it.isRecycled) it.recycle() }
            processedPreviewBitmap?.let { if (!it.isRecycled) it.recycle() }
            orientedBaseBitmap?.let { if (!it.isRecycled) it.recycle() }
            baseLoadedBitmap?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    BackHandler {
        if (showDetailsSheet) {
            showDetailsSheet = false
        } else if (undoStack.isNotEmpty() || currentSnapshot != PhotoEditSnapshot()) {
            showDiscardDialog = true
        } else {
            onDismiss()
        }
    }

    val isWatermarkShowing = shouldBakeWatermarkInPreview &&
        watermarkedPreviewBitmap != null &&
        !watermarkedPreviewBitmap!!.isRecycled

    val activeDisplayBitmap = when {
        isComparingOriginal -> orientedBaseBitmap ?: baseLoadedBitmap
        isWatermarkShowing -> watermarkedPreviewBitmap
        else -> processedPreviewBitmap ?: orientedBaseBitmap ?: baseLoadedBitmap
    }

    val previewLoading = isLoadingPreview
    val previewLoadError = loadErrorMessage
    val orientedAspectWidth = (orientedBaseBitmap?.width ?: baseLoadedBitmap?.width ?: 4).toFloat()
    val orientedAspectHeight = (orientedBaseBitmap?.height ?: baseLoadedBitmap?.height ?: 3).toFloat()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0E1016), StuDOBlack)
                )
            )
            .imePadding()
            .testTag("photo_editor_root")
    ) {
        val density = LocalDensity.current
        val safeTopPadding = WindowInsets.safeDrawing.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()
        val navBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val isCompactHeight = maxHeight < 460.dp

        val topToolbarClearance = safeTopPadding + 68.dp
        val availableBodyHeight = (maxHeight - topToolbarClearance - 88.dp).coerceAtLeast(120.dp)

        // Bottom sheet detents: COLLAPSED (handle + 6 nav tabs), PARTIAL (split workspace), EXPANDED (tall editor)
        val collapsedHeight = (108.dp + navBottomPadding).coerceAtMost(availableBodyHeight)
        val partialHeight = ((if (isCompactHeight) {
            (maxHeight * 0.44f).coerceIn(170.dp, 220.dp)
        } else {
            (maxHeight * 0.39f).coerceIn(245.dp, 345.dp)
        }) + navBottomPadding).coerceAtMost(availableBodyHeight)
        val expandedHeight = ((if (isCompactHeight) {
            (maxHeight * 0.68f).coerceIn(210.dp, 280.dp)
        } else {
            (maxHeight * 0.62f).coerceIn(340.dp, 540.dp)
        }) + navBottomPadding).coerceAtMost(availableBodyHeight)

        val collapsedPx = with(density) { collapsedHeight.toPx() }
        val partialPx = with(density) { partialHeight.toPx() }
        val expandedPx = with(density) { expandedHeight.toPx() }
        val topClearancePx = with(density) { topToolbarClearance.toPx().roundToInt() }
        val minPreviewPx = with(density) { 80.dp.toPx().roundToInt() }
        val sheetMaxWidthPx = with(density) { 680.dp.toPx().roundToInt() }

        val targetSheetPx = when (panelDetent) {
            EditorPanelDetent.COLLAPSED -> collapsedPx
            EditorPanelDetent.PARTIAL -> partialPx
            EditorPanelDetent.EXPANDED -> expandedPx
        }
        val sheetHeightPxAnim = remember { Animatable(partialPx) }
        LaunchedEffect(targetSheetPx, visualTier.isLowEndDevice) {
            val spec = if (visualTier.isLowEndDevice) {
                tween<Float>(durationMillis = 170)
            } else {
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            }
            sheetHeightPxAnim.animateTo(targetSheetPx, animationSpec = spec)
        }

        // Combined Layout guarantees zero overlap between floating toolbar, preview stage, and draggable sheet
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                // SLOT 0: Interactive Photo Preview Canvas
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                ) {
                    PhotoEditorPreviewStage(
                        displayBitmap = activeDisplayBitmap,
                        isLoadingPreview = previewLoading,
                        isComparingOriginal = isComparingOriginal,
                        activeTab = activeTab,
                        snapshot = currentSnapshot,
                        photoMetadata = photoMetadata,
                        visualTier = visualTier,
                        selectedTextId = selectedTextId,
                        selectedBadgeId = selectedBadgeId,
                        drawColor = drawColor,
                        drawStrokeWidth = drawStrokeWidth,
                        drawOpacity = drawOpacity,
                        drawBrushType = drawBrushType,
                        isDrawEraser = isDrawEraser,
                        onSelectText = { id ->
                            selectedTextId = id
                            if (activeTab != EditorTab.TEXT) {
                                activeTab = EditorTab.TEXT
                                if (panelDetent == EditorPanelDetent.COLLAPSED) {
                                    panelDetent = EditorPanelDetent.PARTIAL
                                }
                            }
                        },
                        onSelectBadge = { id ->
                            selectedBadgeId = id
                            if (activeTab != EditorTab.STICKERS) {
                                activeTab = EditorTab.STICKERS
                                if (panelDetent == EditorPanelDetent.COLLAPSED) {
                                    panelDetent = EditorPanelDetent.PARTIAL
                                }
                            }
                        },
                        onMoveText = { id, newPercent ->
                            currentSnapshot = currentSnapshot.copy(
                                textItems = currentSnapshot.textItems.map {
                                    if (it.id == id) it.copy(positionPercent = newPercent) else it
                                }
                            )
                        },
                        onMoveBadge = { id, newPercent ->
                            currentSnapshot = currentSnapshot.copy(
                                stickerItems = currentSnapshot.stickerItems.map {
                                    if (it.id == id) it.copy(positionPercent = newPercent) else it
                                }
                            )
                        },
                        onOverlayDragStarted = { beginContinuousEdit() },
                        onOverlayDragEnded = { commitContinuousEdit() },
                        onCropRectChanged = { rect ->
                            currentSnapshot = currentSnapshot.copy(
                                crop = currentSnapshot.crop.copy(cropRect = rect)
                            )
                        },
                        onCropDragStarted = { beginContinuousEdit() },
                        onCropDragEnded = { commitContinuousEdit() },
                        onDrawPathFinished = { path ->
                            pushSnapshot(
                                currentSnapshot.copy(drawingPaths = currentSnapshot.drawingPaths + path)
                            )
                        },
                        loadErrorMessage = previewLoadError,
                        onErrorClose = onDismiss,
                        isImportedPhoto = isImportedPhoto,
                        isWatermarkBakedInPreview = isWatermarkShowing,
                        sourceDimensionsText = sourceDimensionsText,
                        onOpenPhotoInfo = {
                            showDetailsSheet = true
                            if (richMetadata == null && !isLoadingDetails) {
                                isLoadingDetails = true
                                scope.launch {
                                    val item = com.example.model.MediaItem(
                                        id = 0L,
                                        uri = sourceUri,
                                        isVideo = false,
                                        displayName = "",
                                        dateAdded = 0L,
                                        mimeType = "",
                                        sizeBytes = 0L,
                                        width = 0,
                                        height = 0
                                    )
                                    richMetadata = com.example.util.PhotoMetadataExtractor.extract(context, item)
                                    isLoadingDetails = false
                                }
                            }
                        }
                    )
                }

                // SLOT 1: Genuinely Draggable Liquid Glass Bottom Editing Sheet
                Box(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .widthIn(max = 680.dp)
                        .fillMaxWidth()
                        .adaptiveLiquidGlassPanel(
                            visualTier = visualTier,
                            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                            cornerRadius = 26.dp,
                            topColor = GlassObsidianTop.copy(alpha = 0.86f),
                            bottomColor = GlassObsidianBottom.copy(alpha = 0.95f),
                            borderWidth = 1.dp,
                            coolHighlightAlpha = 0.30f,
                            warmHighlightAlpha = 0.18f,
                            specularSheenAlpha = 0.10f
                        )
                        .padding(bottom = navBottomPadding)
                        .testTag("editor_bottom_panel")
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Tactile Drag Handle Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .pointerInput(collapsedPx, partialPx, expandedPx) {
                                    detectVerticalDragGestures(
                                        onDragEnd = {
                                            panelDetent = snapEditorDetent(
                                                sheetHeightPxAnim.value,
                                                collapsedPx,
                                                partialPx,
                                                expandedPx
                                            )
                                        },
                                        onDragCancel = {
                                            panelDetent = snapEditorDetent(
                                                sheetHeightPxAnim.value,
                                                collapsedPx,
                                                partialPx,
                                                expandedPx
                                            )
                                        },
                                        onVerticalDrag = { change, dragAmount ->
                                            change.consume()
                                            val next = (sheetHeightPxAnim.value - dragAmount)
                                                .coerceIn(collapsedPx, expandedPx)
                                            scope.launch { sheetHeightPxAnim.snapTo(next) }
                                        }
                                    )
                                }
                                .padding(horizontal = 12.dp)
                                .testTag("editor_panel_drag_handle"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = activeTab.title.uppercase(),
                                color = StuDOFocusYellow,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.1.sp,
                                modifier = Modifier.width(64.dp)
                            )

                            Box(
                                modifier = Modifier
                                    .width(42.dp)
                                    .height(4.dp)
                                    .clip(CircleShape)
                                    .background(StuDOSilver.copy(alpha = 0.45f))
                            )

                            Row(
                                modifier = Modifier.width(64.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                IconButton(
                                    onClick = {
                                        panelDetent = when (panelDetent) {
                                            EditorPanelDetent.COLLAPSED -> EditorPanelDetent.PARTIAL
                                            EditorPanelDetent.PARTIAL -> EditorPanelDetent.EXPANDED
                                            EditorPanelDetent.EXPANDED -> EditorPanelDetent.COLLAPSED
                                        }
                                    },
                                    modifier = Modifier
                                        .size(38.dp)
                                        .minimumInteractiveComponentSize()
                                        .testTag("editor_panel_detent_toggle")
                                ) {
                                    Icon(
                                        imageVector = if (panelDetent == EditorPanelDetent.EXPANDED) {
                                            Icons.Default.KeyboardArrowDown
                                        } else {
                                            Icons.Default.KeyboardArrowUp
                                        },
                                        contentDescription = when (panelDetent) {
                                            EditorPanelDetent.COLLAPSED -> "Expand editing panel"
                                            EditorPanelDetent.PARTIAL -> "Maximize editing panel"
                                            EditorPanelDetent.EXPANDED -> "Collapse editing panel"
                                        },
                                        tint = StuDOSilverMuted,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }

                        // Persistent Bottom Navigation Strip (Tune, Crop, Filters, Draw, Text, Badges, Watermark)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 10.dp, vertical = 2.dp)
                                .testTag("editor_bottom_nav"),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val hasActiveWatermark = currentSnapshot.watermarkConfig?.isEnabled == true
                            EditorTab.entries.forEach { tab ->
                                val isSelected = if (tab == EditorTab.WATERMARK) {
                                    hasActiveWatermark
                                } else {
                                    tab == activeTab
                                }
                                Row(
                                    modifier = Modifier
                                        .height(38.dp)
                                        .liquidGlassChipSurface(
                                            shape = RoundedCornerShape(14.dp),
                                            cornerRadius = 14.dp,
                                            isActive = isSelected,
                                            accentColor = StuDOFocusYellow
                                        )
                                        .clickable {
                                            if (pendingBaselineSnapshot != null) commitContinuousEdit()
                                            if (tab == EditorTab.WATERMARK) {
                                                onOpenWatermarkStudio(currentSnapshot)
                                            } else {
                                                activeTab = tab
                                                if (panelDetent == EditorPanelDetent.COLLAPSED) {
                                                    panelDetent = EditorPanelDetent.PARTIAL
                                                }
                                            }
                                        }
                                        .padding(horizontal = 11.dp)
                                        .testTag("editor_nav_${tab.name.lowercase()}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = null,
                                        tint = if (isSelected) StuDOFocusYellow else StuDOSilverMuted,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = tab.title,
                                        color = if (isSelected) StuDOFocusYellow else StuDOSilver,
                                        fontSize = 11.5.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        // Active Tool Workspace Body (collapses cleanly when COLLAPSED)
                        if (panelDetent != EditorPanelDetent.COLLAPSED) {
                            HorizontalDivider(
                                color = Color.White.copy(alpha = 0.08f),
                                thickness = 0.8.dp,
                                modifier = Modifier.padding(top = 6.dp)
                            )

                            AnimatedContent(
                                targetState = activeTab,
                                transitionSpec = {
                                    fadeIn(tween(140)) togetherWith fadeOut(tween(100))
                                },
                                label = "editor_tab_content",
                                modifier = Modifier.weight(1f)
                            ) { tab ->
                                when (tab) {
                                    EditorTab.ADJUST -> TuneEditorPanel(
                                        adjustments = currentSnapshot.adjustments,
                                        selectedTool = selectedAdjustTool,
                                        isExpandedHeight = panelDetent == EditorPanelDetent.EXPANDED,
                                        onSelectTool = { selectedAdjustTool = it },
                                        onPreviewAdjustments = { updated ->
                                            beginContinuousEdit()
                                            currentSnapshot = currentSnapshot.copy(adjustments = updated)
                                        },
                                        onCommitAdjustments = {
                                            commitContinuousEdit()
                                        },
                                        onResetSingleTool = { tool ->
                                            pushSnapshot(
                                                currentSnapshot.copy(
                                                    adjustments = currentSnapshot.adjustments.withValue(tool, tool.defaultVal)
                                                )
                                            )
                                        }
                                    )

                                    EditorTab.CROP -> CropEditorPanel(
                                        cropState = currentSnapshot.crop,
                                        orientedImageWidth = orientedAspectWidth,
                                        orientedImageHeight = orientedAspectHeight,
                                        onRotateCcw = {
                                            pushSnapshot(
                                                currentSnapshot.copy(
                                                    crop = currentSnapshot.crop.copy(
                                                        rotationDegrees = (currentSnapshot.crop.rotationDegrees - 90 + 360) % 360
                                                    )
                                                )
                                            )
                                        },
                                        onRotateCw = {
                                            pushSnapshot(
                                                currentSnapshot.copy(
                                                    crop = currentSnapshot.crop.copy(
                                                        rotationDegrees = (currentSnapshot.crop.rotationDegrees + 90) % 360
                                                    )
                                                )
                                            )
                                        },
                                        onFlipHorizontal = {
                                            pushSnapshot(
                                                currentSnapshot.copy(
                                                    crop = currentSnapshot.crop.copy(
                                                        isFlippedHorizontal = !currentSnapshot.crop.isFlippedHorizontal
                                                    )
                                                )
                                            )
                                        },
                                        onFlipVertical = {
                                            pushSnapshot(
                                                currentSnapshot.copy(
                                                    crop = currentSnapshot.crop.copy(
                                                        isFlippedVertical = !currentSnapshot.crop.isFlippedVertical
                                                    )
                                                )
                                            )
                                        },
                                        onSelectAspectRatio = { aspect, fittedRect ->
                                            pushSnapshot(
                                                currentSnapshot.copy(
                                                    crop = currentSnapshot.crop.copy(
                                                        aspectRatio = aspect,
                                                        cropRect = fittedRect
                                                    )
                                                )
                                            )
                                        },
                                        onResetCrop = {
                                            pushSnapshot(currentSnapshot.copy(crop = CropState()))
                                        },
                                        onApplyCrop = {
                                            activeTab = EditorTab.ADJUST
                                        }
                                    )

                                    EditorTab.FILTERS -> FiltersEditorPanel(
                                        selectedFilter = currentSnapshot.filter,
                                        filterIntensity = currentSnapshot.filterIntensity,
                                        filterThumbnails = filterThumbnails,
                                        onSelectFilter = { preset ->
                                            pushSnapshot(
                                                currentSnapshot.copy(
                                                    filter = preset,
                                                    filterIntensity = if (preset == PhotoFilterType.ORIGINAL) 1.0f else currentSnapshot.filterIntensity.coerceAtLeast(0.15f)
                                                )
                                            )
                                        },
                                        onIntensityChanged = { v ->
                                            beginContinuousEdit()
                                            currentSnapshot = currentSnapshot.copy(filterIntensity = v)
                                        },
                                        onIntensityCommitted = {
                                            commitContinuousEdit()
                                        }
                                    )

                                    EditorTab.DRAW -> DrawEditorPanel(
                                        brushType = drawBrushType,
                                        drawColor = drawColor,
                                        strokeWidth = drawStrokeWidth,
                                        opacity = drawOpacity,
                                        isEraser = isDrawEraser,
                                        hasPaths = currentSnapshot.drawingPaths.isNotEmpty(),
                                        recentColors = recentCustomColors,
                                        onBrushTypeChanged = { drawBrushType = it },
                                        onColorChanged = { drawColor = it },
                                        onColorCommitted = { recordRecentColor(it) },
                                        onStrokeWidthChanged = { drawStrokeWidth = it },
                                        onOpacityChanged = { drawOpacity = it },
                                        onToggleEraser = { isDrawEraser = it },
                                        onClearAllPaths = {
                                            if (currentSnapshot.drawingPaths.isNotEmpty()) {
                                                pushSnapshot(currentSnapshot.copy(drawingPaths = emptyList()))
                                            }
                                        }
                                    )

                                    EditorTab.TEXT -> TextEditorPanel(
                                        textItems = currentSnapshot.textItems,
                                        selectedTextId = selectedTextId,
                                        recentColors = recentCustomColors,
                                        onSelectText = { selectedTextId = it },
                                        onAddNewText = {
                                            val newItem = TextOverlayItem(
                                                text = "StuDO Studio",
                                                color = Color.White,
                                                fontFamily = EditorFontFamily.MODERN_SANS
                                            )
                                            selectedTextId = newItem.id
                                            pushSnapshot(
                                                currentSnapshot.copy(textItems = currentSnapshot.textItems + newItem)
                                            )
                                            if (panelDetent == EditorPanelDetent.PARTIAL) {
                                                panelDetent = EditorPanelDetent.EXPANDED
                                            }
                                        },
                                        onUpdateSelectedText = { isContinuous, transform ->
                                            val targetId = selectedTextId ?: currentSnapshot.textItems.lastOrNull()?.id
                                                ?: return@TextEditorPanel
                                            if (isContinuous) beginContinuousEdit()
                                            val updatedList = currentSnapshot.textItems.map {
                                                if (it.id == targetId) transform(it) else it
                                            }
                                            if (isContinuous) {
                                                currentSnapshot = currentSnapshot.copy(textItems = updatedList)
                                            } else {
                                                pushSnapshot(currentSnapshot.copy(textItems = updatedList))
                                            }
                                        },
                                        onCommitHistory = { commitContinuousEdit() },
                                        onDeleteText = { id ->
                                            val nextList = currentSnapshot.textItems.filterNot { it.id == id }
                                            if (selectedTextId == id) selectedTextId = nextList.lastOrNull()?.id
                                            pushSnapshot(currentSnapshot.copy(textItems = nextList))
                                        },
                                        onRecentColorAdded = { recordRecentColor(it) }
                                    )

                                    EditorTab.STICKERS -> BadgesEditorPanel(
                                        stickerItems = currentSnapshot.stickerItems,
                                        selectedBadgeId = selectedBadgeId,
                                        savedPresets = savedBadgePresets,
                                        recentColors = recentCustomColors,
                                        onSelectBadge = { selectedBadgeId = it },
                                        onAddBadge = { type ->
                                            val newBadge = StickerOverlayItem(type = type)
                                            selectedBadgeId = newBadge.id
                                            pushSnapshot(
                                                currentSnapshot.copy(stickerItems = currentSnapshot.stickerItems + newBadge)
                                            )
                                        },
                                        onAddSavedPreset = { preset ->
                                            val newBadge = StickerOverlayItem(
                                                type = preset.type,
                                                customText = preset.customText,
                                                styleConfig = preset.styleConfig
                                            )
                                            selectedBadgeId = newBadge.id
                                            pushSnapshot(
                                                currentSnapshot.copy(stickerItems = currentSnapshot.stickerItems + newBadge)
                                            )
                                        },
                                        onSaveSelectedAsPreset = { item ->
                                            val label = item.customText?.ifBlank { null } ?: item.type.defaultText
                                            savedBadgePresets.add(
                                                0,
                                                SavedBadgePreset(
                                                    name = label,
                                                    type = item.type,
                                                    customText = label,
                                                    styleConfig = item.styleConfig
                                                )
                                            )
                                        },
                                        onUpdateSelectedBadge = { isContinuous, transform ->
                                            val targetId = selectedBadgeId ?: currentSnapshot.stickerItems.lastOrNull()?.id
                                                ?: return@BadgesEditorPanel
                                            if (isContinuous) beginContinuousEdit()
                                            val updatedList = currentSnapshot.stickerItems.map {
                                                if (it.id == targetId) transform(it) else it
                                            }
                                            if (isContinuous) {
                                                currentSnapshot = currentSnapshot.copy(stickerItems = updatedList)
                                            } else {
                                                pushSnapshot(currentSnapshot.copy(stickerItems = updatedList))
                                            }
                                        },
                                        onCommitHistory = { commitContinuousEdit() },
                                        onDeleteBadge = { id ->
                                            val nextList = currentSnapshot.stickerItems.filterNot { it.id == id }
                                            if (selectedBadgeId == id) selectedBadgeId = nextList.lastOrNull()?.id
                                            pushSnapshot(currentSnapshot.copy(stickerItems = nextList))
                                        },
                                        onRecentColorAdded = { recordRecentColor(it) }
                                    )

                                    EditorTab.WATERMARK -> Box(Modifier.fillMaxSize())
                                }
                            }
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

        // SLOT 2: Floating Top Toolbar
        PhotoEditorTopToolbar(
            visualTier = visualTier,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
            canReset = currentSnapshot != PhotoEditSnapshot(),
            isComparing = isComparingOriginal,
            isExporting = isExporting,
            onClose = {
                if (undoStack.isNotEmpty() || currentSnapshot != PhotoEditSnapshot()) {
                    showDiscardDialog = true
                } else {
                    onDismiss()
                }
            },
            onUndo = { performUndo() },
            onRedo = { performRedo() },
            onComparePress = { isComparingOriginal = true },
            onCompareRelease = { isComparingOriginal = false },
            onReset = { performResetAll() },
            onSave = {
                if (!isExporting) {
                    if (pendingBaselineSnapshot != null) commitContinuousEdit()
                    isExporting = true
                    scope.launch {
                        val savedUri = PhotoProcessingEngine.exportFullResolutionEditedImage(
                            context = context,
                            sourceUri = sourceUri,
                            snapshot = currentSnapshot,
                            isImportedPhoto = isImportedPhoto,
                            preserveLocationMetadata = isSaveLocationEnabled
                        )
                        isExporting = false
                        if (savedUri != null) {
                            onSaveSuccess(savedUri)
                        } else {
                            exportError = "Failed to export high-resolution image copy."
                        }
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )

        // Discard Changes Confirmation Dialog
        if (showDiscardDialog) {
            AlertDialog(
                onDismissRequest = { showDiscardDialog = false },
                containerColor = Color(0xFF1A1C23),
                title = {
                    Text("Discard Changes?", color = StuDOWhite, fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        text = "Unsaved adjustments and overlays will be lost. The original photo remains untouched.",
                        color = StuDOSilverMuted,
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showDiscardDialog = false
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StuDORed),
                        modifier = Modifier.testTag("editor_discard_confirm_button")
                    ) {
                        Text("Discard", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDiscardDialog = false }) {
                        Text("Keep Editing", color = StuDOSilver)
                    }
                }
            )
        }

        // Export Error Dialog
        if (exportError != null) {
            AlertDialog(
                onDismissRequest = { exportError = null },
                containerColor = Color(0xFF1A1C23),
                title = { Text("Save Failed", color = StuDORed, fontWeight = FontWeight.Bold) },
                text = { Text(exportError ?: "", color = StuDOSilver) },
                confirmButton = {
                    TextButton(onClick = { exportError = null }) {
                        Text("OK", color = StuDOFocusYellow)
                    }
                }
            )
        }

        // Authentic Photo Metadata & Info Sheet
        com.example.ui.components.PhotoDetailsSheetOverlay(
            visible = showDetailsSheet,
            metadata = richMetadata,
            isLoading = isLoadingDetails,
            onDismiss = { showDetailsSheet = false }
        )
    }
}
