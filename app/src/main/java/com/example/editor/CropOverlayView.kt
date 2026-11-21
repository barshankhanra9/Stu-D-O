package com.example.editor

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.StuDOFocusYellow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Computes the centered normalized (0..1) crop rectangle for [aspectRatio] on an image
 * whose upright oriented dimensions have aspect `imageWidth / imageHeight`.
 */
fun computeAspectRatioCropRect(
    aspectRatio: CropAspectRatio,
    imageWidth: Float,
    imageHeight: Float
): RectF {
    if (imageWidth <= 0f || imageHeight <= 0f) return RectF(0f, 0f, 1f, 1f)
    val targetRatio = aspectRatio.ratio ?: return RectF(0f, 0f, 1f, 1f)
    val imageAspect = imageWidth / imageHeight
    val (normW, normH) = if (targetRatio > imageAspect) {
        1f to (imageAspect / targetRatio).coerceIn(0.05f, 1f)
    } else {
        (targetRatio / imageAspect).coerceIn(0.05f, 1f) to 1f
    }
    val left = (1f - normW) / 2f
    val top = (1f - normH) / 2f
    return RectF(left, top, left + normW, top + normH)
}

/**
 * Pure pixel-space crop handle drag calculation shared by UI interaction and unit tests.
 */
internal fun computeUpdatedCropPixelRect(
    handle: DragHandle,
    l0: Float,
    t0: Float,
    r0: Float,
    b0: Float,
    dragDelta: Offset,
    totalWidth: Float,
    totalHeight: Float,
    minSizePx: Float,
    lockedRatio: Float?
): RectF {
    var l = l0
    var t = t0
    var r = r0
    var b = b0

    when (handle) {
        DragHandle.INSIDE -> {
            val w = r - l
            val h = b - t
            l = (l + dragDelta.x).coerceIn(0f, max(0f, totalWidth - w))
            t = (t + dragDelta.y).coerceIn(0f, max(0f, totalHeight - h))
            r = l + w
            b = t + h
        }
        DragHandle.TOP_LEFT -> {
            val candL = min(l + dragDelta.x, r - minSizePx).coerceAtLeast(0f)
            val candT = min(t + dragDelta.y, b - minSizePx).coerceAtLeast(0f)
            if (lockedRatio != null) {
                val maxW = r
                val maxH = b
                var w = if (abs(dragDelta.x) >= abs(dragDelta.y)) r - candL else (b - candT) * lockedRatio
                w = w.coerceIn(minSizePx, min(maxW, maxH * lockedRatio))
                val h = w / lockedRatio
                l = (r - w).coerceAtLeast(0f)
                t = (b - h).coerceAtLeast(0f)
            } else {
                l = candL
                t = candT
            }
        }
        DragHandle.TOP_RIGHT -> {
            val candR = max(r + dragDelta.x, l + minSizePx).coerceAtMost(totalWidth)
            val candT = min(t + dragDelta.y, b - minSizePx).coerceAtLeast(0f)
            if (lockedRatio != null) {
                val maxW = totalWidth - l
                val maxH = b
                var w = if (abs(dragDelta.x) >= abs(dragDelta.y)) candR - l else (b - candT) * lockedRatio
                w = w.coerceIn(minSizePx, min(maxW, maxH * lockedRatio))
                val h = w / lockedRatio
                r = (l + w).coerceAtMost(totalWidth)
                t = (b - h).coerceAtLeast(0f)
            } else {
                r = candR
                t = candT
            }
        }
        DragHandle.BOTTOM_LEFT -> {
            val candL = min(l + dragDelta.x, r - minSizePx).coerceAtLeast(0f)
            val candB = max(b + dragDelta.y, t + minSizePx).coerceAtMost(totalHeight)
            if (lockedRatio != null) {
                val maxW = r
                val maxH = totalHeight - t
                var w = if (abs(dragDelta.x) >= abs(dragDelta.y)) r - candL else (candB - t) * lockedRatio
                w = w.coerceIn(minSizePx, min(maxW, maxH * lockedRatio))
                val h = w / lockedRatio
                l = (r - w).coerceAtLeast(0f)
                b = (t + h).coerceAtMost(totalHeight)
            } else {
                l = candL
                b = candB
            }
        }
        DragHandle.BOTTOM_RIGHT -> {
            val candR = max(r + dragDelta.x, l + minSizePx).coerceAtMost(totalWidth)
            val candB = max(b + dragDelta.y, t + minSizePx).coerceAtMost(totalHeight)
            if (lockedRatio != null) {
                val maxW = totalWidth - l
                val maxH = totalHeight - t
                var w = if (abs(dragDelta.x) >= abs(dragDelta.y)) candR - l else (candB - t) * lockedRatio
                w = w.coerceIn(minSizePx, min(maxW, maxH * lockedRatio))
                val h = w / lockedRatio
                r = (l + w).coerceAtMost(totalWidth)
                b = (t + h).coerceAtMost(totalHeight)
            } else {
                r = candR
                b = candB
            }
        }
        DragHandle.EDGE_TOP -> {
            val candT = min(t + dragDelta.y, b - minSizePx).coerceAtLeast(0f)
            if (lockedRatio != null) {
                val cx = (l + r) / 2f
                val maxHalfW = min(cx, totalWidth - cx)
                val maxHByWidth = (maxHalfW * 2f) / lockedRatio
                val h = (b - candT).coerceIn(minSizePx / lockedRatio, min(b, maxHByWidth))
                val halfW = (h * lockedRatio) / 2f
                t = (b - h).coerceAtLeast(0f)
                l = (cx - halfW).coerceAtLeast(0f)
                r = (cx + halfW).coerceAtMost(totalWidth)
            } else {
                t = candT
            }
        }
        DragHandle.EDGE_BOTTOM -> {
            val candB = max(b + dragDelta.y, t + minSizePx).coerceAtMost(totalHeight)
            if (lockedRatio != null) {
                val cx = (l + r) / 2f
                val maxHalfW = min(cx, totalWidth - cx)
                val maxHByWidth = (maxHalfW * 2f) / lockedRatio
                val h = (candB - t).coerceIn(minSizePx / lockedRatio, min(totalHeight - t, maxHByWidth))
                val halfW = (h * lockedRatio) / 2f
                b = (t + h).coerceAtMost(totalHeight)
                l = (cx - halfW).coerceAtLeast(0f)
                r = (cx + halfW).coerceAtMost(totalWidth)
            } else {
                b = candB
            }
        }
        DragHandle.EDGE_LEFT -> {
            val candL = min(l + dragDelta.x, r - minSizePx).coerceAtLeast(0f)
            if (lockedRatio != null) {
                val cy = (t + b) / 2f
                val maxHalfH = min(cy, totalHeight - cy)
                val maxWByHeight = (maxHalfH * 2f) * lockedRatio
                val w = (r - candL).coerceIn(minSizePx, min(r, maxWByHeight))
                val halfH = (w / lockedRatio) / 2f
                l = (r - w).coerceAtLeast(0f)
                t = (cy - halfH).coerceAtLeast(0f)
                b = (cy + halfH).coerceAtMost(totalHeight)
            } else {
                l = candL
            }
        }
        DragHandle.EDGE_RIGHT -> {
            val candR = max(r + dragDelta.x, l + minSizePx).coerceAtMost(totalWidth)
            if (lockedRatio != null) {
                val cy = (t + b) / 2f
                val maxHalfH = min(cy, totalHeight - cy)
                val maxWByHeight = (maxHalfH * 2f) * lockedRatio
                val w = (candR - l).coerceIn(minSizePx, min(totalWidth - l, maxWByHeight))
                val halfH = (w / lockedRatio) / 2f
                r = (l + w).coerceAtMost(totalWidth)
                t = (cy - halfH).coerceAtLeast(0f)
                b = (cy + halfH).coerceAtMost(totalHeight)
            } else {
                r = candR
            }
        }
    }

    return RectF(
        (l / totalWidth).coerceIn(0f, 1f),
        (t / totalHeight).coerceIn(0f, 1f),
        (r / totalWidth).coerceIn(0f, 1f),
        (b / totalHeight).coerceIn(0f, 1f)
    )
}

