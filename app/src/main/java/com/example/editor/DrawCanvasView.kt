package com.example.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag

/**
 * Low-latency freehand vector drawing canvas layer with brush style support (Pen, Marker,
 * Highlighter, Neon, Chisel), stroke opacity, offscreen eraser compositing, and automatic
 * multi-touch yield so 2-finger pinch-to-zoom remains uninterrupted.
 */
@Composable
fun DrawCanvasView(
    paths: List<DrawPathItem>,
    currentColor: Color,
    currentStrokeWidth: Float,
    currentOpacity: Float = 1.0f,
    currentBrushType: DrawBrushType = DrawBrushType.PEN,
    isEraser: Boolean,
    isInteractive: Boolean = true,
    onPathFinished: (DrawPathItem) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalWidth = constraints.maxWidth.toFloat()
        val totalHeight = constraints.maxHeight.toFloat()

        if (totalWidth <= 0f || totalHeight <= 0f) return@BoxWithConstraints

        val livePoints = remember { mutableStateListOf<Offset>() }
        val latestColor by rememberUpdatedState(currentColor)
        val latestWidth by rememberUpdatedState(currentStrokeWidth)
        val latestOpacity by rememberUpdatedState(currentOpacity)
        val latestBrush by rememberUpdatedState(currentBrushType)
        val latestEraser by rememberUpdatedState(isEraser)
        val latestOnPathFinished by rememberUpdatedState(onPathFinished)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("draw_canvas_surface")
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .then(
                    if (isInteractive) {
                        Modifier.pointerInput(totalWidth, totalHeight) {
                            awaitEachGesture {
                                val firstDown = awaitFirstDown(requireUnconsumed = false)
                                livePoints.clear()
                                livePoints.add(
                                    Offset(
                                        (firstDown.position.x / totalWidth).coerceIn(0f, 1f),
                                        (firstDown.position.y / totalHeight).coerceIn(0f, 1f)
                                    )
                                )
                                var cancelledByMultiTouch = false

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressedCount = event.changes.count { it.pressed }
                                    if (pressedCount >= 2) {
                                        // Yield immediately to parent pinch-to-zoom / pan
                                        livePoints.clear()
                                        cancelledByMultiTouch = true
                                        break
                                    }
                                    val activeChange = event.changes.firstOrNull { it.id == firstDown.id }
                                        ?: event.changes.firstOrNull()
                                    if (activeChange == null || !activeChange.pressed) {
                                        break
                                    }
                                    activeChange.consume()
                                    val normPos = Offset(
                                        (activeChange.position.x / totalWidth).coerceIn(0f, 1f),
                                        (activeChange.position.y / totalHeight).coerceIn(0f, 1f)
                                    )
                                    val prev = livePoints.lastOrNull()
                                    if (prev == null || (normPos - prev).getDistance() > 0.001f) {
                                        livePoints.add(normPos)
                                    }
                                }

                                if (!cancelledByMultiTouch && livePoints.size >= 2) {
                                    val completed = DrawPathItem(
                                        points = livePoints.toList(),
                                        color = if (latestEraser) Color.Transparent else latestColor,
                                        strokeWidth = latestWidth,
                                        isEraser = latestEraser,
                                        opacity = latestOpacity,
                                        brushType = latestBrush
                                    )
                                    latestOnPathFinished(completed)
                                }
                                livePoints.clear()
                            }
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            paths.forEach { item ->
                drawVectorStroke(item.points, item.color, item.strokeWidth, item.opacity, item.brushType, item.isEraser, totalWidth, totalHeight)
            }

            if (livePoints.size >= 2) {
                drawVectorStroke(
                    points = livePoints,
                    color = latestColor,
                    strokeWidth = latestWidth,
                    opacity = latestOpacity,
                    brushType = latestBrush,
                    isEraser = latestEraser,
                    totalWidth = totalWidth,
                    totalHeight = totalHeight
                )
            }
        }
    }
}

private fun DrawScope.drawVectorStroke(
    points: List<Offset>,
    color: Color,
    strokeWidth: Float,
    opacity: Float,
    brushType: DrawBrushType,
    isEraser: Boolean,
    totalWidth: Float,
    totalHeight: Float
) {
    if (points.size < 2) return
    val p = Path()
    val p0 = points[0]
    p.moveTo(p0.x * totalWidth, p0.y * totalHeight)

    for (i in 1 until points.size) {
        val pPrev = points[i - 1]
        val pCur = points[i]
        val midX = (pPrev.x + pCur.x) / 2f * totalWidth
        val midY = (pPrev.y + pCur.y) / 2f * totalHeight
        p.quadraticTo(pPrev.x * totalWidth, pPrev.y * totalHeight, midX, midY)
    }
    val last = points.last()
    p.lineTo(last.x * totalWidth, last.y * totalHeight)

    val baseStrokePx = strokeWidth * (totalWidth / 400f).coerceAtLeast(1.5f)

    if (isEraser) {
        drawPath(
            path = p,
            color = Color.Black,
            blendMode = BlendMode.Clear,
            style = Stroke(width = baseStrokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        return
    }

    val safeOpacity = opacity.coerceIn(0.05f, 1f)
    when (brushType) {
        DrawBrushType.NEON -> {
            drawPath(
                path = p,
                color = color.copy(alpha = safeOpacity * 0.35f),
                style = Stroke(width = baseStrokePx * 2.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawPath(
                path = p,
                color = color.copy(alpha = safeOpacity * 0.65f),
                style = Stroke(width = baseStrokePx * 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawPath(
                path = p,
                color = Color.White.copy(alpha = safeOpacity),
                style = Stroke(width = baseStrokePx * 0.72f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        DrawBrushType.HIGHLIGHTER -> {
            drawPath(
                path = p,
                color = color.copy(alpha = (safeOpacity * 0.42f).coerceAtMost(0.45f)),
                style = Stroke(width = baseStrokePx * 1.65f, cap = StrokeCap.Square, join = StrokeJoin.Bevel)
            )
        }
        DrawBrushType.MARKER -> {
            drawPath(
                path = p,
                color = color.copy(alpha = safeOpacity * 0.88f),
                style = Stroke(width = baseStrokePx * 1.25f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        DrawBrushType.CALLIGRAPHY -> {
            drawPath(
                path = p,
                color = color.copy(alpha = safeOpacity),
                style = Stroke(width = baseStrokePx * 1.15f, cap = StrokeCap.Butt, join = StrokeJoin.Miter)
            )
        }
        DrawBrushType.PEN -> {
            drawPath(
                path = p,
                color = color.copy(alpha = safeOpacity),
                style = Stroke(width = baseStrokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}
