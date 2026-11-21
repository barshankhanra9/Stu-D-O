package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs

@Composable
fun LevelIndicatorView(
    rollAngle: Float,
    pitchAngle: Float,
    isLevel: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedRoll by animateFloatAsState(
        targetValue = rollAngle,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 300f),
        label = "level_roll_rotation"
    )

    val neonLevelGreen = Color(0xFF00E676)
    val indicatorColor by animateColorAsState(
        targetValue = if (isLevel) neonLevelGreen else Color.White.copy(alpha = 0.85f),
        label = "level_color"
    )

    val glowColor by animateColorAsState(
        targetValue = if (isLevel) neonLevelGreen.copy(alpha = 0.35f) else Color.Transparent,
        label = "level_glow_color"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Horizon line Canvas
        Canvas(modifier = Modifier.size(180.dp, 60.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f

            // Fixed center reference brackets
            val bracketLen = 22.dp.toPx()
            val centerGap = 16.dp.toPx()
            val fixedColor = Color.White.copy(alpha = 0.35f)

            // Left bracket
            drawLine(
                color = fixedColor,
                start = Offset(cx - centerGap - bracketLen, cy),
                end = Offset(cx - centerGap, cy),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )
            // Right bracket
            drawLine(
                color = fixedColor,
                start = Offset(cx + centerGap, cy),
                end = Offset(cx + centerGap + bracketLen, cy),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )
            // Center reticle dot
            drawCircle(
                color = fixedColor,
                radius = 2.dp.toPx(),
                center = Offset(cx, cy)
            )
        }

        // Dynamic Rotating Horizon Bar
        Box(
            modifier = Modifier
                .size(160.dp, 28.dp)
                .rotate(-animatedRoll),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(150.dp, 20.dp)) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val barLen = 50.dp.toPx()
                val centerGap = 12.dp.toPx()
                val strokeW = if (isLevel) 2.5.dp.toPx() else 1.8.dp.toPx()

                // Left rotating wing
                drawLine(
                    color = indicatorColor,
                    start = Offset(cx - centerGap - barLen, cy),
                    end = Offset(cx - centerGap, cy),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round
                )
                // Right rotating wing
                drawLine(
                    color = indicatorColor,
                    start = Offset(cx + centerGap, cy),
                    end = Offset(cx + centerGap + barLen, cy),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round
                )
                // Center alignment indicator
                if (isLevel) {
                    drawCircle(
                        color = indicatorColor,
                        radius = 3.5.dp.toPx(),
                        center = Offset(cx, cy)
                    )
                }
            }
        }

        // Degree Readout Pill below level indicator
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 48.dp)
        ) {
            val formattedDegree = if (abs(rollAngle) < 0.2f) "0.0°" else String.format(Locale.US, "%+.1f°", rollAngle)

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.6f),
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
                    .border(
                        0.8.dp,
                        if (isLevel) neonLevelGreen.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.2f),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 2.5.dp)
            ) {
                Text(
                    text = formattedDegree,
                    color = indicatorColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
