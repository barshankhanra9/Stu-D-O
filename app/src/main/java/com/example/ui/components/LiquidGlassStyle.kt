package com.example.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.GlassChannelDark
import com.example.ui.theme.GlassCoolBlueSpecular
import com.example.ui.theme.GlassObsidianBottom
import com.example.ui.theme.GlassObsidianTop
import com.example.ui.theme.GlassWarmAmberSpecular
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDORed

/**
 * Lightweight, GPU-friendly Liquid Glass surface modifier tailored for mid-range and flagship Android devices.
 * Renders a deep translucent charcoal optical base, soft frosted refraction sheen, subtle internal shadow,
 * and fine translucent specular edges (soft cool-blue upper-left, restrained warm-amber lower-right)
 * without expensive full-screen blur shaders.
 */
fun Modifier.liquidGlassSurface(
    shape: Shape = RoundedCornerShape(24.dp),
    cornerRadius: Dp = 24.dp,
    topColor: Color = GlassObsidianTop.copy(alpha = 0.48f),
    bottomColor: Color = GlassObsidianBottom.copy(alpha = 0.62f),
    borderWidth: Dp = 0.8.dp,
    coolHighlightAlpha: Float = 0.36f,
    warmHighlightAlpha: Float = 0.24f,
    specularSheenAlpha: Float = 0.14f,
    internalShadowAlpha: Float = 0.24f
): Modifier = this
    .clip(shape)
    .drawWithCache {
        val radiusPx = cornerRadius.toPx().coerceAtMost(minOf(size.width, size.height) / 2f)
        val cr = CornerRadius(radiusPx, radiusPx)
        val strokePx = borderWidth.toPx()
        val insetStrokePx = (strokePx * 0.72f).coerceAtLeast(0.6.dp.toPx().coerceAtLeast(0.6f))

        val bodyBrush = Brush.verticalGradient(
            colors = listOf(topColor, bottomColor),
            startY = 0f,
            endY = size.height
        )

        // Subtle optical refraction sheen from upper-left + restrained warm whisper bottom-right
        val sheenBrush = Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to Color.White.copy(alpha = specularSheenAlpha),
                0.26f to GlassCoolBlueSpecular.copy(alpha = specularSheenAlpha * 0.42f),
                0.58f to Color.Transparent,
                1.0f to GlassWarmAmberSpecular.copy(alpha = specularSheenAlpha * 0.24f)
            ),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height)
        )

        // Subtle internal depth shadow near the lower edge for realistic curved glass thickness
        val internalDepthBrush = Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,
                0.52f to Color.Transparent,
                1.0f to Color.Black.copy(alpha = internalShadowAlpha)
            ),
            startY = 0f,
            endY = size.height
        )

        // Fine translucent optical border (cool-blue upper-left -> muted middle -> warm amber lower-right)
        val chromaticBorderBrush = Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to GlassCoolBlueSpecular.copy(alpha = coolHighlightAlpha),
                0.24f to Color.White.copy(alpha = coolHighlightAlpha * 0.52f),
                0.52f to Color.White.copy(alpha = 0.06f),
                0.80f to Color.White.copy(alpha = warmHighlightAlpha * 0.40f),
                1.0f to GlassWarmAmberSpecular.copy(alpha = warmHighlightAlpha)
            ),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height)
        )

        // Crisp inner top bevel highlight
        val innerTopRimBrush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = specularSheenAlpha * 0.85f),
                Color.Transparent
            ),
            startY = 0f,
            endY = size.height * 0.36f
        )

        onDrawBehind {
            drawRoundRect(brush = bodyBrush, cornerRadius = cr)
            drawRoundRect(brush = sheenBrush, cornerRadius = cr)
            drawRoundRect(brush = internalDepthBrush, cornerRadius = cr)

            // Thin inner top specular highlight line
            drawRoundRect(
                brush = innerTopRimBrush,
                topLeft = Offset(strokePx, strokePx),
                size = Size(
                    (size.width - strokePx * 2f).coerceAtLeast(0f),
                    (size.height - strokePx * 2f).coerceAtLeast(0f)
                ),
                cornerRadius = CornerRadius(
                    (radiusPx - strokePx).coerceAtLeast(0f),
                    (radiusPx - strokePx).coerceAtLeast(0f)
                ),
                style = Stroke(width = insetStrokePx)
            )

            // Fine outer translucent edge
            drawRoundRect(
                brush = chromaticBorderBrush,
                topLeft = Offset(strokePx / 2f, strokePx / 2f),
                size = Size(
                    (size.width - strokePx).coerceAtLeast(0f),
                    (size.height - strokePx).coerceAtLeast(0f)
                ),
                cornerRadius = cr,
                style = Stroke(width = strokePx)
            )
        }
    }

