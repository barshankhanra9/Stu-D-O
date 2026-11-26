package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.HistogramData
import com.example.model.HistogramDisplayMode
import com.example.ui.theme.GlassCoolBlueSpecular
import com.example.ui.theme.GlassObsidianBottom
import com.example.ui.theme.GlassObsidianTop
import com.example.ui.theme.GlassWarmAmberSpecular
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDORed
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

private val RgbRedCurveColor = Color(0xFFFF453A)
private val RgbGreenCurveColor = Color(0xFF32D74B)
private val RgbBlueCurveColor = Color(0xFF409CFF)

/**
 * Full-screen floating overlay host that positions [LiveHistogramView] over the camera viewfinder
 * without pushing the 4:3 frame downward, supporting free dragging within safe screen bounds
 * and smooth spring snap-to-corner/edge physics.
 */
@Composable
fun FloatingLiveHistogramOverlay(
    exposureIndex: Int,
    histogramDataState: StateFlow<HistogramData>,
    histogramMode: HistogramDisplayMode,
    isCollapsed: Boolean,
    hapticsEnabled: Boolean,
    onToggleMode: () -> Unit,
    onToggleCollapsed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val haptic = rememberStuDOHaptic(hapticsEnabled)

    val statusTopPx = WindowInsets.statusBars.getTop(density).toFloat()
    val navBottomPx = WindowInsets.navigationBars.getBottom(density).toFloat()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag("floating_histogram_bounds")
    ) {
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }

        val edgeMarginPx = with(density) { 12.dp.toPx() }
        val topSafeMarginPx = statusTopPx + with(density) { 64.dp.toPx() }
        val bottomSafeMarginPx = navBottomPx + with(density) { 16.dp.toPx() }
        val cornerMagnetPx = with(density) { 48.dp.toPx() }

        var cardSize by remember { mutableStateOf(IntSize.Zero) }
        var hasInitializedPosition by remember { mutableStateOf(false) }
        var snappedToRight by remember { mutableStateOf(true) }
        var isDragging by remember { mutableStateOf(false) }

        val offsetX = remember { Animatable(0f) }
        val offsetY = remember { Animatable(0f) }

        val cardW = cardSize.width.toFloat()
        val cardH = cardSize.height.toFloat()
        val minX = edgeMarginPx
        val maxX = (containerWidthPx - cardW - edgeMarginPx).coerceAtLeast(minX)
        val minY = topSafeMarginPx.coerceAtMost((containerHeightPx - cardH - bottomSafeMarginPx).coerceAtLeast(0f))
        val maxY = (containerHeightPx - cardH - bottomSafeMarginPx).coerceAtLeast(minY)

        val latestMinX by rememberUpdatedState(minX)
        val latestMaxX by rememberUpdatedState(maxX)
        val latestMinY by rememberUpdatedState(minY)
        val latestMaxY by rememberUpdatedState(maxY)

        // Initialize at top-right under CameraTopBar and keep edge-anchored when collapsing/expanding
        LaunchedEffect(cardSize, containerWidthPx, containerHeightPx, minY, maxY) {
            if (cardSize.width <= 0 || cardSize.height <= 0 || containerWidthPx <= 0f) return@LaunchedEffect
            if (!hasInitializedPosition) {
                hasInitializedPosition = true
                snappedToRight = true
                offsetX.snapTo(maxX)
                offsetY.snapTo(minY)
            } else if (!isDragging) {
                val targetX = if (snappedToRight) maxX else minX
                val clampedY = offsetY.value.coerceIn(minY, maxY)
                launch {
                    if (abs(offsetX.value - targetX) > 0.5f) {
                        offsetX.animateTo(
                            targetValue = targetX,
                            animationSpec = spring(
                                dampingRatio = 0.82f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )
                    }
                }
                launch {
                    if (abs(offsetY.value - clampedY) > 0.5f) {
                        offsetY.animateTo(
                            targetValue = clampedY,
                            animationSpec = spring(
                                dampingRatio = 0.82f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )
                    }
                }
            }
        }

        val dragScale by animateFloatAsState(
            targetValue = if (isDragging) 1.045f else 1f,
            animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium),
            label = "histogram_drag_scale"
        )

        LiveHistogramView(
            exposureIndex = exposureIndex,
            histogramDataState = histogramDataState,
            histogramMode = histogramMode,
            isCollapsed = isCollapsed,
            hapticsEnabled = hapticsEnabled,
            onToggleMode = onToggleMode,
            onToggleCollapsed = {
                haptic.confirm()
                onToggleCollapsed()
            },
            modifier = Modifier
                .onSizeChanged { cardSize = it }
                .offset {
                    IntOffset(
                        x = offsetX.value.roundToInt(),
                        y = offsetY.value.roundToInt()
                    )
                }
                .graphicsLayer {
                    scaleX = dragScale
                    scaleY = dragScale
                }
                .pointerInput(Unit) {
                    val velocityTracker = VelocityTracker()
                    detectDragGestures(
                        onDragStart = {
                            isDragging = true
                            velocityTracker.resetTracking()
                            haptic.tick()
                        },
                        onDragEnd = {
                            isDragging = false
                            val vel = velocityTracker.calculateVelocity()
                            val vx = vel.x.coerceIn(-2400f, 2400f)
                            val vy = vel.y.coerceIn(-2400f, 2400f)

                            val curMinX = latestMinX
                            val curMaxX = latestMaxX
                            val curMinY = latestMinY
                            val curMaxY = latestMaxY

                            val projectedX = offsetX.value + vx * 0.14f
                            val projectedY = offsetY.value + vy * 0.14f

                            val snapRight = projectedX >= (curMinX + curMaxX) * 0.5f
                            snappedToRight = snapRight
                            val targetX = if (snapRight) curMaxX else curMinX

                            val clampedY = projectedY.coerceIn(curMinY, curMaxY)
                            val targetY = when {
                                clampedY - curMinY <= cornerMagnetPx -> curMinY
                                curMaxY - clampedY <= cornerMagnetPx -> curMaxY
                                else -> clampedY
                            }

                            haptic.tick()
                            coroutineScope.launch {
                                launch {
                                    offsetX.animateTo(
                                        targetValue = targetX,
                                        initialVelocity = vx,
                                        animationSpec = spring(
                                            dampingRatio = 0.78f,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                                launch {
                                    offsetY.animateTo(
                                        targetValue = targetY,
                                        initialVelocity = vy,
                                        animationSpec = spring(
                                            dampingRatio = 0.80f,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                            val curMinX = latestMinX
                            val curMaxX = latestMaxX
                            val curMinY = latestMinY
                            val curMaxY = latestMaxY
                            val snapRight = offsetX.value >= (curMinX + curMaxX) * 0.5f
                            snappedToRight = snapRight
                            val targetX = if (snapRight) curMaxX else curMinX
                            val targetY = offsetY.value.coerceIn(curMinY, curMaxY)
                            coroutineScope.launch {
                                launch { offsetX.animateTo(targetX, spring()) }
                                launch { offsetY.animateTo(targetY, spring()) }
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val nextX = (offsetX.value + dragAmount.x).coerceIn(latestMinX, latestMaxX)
                            val nextY = (offsetY.value + dragAmount.y).coerceIn(latestMinY, latestMaxY)
                            velocityTracker.addPosition(
                                change.uptimeMillis,
                                Offset(nextX + change.position.x, nextY + change.position.y)
                            )
                            coroutineScope.launch {
                                offsetX.snapTo(nextX)
                                offsetY.snapTo(nextY)
                            }
                        }
                    )
                }
        )
    }
}

@Composable
fun LiveHistogramView(
    exposureIndex: Int,
    histogramBinsState: StateFlow<List<Float>>? = null,
    histogramDataState: StateFlow<HistogramData>? = null,
    histogramMode: HistogramDisplayMode = HistogramDisplayMode.LUMA,
    isCollapsed: Boolean = false,
    hapticsEnabled: Boolean = true,
    onToggleMode: () -> Unit = {},
    onToggleCollapsed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptic = rememberStuDOHaptic(hapticsEnabled)

    val collectedData = histogramDataState?.collectAsState()?.value
    val collectedBins = if (histogramDataState == null && histogramBinsState != null) {
        histogramBinsState.collectAsState().value
    } else {
        emptyList()
    }

    val targetData = remember(collectedData, collectedBins) {
        when {
            collectedData != null -> collectedData
            collectedBins.isNotEmpty() -> HistogramData(
                lumaBins = collectedBins,
                redBins = collectedBins,
                greenBins = collectedBins,
                blueBins = collectedBins
            )
            else -> HistogramData.Empty
        }
    }

    // Smooth 60fps interpolated curve arrays without triggering composable recomposition
    val binCount = if (targetData.lumaBins.size >= 2) targetData.lumaBins.size else 32
    val displayedLuma = remember(binCount) { FloatArray(binCount) }
    val displayedRed = remember(binCount) { FloatArray(binCount) }
    val displayedGreen = remember(binCount) { FloatArray(binCount) }
    val displayedBlue = remember(binCount) { FloatArray(binCount) }
    var displayedShadowClip by remember { mutableStateOf(0f) }
    var displayedHighlightClip by remember { mutableStateOf(0f) }
    var animationFrameTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(targetData, binCount) {
        var lastFrameNs = 0L
        while (true) {
            val settled = withFrameNanos { frameNs ->
                val dt = if (lastFrameNs == 0L) {
                    0.016f
                } else {
                    ((frameNs - lastFrameNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                }
                lastFrameNs = frameNs
                // Exponential smoothing tuned for crisp yet silky 60fps transitions
                val alpha = (1f - exp(-dt * 18f)).coerceIn(0.08f, 1f)

                var maxDiff = 0f
                val hasData = targetData.hasData
                for (i in 0 until binCount) {
                    val tLuma = if (hasData) targetData.lumaBins.getOrElse(i) { 0f }.coerceIn(0f, 1f) else 0f
                    val tRed = if (hasData) targetData.redBins.getOrElse(i) { tLuma }.coerceIn(0f, 1f) else 0f
                    val tGreen = if (hasData) targetData.greenBins.getOrElse(i) { tLuma }.coerceIn(0f, 1f) else 0f
                    val tBlue = if (hasData) targetData.blueBins.getOrElse(i) { tLuma }.coerceIn(0f, 1f) else 0f

                    val dL = tLuma - displayedLuma[i]
                    val dR = tRed - displayedRed[i]
                    val dG = tGreen - displayedGreen[i]
                    val dB = tBlue - displayedBlue[i]

                    displayedLuma[i] += dL * alpha
                    displayedRed[i] += dR * alpha
                    displayedGreen[i] += dG * alpha
                    displayedBlue[i] += dB * alpha

                    maxDiff = maxOf(maxDiff, abs(dL), abs(dR), abs(dG), abs(dB))
                }

                val dShadow = targetData.shadowClipFraction - displayedShadowClip
                val dHigh = targetData.highlightClipFraction - displayedHighlightClip
                displayedShadowClip += dShadow * alpha
                displayedHighlightClip += dHigh * alpha
                maxDiff = maxOf(maxDiff, abs(dShadow), abs(dHigh))

                animationFrameTick++

                if (maxDiff < 0.002f) {
                    for (i in 0 until binCount) {
                        val tLuma = if (hasData) targetData.lumaBins.getOrElse(i) { 0f }.coerceIn(0f, 1f) else 0f
                        displayedLuma[i] = tLuma
                        displayedRed[i] = if (hasData) targetData.redBins.getOrElse(i) { tLuma }.coerceIn(0f, 1f) else 0f
                        displayedGreen[i] = if (hasData) targetData.greenBins.getOrElse(i) { tLuma }.coerceIn(0f, 1f) else 0f
                        displayedBlue[i] = if (hasData) targetData.blueBins.getOrElse(i) { tLuma }.coerceIn(0f, 1f) else 0f
                    }
                    displayedShadowClip = targetData.shadowClipFraction
                    displayedHighlightClip = targetData.highlightClipFraction
                    animationFrameTick++
                    true
                } else {
                    false
                }
            }
            if (settled) break
        }
    }

    val rgbMix by animateFloatAsState(
        targetValue = if (histogramMode == HistogramDisplayMode.RGB) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "histogram_mode_crossfade"
    )

    val cornerDp = if (isCollapsed) 20.dp else 18.dp
    val containerShape = RoundedCornerShape(cornerDp)
    val accessibilityDesc = if (isCollapsed) {
        stringResource(R.string.histogram_collapsed_cd)
    } else {
        stringResource(R.string.histogram_expanded_cd)
    }

    val isShadowClipping = targetData.isShadowClipping
    val isHighlightClipping = targetData.isHighlightClipping

    Box(
        modifier = modifier
            .floatingLiquidGlassShadow(cornerRadius = cornerDp)
            .liquidGlassSurface(
                shape = containerShape,
                cornerRadius = cornerDp,
                topColor = GlassObsidianTop.copy(alpha = 0.60f),
                bottomColor = GlassObsidianBottom.copy(alpha = 0.78f),
                borderWidth = 0.9.dp,
                coolHighlightAlpha = 0.36f,
                warmHighlightAlpha = 0.24f,
                specularSheenAlpha = 0.16f,
                internalShadowAlpha = 0.28f
            )
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.82f,
                    stiffness = Spring.StiffnessMedium
                )
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        onToggleCollapsed()
                    }
                )
            }
            .semantics {
                contentDescription = accessibilityDesc
            }
            .testTag("live_histogram_view")
    ) {
        AnimatedContent(
            targetState = isCollapsed,
            transitionSpec = {
                (fadeIn(tween(180)) + scaleIn(initialScale = 0.92f, animationSpec = tween(200)))
                    .togetherWith(fadeOut(tween(140)) + scaleOut(targetScale = 0.92f, animationSpec = tween(160)))
            },
            label = "histogram_collapse_expand"
        ) { collapsed ->
            if (collapsed) {
                CompactHistogramMiniPill(
                    histogramMode = histogramMode,
                    isShadowClipping = isShadowClipping,
                    shadowClipFraction = targetData.shadowClipFraction,
                    isHighlightClipping = isHighlightClipping,
                    highlightClipFraction = targetData.highlightClipFraction,
                    onToggleMode = {
                        haptic.tick()
                        onToggleMode()
                    },
                    onToggleCollapsed = onToggleCollapsed
                )
            } else {
                ExpandedHistogramContent(
                    histogramMode = histogramMode,
                    hasData = targetData.hasData,
                    rgbMix = rgbMix,
                    displayedLuma = displayedLuma,
                    displayedRed = displayedRed,
                    displayedGreen = displayedGreen,
                    displayedBlue = displayedBlue,
                    animationFrameTick = animationFrameTick,
                    isShadowClipping = isShadowClipping,
                    shadowClipFraction = targetData.shadowClipFraction,
                    isHighlightClipping = isHighlightClipping,
                    highlightClipFraction = targetData.highlightClipFraction,
                    onToggleMode = {
                        haptic.tick()
                        onToggleMode()
                    },
                    onToggleCollapsed = onToggleCollapsed
                )
            }
        }
    }
}

@Composable
private fun CompactHistogramMiniPill(
    histogramMode: HistogramDisplayMode,
    isShadowClipping: Boolean,
    shadowClipFraction: Float,
    isHighlightClipping: Boolean,
    highlightClipFraction: Float,
    onToggleMode: () -> Unit,
    onToggleCollapsed: () -> Unit
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("histogram_mini_pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        ClippingIndicatorDot(
            isClipping = isShadowClipping,
            clipFraction = shadowClipFraction,
            contentDescriptionText = stringResource(R.string.histogram_shadow_clip_cd),
            modifier = Modifier.testTag("histogram_shadow_clip_dot")
        )

        // Micro waveform emblem
        Canvas(modifier = Modifier.size(width = 16.dp, height = 10.dp)) {
            val w = size.width
            val h = size.height
            val heights = floatArrayOf(0.35f, 0.72f, 1.0f, 0.58f, 0.30f)
            val barW = w / (heights.size * 1.6f)
            val step = w / heights.size
            val barColor = if (histogramMode == HistogramDisplayMode.RGB) {
                GlassCoolBlueSpecular.copy(alpha = 0.90f)
            } else {
                GlassWarmAmberSpecular.copy(alpha = 0.90f)
            }
            for (i in heights.indices) {
                val bh = (h * heights[i]).coerceAtLeast(2f)
                val bx = i * step + (step - barW) * 0.5f
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(bx, h - bh),
                    size = Size(barW, bh),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                )
            }
        }

        HistogramModePillButton(
            mode = histogramMode,
            onClick = onToggleMode,
            onDoubleClick = onToggleCollapsed
        )

        ClippingIndicatorDot(
            isClipping = isHighlightClipping,
            clipFraction = highlightClipFraction,
            contentDescriptionText = stringResource(R.string.histogram_highlight_clip_cd),
            modifier = Modifier.testTag("histogram_highlight_clip_dot")
        )
    }
}

@Composable
private fun ExpandedHistogramContent(
    histogramMode: HistogramDisplayMode,
    hasData: Boolean,
    rgbMix: Float,
    displayedLuma: FloatArray,
    displayedRed: FloatArray,
    displayedGreen: FloatArray,
    displayedBlue: FloatArray,
    animationFrameTick: Int,
    isShadowClipping: Boolean,
    shadowClipFraction: Float,
    isHighlightClipping: Boolean,
    highlightClipFraction: Float,
    onToggleMode: () -> Unit,
    onToggleCollapsed: () -> Unit
) {
    val lumaFillBrush = remember {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.White.copy(alpha = 0.48f),
                0.55f to GlassWarmAmberSpecular.copy(alpha = 0.26f),
                1.0f to StuDOAmber.copy(alpha = 0.04f)
            )
        )
    }
    val lumaStrokeBrush = remember {
        Brush.horizontalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.88f),
                GlassWarmAmberSpecular.copy(alpha = 0.96f),
                Color.White.copy(alpha = 0.90f)
            )
        )
    }
    val redFillBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                RgbRedCurveColor.copy(alpha = 0.46f),
                RgbRedCurveColor.copy(alpha = 0.05f)
            )
        )
    }
    val greenFillBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                RgbGreenCurveColor.copy(alpha = 0.46f),
                RgbGreenCurveColor.copy(alpha = 0.05f)
            )
        )
    }
    val blueFillBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                RgbBlueCurveColor.copy(alpha = 0.46f),
                RgbBlueCurveColor.copy(alpha = 0.05f)
            )
        )
    }

    // Pre-allocated reusable Paths for zero-allocation 60fps Canvas drawing
    val fillPath = remember { Path() }
    val strokePath = remember { Path() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(156.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("histogram_expanded_content")
    ) {
        // Header: Title + Interactive LUMA / RGB mode toggle pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.histogram_title),
                color = StuDOSilver.copy(alpha = 0.90f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.85.sp
            )
            HistogramModePillButton(
                mode = histogramMode,
                onClick = onToggleMode,
                onDoubleClick = null
            )
        }

        // Smooth Cubic Bézier Graph Canvas with Quartile Grid Lines
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            // Background quartile grid layer (always crisp, unaffected by BlendMode.Plus offscreen layer)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val gridLineWidth = 0.75.dp.toPx()
                val quartileColor = Color.White.copy(alpha = 0.10f)
                val midQuartileColor = Color.White.copy(alpha = 0.14f)
                val horizontalColor = Color.White.copy(alpha = 0.06f)

                // Vertical quartile lines (25%, 50%, 75%) separating Shadows, Midtones, Highlights
                drawLine(quartileColor, Offset(w * 0.25f, 0f), Offset(w * 0.25f, h), gridLineWidth)
                drawLine(midQuartileColor, Offset(w * 0.50f, 0f), Offset(w * 0.50f, h), gridLineWidth)
                drawLine(quartileColor, Offset(w * 0.75f, 0f), Offset(w * 0.75f, h), gridLineWidth)

                // Subtle horizontal 50% reference line
                drawLine(horizontalColor, Offset(0f, h * 0.50f), Offset(w, h * 0.50f), gridLineWidth)
            }

            // Foreground curve Canvas (Offscreen compositing enables authentic additive RGB overlap)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .testTag("histogram_curve_canvas")
            ) {
                // Read animationFrameTick inside draw block so 60fps updates never recompose parent tree
                @Suppress("UNUSED_VARIABLE")
                val tick = animationFrameTick
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@Canvas

                val strokePx = 1.35.dp.toPx()

                if (!hasData && displayedLuma.all { it < 0.003f }) {
                    // Honest flat baseline while awaiting first camera frame
                    drawLine(
                        color = Color.White.copy(alpha = 0.28f),
                        start = Offset(0f, h - strokePx * 0.5f),
                        end = Offset(w, h - strokePx * 0.5f),
                        strokeWidth = strokePx
                    )
                    return@Canvas
                }

                // LUMA cubic Bézier curve (white-to-gold vertical gradient fill)
                val lumaAlpha = (1f - rgbMix).coerceIn(0f, 1f)
                if (lumaAlpha > 0.01f) {
                    buildSmoothCubicBezierPaths(
                        bins = displayedLuma,
                        width = w,
                        height = h,
                        fillPath = fillPath,
                        strokePath = strokePath
                    )
                    drawPath(
                        path = fillPath,
                        brush = lumaFillBrush,
                        alpha = lumaAlpha
                    )
                    drawPath(
                        path = strokePath,
                        brush = lumaStrokeBrush,
                        alpha = lumaAlpha,
                        style = Stroke(
                            width = strokePx,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // RGB additive overlapping translucent curves
                val rgbAlpha = rgbMix.coerceIn(0f, 1f)
                if (rgbAlpha > 0.01f) {
                    drawAdditiveRgbCurve(
                        bins = displayedRed,
                        width = w,
                        height = h,
                        fillBrush = redFillBrush,
                        strokeColor = RgbRedCurveColor,
                        strokePx = strokePx,
                        alpha = rgbAlpha,
                        fillPath = fillPath,
                        strokePath = strokePath
                    )
                    drawAdditiveRgbCurve(
                        bins = displayedGreen,
                        width = w,
                        height = h,
                        fillBrush = greenFillBrush,
                        strokeColor = RgbGreenCurveColor,
                        strokePx = strokePx,
                        alpha = rgbAlpha,
                        fillPath = fillPath,
                        strokePath = strokePath
                    )
                    drawAdditiveRgbCurve(
                        bins = displayedBlue,
                        width = w,
                        height = h,
                        fillBrush = blueFillBrush,
                        strokeColor = RgbBlueCurveColor,
                        strokePx = strokePx,
                        alpha = rgbAlpha,
                        fillPath = fillPath,
                        strokePath = strokePath
                    )
                }
            }
        }

        // Bottom Markers: [Dot] S  |  M  |  H [Dot]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val shadowLabelColor by animateColorAsState(
                targetValue = resolveClipColor(isShadowClipping, shadowClipFraction, StuDOSilverMuted),
                animationSpec = tween(180),
                label = "shadow_label_color"
            )
            val highlightLabelColor by animateColorAsState(
                targetValue = resolveClipColor(isHighlightClipping, highlightClipFraction, StuDOSilverMuted),
                animationSpec = tween(180),
                label = "highlight_label_color"
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ClippingIndicatorDot(
                    isClipping = isShadowClipping,
                    clipFraction = shadowClipFraction,
                    contentDescriptionText = stringResource(R.string.histogram_shadow_clip_cd),
                    modifier = Modifier.testTag("histogram_shadow_clip_dot")
                )
                Text(
                    text = "S",
                    color = shadowLabelColor,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Text(
                text = "M",
                color = StuDOSilverMuted,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "H",
                    color = highlightLabelColor,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                ClippingIndicatorDot(
                    isClipping = isHighlightClipping,
                    clipFraction = highlightClipFraction,
                    contentDescriptionText = stringResource(R.string.histogram_highlight_clip_cd),
                    modifier = Modifier.testTag("histogram_highlight_clip_dot")
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistogramModePillButton(
    mode: HistogramDisplayMode,
    onClick: () -> Unit,
    onDoubleClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isRgb = mode == HistogramDisplayMode.RGB
    val pillAccent = if (isRgb) GlassCoolBlueSpecular else GlassWarmAmberSpecular
    val pillText = if (isRgb) stringResource(R.string.histogram_mode_rgb) else stringResource(R.string.histogram_mode_luma)
    val toggleCd = stringResource(R.string.histogram_toggle_mode_cd, pillText)

    Box(
        modifier = modifier
            .liquidGlassChipSurface(
                shape = RoundedCornerShape(10.dp),
                cornerRadius = 10.dp,
                isActive = true,
                accentColor = pillAccent,
                activeFillAlpha = 0.26f
            )
            .combinedClickable(
                onClick = onClick,
                onDoubleClick = onDoubleClick
            )
            .heightIn(min = 20.dp)
            .padding(horizontal = 7.dp, vertical = 2.5.dp)
            .semantics {
                contentDescription = toggleCd
            }
            .testTag("histogram_mode_pill"),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (isRgb) {
                // Tri-color micro dot cluster for instant RGB visual recognition
                Canvas(modifier = Modifier.size(width = 11.dp, height = 5.dp)) {
                    val r = 1.6.dp.toPx()
                    val cy = size.height / 2f
                    drawCircle(RgbRedCurveColor, radius = r, center = Offset(r, cy))
                    drawCircle(RgbGreenCurveColor, radius = r, center = Offset(size.width * 0.5f, cy))
                    drawCircle(RgbBlueCurveColor, radius = r, center = Offset(size.width - r, cy))
                }
            } else {
                Canvas(modifier = Modifier.size(5.dp)) {
                    drawCircle(
                        color = GlassWarmAmberSpecular,
                        radius = size.minDimension / 2f
                    )
                }
            }
            Spacer(modifier = Modifier.width(1.dp))
            Text(
                text = pillText,
                color = if (isRgb) Color.White else GlassWarmAmberSpecular,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
private fun ClippingIndicatorDot(
    isClipping: Boolean,
    clipFraction: Float,
    contentDescriptionText: String,
    modifier: Modifier = Modifier
) {
    val targetDotColor = resolveClipColor(
        isClipping = isClipping,
        clipFraction = clipFraction,
        inactiveColor = Color.White.copy(alpha = 0.20f)
    )
    val animatedDotColor by animateColorAsState(
        targetValue = targetDotColor,
        animationSpec = tween(durationMillis = 160),
        label = "clip_dot_color"
    )
    val haloAlpha by animateFloatAsState(
        targetValue = if (isClipping) 0.55f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "clip_dot_halo"
    )

    Canvas(
        modifier = modifier
            .size(10.dp)
            .semantics {
                contentDescription = if (isClipping) {
                    "$contentDescriptionText active"
                } else {
                    "$contentDescriptionText inactive"
                }
            }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        if (haloAlpha > 0.01f) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        animatedDotColor.copy(alpha = haloAlpha),
                        Color.Transparent
                    ),
                    center = center,
                    radius = size.minDimension * 0.5f
                ),
                radius = size.minDimension * 0.5f,
                center = center
            )
        }
        drawCircle(
            color = animatedDotColor,
            radius = 2.4.dp.toPx(),
            center = center
        )
    }
}

private fun resolveClipColor(
    isClipping: Boolean,
    clipFraction: Float,
    inactiveColor: Color
): Color {
    if (!isClipping) return inactiveColor
    // Blend from StuDOAmber (>2% clip) into vivid StuDORed (>=6% heavy clip)
    val severity = ((clipFraction - HistogramData.CLIP_THRESHOLD) / 0.04f).coerceIn(0f, 1f)
    return lerp(StuDOAmber, StuDORed, severity)
}

private fun DrawScope.drawAdditiveRgbCurve(
    bins: FloatArray,
    width: Float,
    height: Float,
    fillBrush: Brush,
    strokeColor: Color,
    strokePx: Float,
    alpha: Float,
    fillPath: Path,
    strokePath: Path
) {
    buildSmoothCubicBezierPaths(
        bins = bins,
        width = width,
        height = height,
        fillPath = fillPath,
        strokePath = strokePath
    )
    drawPath(
        path = fillPath,
        brush = fillBrush,
        alpha = alpha,
        blendMode = BlendMode.Plus
    )
    drawPath(
        path = strokePath,
        color = strokeColor.copy(alpha = 0.92f),
        alpha = alpha,
        style = Stroke(
            width = strokePx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        ),
        blendMode = BlendMode.Plus
    )
}

/**
 * Builds both the closed area fill path and open top curve path using Catmull-Rom to
 * Cubic Bézier spline conversion with vertical control-point clamping so the curve
 * never dips below the baseline or clips above the top.
 */
private fun buildSmoothCubicBezierPaths(
    bins: FloatArray,
    width: Float,
    height: Float,
    fillPath: Path,
    strokePath: Path
) {
    fillPath.reset()
    strokePath.reset()
    val count = bins.size
    if (count < 2) return

    val usableH = height * 0.90f
    val denom = (count - 1).toFloat()

    fun xAt(index: Int): Float = (index.toFloat() / denom) * width
    fun yAt(index: Int): Float {
        val v = bins[index.coerceIn(0, count - 1)].coerceIn(0f, 1f)
        return height - (v * usableH)
    }

    val startX = xAt(0)
    val startY = yAt(0)

    strokePath.moveTo(startX, startY)
    fillPath.moveTo(0f, height)
    fillPath.lineTo(startX, startY)

    for (i in 0 until count - 1) {
        val i0 = (i - 1).coerceAtLeast(0)
        val i1 = i
        val i2 = i + 1
        val i3 = (i + 2).coerceAtMost(count - 1)

        val x0 = xAt(i0)
        val y0 = yAt(i0)
        val x1 = xAt(i1)
        val y1 = yAt(i1)
        val x2 = xAt(i2)
        val y2 = yAt(i2)
        val x3 = xAt(i3)
        val y3 = yAt(i3)

        val cp1x = x1 + (x2 - x0) / 6f
        val cp1y = (y1 + (y2 - y0) / 6f).coerceIn(0f, height)
        val cp2x = x2 - (x3 - x1) / 6f
        val cp2y = (y2 - (y3 - y1) / 6f).coerceIn(0f, height)

        strokePath.cubicTo(cp1x, cp1y, cp2x, cp2y, x2, y2)
        fillPath.cubicTo(cp1x, cp1y, cp2x, cp2y, x2, y2)
    }

    fillPath.lineTo(width, height)
    fillPath.close()
}

/**
 * Hardware-independent soft multi-ring ambient drop shadow rendered behind translucent Liquid Glass
 * surfaces without darkening their frosted interior.
 */
private fun Modifier.floatingLiquidGlassShadow(
    cornerRadius: Dp
): Modifier = this.drawWithCache {
    val rPx = cornerRadius.toPx().coerceAtMost(minOf(size.width, size.height) / 2f)
    val layers = listOf(
        Triple(8.dp.toPx(), 4.dp.toPx(), 0.10f),
        Triple(4.dp.toPx(), 2.5.dp.toPx(), 0.14f),
        Triple(1.5.dp.toPx(), 1.dp.toPx(), 0.16f)
    )
    onDrawBehind {
        for ((spread, yOffset, alpha) in layers) {
            val cr = CornerRadius(rPx + spread * 0.5f, rPx + spread * 0.5f)
            drawRoundRect(
                color = Color.Black.copy(alpha = alpha),
                topLeft = Offset(-spread, -spread * 0.4f + yOffset),
                size = Size(
                    width = size.width + spread * 2f,
                    height = size.height + spread * 2f
                ),
                cornerRadius = cr
            )
        }
    }
}
