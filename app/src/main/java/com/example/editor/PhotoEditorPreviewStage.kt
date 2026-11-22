package com.example.editor

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector3D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.ui.components.LiquidGlassVisualTier
import com.example.ui.components.adaptiveLiquidGlassPanel
import com.example.ui.components.liquidGlassChipSurface
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDOFocusYellow
import com.example.ui.theme.StuDORed
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite
import com.example.util.RealCameraMetadata
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private const val EDITOR_MIN_ZOOM = 1.0f
private const val EDITOR_MAX_ZOOM = 5.0f

private data class EditorPreviewTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float
)

private val EditorPreviewTransformConverter = TwoWayConverter<EditorPreviewTransform, AnimationVector3D>(
    convertToVector = { AnimationVector3D(it.scale, it.offsetX, it.offsetY) },
    convertFromVector = { EditorPreviewTransform(it.v1, it.v2, it.v3) }
)

internal data class OverlayHitBounds(
    val id: String,
    val isText: Boolean,
    val centerPx: Offset,
    val halfWidthPx: Float,
    val halfHeightPx: Float,
    val rotationDeg: Float,
    val scale: Float
) {
    fun contains(point: Offset, paddingPx: Float): Boolean {
        val dx = point.x - centerPx.x
        val dy = point.y - centerPx.y
        val rad = Math.toRadians(-rotationDeg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val localX = (dx * cosA - dy * sinA) / scale.coerceAtLeast(0.35f)
        val localY = (dx * sinA + dy * cosA) / scale.coerceAtLeast(0.35f)
        return abs(localX) <= halfWidthPx + paddingPx && abs(localY) <= halfHeightPx + paddingPx
    }
}

internal fun clampEditorOffset(
    offset: Offset,
    scale: Float,
    containerSize: IntSize
): Offset {
    if (scale <= EDITOR_MIN_ZOOM + 0.001f || containerSize.width <= 0 || containerSize.height <= 0) {
        return Offset.Zero
    }
    val maxOffsetX = ((containerSize.width * scale - containerSize.width) / 2f).coerceAtLeast(0f)
    val maxOffsetY = ((containerSize.height * scale - containerSize.height) / 2f).coerceAtLeast(0f)
    return Offset(
        x = offset.x.coerceIn(-maxOffsetX, maxOffsetX),
        y = offset.y.coerceIn(-maxOffsetY, maxOffsetY)
    )
}

internal fun computeEditorFocalZoom(
    currentScale: Float,
    currentOffset: Offset,
    zoomChange: Float,
    panChange: Offset,
    centroid: Offset,
    containerSize: IntSize
): Pair<Float, Offset> {
    val safeScale = currentScale.coerceIn(EDITOR_MIN_ZOOM, EDITOR_MAX_ZOOM)
    val nextScale = (safeScale * zoomChange).coerceIn(EDITOR_MIN_ZOOM, EDITOR_MAX_ZOOM)
    if (nextScale <= EDITOR_MIN_ZOOM + 0.001f) {
        return EDITOR_MIN_ZOOM to Offset.Zero
    }
    val zoomFactor = nextScale / safeScale
    val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
    val focalFromCenter = centroid - center
    val unClamped = (currentOffset - focalFromCenter) * zoomFactor + focalFromCenter + panChange
    return nextScale to clampEditorOffset(unClamped, nextScale, containerSize)
}

/**
 * Interactive photo viewport with pinch-to-zoom, pan, double-tap fit reset,
 * accurate WYSIWYG centered text & badge overlays, continuous finger-following overlay drag,
 * and clean crop/draw integration.
 */
@Composable
fun PhotoEditorPreviewStage(
    displayBitmap: Bitmap?,
    isLoadingPreview: Boolean,
    isComparingOriginal: Boolean,
    activeTab: EditorTab,
    snapshot: PhotoEditSnapshot,
    photoMetadata: RealCameraMetadata?,
    visualTier: LiquidGlassVisualTier,
    selectedTextId: String?,
    selectedBadgeId: String?,
    drawColor: Color,
    drawStrokeWidth: Float,
    drawOpacity: Float,
    drawBrushType: DrawBrushType,
    isDrawEraser: Boolean,
    onSelectText: (String) -> Unit,
    onSelectBadge: (String) -> Unit,
    onMoveText: (id: String, newPercent: Offset) -> Unit,
    onMoveBadge: (id: String, newPercent: Offset) -> Unit,
    onOverlayDragStarted: () -> Unit,
    onOverlayDragEnded: () -> Unit,
    onCropRectChanged: (RectF) -> Unit,
    onCropDragStarted: () -> Unit,
    onCropDragEnded: () -> Unit,
    onDrawPathFinished: (DrawPathItem) -> Unit,
    modifier: Modifier = Modifier,
    loadErrorMessage: String? = null,
    onErrorClose: () -> Unit = {},
    isImportedPhoto: Boolean = false,
    isWatermarkBakedInPreview: Boolean = false,
    sourceDimensionsText: String? = null,
    onOpenPhotoInfo: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var previewScale by remember { mutableFloatStateOf(EDITOR_MIN_ZOOM) }
    var previewOffsetX by remember { mutableFloatStateOf(0f) }
    var previewOffsetY by remember { mutableFloatStateOf(0f) }
    var frameSizePx by remember { mutableStateOf(IntSize.Zero) }
    var resetJob by remember { mutableStateOf<Job?>(null) }

    // Reset zoom to fit cleanly when entering Crop mode so handles are fully visible
    LaunchedEffect(activeTab) {
        if (activeTab == EditorTab.CROP && previewScale > EDITOR_MIN_ZOOM + 0.02f) {
            resetJob?.cancel()
            previewScale = EDITOR_MIN_ZOOM
            previewOffsetX = 0f
            previewOffsetY = 0f
        }
    }

    fun animateResetToFit() {
        resetJob?.cancel()
        if (previewScale <= EDITOR_MIN_ZOOM + 0.005f && abs(previewOffsetX) < 0.5f && abs(previewOffsetY) < 0.5f) {
            previewScale = EDITOR_MIN_ZOOM
            previewOffsetX = 0f
            previewOffsetY = 0f
            return
        }
        val anim = Animatable(
            EditorPreviewTransform(previewScale, previewOffsetX, previewOffsetY),
            EditorPreviewTransformConverter
        )
        resetJob = scope.launch {
            val spec = if (visualTier.isLowEndDevice) {
                tween<EditorPreviewTransform>(150)
            } else {
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
            }
            anim.animateTo(EditorPreviewTransform(EDITOR_MIN_ZOOM, 0f, 0f), animationSpec = spec) {
                previewScale = value.scale.coerceIn(EDITOR_MIN_ZOOM, EDITOR_MAX_ZOOM)
                previewOffsetX = value.offsetX
                previewOffsetY = value.offsetY
            }
            previewScale = EDITOR_MIN_ZOOM
            previewOffsetX = 0f
            previewOffsetY = 0f
        }
    }

    LaunchedEffect(frameSizePx, previewScale) {
        if (frameSizePx.width > 0 && frameSizePx.height > 0) {
            val clamped = clampEditorOffset(Offset(previewOffsetX, previewOffsetY), previewScale, frameSizePx)
            previewOffsetX = clamped.x
            previewOffsetY = clamped.y
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("editor_preview_stage"),
        contentAlignment = Alignment.Center
    ) {
        when {
            loadErrorMessage != null -> {
                Column(
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .adaptiveLiquidGlassPanel(
                            visualTier = visualTier,
                            shape = RoundedCornerShape(22.dp),
                            cornerRadius = 22.dp
                        )
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                        .testTag("editor_load_error_card"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.BrokenImage,
                        contentDescription = null,
                        tint = StuDORed,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = "Unable to Open Image",
                        color = StuDOWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = loadErrorMessage,
                        color = StuDOSilver,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Button(
                        onClick = onErrorClose,
                        colors = ButtonDefaults.buttonColors(containerColor = StuDOFocusYellow),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("editor_load_error_close_button")
                    ) {
                        Text("Close", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }

            isLoadingPreview -> {
                CircularProgressIndicator(
                    color = StuDOFocusYellow,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(36.dp)
                )
            }

            displayBitmap == null || displayBitmap.isRecycled -> {
                Text(
                    text = "Preparing Photo...",
                    color = StuDOSilverMuted,
                    fontSize = 12.sp
                )
            }

            else -> {
                val bmpW = displayBitmap.width.coerceAtLeast(1).toFloat()
        val bmpH = displayBitmap.height.coerceAtLeast(1).toFloat()
        val bmpAspect = bmpW / bmpH
        val boxAspect = if (maxHeight > 0.dp) maxWidth / maxHeight else 1f

        val fittedW = if (bmpAspect > boxAspect) maxWidth else maxHeight * bmpAspect
        val fittedH = if (bmpAspect > boxAspect) maxWidth / bmpAspect else maxHeight

        val hitBoundsMap = remember { mutableStateMapOf<String, OverlayHitBounds>() }
        val latestTextItems by rememberUpdatedState(snapshot.textItems)
        val latestStickerItems by rememberUpdatedState(snapshot.stickerItems)
        val latestOnSelectText by rememberUpdatedState(onSelectText)
        val latestOnSelectBadge by rememberUpdatedState(onSelectBadge)
        val latestOnMoveText by rememberUpdatedState(onMoveText)
        val latestOnMoveBadge by rememberUpdatedState(onMoveBadge)
        val latestOnDragStarted by rememberUpdatedState(onOverlayDragStarted)
        val latestOnDragEnded by rememberUpdatedState(onOverlayDragEnded)

        Box(
            modifier = Modifier
                .size(width = fittedW, height = fittedH)
                .clip(RoundedCornerShape(8.dp))
                .clipToBounds()
                .border(0.75.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                .onSizeChanged { frameSizePx = it }
                .pointerInput(activeTab) {
                    if (activeTab != EditorTab.CROP && activeTab != EditorTab.DRAW) {
                        detectTapGestures(onDoubleTap = { animateResetToFit() })
                    }
                }
                .pointerInput(activeTab) {
                    awaitEachGesture {
                        var zoomDeltaAcc = 1f
                        var panDeltaAcc = Offset.Zero
                        var gestureLocked = false
                        val touchSlop = viewConfiguration.touchSlop

                        awaitFirstDown(requireUnconsumed = false)
                        resetJob?.cancel()

                        try {
                            do {
                                val event = awaitPointerEvent()
                                val pressedCount = event.changes.count { it.pressed }
                                if (pressedCount == 0 || event.changes.any { it.isConsumed }) {
                                    break
                                }

                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val centroidSize = event.calculateCentroidSize(useCurrent = false)

                                if (!gestureLocked) {
                                    if (pressedCount >= 2) {
                                        zoomDeltaAcc *= zoomChange
                                        panDeltaAcc += panChange
                                        val zoomMotion = abs(1f - zoomDeltaAcc) * centroidSize
                                        if (zoomMotion > touchSlop || panDeltaAcc.getDistance() > touchSlop || abs(zoomChange - 1f) > 0.01f) {
                                            gestureLocked = true
                                        }
                                    } else if (previewScale > EDITOR_MIN_ZOOM + 0.01f && activeTab != EditorTab.DRAW) {
                                        panDeltaAcc += panChange
                                        if (panDeltaAcc.getDistance() > touchSlop) {
                                            gestureLocked = true
                                        }
                                    }
                                }

                                if (gestureLocked) {
                                    val applyZoom = pressedCount >= 2 || abs(zoomChange - 1f) > 0.001f
                                    val applyPan = previewScale > EDITOR_MIN_ZOOM + 0.01f || pressedCount >= 2
                                    if (applyZoom || applyPan) {
                                        val safeCentroid = if (centroid == Offset.Unspecified) {
                                            Offset(size.width / 2f, size.height / 2f)
                                        } else {
                                            centroid
                                        }
                                        val (nextScale, nextOffset) = computeEditorFocalZoom(
                                            currentScale = previewScale,
                                            currentOffset = Offset(previewOffsetX, previewOffsetY),
                                            zoomChange = if (applyZoom) zoomChange else 1f,
                                            panChange = if (applyPan) panChange else Offset.Zero,
                                            centroid = safeCentroid,
                                            containerSize = size
                                        )
                                        previewScale = nextScale
                                        previewOffsetX = nextOffset.x
                                        previewOffsetY = nextOffset.y
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            val clamped = clampEditorOffset(
                                Offset(previewOffsetX, previewOffsetY),
                                previewScale,
                                size
                            )
                            previewOffsetX = clamped.x
                            previewOffsetY = clamped.y
                        }
                    }
                }
                .testTag("editor_photo_preview_frame"),
            contentAlignment = Alignment.Center
        ) {
            // Zoomable & Pannable Transformed Content Layer
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = previewScale
                        scaleY = previewScale
                        translationX = previewOffsetX
                        translationY = previewOffsetY
                    }
            ) {
                // 1. Rendered Photo (matches export pixel-for-pixel)
                Image(
                    bitmap = displayBitmap.asImageBitmap(),
                    contentDescription = "Photo being edited",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("editor_photo_preview_image")
                )

                if (!isComparingOriginal) {
                    // 2. Vector Drawing Layer
                    if (activeTab == EditorTab.DRAW) {
                        DrawCanvasView(
                            paths = if (isWatermarkBakedInPreview) emptyList() else snapshot.drawingPaths,
                            currentColor = drawColor,
                            currentStrokeWidth = drawStrokeWidth,
                            currentOpacity = drawOpacity,
                            currentBrushType = drawBrushType,
                            isEraser = isDrawEraser,
                            isInteractive = true,
                            onPathFinished = onDrawPathFinished,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (!isWatermarkBakedInPreview && snapshot.drawingPaths.isNotEmpty()) {
                        DrawCanvasView(
                            paths = snapshot.drawingPaths,
                            currentColor = Color.Transparent,
                            currentStrokeWidth = 1f,
                            isEraser = false,
                            isInteractive = false,
                            onPathFinished = {},
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // 3. Centered Text & Badge Visual Layer (WYSIWYG alignment with Canvas export)
                    if (!isWatermarkBakedInPreview && activeTab != EditorTab.CROP && (snapshot.textItems.isNotEmpty() || snapshot.stickerItems.isNotEmpty())) {
                        val densityFactor = (with(density) { fittedW.toPx() }) / 600f
                        val scaleDensityFactor = (densityFactor / density.density).coerceAtLeast(0.25f)

                        val visibleBadges = remember(snapshot.stickerItems, photoMetadata) {
                            snapshot.stickerItems.filter {
                                PhotoProcessingEngine.resolveBadgeDisplayText(it, photoMetadata) != null
                            }
                        }

                        // Clean unused hit bounds when items deleted
                        val activeIds = buildSet {
                            snapshot.textItems.forEach { add(it.id) }
                            visibleBadges.forEach { add(it.id) }
                        }
                        hitBoundsMap.keys.toList().forEach { key ->
                            if (key !in activeIds) hitBoundsMap.remove(key)
                        }

                        val orderedOverlays: List<Pair<String, Offset>> = buildList {
                            snapshot.textItems.forEach { add(it.id to it.positionPercent) }
                            visibleBadges.forEach { add(it.id to it.positionPercent) }
                        }

                        // Stationary Overlay Pointer Hit-Test & Continuous 60fps Drag Layer
                        val hitPadPx = with(density) { 14.dp.toPx() }
                        val canInteractOverlays = activeTab != EditorTab.DRAW

                        Layout(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (canInteractOverlays) {
                                        Modifier.pointerInput(Unit) {
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                val wF = size.width.toFloat().coerceAtLeast(1f)
                                                val hF = size.height.toFloat().coerceAtLeast(1f)

                                                // Search in reverse draw order (Badges over Text)
                                                val badgeHits = latestStickerItems.reversed().mapNotNull { hitBoundsMap[it.id] }
                                                val textHits = latestTextItems.reversed().mapNotNull { hitBoundsMap[it.id] }
                                                val hit = (badgeHits + textHits).firstOrNull { it.contains(down.position, hitPadPx) }
                                                    ?: return@awaitEachGesture

                                                // Select overlay cleanly
                                                if (hit.isText) {
                                                    latestOnSelectText(hit.id)
                                                } else {
                                                    latestOnSelectBadge(hit.id)
                                                }

                                                val startPosPercent = if (hit.isText) {
                                                    latestTextItems.firstOrNull { it.id == hit.id }?.positionPercent
                                                } else {
                                                    latestStickerItems.firstOrNull { it.id == hit.id }?.positionPercent
                                                } ?: return@awaitEachGesture

                                                var dragStarted = false
                                                var accDelta = Offset.Zero
                                                var prevPoint = down.position

                                                try {
                                                    while (true) {
                                                        val event = awaitPointerEvent()
                                                        val pressedCount = event.changes.count { it.pressed }
                                                        if (pressedCount >= 2) {
                                                            // Multi-touch pinch requested -> yield cleanly
                                                            break
                                                        }
                                                        val change = event.changes.firstOrNull { it.id == down.id }
                                                            ?: event.changes.firstOrNull()
                                                        if (change == null || !change.pressed) {
                                                            break
                                                        }
                                                        val moveDelta = change.position - prevPoint
                                                        prevPoint = change.position
                                                        if (moveDelta != Offset.Zero) {
                                                            accDelta += moveDelta
                                                            if (!dragStarted && accDelta.getDistance() > 2f) {
                                                                dragStarted = true
                                                                latestOnDragStarted()
                                                            }
                                                            if (dragStarted) {
                                                                change.consume()
                                                                val nextPercent = Offset(
                                                                    x = (startPosPercent.x + accDelta.x / wF).coerceIn(0.03f, 0.97f),
                                                                    y = (startPosPercent.y + accDelta.y / hF).coerceIn(0.03f, 0.97f)
                                                                )
                                                                if (hit.isText) {
                                                                    latestOnMoveText(hit.id, nextPercent)
                                                                } else {
                                                                    latestOnMoveBadge(hit.id, nextPercent)
                                                                }
                                                            }
                                                        }
                                                    }
                                                } finally {
                                                    if (dragStarted) {
                                                        latestOnDragEnded()
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Modifier
                                    }
                                ),
                            content = {
                                snapshot.textItems.forEach { item ->
                                    val isSel = activeTab == EditorTab.TEXT && item.id == selectedTextId
                                    Box {
                                        PreviewTextOverlayChip(
                                            item = item,
                                            isSelected = isSel,
                                            densityFactor = densityFactor,
                                            scaleDensityFactor = scaleDensityFactor
                                        )
                                    }
                                }
                                visibleBadges.forEach { badge ->
                                    val isSel = activeTab == EditorTab.STICKERS && badge.id == selectedBadgeId
                                    Box {
                                        PreviewBadgeOverlayChip(
                                            sticker = badge,
                                            metadata = photoMetadata,
                                            isSelected = isSel,
                                            scaleDensityFactor = scaleDensityFactor
                                        )
                                    }
                                }
                            }
                        ) { measurables, constraints ->
                            val stageW = constraints.maxWidth
                            val stageH = constraints.maxHeight
                            val loose = constraints.copy(minWidth = 0, minHeight = 0)
                            val placeables = measurables.map { it.measure(loose) }

                            val textCount = snapshot.textItems.size
                            placeables.forEachIndexed { idx, p ->
                                if (idx < orderedOverlays.size) {
                                    val isText = idx < textCount
                                    val id = orderedOverlays[idx].first
                                    val pos = orderedOverlays[idx].second
                                    val rot = if (isText) snapshot.textItems[idx].rotation else visibleBadges[idx - textCount].rotation
                                    val sc = if (isText) snapshot.textItems[idx].scale else visibleBadges[idx - textCount].scale
                                    hitBoundsMap[id] = OverlayHitBounds(
                                        id = id,
                                        isText = isText,
                                        centerPx = Offset(pos.x * stageW, pos.y * stageH),
                                        halfWidthPx = max(24f, p.width / 2f),
                                        halfHeightPx = max(20f, p.height / 2f),
                                        rotationDeg = rot,
                                        scale = sc
                                    )
                                }
                            }

                            layout(stageW, stageH) {
                                placeables.forEachIndexed { idx, placeable ->
                                    val pos = orderedOverlays[idx].second
                                    val x = (pos.x * stageW - placeable.width / 2f).roundToInt()
                                    val y = (pos.y * stageH - placeable.height / 2f).roundToInt()
                                    placeable.placeRelative(x, y)
                                }
                            }
                        }
                    }

                    // 4. Precision Interactive Crop Overlay
                    if (activeTab == EditorTab.CROP) {
                        CropOverlayView(
                            cropState = snapshot.crop,
                            onCropRectChanged = onCropRectChanged,
                            onCropDragStarted = onCropDragStarted,
                            onCropDragEnded = onCropDragEnded,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // Before/After Compare Tag
            if (isComparingOriginal) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.70f))
                        .border(0.8.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                        .testTag("editor_original_badge")
                ) {
                    Text(
                        text = "ORIGINAL",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }

            // Transient Zoom Badge when zoomed in
            if (previewScale > EDITOR_MIN_ZOOM + 0.02f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .border(0.8.dp, StuDOAmber.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .clickable { animateResetToFit() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .semantics { contentDescription = "Reset photo zoom" }
                        .testTag("editor_zoom_badge")
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1f×", previewScale),
                        color = StuDOFocusYellow,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Authentic source origin + metadata sheet trigger pill
        if (!isComparingOriginal) {
            val sourceTagLabel = if (isImportedPhoto) "IMPORTED" else "STU•D•O"
            val pillText = if (!sourceDimensionsText.isNullOrBlank()) {
                "$sourceTagLabel · $sourceDimensionsText"
            } else {
                sourceTagLabel
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .minimumInteractiveComponentSize()
                    .clickable(onClick = onOpenPhotoInfo)
                    .semantics { contentDescription = "View photo metadata and details" }
                    .testTag("editor_metadata_info_button"),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(12.dp),
                            cornerRadius = 12.dp,
                            isActive = false,
                            accentColor = StuDOAmber
                        )
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = if (isImportedPhoto) StuDOAmber else StuDOFocusYellow,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = pillText,
                        color = StuDOWhite,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
            }
        }
    }
}

@Composable
private fun PreviewTextOverlayChip(
    item: TextOverlayItem,
    isSelected: Boolean,
    densityFactor: Float,
    scaleDensityFactor: Float
) {
    val bgStyle = if (!item.hasBackground) TextBackgroundStyle.NONE else item.backgroundStyle
    val bgShape = when (bgStyle) {
        TextBackgroundStyle.PILL -> CircleShape
        TextBackgroundStyle.ROUNDED -> RoundedCornerShape((14f * scaleDensityFactor).dp)
        TextBackgroundStyle.OUTLINE -> RoundedCornerShape((8f * scaleDensityFactor).dp)
        TextBackgroundStyle.NONE -> RoundedCornerShape(6.dp)
    }

    val composeAlign = when (item.textAlign) {
        EditorTextAlign.LEFT -> TextAlign.Start
        EditorTextAlign.CENTER -> TextAlign.Center
        EditorTextAlign.RIGHT -> TextAlign.End
    }

    val boxBgColor = when (bgStyle) {
        TextBackgroundStyle.NONE -> Color.Transparent
        TextBackgroundStyle.OUTLINE -> item.backgroundColor.copy(alpha = item.backgroundOpacity * 0.45f)
        else -> item.backgroundColor.copy(alpha = item.backgroundOpacity)
    }

    val selectionBorderMod = if (isSelected) {
        Modifier.drawBehind {
            drawRoundRect(
                color = StuDOFocusYellow.copy(alpha = 0.9f),
                cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
                style = Stroke(
                    width = 1.4.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                )
            )
        }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = item.scale
                scaleY = item.scale
                rotationZ = item.rotation
            }
            .then(selectionBorderMod)
            .padding(if (isSelected) 4.dp else 0.dp)
            .clip(bgShape)
            .background(boxBgColor)
            .then(
                if (bgStyle == TextBackgroundStyle.OUTLINE) {
                    Modifier.border((2f * scaleDensityFactor).dp, item.color.copy(alpha = item.textOpacity), bgShape)
                } else {
                    Modifier
                }
            )
            .padding(
                horizontal = if (bgStyle == TextBackgroundStyle.PILL) (24f * scaleDensityFactor).dp else (18f * scaleDensityFactor).dp,
                vertical = (12f * scaleDensityFactor).dp
            )
            .testTag("preview_text_item_${item.id}"),
        contentAlignment = Alignment.Center
    ) {
        val fontSizeSp = (item.fontSize * scaleDensityFactor).coerceAtLeast(4f).sp
        val styleBase = TextStyle(
            fontFamily = item.fontFamily.toComposeFontFamily(),
            fontSize = fontSizeSp,
            fontWeight = if (item.isBold || item.fontWeight >= 650) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (item.isItalic) FontStyle.Italic else FontStyle.Normal,
            textAlign = composeAlign,
            letterSpacing = item.letterSpacing.em,
            lineHeight = fontSizeSp * item.lineSpacing.coerceIn(0.8f, 2.2f)
        )

        Box(contentAlignment = Alignment.Center) {
            if (item.hasOutline) {
                Text(
                    text = item.text,
                    style = styleBase.copy(
                        color = item.outlineColor.copy(alpha = item.textOpacity),
                        drawStyle = Stroke(width = (item.outlineWidth * densityFactor * 2f).coerceAtLeast(1f))
                    )
                )
            }
            Text(
                text = item.text,
                style = styleBase.copy(
                    color = item.color.copy(alpha = item.textOpacity),
                    shadow = if (item.hasShadow) {
                        Shadow(
                            color = Color.Black.copy(alpha = 0.65f * item.textOpacity),
                            offset = Offset(0f, 2f * densityFactor),
                            blurRadius = 6f * densityFactor
                        )
                    } else null
                )
            )
        }
    }
}

@Composable
private fun PreviewBadgeOverlayChip(
    sticker: StickerOverlayItem,
    metadata: RealCameraMetadata?,
    isSelected: Boolean,
    scaleDensityFactor: Float
) {
    val label = PhotoProcessingEngine.resolveBadgeDisplayText(sticker, metadata) ?: return
    val style = sticker.styleConfig

    val shape = when (style.shape) {
        BadgeShape.PILL, BadgeShape.CIRCLE -> CircleShape
        BadgeShape.RIBBON -> GenericShape { sz, _ ->
            val notch = sz.height * 0.24f
            moveTo(0f, 0f)
            lineTo(sz.width, 0f)
            lineTo(sz.width - notch, sz.height / 2f)
            lineTo(sz.width, sz.height)
            lineTo(0f, sz.height)
            lineTo(notch, sz.height / 2f)
            close()
        }
        else -> RoundedCornerShape((style.cornerRadiusDp * scaleDensityFactor).coerceAtLeast(2f).dp)
    }

    val bgColor = if (style.shape == BadgeShape.OUTLINED || style.backgroundColor.alpha <= 0.01f) {
        Color.Transparent
    } else {
        style.backgroundColor.copy(alpha = (style.backgroundColor.alpha * style.opacity).coerceIn(0f, 1f))
    }

    val borderWidth = if (style.borderThicknessDp > 0.05f || style.shape == BadgeShape.OUTLINED || style.shape == BadgeShape.FRAME || style.shape == BadgeShape.STAMP) {
        (max(style.borderThicknessDp, 1.1f) * scaleDensityFactor).dp
    } else 0.dp

    val selectionBorder = if (isSelected) {
        Modifier.drawBehind {
            drawRoundRect(
                color = StuDOFocusYellow.copy(alpha = 0.9f),
                cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
                style = Stroke(
                    width = 1.4.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                )
            )
        }
    } else Modifier

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = sticker.scale
                scaleY = sticker.scale
                rotationZ = sticker.rotation
            }
            .then(selectionBorder)
            .padding(if (isSelected) 4.dp else 0.dp)
            .then(if (style.hasShadow && bgColor != Color.Transparent) Modifier.shadow((4f * scaleDensityFactor).dp, shape) else Modifier)
            .clip(shape)
            .background(bgColor)
            .then(
                if (borderWidth > 0.dp && style.shape != BadgeShape.RIBBON) {
                    Modifier.border(borderWidth, style.borderColor.copy(alpha = style.opacity), shape)
                } else Modifier
            )
            .padding(
                horizontal = when (style.shape) {
                    BadgeShape.CIRCLE -> (14f * scaleDensityFactor).dp
                    BadgeShape.RIBBON -> (20f * scaleDensityFactor).dp
                    else -> (14f * scaleDensityFactor).dp
                },
                vertical = when (style.shape) {
                    BadgeShape.CIRCLE -> (14f * scaleDensityFactor).dp
                    else -> (8f * scaleDensityFactor).dp
                }
            )
            .testTag("preview_badge_item_${sticker.id}"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = style.textColor.copy(alpha = style.opacity),
            fontFamily = style.fontFamily.toComposeFontFamily(),
            fontSize = (style.fontSizeSp * scaleDensityFactor).coerceAtLeast(7f).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}
