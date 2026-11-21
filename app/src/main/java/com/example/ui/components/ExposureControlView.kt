package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDOBlack
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOWhite
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ExposureControlView(
    exposureIndex: Int,
    minExposureIndex: Int,
    maxExposureIndex: Int,
    exposureStep: Float,
    isSupported: Boolean,
    onExposureChanged: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isSupported || minExposureIndex >= maxExposureIndex) return

    val currentEV = exposureIndex * (if (exposureStep > 0f) exposureStep else 0.1666f)
    val formattedEV = if (currentEV >= 0) String.format(Locale.US, "+%.1f EV", currentEV) else String.format(Locale.US, "%.1f EV", currentEV)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.15f),
                        Color(0xFF14151D).copy(alpha = 0.85f)
                    )
                )
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.35f),
                        Color.White.copy(alpha = 0.08f)
                    )
                ),
                RoundedCornerShape(22.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("exposure_control_panel")
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(190.dp)
        ) {
            // Header with Sun icon, EV readout, and close button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.WbSunny,
                        contentDescription = "Exposure",
                        tint = StuDOAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = formattedEV,
                        color = StuDOWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Reset button (0 EV)
                if (exposureIndex != 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.18f))
                            .clickable { onExposureChanged(0) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "RESET",
                            color = StuDOAmber,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                // Close Button
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Exposure",
                        tint = StuDOSilver,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            // Slider
            Slider(
                value = exposureIndex.toFloat(),
                onValueChange = { floatVal ->
                    onExposureChanged(floatVal.roundToInt())
                },
                valueRange = minExposureIndex.toFloat()..maxExposureIndex.toFloat(),
                steps = (maxExposureIndex - minExposureIndex - 1).coerceAtLeast(0),
                colors = SliderDefaults.colors(
                    thumbColor = StuDOAmber,
                    activeTrackColor = StuDOAmber,
                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                ),
                modifier = Modifier.height(28.dp)
            )

            // Min and Max scale markers
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val minEV = minExposureIndex * (if (exposureStep > 0f) exposureStep else 0.1666f)
                val maxEV = maxExposureIndex * (if (exposureStep > 0f) exposureStep else 0.1666f)

                Text(
                    text = String.format(Locale.US, "%.0f", minEV),
                    color = StuDOSilver,
                    fontSize = 9.sp
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "0",
                    color = if (exposureIndex == 0) StuDOAmber else StuDOSilver,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = String.format(Locale.US, "+%.0f", maxEV),
                    color = StuDOSilver,
                    fontSize = 9.sp
                )
            }
        }
    }
}
