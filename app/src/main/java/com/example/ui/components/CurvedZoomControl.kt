package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.GlassCoolBlueSpecular
import com.example.ui.theme.GlassObsidianBottom
import com.example.ui.theme.GlassWarmAmberSpecular
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDOFocusYellow
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOWhite
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Computes dynamic lens preset ratios strictly from the active camera's genuine [minZoomRatio]..[maxZoomRatio].
 * Never fabricates zoom values or labels digital zoom as optical zoom.
 */
internal fun computeDynamicZoomPresets(minZoomRatio: Float, maxZoomRatio: Float): List<Float> {
    val safeMin = minZoomRatio.coerceAtLeast(0.1f)
    val safeMax = maxZoomRatio.coerceAtLeast(safeMin)
    val presets = mutableListOf<Float>()

    if (safeMin <= 0.65f) {
        val roundedWide = (safeMin * 10f).roundToInt() / 10f
        presets.add(if (abs(roundedWide - 0.5f) <= 0.1f) 0.5f else roundedWide)
    } else if (safeMin < 0.95f) {
        presets.add((safeMin * 10f).roundToInt() / 10f)
    }

    if (1.0f in safeMin..safeMax) {
        presets.add(1.0f)
    } else if (presets.isEmpty()) {
        presets.add(safeMin)
    }

    if (safeMax >= 2.0f) presets.add(2.0f)
    if (safeMax >= 5.0f) presets.add(5.0f)
    else if (safeMax >= 3.0f && safeMax < 5.0f) presets.add(3.0f)

    return presets.distinct()
}

private fun zoomToNormalized(zoom: Float, minZ: Float, maxZ: Float): Float {
    if (maxZ <= minZ + 0.01f) return 0f
    val clamped = zoom.coerceIn(minZ, maxZ)
    return (ln(clamped / minZ) / ln(maxZ / minZ)).coerceIn(0f, 1f)
}

private fun normalizedToZoom(norm: Float, minZ: Float, maxZ: Float): Float {
    if (maxZ <= minZ + 0.01f) return minZ
    val c = norm.coerceIn(0f, 1f)
    return (minZ * (maxZ / minZ).pow(c)).coerceIn(minZ, maxZ)
}

