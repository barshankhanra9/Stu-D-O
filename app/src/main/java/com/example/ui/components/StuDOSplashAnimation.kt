package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.StuDOBlack
import com.example.ui.theme.StuDOFocusYellow
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite
import kotlinx.coroutines.delay

@Composable
fun StuDOSplashAnimation(
    onAnimationFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isVisible by remember { mutableStateOf(true) }
    var startShutterFlash by remember { mutableStateOf(false) }

    // Core entrance animations
    val logoScale = remember { Animatable(0.65f) }
    val logoRotation = remember { Animatable(-30f) }
    val ringRotation = remember { Animatable(0f) }
    val contentAlpha = remember { Animatable(0f) }
    val shutterFlashAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // Step 1: Smooth cinematic zoom-in and fade-in of logo and aperture ring
        contentAlpha.animateTo(1f, tween(durationMillis = 400, easing = LinearEasing))
    }

    LaunchedEffect(Unit) {
        // Scale logo with organic spring
        logoScale.animateTo(
            targetValue = 1.0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
    }

    LaunchedEffect(Unit) {
        // Aperture rotation
        logoRotation.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing)
        )
    }

    LaunchedEffect(Unit) {
        // Outer mechanical aperture ring rotation
        ringRotation.animateTo(
            targetValue = 90f,
            animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing)
        )
    }

    LaunchedEffect(Unit) {
        // Shutter snap simulation after zoom-in completes
        delay(750)
        startShutterFlash = true
        shutterFlashAlpha.snapTo(0.9f)
        shutterFlashAlpha.animateTo(0f, tween(durationMillis = 200, easing = FastOutSlowInEasing))

        // Brief hold to let the user admire the aesthetic presentation
        delay(400)
        isVisible = false
        delay(350)
        onAnimationFinished()
    }

    val interactionSource = remember { MutableInteractionSource() }

    AnimatedVisibility(
        visible = isVisible,
        exit = fadeOut(animationSpec = tween(350)) + scaleOut(targetScale = 1.12f, animationSpec = tween(350)),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF1E1F24),
                            Color(0xFF0C0D10),
                            StuDOBlack
                        )
                    )
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) {
                    // Tap to skip
                    isVisible = false
                    onAnimationFinished()
                }
                .testTag("splash_animation_overlay"),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Liquid glass frosted lens container
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .scale(logoScale.value)
                        .rotate(ringRotation.value),
                    contentAlignment = Alignment.Center
                ) {
                    // Outer liquid glass ring with specular reflection gradient
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(
                                Brush.sweepGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.25f),
                                        Color.White.copy(alpha = 0.05f),
                                        Color.White.copy(alpha = 0.35f),
                                        Color.White.copy(alpha = 0.08f),
                                        Color.White.copy(alpha = 0.25f)
                                    )
                                )
                            )
                            .border(
                                1.5.dp,
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.45f),
                                        Color.White.copy(alpha = 0.1f)
                                    )
                                ),
                                CircleShape
                            )
                    )

                    // Secondary inner frosted ring
                    Box(
                        modifier = Modifier
                            .size(136.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0A0B0E).copy(alpha = 0.85f))
                            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                    )

                    // Brand Aperture Logo with rotation
                    Image(
                        painter = painterResource(id = R.drawable.studo_camera_icon_1793330171422),
                        contentDescription = "STU•D•O Aperture",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(118.dp)
                            .clip(CircleShape)
                            .rotate(logoRotation.value)
                            .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                    )

                    // Shutter iris snap overlay flash inside lens
                    if (shutterFlashAlpha.value > 0f) {
                        Box(
                            modifier = Modifier
                                .size(118.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = shutterFlashAlpha.value))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Typography: STU•D•O with liquid glass pill
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.scale(contentAlpha.value)
                ) {
                    Text(
                        text = "STU•D•O",
                        color = StuDOWhite,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 8.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Liquid glass pill badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.15f),
                                        Color.White.copy(alpha = 0.05f)
                                    )
                                )
                            )
                            .border(
                                1.dp,
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.3f),
                                        Color.White.copy(alpha = 0.08f)
                                    )
                                ),
                                RoundedCornerShape(16.dp)
                            )
                            .padding(horizontal = 14.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "PROFESSIONAL CAMERA",
                            color = StuDOSilver,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 2.sp
                        )
                    }
                }
            }

            // Full-screen camera flash snap when shutter snaps
            if (shutterFlashAlpha.value > 0.3f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = shutterFlashAlpha.value * 0.45f))
                )
            }
        }
    }
}
