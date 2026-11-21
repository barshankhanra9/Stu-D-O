package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CameraUiState
import com.example.model.ProControlTab
import com.example.model.ProWhitebalanceMode
import com.example.ui.theme.StuDOBlack
import com.example.ui.theme.StuDOCharcoal
import com.example.ui.theme.StuDOFocusYellow
import com.example.ui.theme.StuDOGrey
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite
import java.util.Locale

@Composable
fun ProControlsBar(
    uiState: CameraUiState,
    onTabSelected: (ProControlTab) -> Unit,
    onIsoChanged: (Int?) -> Unit,
    onShutterChanged: (Long?) -> Unit,
    onEvChanged: (Int) -> Unit,
    onFocusChanged: (Float?, Boolean) -> Unit,
    onWhiteBalanceChanged: (ProWhitebalanceMode) -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val proSettings = uiState.proSettings
    val proCaps = uiState.proCapabilities

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Expandable Parameter Value Selector Drawer
        AnimatedVisibility(
            visible = uiState.activeProTab != ProControlTab.NONE,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .liquidGlassSurface(
                        shape = RoundedCornerShape(18.dp),
                        cornerRadius = 18.dp,
                        topColor = com.example.ui.theme.GlassObsidianTop.copy(alpha = 0.56f),
                        bottomColor = com.example.ui.theme.GlassObsidianBottom.copy(alpha = 0.72f),
                        borderWidth = 0.85.dp,
                        coolHighlightAlpha = 0.36f,
                        warmHighlightAlpha = 0.24f,
                        specularSheenAlpha = 0.14f
                    )
                    .pointerInput(Unit) { detectTapGestures { /* absorb inside pro drawer taps */ } }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("pro_parameter_drawer")
            ) {
                when (uiState.activeProTab) {
                    ProControlTab.ISO -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProValuePill(
                                label = "AUTO",
                                isSelected = proSettings.iso == null,
                                onClick = { onIsoChanged(null) }
                            )
                            proCaps.supportedIsos.forEach { iso ->
                                ProValuePill(
                                    label = "$iso",
                                    isSelected = proSettings.iso == iso,
                                    onClick = { onIsoChanged(iso) }
                                )
                            }
                        }
                    }

                    ProControlTab.SHUTTER -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProValuePill(
                                label = "AUTO",
                                isSelected = proSettings.shutterSpeedNanos == null,
                                onClick = { onShutterChanged(null) }
                            )
                            proCaps.supportedShutterSpeeds.forEach { speedNanos ->
                                val label = if (speedNanos < 1_000_000_000L) {
                                    "1/${Math.round(1_000_000_000.0 / speedNanos)}s"
                                } else {
                                    String.format(Locale.US, "%.1fs", speedNanos / 1_000_000_000.0)
                                }
                                ProValuePill(
                                    label = label,
                                    isSelected = proSettings.shutterSpeedNanos == speedNanos,
                                    onClick = { onShutterChanged(speedNanos) }
                                )
                            }
                        }
                    }

                    ProControlTab.EV -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "EXPOSURE COMPENSATION",
                                    color = StuDOSilverMuted,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                val evVal = proSettings.evCompensation * proCaps.evStep
                                Text(
                                    text = String.format(Locale.US, "%+.1f EV", evVal),
                                    color = StuDOFocusYellow,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Slider(
                                value = proSettings.evCompensation.toFloat(),
                                onValueChange = { onEvChanged(it.toInt()) },
                                valueRange = proCaps.minEv.toFloat()..proCaps.maxEv.toFloat(),
                                steps = (proCaps.maxEv - proCaps.minEv - 1).coerceAtLeast(0),
                                colors = SliderDefaults.colors(
                                    thumbColor = StuDOFocusYellow,
                                    activeTrackColor = StuDOFocusYellow,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    ProControlTab.FOCUS -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ProValuePill(
                                        label = "AUTO (AF)",
                                        isSelected = !proSettings.isManualFocus,
                                        onClick = { onFocusChanged(null, false) }
                                    )
                                    ProValuePill(
                                        label = "MANUAL (MF)",
                                        isSelected = proSettings.isManualFocus,
                                        onClick = { onFocusChanged(proSettings.focusDistance ?: 2.0f, true) }
                                    )
                                }
                                if (proSettings.isManualFocus) {
                                    val dist = proSettings.focusDistance ?: 0f
                                    val distText = if (dist <= 0.05f) "∞ Inf" else String.format(Locale.US, "%.1f m", 1.0f / dist)
                                    Text(
                                        text = distText,
                                        color = StuDOFocusYellow,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (proSettings.isManualFocus) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Slider(
                                    value = (proSettings.focusDistance ?: 0f).coerceIn(0f, proCaps.maxFocusDistance),
                                    onValueChange = { onFocusChanged(it, true) },
                                    valueRange = 0f..proCaps.maxFocusDistance,
                                    colors = SliderDefaults.colors(
                                        thumbColor = StuDOFocusYellow,
                                        activeTrackColor = StuDOFocusYellow,
                                        inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    ProControlTab.WB -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            proCaps.supportedAwbModes.forEach { mode ->
                                ProValuePill(
                                    label = "${mode.displayName} (${mode.kelvinText})",
                                    isSelected = proSettings.whiteBalance == mode,
                                    onClick = { onWhiteBalanceChanged(mode) }
                                )
                            }
                        }
                    }

                    ProControlTab.NONE -> {}
                }
            }
        }

        // Pro Mode Navigation Tabs Capsule: [ISO] [S] [EV] [FOCUS] [WB] [RESET]
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassRecessedTrack(
                    shape = RoundedCornerShape(20.dp),
                    cornerRadius = 20.dp,
                    coolHighlightAlpha = 0.28f,
                    warmHighlightAlpha = 0.20f
                )
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ISO Tab
                val isoText = proSettings.iso?.toString() ?: "AUTO"
                ProTabButton(
                    title = "ISO",
                    value = isoText,
                    isActive = uiState.activeProTab == ProControlTab.ISO,
                    isModified = proSettings.iso != null,
                    onClick = {
                        val next = if (uiState.activeProTab == ProControlTab.ISO) ProControlTab.NONE else ProControlTab.ISO
                        onTabSelected(next)
                    },
                    modifier = Modifier.testTag("pro_tab_iso")
                )

                // Shutter Tab
                val shutterText = proSettings.formatShutter()
                ProTabButton(
                    title = "S",
                    value = shutterText,
                    isActive = uiState.activeProTab == ProControlTab.SHUTTER,
                    isModified = proSettings.shutterSpeedNanos != null,
                    onClick = {
                        val next = if (uiState.activeProTab == ProControlTab.SHUTTER) ProControlTab.NONE else ProControlTab.SHUTTER
                        onTabSelected(next)
                    },
                    modifier = Modifier.testTag("pro_tab_shutter")
                )

                // EV Tab
                val evVal = proSettings.evCompensation * proCaps.evStep
                val evText = if (proSettings.evCompensation == 0) "0" else String.format(Locale.US, "%+.1f", evVal)
                ProTabButton(
                    title = "EV",
                    value = evText,
                    isActive = uiState.activeProTab == ProControlTab.EV,
                    isModified = proSettings.evCompensation != 0,
                    onClick = {
                        val next = if (uiState.activeProTab == ProControlTab.EV) ProControlTab.NONE else ProControlTab.EV
                        onTabSelected(next)
                    },
                    modifier = Modifier.testTag("pro_tab_ev")
                )

                // Focus Tab
                val focusText = if (proSettings.isManualFocus) "MF" else "AF"
                ProTabButton(
                    title = "FOCUS",
                    value = focusText,
                    isActive = uiState.activeProTab == ProControlTab.FOCUS,
                    isModified = proSettings.isManualFocus,
                    onClick = {
                        val next = if (uiState.activeProTab == ProControlTab.FOCUS) ProControlTab.NONE else ProControlTab.FOCUS
                        onTabSelected(next)
                    },
                    modifier = Modifier.testTag("pro_tab_focus")
                )

                // WB Tab
                val wbText = proSettings.whiteBalance.kelvinText
                ProTabButton(
                    title = "WB",
                    value = wbText,
                    isActive = uiState.activeProTab == ProControlTab.WB,
                    isModified = proSettings.whiteBalance != ProWhitebalanceMode.AUTO,
                    onClick = {
                        val next = if (uiState.activeProTab == ProControlTab.WB) ProControlTab.NONE else ProControlTab.WB
                        onTabSelected(next)
                    },
                    modifier = Modifier.testTag("pro_tab_wb")
                )

                // Reset Tab (if modified)
                if (!proSettings.isAuto) {
                    Box(
                        modifier = Modifier
                            .liquidGlassChipSurface(
                                shape = RoundedCornerShape(12.dp),
                                cornerRadius = 12.dp,
                                isActive = false,
                                accentColor = StuDOFocusYellow
                            )
                            .clickable { onResetAll() }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("pro_tab_reset"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "RESET",
                            color = StuDOFocusYellow,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProTabButton(
    title: String,
    value: String,
    isActive: Boolean,
    isModified: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .liquidGlassChipSurface(
                shape = RoundedCornerShape(12.dp),
                cornerRadius = 12.dp,
                isActive = isActive,
                accentColor = StuDOFocusYellow,
                inactiveTopAlpha = 0.04f,
                inactiveBottomAlpha = 0.08f,
                activeFillAlpha = 0.18f
            )
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                color = if (isActive) StuDOFocusYellow else StuDOSilverMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            Text(
                text = value,
                color = if (isModified) StuDOFocusYellow else StuDOWhite.copy(alpha = 0.94f),
                fontSize = 11.sp,
                fontWeight = if (isModified) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ProValuePill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .liquidGlassChipSurface(
                shape = RoundedCornerShape(12.dp),
                cornerRadius = 12.dp,
                isActive = isSelected,
                accentColor = StuDOFocusYellow,
                activeFillAlpha = 0.24f
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) StuDOFocusYellow else StuDOWhite.copy(alpha = 0.92f),
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}
