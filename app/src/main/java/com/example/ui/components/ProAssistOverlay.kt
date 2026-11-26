package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.util.ProAssistOverlayFrame
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * Real-time Pro Mode Focus Peaking & Zebra Stripes viewfinder overlay.
 *
 * Aligns upright/mirrored analysis masks onto the viewfinder using the exact same FILL_CENTER
 * scale-and-center transform used by CameraX PreviewView across 4:3, 16:9, 1:1, and FULL aspect
 * ratios. Renders crisp Focus Peaking edges and animated 45° diagonal hatched Zebra Stripes over
 * blown-out highlights using GPU offscreen compositing.
 */
@Composable
fun ProAssistOverlay(
    frameState: StateFlow<ProAssistOverlayFrame?>,
    isPeakingVisible: Boolean,
    isZebraVisible: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isPeakingVisible && !isZebraVisible) return

    val frame by frameState.collectAsState()
    val overlayCd = stringResource(R.string.pro_assist_overlay_cd)

    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = overlayCd }
            .testTag("pro_assist_overlay")
    ) {
        // Animated Zebra Stripes Layer (runs 60fps GPU stripe shader ONLY while Zebra is visible)
        if (isZebraVisible) {
            val infiniteTransition = rememberInfiniteTransition(label = "zebra_stripe_transition")
            val stripePhase by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 850, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "zebra_stripe_phase"
            )

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .testTag("pro_zebra_overlay_canvas")
            ) {
                val activeFrame = frame ?: return@Canvas
                val mask = activeFrame.zebraMaskBitmap ?: return@Canvas
                if (!activeFrame.hasZebraHighlights || activeFrame.frameWidth <= 0 || activeFrame.frameHeight <= 0) {
                    return@Canvas
                }
                if (size.width <= 0f || size.height <= 0f) return@Canvas

                val scale = maxOf(
                    size.width / activeFrame.frameWidth.toFloat(),
                    size.height / activeFrame.frameHeight.toFloat()
                )
                val drawW = (activeFrame.frameWidth * scale).roundToInt().coerceAtLeast(1)
                val drawH = (activeFrame.frameHeight * scale).roundToInt().coerceAtLeast(1)
                val offsetX = ((size.width - drawW) * 0.5f).roundToInt()
                val offsetY = ((size.height - drawH) * 0.5f).roundToInt()

                // 1. Draw the highlight-clipped alpha mask aligned identically to PreviewView FILL_CENTER
                drawImage(
                    image = mask,
                    dstOffset = IntOffset(offsetX, offsetY),
                    dstSize = IntSize(drawW, drawH),
                    filterQuality = FilterQuality.None
                )

                // 2. Mask repeating animated 45° diagonal hatched stripes strictly onto clipped highlights
                val periodPx = 11.dp.toPx().coerceAtLeast(4f)
                val diagStep = periodPx * 0.70710677f
                val shift = stripePhase * diagStep
                val stripeBrush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0.00f to Color(0xFF0A0B0E).copy(alpha = 0.80f),
                        0.42f to Color(0xFF0A0B0E).copy(alpha = 0.80f),
                        0.46f to Color.White.copy(alpha = 0.22f),
                        0.96f to Color.White.copy(alpha = 0.22f),
                        1.00f to Color(0xFF0A0B0E).copy(alpha = 0.80f)
                    ),
                    start = Offset(shift, shift),
                    end = Offset(shift + diagStep, shift + diagStep),
                    tileMode = TileMode.Repeated
                )

                drawRect(
                    brush = stripeBrush,
                    topLeft = Offset(offsetX.toFloat(), offsetY.toFloat()),
                    size = Size(drawW.toFloat(), drawH.toFloat()),
                    blendMode = BlendMode.SrcIn
                )
            }
        }

        // Crisp Focus Peaking Edge Highlights Layer
        if (isPeakingVisible) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("pro_peaking_overlay_canvas")
            ) {
                val activeFrame = frame ?: return@Canvas
                val peakingImg = activeFrame.peakingBitmap ?: return@Canvas
                if (!activeFrame.hasPeakingEdges || activeFrame.frameWidth <= 0 || activeFrame.frameHeight <= 0) {
                    return@Canvas
                }
                if (size.width <= 0f || size.height <= 0f) return@Canvas

                val scale = maxOf(
                    size.width / activeFrame.frameWidth.toFloat(),
                    size.height / activeFrame.frameHeight.toFloat()
                )
                val drawW = (activeFrame.frameWidth * scale).roundToInt().coerceAtLeast(1)
                val drawH = (activeFrame.frameHeight * scale).roundToInt().coerceAtLeast(1)
                val offsetX = ((size.width - drawW) * 0.5f).roundToInt()
                val offsetY = ((size.height - drawH) * 0.5f).roundToInt()

                drawImage(
                    image = peakingImg,
                    dstOffset = IntOffset(offsetX, offsetY),
                    dstSize = IntSize(drawW, drawH),
                    filterQuality = FilterQuality.Low
                )
            }
        }
    }
}