/**
 * Interactive touch-enabled crop overlay with Rule-of-Thirds grid, continuous 60fps corner/edge/body
 * dragging, and mathematically accurate pixel-space aspect ratio locking.
 */
@Composable
fun CropOverlayView(
    cropState: CropState,
    onCropRectChanged: (RectF) -> Unit,
    onCropDragStarted: () -> Unit = {},
    onCropDragEnded: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalWidth = constraints.maxWidth.toFloat()
        val totalHeight = constraints.maxHeight.toFloat()

        if (totalWidth <= 0f || totalHeight <= 0f) return@BoxWithConstraints

        val latestCropState by rememberUpdatedState(cropState)
        val latestOnCropRectChanged by rememberUpdatedState(onCropRectChanged)
        val latestOnCropDragStarted by rememberUpdatedState(onCropDragStarted)
        val latestOnCropDragEnded by rememberUpdatedState(onCropDragEnded)

        var activeHandle by remember { mutableStateOf<DragHandle?>(null) }
        val touchRadius = with(density) { 44.dp.toPx() }
        val edgeHitThickness = with(density) { 28.dp.toPx() }
        val minSizePx = with(density) { 56.dp.toPx() }

        val currentRect = cropState.cropRect

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("crop_overlay_canvas")
                .pointerInput(totalWidth, totalHeight) {
                    val slopThreshold = minOf(viewConfiguration.touchSlop, 8f)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startOffset = down.position
                        val rect0 = latestCropState.cropRect
                        var dragPxLeft = rect0.left * totalWidth
                        var dragPxTop = rect0.top * totalHeight
                        var dragPxRight = rect0.right * totalWidth
                        var dragPxBottom = rect0.bottom * totalHeight

                        val detected = when {
                            (startOffset - Offset(dragPxLeft, dragPxTop)).getDistance() < touchRadius -> DragHandle.TOP_LEFT
                            (startOffset - Offset(dragPxRight, dragPxTop)).getDistance() < touchRadius -> DragHandle.TOP_RIGHT
                            (startOffset - Offset(dragPxLeft, dragPxBottom)).getDistance() < touchRadius -> DragHandle.BOTTOM_LEFT
                            (startOffset - Offset(dragPxRight, dragPxBottom)).getDistance() < touchRadius -> DragHandle.BOTTOM_RIGHT

                            abs(startOffset.y - dragPxTop) < edgeHitThickness && startOffset.x in dragPxLeft..dragPxRight -> DragHandle.EDGE_TOP
                            abs(startOffset.y - dragPxBottom) < edgeHitThickness && startOffset.x in dragPxLeft..dragPxRight -> DragHandle.EDGE_BOTTOM
                            abs(startOffset.x - dragPxLeft) < edgeHitThickness && startOffset.y in dragPxTop..dragPxBottom -> DragHandle.EDGE_LEFT
                            abs(startOffset.x - dragPxRight) < edgeHitThickness && startOffset.y in dragPxTop..dragPxBottom -> DragHandle.EDGE_RIGHT

                            startOffset.x in dragPxLeft..dragPxRight && startOffset.y in dragPxTop..dragPxBottom -> DragHandle.INSIDE
                            else -> null
                        } ?: return@awaitEachGesture

                        var dragStarted = false
                        var accSlop = Offset.Zero
                        var prevPoint = startOffset

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) {
                                    break
                                }
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                if (change == null || !change.pressed) {
                                    break
                                }
                                val delta = change.position - prevPoint
                                prevPoint = change.position
                                if (delta == Offset.Zero) continue

                                val appliedDelta = if (!dragStarted) {
                                    accSlop += delta
                                    if (accSlop.getDistance() < slopThreshold) {
                                        null
                                    } else {
                                        dragStarted = true
                                        activeHandle = detected
                                        latestOnCropDragStarted()
                                        accSlop
                                    }
                                } else {
                                    delta
                                }

                                if (appliedDelta != null) {
                                    change.consume()
                                    val lockedRatio = when (latestCropState.aspectRatio) {
                                        CropAspectRatio.FREE -> null
                                        CropAspectRatio.ORIGINAL -> totalWidth / totalHeight
                                        else -> latestCropState.aspectRatio.ratio
                                    }

                                    val newNormRect = computeUpdatedCropPixelRect(
                                        handle = detected,
                                        l0 = dragPxLeft,
                                        t0 = dragPxTop,
                                        r0 = dragPxRight,
                                        b0 = dragPxBottom,
                                        dragDelta = appliedDelta,
                                        totalWidth = totalWidth,
                                        totalHeight = totalHeight,
                                        minSizePx = minSizePx,
                                        lockedRatio = lockedRatio
                                    )

                                    dragPxLeft = newNormRect.left * totalWidth
                                    dragPxTop = newNormRect.top * totalHeight
                                    dragPxRight = newNormRect.right * totalWidth
                                    dragPxBottom = newNormRect.bottom * totalHeight

                                    latestOnCropRectChanged(newNormRect)
                                }
                            }
                        } finally {
                            if (dragStarted) {
                                activeHandle = null
                                latestOnCropDragEnded()
                            }
                        }
                    }
                }
        ) {
            val left = currentRect.left * totalWidth
            val top = currentRect.top * totalHeight
            val right = currentRect.right * totalWidth
            val bottom = currentRect.bottom * totalHeight
            val rectWidth = max(1f, right - left)
            val rectHeight = max(1f, bottom - top)

            // 1. Dark outer scrim
            val scrimColor = Color.Black.copy(alpha = 0.68f)
            drawRect(scrimColor, Offset.Zero, Size(totalWidth, top))
            drawRect(scrimColor, Offset(0f, bottom), Size(totalWidth, totalHeight - bottom))
            drawRect(scrimColor, Offset(0f, top), Size(left, rectHeight))
            drawRect(scrimColor, Offset(right, top), Size(totalWidth - right, rectHeight))

            // 2. Crisp crop border outline
            drawRect(
                color = StuDOFocusYellow.copy(alpha = 0.90f),
                topLeft = Offset(left, top),
                size = Size(rectWidth, rectHeight),
                style = Stroke(width = 1.6.dp.toPx())
            )

            // 3. Rule of Thirds guidelines
            val colStep = rectWidth / 3f
            val rowStep = rectHeight / 3f
            val gridColor = Color.White.copy(alpha = if (activeHandle != null) 0.60f else 0.36f)
            val gridStroke = Stroke(
                width = 0.9.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            )

            drawLine(gridColor, Offset(left + colStep, top), Offset(left + colStep, bottom), strokeWidth = gridStroke.width, pathEffect = gridStroke.pathEffect)
            drawLine(gridColor, Offset(left + colStep * 2f, top), Offset(left + colStep * 2f, bottom), strokeWidth = gridStroke.width, pathEffect = gridStroke.pathEffect)
            drawLine(gridColor, Offset(left, top + rowStep), Offset(right, top + rowStep), strokeWidth = gridStroke.width, pathEffect = gridStroke.pathEffect)
            drawLine(gridColor, Offset(left, top + rowStep * 2f), Offset(right, top + rowStep * 2f), strokeWidth = gridStroke.width, pathEffect = gridStroke.pathEffect)

            // 4. Studio Corner L-Brackets
            val cornerLen = min(24.dp.toPx(), min(rectWidth, rectHeight) * 0.3f)
            val handleWidth = 3.8.dp.toPx()
            val handleColor = StuDOFocusYellow

            drawLine(handleColor, Offset(left - 1f, top), Offset(left + cornerLen, top), strokeWidth = handleWidth, cap = StrokeCap.Round)
            drawLine(handleColor, Offset(left, top - 1f), Offset(left, top + cornerLen), strokeWidth = handleWidth, cap = StrokeCap.Round)

            drawLine(handleColor, Offset(right + 1f, top), Offset(right - cornerLen, top), strokeWidth = handleWidth, cap = StrokeCap.Round)
            drawLine(handleColor, Offset(right, top - 1f), Offset(right, top + cornerLen), strokeWidth = handleWidth, cap = StrokeCap.Round)

            drawLine(handleColor, Offset(left - 1f, bottom), Offset(left + cornerLen, bottom), strokeWidth = handleWidth, cap = StrokeCap.Round)
            drawLine(handleColor, Offset(left, bottom + 1f), Offset(left, bottom - cornerLen), strokeWidth = handleWidth, cap = StrokeCap.Round)

            drawLine(handleColor, Offset(right + 1f, bottom), Offset(right - cornerLen, bottom), strokeWidth = handleWidth, cap = StrokeCap.Round)
            drawLine(handleColor, Offset(right, bottom + 1f), Offset(right, bottom - cornerLen), strokeWidth = handleWidth, cap = StrokeCap.Round)

            // 5. Edge Center Grip Pills
            val gripLen = min(20.dp.toPx(), min(rectWidth, rectHeight) * 0.25f)
            val gripWidth = 3.dp.toPx()
            val midX = (left + right) / 2f
            val midY = (top + bottom) / 2f

            drawLine(handleColor, Offset(midX - gripLen / 2f, top), Offset(midX + gripLen / 2f, top), strokeWidth = gripWidth, cap = StrokeCap.Round)
            drawLine(handleColor, Offset(midX - gripLen / 2f, bottom), Offset(midX + gripLen / 2f, bottom), strokeWidth = gripWidth, cap = StrokeCap.Round)
            drawLine(handleColor, Offset(left, midY - gripLen / 2f), Offset(left, midY + gripLen / 2f), strokeWidth = gripWidth, cap = StrokeCap.Round)
            drawLine(handleColor, Offset(right, midY - gripLen / 2f), Offset(right, midY + gripLen / 2f), strokeWidth = gripWidth, cap = StrokeCap.Round)
        }
    }
}

internal enum class DragHandle {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    EDGE_TOP,
    EDGE_BOTTOM,
    EDGE_LEFT,
    EDGE_RIGHT,
    INSIDE
}
