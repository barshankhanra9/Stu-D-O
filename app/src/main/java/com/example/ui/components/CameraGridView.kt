package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.model.GridOption

@Composable
fun CameraGridView(
    gridOption: GridOption,
    modifier: Modifier = Modifier
) {
    if (gridOption == GridOption.OFF) return

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            if (width <= 0f || height <= 0f) return@Canvas

            val strokeColor = Color.White.copy(alpha = 0.50f)
            val shadowColor = Color.Black.copy(alpha = 0.24f)
            val strokeWidth = 1.dp.toPx()
            val shadowWidth = 2.dp.toPx()
            val dotColor = Color.White.copy(alpha = 0.88f)
            val dotShadowColor = Color.Black.copy(alpha = 0.30f)
            val dotRadius = 2.5.dp.toPx()

            fun drawCrissLine(start: Offset, end: Offset, alphaMul: Float = 1f) {
                drawLine(shadowColor.copy(alpha = shadowColor.alpha * alphaMul), start, end, shadowWidth)
                drawLine(strokeColor.copy(alpha = strokeColor.alpha * alphaMul), start, end, strokeWidth)
            }

            fun drawAnchorDot(center: Offset, radius: Float = dotRadius) {
                drawCircle(dotShadowColor, radius = radius + 0.75.dp.toPx(), center = center)
                drawCircle(dotColor, radius = radius, center = center)
            }

            when (gridOption) {
                GridOption.OFF -> {}

                GridOption.THIRDS -> {
                    val x1 = width / 3f
                    val x2 = (width * 2f) / 3f
                    val y1 = height / 3f
                    val y2 = (height * 2f) / 3f

                    drawCrissLine(Offset(x1, 0f), Offset(x1, height))
                    drawCrissLine(Offset(x2, 0f), Offset(x2, height))
                    drawCrissLine(Offset(0f, y1), Offset(width, y1))
                    drawCrissLine(Offset(0f, y2), Offset(width, y2))

                    drawAnchorDot(Offset(x1, y1))
                    drawAnchorDot(Offset(x2, y1))
                    drawAnchorDot(Offset(x1, y2))
                    drawAnchorDot(Offset(x2, y2))
                }

                GridOption.FOUR_BY_FOUR -> {
                    for (i in 1..3) {
                        val x = width * (i / 4f)
                        val y = height * (i / 4f)
                        val emphasis = if (i == 2) 1.0f else 0.78f
                        drawCrissLine(Offset(x, 0f), Offset(x, height), emphasis)
                        drawCrissLine(Offset(0f, y), Offset(width, y), emphasis)
                    }
                    // Precision center cross marker
                    drawAnchorDot(Offset(width / 2f, height / 2f), radius = 2.dp.toPx())
                }

                GridOption.DIAGONAL -> {
                    // Corner-to-corner composition diagonals + center cross axes
                    drawCrissLine(Offset(0f, 0f), Offset(width, height), 0.90f)
                    drawCrissLine(Offset(width, 0f), Offset(0f, height), 0.90f)
                    drawCrissLine(Offset(width / 2f, 0f), Offset(width / 2f, height), 0.65f)
                    drawCrissLine(Offset(0f, height / 2f), Offset(width, height / 2f), 0.65f)
                    drawAnchorDot(Offset(width / 2f, height / 2f), radius = 2.2.dp.toPx())
                }

                GridOption.GOLDEN_RATIO -> {
                    // Phi proportions (approx 0.382 and 0.618)
                    val phi1 = 0.382f
                    val phi2 = 0.618f

                    val x1 = width * phi1
                    val x2 = width * phi2
                    val y1 = height * phi1
                    val y2 = height * phi2

                    drawCrissLine(Offset(x1, 0f), Offset(x1, height))
                    drawCrissLine(Offset(x2, 0f), Offset(x2, height))
                    drawCrissLine(Offset(0f, y1), Offset(width, y1))
                    drawCrissLine(Offset(0f, y2), Offset(width, y2))

                    drawAnchorDot(Offset(x1, y1))
                    drawAnchorDot(Offset(x2, y1))
                    drawAnchorDot(Offset(x1, y2))
                    drawAnchorDot(Offset(x2, y2))
                }

                GridOption.GOLDEN_SPIRAL -> {
                    drawFibonacciSpiralGuide(
                        width = width,
                        height = height,
                        strokeColor = strokeColor,
                        shadowColor = shadowColor,
                        strokeWidth = strokeWidth,
                        shadowWidth = shadowWidth,
                        drawAnchorDot = ::drawAnchorDot
                    )
                }

                GridOption.SQUARE -> {
                    // Center square 1:1 framing guide with corner brackets & crosshair
                    val minSide = minOf(width, height)
                    val left = (width - minSide) / 2f
                    val top = (height - minSide) / 2f

                    drawRect(
                        color = shadowColor,
                        topLeft = Offset(left, top),
                        size = Size(minSide, minSide),
                        style = Stroke(width = shadowWidth * 1.2f)
                    )
                    drawRect(
                        color = Color.White.copy(alpha = 0.65f),
                        topLeft = Offset(left, top),
                        size = Size(minSide, minSide),
                        style = Stroke(width = strokeWidth * 1.4f)
                    )

                    val centerX = width / 2f
                    val centerY = height / 2f
                    val crosshairLen = 12.dp.toPx()
                    drawCrissLine(
                        Offset(centerX - crosshairLen, centerY),
                        Offset(centerX + crosshairLen, centerY)
                    )
                    drawCrissLine(
                        Offset(centerX, centerY - crosshairLen),
                        Offset(centerX, centerY + crosshairLen)
                    )
                }

                GridOption.CENTER_CROSS -> {
                    val cx = width / 2f
                    val cy = height / 2f
                    val crossArm = 20.dp.toPx()
                    val innerGap = 5.dp.toPx()

                    // Split reticle arms around exact center pixel for unobstructed sighting
                    drawCrissLine(Offset(cx - crossArm, cy), Offset(cx - innerGap, cy), 1.15f)
                    drawCrissLine(Offset(cx + innerGap, cy), Offset(cx + crossArm, cy), 1.15f)
                    drawCrissLine(Offset(cx, cy - crossArm), Offset(cx, cy - innerGap), 1.15f)
                    drawCrissLine(Offset(cx, cy + innerGap), Offset(cx, cy + crossArm), 1.15f)

                    // Subtle center alignment ring + quarter tick marks on frame axes
                    drawCircle(
                        color = shadowColor,
                        radius = innerGap,
                        center = Offset(cx, cy),
                        style = Stroke(width = shadowWidth)
                    )
                    drawCircle(
                        color = dotColor,
                        radius = innerGap,
                        center = Offset(cx, cy),
                        style = Stroke(width = strokeWidth)
                    )

                    val tick = 10.dp.toPx()
                    drawCrissLine(Offset(0f, cy), Offset(tick, cy), 0.85f)
                    drawCrissLine(Offset(width - tick, cy), Offset(width, cy), 0.85f)
                    drawCrissLine(Offset(cx, 0f), Offset(cx, tick), 0.85f)
                    drawCrissLine(Offset(cx, height - tick), Offset(cx, height), 0.85f)
                }

                GridOption.CINEMA_239 -> {
                    // True 2.39:1 Anamorphic Widescreen letterbox framing guide
                    val targetAspect = 2.39f
                    val frameW: Float
                    val frameH: Float
                    if (width >= height) {
                        val candidateH = width / targetAspect
                        if (candidateH <= height) {
                            frameW = width
                            frameH = candidateH
                        } else {
                            frameH = height
                            frameW = height * targetAspect
                        }
                    } else {
                        // In portrait orientation, frame 2.39:1 wide crop horizontally across viewfinder width
                        frameW = width
                        frameH = (width / targetAspect).coerceAtMost(height)
                    }
                    val left = (width - frameW) / 2f
                    val top = (height - frameH) / 2f
                    val right = left + frameW
                    val bottom = top + frameH

                    // Subtle matte scrim outside 2.39:1 frame
                    if (top > 1f) {
                        drawRect(Color.Black.copy(alpha = 0.24f), Offset.Zero, Size(width, top))
                        drawRect(Color.Black.copy(alpha = 0.24f), Offset(0f, bottom), Size(width, height - bottom))
                    }
                    if (left > 1f) {
                        drawRect(Color.Black.copy(alpha = 0.24f), Offset(0f, top), Size(left, frameH))
                        drawRect(Color.Black.copy(alpha = 0.24f), Offset(right, top), Size(width - right, frameH))
                    }

                    // Crisp 2.39:1 border lines
                    drawCrissLine(Offset(left, top), Offset(right, top), 1.1f)
                    drawCrissLine(Offset(left, bottom), Offset(right, bottom), 1.1f)
                    if (left > 1f) {
                        drawCrissLine(Offset(left, top), Offset(left, bottom), 1.1f)
                        drawCrissLine(Offset(right, top), Offset(right, bottom), 1.1f)
                    }

                    // Center cinema horizon cross
                    val cx = width / 2f
                    val cy = height / 2f
                    val mark = 10.dp.toPx()
                    drawCrissLine(Offset(cx - mark, cy), Offset(cx + mark, cy), 0.85f)
                    drawCrissLine(Offset(cx, cy - mark), Offset(cx, cy + mark), 0.85f)
                }
            }
        }
    }
}

