package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.GlassCoolBlueSpecular

/**
 * Reusable theme-aware StuDO liquid-glass lens logo component.
 *
 * Automatically adapts contrast, ambient shadow, rim borders, and glass specular
 * highlights between Light Mode and Dark Mode while preserving the circular
 * liquid-glass lens without clipping.
 */
@Composable
fun StuDOLogo(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    showBadgeBackdrop: Boolean = false,
    isDarkTheme: Boolean = isSystemInDarkTheme(),
    contentDescription: String? = "StuDO Logo"
) {
    val badgeShape = RoundedCornerShape(size * 0.24f)

    if (showBadgeBackdrop) {
        val shadowColor = if (isDarkTheme) {
            Color(0xFF38BDF8).copy(alpha = 0.20f)
        } else {
            Color(0xFF0F172A).copy(alpha = 0.28f)
        }
        val borderBrush = if (isDarkTheme) {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.34f),
                    GlassCoolBlueSpecular.copy(alpha = 0.16f),
                    Color.White.copy(alpha = 0.08f)
                ),
                start = Offset.Zero,
                end = Offset.Infinite
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    Color(0xFF0F172A).copy(alpha = 0.24f),
                    Color(0xFF334155).copy(alpha = 0.12f),
                    Color(0xFF0F172A).copy(alpha = 0.28f)
                ),
                start = Offset.Zero,
                end = Offset.Infinite
            )
        }

        Box(
            modifier = modifier
                .size(size)
                .shadow(
                    elevation = if (isDarkTheme) 12.dp else 10.dp,
                    shape = badgeShape,
                    ambientColor = shadowColor,
                    spotColor = shadowColor
                )
                .clip(badgeShape)
                .border(width = 1.dp, brush = borderBrush, shape = badgeShape)
                .testTag("studo_theme_logo"),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.studo_logo_squircle),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        // Floating circular liquid-glass lens with theme-aware contrast, rim & ambient shadow
        val ambientGlowColor = if (isDarkTheme) {
            Color(0xFF5EEAD4).copy(alpha = 0.12f)
        } else {
            Color(0xFF0F172A).copy(alpha = 0.20f)
        }
        val shadowSpotColor = if (isDarkTheme) {
            Color(0xFF38BDF8).copy(alpha = 0.22f)
        } else {
            Color(0xFF0F172A).copy(alpha = 0.30f)
        }
        val outerRimBrush = if (isDarkTheme) {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.34f),
                    GlassCoolBlueSpecular.copy(alpha = 0.22f),
                    Color.White.copy(alpha = 0.08f)
                ),
                start = Offset.Zero,
                end = Offset.Infinite
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    Color(0xFF1E293B).copy(alpha = 0.32f),
                    Color(0xFF475569).copy(alpha = 0.16f),
                    Color(0xFF0F172A).copy(alpha = 0.36f)
                ),
                start = Offset.Zero,
                end = Offset.Infinite
            )
        }
        val glassSheenBrush = Brush.linearGradient(
            colors = if (isDarkTheme) {
                listOf(
                    Color.White.copy(alpha = 0.14f),
                    Color.Transparent,
                    GlassCoolBlueSpecular.copy(alpha = 0.06f)
                )
            } else {
                listOf(
                    Color.White.copy(alpha = 0.26f),
                    Color.Transparent,
                    Color(0xFF0F172A).copy(alpha = 0.06f)
                )
            },
            start = Offset.Zero,
            end = Offset.Infinite
        )

        Box(
            modifier = modifier
                .size(size)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(ambientGlowColor, Color.Transparent),
                            center = center,
                            radius = this.size.minDimension * 0.62f
                        ),
                        radius = this.size.minDimension * 0.62f
                    )
                }
                .padding((size * 0.03f).coerceAtLeast(1.dp))
                .shadow(
                    elevation = if (isDarkTheme) 10.dp else 8.dp,
                    shape = CircleShape,
                    ambientColor = shadowSpotColor,
                    spotColor = shadowSpotColor
                )
                .clip(CircleShape)
                .background(
                    color = if (isDarkTheme) Color(0xFF070A10) else Color(0xFF1B2330),
                    shape = CircleShape
                )
                .testTag("studo_theme_logo"),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.studo_logo_lens),
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
            // Theme-aware optical rim & subtle top-left glass reflection overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(brush = glassSheenBrush)
                    .border(
                        width = if (size >= 64.dp) 1.25.dp else 0.75.dp,
                        brush = outerRimBrush,
                        shape = CircleShape
                    )
            )
        }
    }
}
