package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StuDOColorScheme = darkColorScheme(
    primary = StuDOSilver,
    onPrimary = Color(0xFF09090B),
    primaryContainer = StuDOSurfaceVariant,
    onPrimaryContainer = StuDOWhite,
    secondary = StuDOSilverMuted,
    onSecondary = StuDOCarbon,
    secondaryContainer = StuDOSurfaceElevated,
    onSecondaryContainer = StuDOWhite,
    background = StuDOBlack,
    onBackground = StuDOWhite,
    surface = StuDOCarbon,
    onSurface = StuDOWhite,
    surfaceVariant = StuDOSurfaceVariant,
    onSurfaceVariant = StuDOSilverMuted,
    outline = StuDOBorder,
    outlineVariant = StuDOGreyDark
)

@Composable
fun StuDOTheme(
    content: @Composable () -> Unit
) {
    // StuDO is strictly an AMOLED dark photography tool. Viewfinders must remain deep black
    // regardless of system daytime/nighttime theme to prevent eye pupil dilation distortion.
    MaterialTheme(
        colorScheme = StuDOColorScheme,
        typography = Typography,
        content = content
    )
}