private fun DrawScope.drawFibonacciSpiralGuide(
    width: Float,
    height: Float,
    strokeColor: Color,
    shadowColor: Color,
    strokeWidth: Float,
    shadowWidth: Float,
    drawAnchorDot: (Offset, Float) -> Unit
) {
    // Classic golden rectangle subdivision (Phi = 0.618034)
    val phi = 0.6180339887f
    val spiralPath = Path()
    val divLines = mutableListOf<Pair<Offset, Offset>>()

    var left = 0f
    var top = 0f
    var right = width
    var bottom = height

    // 7 nested golden rectangle subdivisions winding clockwise into the focal eye
    for (step in 0 until 7) {
        val w = right - left
        val h = bottom - top
        if (w < 4f || h < 4f) break

        when (step % 4) {
            0 -> {
                // Cut square from left/top portion depending on orientation
                val cut = w * phi
                val xSplit = left + cut
                divLines.add(Offset(xSplit, top) to Offset(xSplit, bottom))
                val rx = cut
                val ry = h
                val arcRect = Rect(left, top, left + rx * 2f, top + ry * 2f)
                if (step == 0) {
                    spiralPath.arcTo(arcRect, 180f, 90f, true)
                } else {
                    spiralPath.arcTo(arcRect, 180f, 90f, false)
                }
                left = xSplit
            }
            1 -> {
                val cut = h * phi
                val ySplit = top + cut
                divLines.add(Offset(left, ySplit) to Offset(right, ySplit))
                val arcRect = Rect(right - w * 2f, top, right, top + cut * 2f)
                spiralPath.arcTo(arcRect, 270f, 90f, false)
                top = ySplit
            }
            2 -> {
                val cut = w * phi
                val xSplit = right - cut
                divLines.add(Offset(xSplit, top) to Offset(xSplit, bottom))
                val arcRect = Rect(right - cut * 2f, bottom - h * 2f, right, bottom)
                spiralPath.arcTo(arcRect, 0f, 90f, false)
                right = xSplit
            }
            3 -> {
                val cut = h * phi
                val ySplit = bottom - cut
                divLines.add(Offset(left, ySplit) to Offset(right, ySplit))
                val arcRect = Rect(left, bottom - cut * 2f, left + w * 2f, bottom)
                spiralPath.arcTo(arcRect, 90f, 90f, false)
                bottom = ySplit
            }
        }
    }

    // Draw subtle golden rectangle subdivision lines
    divLines.forEach { (start, end) ->
        drawLine(shadowColor.copy(alpha = 0.14f), start, end, shadowWidth)
        drawLine(strokeColor.copy(alpha = 0.30f), start, end, strokeWidth)
    }

    // Draw smooth spiral arc
    drawPath(
        path = spiralPath,
        color = shadowColor,
        style = Stroke(width = shadowWidth * 1.15f)
    )
    drawPath(
        path = spiralPath,
        color = Color.White.copy(alpha = 0.68f),
        style = Stroke(width = strokeWidth * 1.25f)
    )

    // Highlight focal eye of the spiral
    drawAnchorDot(Offset((left + right) / 2f, (top + bottom) / 2f), 2.5.dp.toPx())
}