/**
 * Recessed carved optical glass channel with realistic inner shadow so active glass beads
 * appear elevated inside a dark translucent trough.
 */
fun Modifier.liquidGlassRecessedTrack(
    shape: Shape = RoundedCornerShape(24.dp),
    cornerRadius: Dp = 24.dp,
    coolHighlightAlpha: Float = 0.28f,
    warmHighlightAlpha: Float = 0.20f
): Modifier = this
    .clip(shape)
    .drawWithCache {
        val radiusPx = cornerRadius.toPx().coerceAtMost(minOf(size.width, size.height) / 2f)
        val cr = CornerRadius(radiusPx, radiusPx)
        val strokePx = 0.85.dp.toPx()
        val innerShadowPx = 2.2.dp.toPx()

        val trackFill = Brush.verticalGradient(
            colors = listOf(
                GlassChannelDark.copy(alpha = 0.78f),
                GlassObsidianBottom.copy(alpha = 0.66f)
            ),
            startY = 0f,
            endY = size.height
        )

        val recessedTopShadow = Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.Black.copy(alpha = 0.38f),
                0.42f to Color.Black.copy(alpha = 0.14f),
                0.85f to Color.Transparent,
                1.0f to Color.White.copy(alpha = 0.04f)
            ),
            startY = 0f,
            endY = size.height
        )

        val chromaticRim = Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to GlassCoolBlueSpecular.copy(alpha = coolHighlightAlpha),
                0.28f to Color.White.copy(alpha = 0.16f),
                0.55f to Color.White.copy(alpha = 0.05f),
                0.82f to Color.White.copy(alpha = 0.11f),
                1.0f to GlassWarmAmberSpecular.copy(alpha = warmHighlightAlpha)
            ),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height)
        )

        onDrawBehind {
            drawRoundRect(brush = trackFill, cornerRadius = cr)
            drawRoundRect(brush = recessedTopShadow, cornerRadius = cr)

            // Recessed inner shadow rim
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.28f),
                topLeft = Offset(innerShadowPx * 0.5f, innerShadowPx * 0.5f),
                size = Size(
                    (size.width - innerShadowPx).coerceAtLeast(0f),
                    (size.height - innerShadowPx).coerceAtLeast(0f)
                ),
                cornerRadius = CornerRadius(
                    (radiusPx - innerShadowPx * 0.5f).coerceAtLeast(0f),
                    (radiusPx - innerShadowPx * 0.5f).coerceAtLeast(0f)
                ),
                style = Stroke(width = innerShadowPx)
            )

            // Outer translucent channel lip
            drawRoundRect(
                brush = chromaticRim,
                topLeft = Offset(strokePx / 2f, strokePx / 2f),
                size = Size(
                    (size.width - strokePx).coerceAtLeast(0f),
                    (size.height - strokePx).coerceAtLeast(0f)
                ),
                cornerRadius = cr,
                style = Stroke(width = strokePx)
            )
        }
    }

/**
 * Consistent lightweight inner glass chip modifier for control pills inside capsules and drawers.
 * Preserves dark translucent depth, subtle specular bevel, and restrained active tinting.
 */
fun Modifier.liquidGlassChipSurface(
    shape: Shape = RoundedCornerShape(14.dp),
    cornerRadius: Dp = 14.dp,
    isActive: Boolean = false,
    isEnabled: Boolean = true,
    accentColor: Color = GlassCoolBlueSpecular,
    inactiveTopAlpha: Float = 0.09f,
    inactiveBottomAlpha: Float = 0.24f,
    activeFillAlpha: Float = 0.22f
): Modifier = this
    .clip(shape)
    .drawWithCache {
        val radiusPx = cornerRadius.toPx().coerceAtMost(minOf(size.width, size.height) / 2f)
        val cr = CornerRadius(radiusPx, radiusPx)
        val strokePx = if (isActive) 0.9.dp.toPx() else 0.7.dp.toPx()

        val fillBrush = if (!isEnabled) {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.04f),
                    Color.Black.copy(alpha = 0.14f)
                ),
                startY = 0f,
                endY = size.height
            )
        } else if (isActive) {
            Brush.verticalGradient(
                colors = listOf(
                    accentColor.copy(alpha = activeFillAlpha),
                    GlassObsidianBottom.copy(alpha = 0.44f)
                ),
                startY = 0f,
                endY = size.height
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = inactiveTopAlpha),
                    Color.Black.copy(alpha = inactiveBottomAlpha)
                ),
                startY = 0f,
                endY = size.height
            )
        }

        val innerTopHighlight = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = if (!isEnabled) 0.04f else if (isActive) 0.18f else 0.11f),
                Color.Transparent
            ),
            startY = 0f,
            endY = size.height * 0.45f
        )

        val borderBrush = if (!isEnabled) {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.08f),
                    Color.White.copy(alpha = 0.04f)
                )
            )
        } else if (isActive) {
            Brush.linearGradient(
                colorStops = arrayOf(
                    0.0f to accentColor.copy(alpha = 0.62f),
                    0.5f to Color.White.copy(alpha = 0.26f),
                    1.0f to accentColor.copy(alpha = 0.44f)
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height)
            )
        } else {
            Brush.linearGradient(
                colorStops = arrayOf(
                    0.0f to GlassCoolBlueSpecular.copy(alpha = 0.22f),
                    0.35f to Color.White.copy(alpha = 0.14f),
                    0.70f to Color.White.copy(alpha = 0.06f),
                    1.0f to GlassWarmAmberSpecular.copy(alpha = 0.14f)
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height)
            )
        }

        onDrawBehind {
            drawRoundRect(brush = fillBrush, cornerRadius = cr)
            drawRoundRect(brush = innerTopHighlight, cornerRadius = cr)
            drawRoundRect(
                brush = borderBrush,
                topLeft = Offset(strokePx / 2f, strokePx / 2f),
                size = Size(
                    (size.width - strokePx).coerceAtLeast(0f),
                    (size.height - strokePx).coerceAtLeast(0f)
                ),
                cornerRadius = cr,
                style = Stroke(width = strokePx)
            )
        }
    }

