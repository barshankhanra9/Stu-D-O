package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.StuDOFocusYellow
import com.example.ui.theme.StuDOWhite
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun FocusReticle(
    point: Offset,
    visible: Boolean,
    exposureIndex: Int,
    minExposureIndex: Int,
    maxExposureIndex: Int,
    exposureStep: Float,
    isExposureSupported: Boolean,
    onExposureChanged: (Int) -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!visible) return

    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }

    val scaleAnim = remember { Animatable(1.35f) }
    val alphaAnim = remember { Animatable(0f) }

    LaunchedEffect(point) {
        scaleAnim.snapTo(1.35f)
        alphaAnim.snapTo(1f)
        scaleAnim.animateTo(
            targetValue = 1.0f,
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
        )
    }

    val reticleSize = 70.dp
    val halfSize = with(density) { (reticleSize / 2).toPx() }

    val sliderHeight = 110.dp
    val sliderHeightPx = with(density) { sliderHeight.toPx() }

    // Place slider on the right unless point is near right edge
    val isNearRightEdge = point.x > (screenWidthPx - with(density) { 90.dp.toPx() })
    val sliderOffsetX = if (isNearRightEdge) -with(density) { 56.dp.toPx() } else halfSize + with(density) { 14.dp.toPx() }

    val totalRange = (maxExposureIndex - minExposureIndex).coerceAtLeast(1)
    val fraction = ((exposureIndex - minExposureIndex).toFloat() / totalRange.toFloat()).coerceIn(0f, 1f)
    // Invert fraction for vertical axis (higher value moves up)
    val sunYOffsetPx = (1f - fraction) * (sliderHeightPx - with(density) { 28.dp.toPx() })

    // Accumulator for vertical drag deltas
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    x = (point.x - halfSize).roundToInt(),
                    y = (point.y - halfSize).roundToInt()
                )
            }
            .size(width = reticleSize + 80.dp, height = reticleSize + 40.dp)
            .testTag("camera_focus_reticle")
    ) {
        // Main Focus Reticle (brackets + center dot)
        Canvas(modifier = Modifier.size(reticleSize)) {
            val strokeWidth = 1.8.dp.toPx()
            val bracketLength = 13.dp.toPx()
            val color = StuDOFocusYellow
            val w = size.width
            val h = size.height

            // Top-Left corner
            drawLine(color, Offset(0f, 0f), Offset(bracketLength, 0f), strokeWidth)
            drawLine(color, Offset(0f, 0f), Offset(0f, bracketLength), strokeWidth)

            // Top-Right corner
            drawLine(color, Offset(w, 0f), Offset(w - bracketLength, 0f), strokeWidth)
            drawLine(color, Offset(w, 0f), Offset(w, bracketLength), strokeWidth)

            // Bottom-Left corner
            drawLine(color, Offset(0f, h), Offset(bracketLength, h), strokeWidth)
            drawLine(color, Offset(0f, h), Offset(0f, h - bracketLength), strokeWidth)

            // Bottom-Right corner
            drawLine(color, Offset(w, h), Offset(w - bracketLength, h), strokeWidth)
            drawLine(color, Offset(w, h), Offset(w, h - bracketLength), strokeWidth)

            // Subtle center focal dot
            drawCircle(
                color = color.copy(alpha = 0.85f),
                radius = 2.dp.toPx(),
                center = Offset(w / 2f, h / 2f)
            )

            // Faint outer precision ring
            drawCircle(
                color = color.copy(alpha = 0.3f),
                radius = (w / 2f) - 2.dp.toPx(),
                style = Stroke(width = 0.8.dp.toPx())
            )
        }

        // On-Focus Vertical Exposure Slider (Pixel / Flagship Camera style)
        if (isExposureSupported && maxExposureIndex > minExposureIndex) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = sliderOffsetX.roundToInt(),
                            y = (halfSize - (sliderHeightPx / 2f)).roundToInt()
                        )
                    }
                    .width(44.dp)
                    .height(sliderHeight)
                    .pointerInput(exposureIndex, minExposureIndex, maxExposureIndex) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragAccumulator = 0f
                                onInteraction()
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                onInteraction()
                                // Drag up decreases Y (increases exposure)
                                dragAccumulator -= dragAmount
                                val stepPx = sliderHeightPx / totalRange.toFloat()
                                if (kotlin.math.abs(dragAccumulator) >= stepPx) {
                                    val steps = (dragAccumulator / stepPx).toInt()
                                    if (steps != 0) {
                                        val newIndex = (exposureIndex + steps).coerceIn(minExposureIndex, maxExposureIndex)
                                        if (newIndex != exposureIndex) {
                                            onExposureChanged(newIndex)
                                        }
                                        dragAccumulator %= stepPx
                                    }
                                }
                            }
                        )
                    }
                    .testTag("on_focus_exposure_slider")
            ) {
                // Vertical track line
                Canvas(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(2.dp)
                        .height(sliderHeight - 16.dp)
                ) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.35f),
                        start = Offset(size.width / 2f, 0f),
                        end = Offset(size.width / 2f, size.height),
                        strokeWidth = 1.5.dp.toPx()
                    )
                }

                // Sun Drag Handle
                Box(
                    modifier = Modifier
                        .offset { IntOffset(x = 8.dp.roundToPx(), y = sunYOffsetPx.roundToInt()) }
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .border(1.dp, StuDOFocusYellow.copy(alpha = 0.8f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.WbSunny,
                        contentDescription = "Exposure slider",
                        tint = StuDOFocusYellow,
                        modifier = Modifier.size(17.dp)
                    )
                }

                // Numeric EV value indicator when shifted
                if (exposureIndex != 0) {
                    val evValue = exposureIndex * (if (exposureStep > 0f) exposureStep else 0.5f)
                    val formattedEv = if (evValue > 0) "+${String.format(Locale.US, "%.1f", evValue)}" else String.format(Locale.US, "%.1f", evValue)

                    Box(
                        modifier = Modifier
                            .align(if (isNearRightEdge) Alignment.CenterStart else Alignment.CenterEnd)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.75f))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = formattedEv,
                            color = StuDOWhite,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