@Composable
fun CurvedZoomControl(
    zoomRatio: Float,
    minZoomRatio: Float,
    maxZoomRatio: Float,
    hapticsEnabled: Boolean,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = rememberStuDOHaptic(hapticsEnabled)
    val canZoom = maxZoomRatio > minZoomRatio + 0.05f

    var isGestureArcActive by remember { mutableStateOf(false) }
    var isPinnedArcActive by remember { mutableStateOf(false) }
    val isArcVisible = canZoom && (isGestureArcActive || isPinnedArcActive)

    val latestZoom by rememberUpdatedState(zoomRatio)
    val latestMinZoom by rememberUpdatedState(minZoomRatio)
    val latestMaxZoom by rememberUpdatedState(maxZoomRatio)
    val latestOnZoomChange by rememberUpdatedState(onZoomChange)

    val arcProgress by animateFloatAsState(
        targetValue = if (isArcVisible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.78f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "curved_zoom_arc_progress"
    )

    val pillAlpha = (1f - arcProgress * 1.4f).coerceIn(0f, 1f)
    var lastHapticBucket by remember { mutableIntStateOf(-1) }

    fun maybeTriggerMeaningfulZoomHaptic(newZoom: Float) {
        // Meaningful increments: every 0.5x step, plus snapping near 1.0x / min / max
        val bucket = when {
            abs(newZoom - latestMinZoom) < 0.06f -> -10
            abs(newZoom - 1.0f) < 0.06f -> 100
            abs(newZoom - latestMaxZoom) < 0.08f -> 9999
            else -> (newZoom * 2f).roundToInt() // 0.5x increments
        }
        if (bucket != lastHapticBucket) {
            lastHapticBucket = bucket
            haptic.tick()
        }
    }

    val containerHeight by animateDpAsState(
        targetValue = if (isArcVisible) 132.dp else 38.dp,
        animationSpec = spring(
            dampingRatio = 0.78f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "curved_zoom_container_height"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(containerHeight)
            .pointerInput(canZoom) {
                if (!canZoom) return@pointerInput
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var enteredArc = false
                    var dragNorm = zoomToNormalized(latestZoom, latestMinZoom, latestMaxZoom)
                    var totalDx = 0f
                    val downTime = System.currentTimeMillis()
                    lastHapticBucket = (latestZoom * 2f).roundToInt()

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull()
                            ?: break

                        if (!change.pressed) {
                            break
                        }

                        val dx = change.positionChange().x
                        totalDx += dx

                        if (!enteredArc) {
                            val heldMs = System.currentTimeMillis() - downTime
                            if (abs(totalDx) > touchSlop || heldMs >= 180L) {
                                enteredArc = true
                                isGestureArcActive = true
                                isPinnedArcActive = false
                                haptic.tick()
                            }
                        }

                        if (enteredArc) {
                            change.consume()
                            if (abs(dx) > 0.2f) {
                                // Dragging right zooms in; comfortable ~240dp full span
                                val sensitivityPx = 240.dp.toPx().coerceAtLeast(200f)
                                dragNorm = (dragNorm + (dx / sensitivityPx)).coerceIn(0f, 1f)
                                val nextZoom = normalizedToZoom(dragNorm, latestMinZoom, latestMaxZoom)
                                maybeTriggerMeaningfulZoomHaptic(nextZoom)
                                latestOnZoomChange(nextZoom)
                            }
                        }
                    }

                    if (enteredArc) {
                        isGestureArcActive = false
                    }
                }
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        // 1. Compact Idle Liquid Glass Zoom Pill
        if (pillAlpha > 0.02f) {
            val presets = remember(minZoomRatio, maxZoomRatio) {
                computeDynamicZoomPresets(minZoomRatio, maxZoomRatio)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .alpha(pillAlpha)
                    .scale(0.92f + 0.08f * pillAlpha)
                    .liquidGlassRecessedTrack(
                        shape = RoundedCornerShape(24.dp),
                        cornerRadius = 24.dp,
                        coolHighlightAlpha = 0.30f,
                        warmHighlightAlpha = 0.20f
                    )
                    .padding(horizontal = 5.dp, vertical = 4.dp)
                    .testTag("zoom_chips_strip")
            ) {
                presets.forEach { preset ->
                    val isSelected = abs(zoomRatio - preset) < 0.15f
                    val beadScale by animateFloatAsState(
                        targetValue = if (isSelected) 1f else 0.94f,
                        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium),
                        label = "zoom_chip_scale"
                    )
                    val chipModifier = if (isSelected) {
                        Modifier
                            .scale(beadScale)
                            .liquidGlassSurface(
                                shape = CircleShape,
                                cornerRadius = 14.dp,
                                topColor = Color.White.copy(alpha = 0.21f),
                                bottomColor = GlassObsidianBottom.copy(alpha = 0.84f),
                                borderWidth = 0.8.dp,
                                coolHighlightAlpha = 0.44f,
                                warmHighlightAlpha = 0.28f,
                                specularSheenAlpha = 0.20f,
                                internalShadowAlpha = 0.28f
                            )
                    } else {
                        Modifier.clip(CircleShape)
                    }

                    Box(
                        modifier = chipModifier
                            .clickable(enabled = !isGestureArcActive) {
                                if (!isSelected) haptic.tick()
                                isPinnedArcActive = false
                                onZoomChange(preset)
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val label = when {
                            preset < 1.0f -> String.format(Locale.US, "%.1f", preset)
                            abs(preset - preset.roundToInt()) < 0.05f -> "${preset.roundToInt()}"
                            else -> String.format(Locale.US, "%.1f", preset)
                        }
                        Text(
                            text = label,
                            color = if (isSelected) StuDOFocusYellow else StuDOSilver.copy(alpha = 0.78f),
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }

                // Live magnification readout pill (tapping toggles fine curved arc; drag/hold anywhere also opens arc)
                Box(
                    modifier = Modifier
                        .liquidGlassChipSurface(
                            shape = CircleShape,
                            cornerRadius = 14.dp,
                            isActive = isPinnedArcActive,
                            accentColor = StuDOAmber,
                            inactiveTopAlpha = 0.06f,
                            inactiveBottomAlpha = 0.16f,
                            activeFillAlpha = 0.22f
                        )
                        .clickable(enabled = canZoom && !isGestureArcActive) {
                            haptic.tick()
                            isPinnedArcActive = !isPinnedArcActive
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("zoom_live_readout"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${String.format(Locale.US, "%.1f", zoomRatio)}x",
                        color = if (isPinnedArcActive) StuDOAmber else StuDOWhite.copy(alpha = 0.88f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // 2. Expanded Curved Semi-Transparent Glass Zoom Arc (grows upward without shifting layout)
        if (arcProgress > 0.01f) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .height(132.dp)
                    .alpha(arcProgress)
                    .scale(0.86f + 0.14f * arcProgress)
                    .pointerInput(isPinnedArcActive, canZoom) {
                        if (!isPinnedArcActive || !canZoom) return@pointerInput
                        // Allow dragging directly along the pinned arc as well
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var dragNorm = zoomToNormalized(latestZoom, latestMinZoom, latestMaxZoom)
                            lastHapticBucket = (latestZoom * 2f).roundToInt()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val dx = change.positionChange().x
                                if (abs(dx) > 0.2f) {
                                    change.consume()
                                    val sensitivityPx = 240.dp.toPx().coerceAtLeast(200f)
                                    dragNorm = (dragNorm + (dx / sensitivityPx)).coerceIn(0f, 1f)
                                    val nextZoom = normalizedToZoom(dragNorm, latestMinZoom, latestMaxZoom)
                                    maybeTriggerMeaningfulZoomHaptic(nextZoom)
                                    latestOnZoomChange(nextZoom)
                                }
                            }
                        }
                    }
                    .testTag("curved_zoom_arc"),
                contentAlignment = Alignment.TopCenter
            ) {
                CurvedZoomArcCanvas(
                    zoomRatio = zoomRatio,
                    minZoomRatio = minZoomRatio,
                    maxZoomRatio = maxZoomRatio,
                    arcProgress = arcProgress,
                    onCollapse = { isPinnedArcActive = false }
                )
            }
        }
    }
}

@Composable
private fun CurvedZoomArcCanvas(
    zoomRatio: Float,
    minZoomRatio: Float,
    maxZoomRatio: Float,
    arcProgress: Float,
    onCollapse: () -> Unit
) {
    val activeNorm = zoomToNormalized(zoomRatio, minZoomRatio, maxZoomRatio)
    val landmarks = remember(minZoomRatio, maxZoomRatio) {
        val candidates = listOf(0.5f, 0.6f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 8.0f, 10.0f)
        val filtered = candidates.filter { it in (minZoomRatio + 0.05f)..(maxZoomRatio - 0.05f) }
        (listOf(minZoomRatio) + filtered + listOf(maxZoomRatio)).distinct()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val centerX = w / 2f

            // Arc geometry: smooth lens-barrel curve cresting near top-center
            val arcRadius = w * 0.92f
            val arcCenterY = h * 0.34f + arcRadius
            val bandThickness = 44.dp.toPx()
            val halfSpanDeg = 28f // Total 56 degree arc window

            val topArcCenter = Offset(centerX, arcCenterY)
            val rectOuter = Rect(
                left = centerX - arcRadius,
                top = arcCenterY - arcRadius,
                right = centerX + arcRadius,
                bottom = arcCenterY + arcRadius
            )
            val innerR = arcRadius - bandThickness
            val rectInner = Rect(
                left = centerX - innerR,
                top = arcCenterY - innerR,
                right = centerX + innerR,
                bottom = arcCenterY + innerR
            )

            val startAngle = -90f - halfSpanDeg
            val sweepAngle = halfSpanDeg * 2f

            // Transparent lines-only arc: chromatic hairline rim along outer and inner arc edges (no black/dark fill)
            drawArc(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0.0f to GlassCoolBlueSpecular.copy(alpha = 0.48f * arcProgress),
                        0.35f to Color.White.copy(alpha = 0.34f * arcProgress),
                        0.68f to Color.White.copy(alpha = 0.18f * arcProgress),
                        1.0f to GlassWarmAmberSpecular.copy(alpha = 0.36f * arcProgress)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(w, h)
                ),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = rectOuter.topLeft,
                size = rectOuter.size,
                style = Stroke(width = 1.1.dp.toPx(), cap = StrokeCap.Round)
            )

            drawArc(
                color = Color.White.copy(alpha = 0.18f * arcProgress),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = rectInner.topLeft,
                size = rectInner.size,
                style = Stroke(width = 0.8.dp.toPx(), cap = StrokeCap.Round)
            )

            // Fine tick marks rotating relative to activeNorm so active ratio sits under center needle
            val dialSpanDeg = 46f // angular travel for full 0..1 zoom range
            val minorSteps = 28
            for (i in 0..minorSteps) {
                val tickNorm = i.toFloat() / minorSteps.toFloat()
                val relDeg = (tickNorm - activeNorm) * dialSpanDeg
                if (abs(relDeg) <= halfSpanDeg - 2f) {
                    val rad = (relDeg - 90f) * (PI / 180.0)
                    val cosA = cos(rad).toFloat()
                    val sinA = sin(rad).toFloat()
                    val isMajor = i % 7 == 0
                    val rOuterTick = arcRadius - 8.dp.toPx()
                    val rInnerTick = rOuterTick - (if (isMajor) 12.dp.toPx() else 7.dp.toPx())
                    val edgeFade = (1f - (abs(relDeg) / halfSpanDeg).pow(2)).coerceIn(0.18f, 1f)

                    drawLine(
                        color = Color.White.copy(alpha = (if (isMajor) 0.72f else 0.36f) * edgeFade * arcProgress),
                        start = Offset(topArcCenter.x + rInnerTick * cosA, topArcCenter.y + rInnerTick * sinA),
                        end = Offset(topArcCenter.x + rOuterTick * cosA, topArcCenter.y + rOuterTick * sinA),
                        strokeWidth = if (isMajor) 1.5.dp.toPx() else 0.9.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            // Major landmark ticks (dynamically sourced from active camera range)
            landmarks.forEach { lm ->
                val lmNorm = zoomToNormalized(lm, minZoomRatio, maxZoomRatio)
                val relDeg = (lmNorm - activeNorm) * dialSpanDeg
                if (abs(relDeg) <= halfSpanDeg - 2f) {
                    val rad = (relDeg - 90f) * (PI / 180.0)
                    val cosA = cos(rad).toFloat()
                    val sinA = sin(rad).toFloat()
                    val rOuterTick = arcRadius - 6.dp.toPx()
                    val rInnerTick = rOuterTick - 15.dp.toPx()
                    val isNearActive = abs(lmNorm - activeNorm) < 0.04f
                    val tickColor = if (isNearActive) StuDOFocusYellow else StuDOAmber.copy(alpha = 0.85f)
                    drawLine(
                        color = tickColor.copy(alpha = tickColor.alpha * arcProgress),
                        start = Offset(topArcCenter.x + rInnerTick * cosA, topArcCenter.y + rInnerTick * sinA),
                        end = Offset(topArcCenter.x + rOuterTick * cosA, topArcCenter.y + rOuterTick * sinA),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            // Center fixed index needle right below the magnification droplet
            val needleOuter = arcRadius - 3.dp.toPx()
            val needleInner = arcRadius - 21.dp.toPx()
            drawLine(
                color = StuDOFocusYellow.copy(alpha = 0.95f * arcProgress),
                start = Offset(centerX, arcCenterY - needleOuter),
                end = Offset(centerX, arcCenterY - needleInner),
                strokeWidth = 2.2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // Transparent Magnification Droplet / Lens showing active magnification
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, 2.dp.roundToPx()) }
                .scale(1.06f)
                .liquidGlassSurface(
                    shape = RoundedCornerShape(18.dp),
                    cornerRadius = 18.dp,
                    topColor = Color.Transparent,
                    bottomColor = Color.Transparent,
                    borderWidth = 0.95.dp,
                    coolHighlightAlpha = 0.50f,
                    warmHighlightAlpha = 0.32f,
                    specularSheenAlpha = 0.0f,
                    internalShadowAlpha = 0.0f
                )
                .clickable { onCollapse() }
                .padding(horizontal = 14.dp, vertical = 5.dp)
                .testTag("curved_zoom_droplet"),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${String.format(Locale.US, "%.1f", zoomRatio)}x",
                color = StuDOWhite,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp
            )
        }
    }
}