/**
 * Computes recording tint colors smoothly for the left brand/timer capsule.
 */
object LiquidGlassTints {
    val recordingTop = Color(0xFF6B1419).copy(alpha = 0.76f)
    val recordingBottom = Color(0xFF28080A).copy(alpha = 0.84f)
    val recordingBorderCool = StuDORed.copy(alpha = 0.65f)

    val pausedTop = Color(0xFF5A3B0E).copy(alpha = 0.74f)
    val pausedBottom = Color(0xFF231605).copy(alpha = 0.82f)
    val pausedBorderCool = StuDOAmber.copy(alpha = 0.65f)
}

/**
 * Crisp, Android-native haptic performer that respects user haptic settings and gracefully no-ops
 * when unavailable. Avoids frame-by-frame vibration.
 */
class StuDOHapticPerformer(
    private val view: View,
    private val composeHaptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    private val enabled: Boolean
) {
    fun tick() {
        if (!enabled) return
        try {
            val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                HapticFeedbackConstants.CLOCK_TICK
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
            if (!view.performHapticFeedback(constant)) {
                composeHaptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        } catch (_: Throwable) {
            try {
                composeHaptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            } catch (_: Throwable) {}
        }
    }

    fun confirm() {
        if (!enabled) return
        try {
            val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                HapticFeedbackConstants.CONTEXT_CLICK
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
            if (!view.performHapticFeedback(constant)) {
                composeHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        } catch (_: Throwable) {
            try {
                composeHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
            } catch (_: Throwable) {}
        }
    }
}

@Composable
fun rememberStuDOHaptic(enabled: Boolean): StuDOHapticPerformer {
    val view = LocalView.current
    val composeHaptic = LocalHapticFeedback.current
    return remember(view, composeHaptic, enabled) {
        StuDOHapticPerformer(view, composeHaptic, enabled)
    }
}

@androidx.compose.runtime.Immutable
data class LiquidGlassVisualTier(
    val isLowEndDevice: Boolean
)

@Composable
fun rememberLiquidGlassVisualTier(): LiquidGlassVisualTier {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) {
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val isLowRam = try {
            am?.isLowRamDevice == true
        } catch (_: Throwable) {
            false
        }
        val isLowEnd = isLowRam || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        LiquidGlassVisualTier(isLowEndDevice = isLowEnd)
    }
}

fun Modifier.adaptiveLiquidGlassPanel(
    visualTier: LiquidGlassVisualTier,
    shape: Shape = RoundedCornerShape(24.dp),
    cornerRadius: Dp = 24.dp,
    topColor: Color = GlassObsidianTop.copy(alpha = 0.78f),
    bottomColor: Color = GlassObsidianBottom.copy(alpha = 0.90f),
    borderWidth: Dp = 1.dp,
    coolHighlightAlpha: Float = 0.28f,
    warmHighlightAlpha: Float = 0.20f,
    specularSheenAlpha: Float = 0.12f
): Modifier {
    return if (visualTier.isLowEndDevice) {
        this
            .clip(shape)
            .background(bottomColor)
            .border(borderWidth, Color.White.copy(alpha = 0.16f), shape)
    } else {
        this.liquidGlassSurface(
            shape = shape,
            cornerRadius = cornerRadius,
            topColor = topColor,
            bottomColor = bottomColor,
            borderWidth = borderWidth,
            coolHighlightAlpha = coolHighlightAlpha,
            warmHighlightAlpha = warmHighlightAlpha,
            specularSheenAlpha = specularSheenAlpha
        )
    }
}
