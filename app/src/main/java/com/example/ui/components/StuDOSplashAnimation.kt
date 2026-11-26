package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.StuDOBlack
import com.example.ui.theme.StuDOWhite

/**
 * Legacy splash composable retained for API compatibility; completes immediately
 * with zero artificial delay or intro animation for instant app startup.
 */
@Composable
fun StuDOSplashAnimation(
    onAnimationFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(Unit) {
        onAnimationFinished()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(StuDOBlack)
            .testTag("splash_animation_overlay"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            StuDOLogo(
                size = 116.dp,
                isDarkTheme = true,
                contentDescription = "STU•D•O Logo"
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "STU•D•O",
                color = StuDOWhite,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp
            )
        }
    }
}
