package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import kotlinx.coroutines.flow.StateFlow

@Composable
fun LiveHistogramView(
    exposureIndex: Int,
    histogramBinsState: StateFlow<List<Float>>? = null,
    modifier: Modifier = Modifier
) {
    val liveBins = if (histogramBinsState != null) {
        histogramBinsState.collectAsState().value
    } else {
        emptyList()
    }
    val cardBg = remember {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.14f),
                Color(0xFF101117).copy(alpha = 0.75f)
            )
        )
    }
    val fillBrush = remember {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.35f),
                Color.White.copy(alpha = 0.05f)
            )
        )
    }

    Box(
        modifier = modifier
            .liquidGlassSurface(
                shape = RoundedCornerShape(16.dp),
                cornerRadius = 16.dp,
                topColor = com.example.ui.theme.GlassObsidianTop.copy(alpha = 0.56f),
                bottomColor = com.example.ui.theme.GlassObsidianBottom.copy(alpha = 0.72f),
                borderWidth = 0.85.dp,
                coolHighlightAlpha = 0.32f,
                warmHighlightAlpha = 0.22f,
                specularSheenAlpha = 0.13f
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("live_histogram_view")
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(96.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "HISTOGRAM",
                    color = StuDOSilver,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = if (liveBins.isNotEmpty()) "LUMA" else "WAIT",
                    color = Color(0xFF64B5F6),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Real Y-plane luminance Canvas
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
            ) {
                val w = size.width
                val h = size.height

                val gridColor = Color.White.copy(alpha = 0.1f)
                drawLine(gridColor, Offset(w * 0.33f, 0f), Offset(w * 0.33f, h), 0.8f)
                drawLine(gridColor, Offset(w * 0.66f, 0f), Offset(w * 0.66f, h), 0.8f)

                if (liveBins.size >= 2) {
                    val pathLuma = Path().apply {
                        moveTo(0f, h)
                        val count = liveBins.size
                        for (i in 0 until count) {
                            val x = (i.toFloat() / (count - 1).toFloat()) * w
                            val y = h - (liveBins[i].coerceIn(0f, 1f) * (h * 0.92f))
                            lineTo(x, y)
                        }
                        lineTo(w, h)
                        close()
                    }
                    drawPath(path = pathLuma, brush = fillBrush)
                    drawPath(
                        path = pathLuma,
                        color = Color.White.copy(alpha = 0.85f),
                        style = Stroke(width = 1.2.dp.toPx())
                    )
                } else {
                    // Honest flat baseline when camera frame stream is not active
                    drawLine(
                        color = Color.White.copy(alpha = 0.25f),
                        start = Offset(0f, h - 1f),
                        end = Offset(w, h - 1f),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }

            // Label ticks: S (Shadows), M (Midtones), H (Highlights)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("S", color = StuDOSilverMuted, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                Text("M", color = StuDOSilverMuted, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                Text("H", color = StuDOSilverMuted, fontSize = 7.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
