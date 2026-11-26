package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.CameraUiState
import com.example.model.FocusPeakingColor
import com.example.model.FocusPeakingSensitivity
import com.example.model.ProAssistPopover
import com.example.model.ProControlTab
import com.example.model.ProWhitebalanceMode
import com.example.model.ZebraThreshold
import com.example.ui.theme.GlassObsidianBottom
import com.example.ui.theme.GlassObsidianTop
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDOFocusYellow
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
    onToggleFocusPeaking: () -> Unit = {},
    onToggleZebraStripes: () -> Unit = {},
    onSelectAssistPopover: (ProAssistPopover) -> Unit = {},
    onFocusPeakingColorChanged: (FocusPeakingColor) -> Unit = {},
    onFocusPeakingSensitivityChanged: (FocusPeakingSensitivity) -> Unit = {},
    onZebraThresholdChanged: (ZebraThreshold) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val proSettings = uiState.proSettings
    val proCaps = uiState.proCapabilities
    val haptic = rememberStuDOHaptic(uiState.isHapticFeedbackEnabled)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Compact Liquid Glass Customization Popover for Focus Peaking or Zebra Stripes
        AnimatedVisibility(
            visible = uiState.activeProAssistPopover != ProAssistPopover.NONE,
            enter = fadeIn(tween(160)) + expandVertically(),
            exit = fadeOut(tween(140)) + shrinkVertically()
        ) {
            ProAssistPopoverPanel(
                popover = uiState.activeProAssistPopover,
                peakingColor = uiState.focusPeakingColor,
                peakingSensitivity = uiState.focusPeakingSensitivity,
                zebraThreshold = uiState.zebraThreshold,
                onSelectPeakingColor = { color ->
                    haptic.tick()
                    onFocusPeakingColorChanged(color)
                },
                onSelectPeakingSensitivity = { sens ->
                    haptic.tick()
                    onFocusPeakingSensitivityChanged(sens)
                },
                onSelectZebraThreshold = { threshold ->
                    haptic.tick()
                    onZebraThresholdChanged(threshold)
                },
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        // Pro Mode Liquid Glass Visual Assist Toolbar: [ PEAK ▾ ]  [ ZEBRA ▾ ]
        Row(
            modifier = Modifier
                .padding(bottom = 6.dp)
                .testTag("pro_assist_toolbar"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProPeakingTogglePill(
                isEnabled = uiState.isFocusPeakingEnabled,
                color = uiState.focusPeakingColor,
                sensitivity = uiState.focusPeakingSensitivity,
                isPopoverOpen = uiState.activeProAssistPopover == ProAssistPopover.FOCUS_PEAKING,
                onToggle = {
                    haptic.tick()
                    onToggleFocusPeaking()
                },
                onOpenOptions = {
                    haptic.confirm()
                    onSelectAssistPopover(ProAssistPopover.FOCUS_PEAKING)
                }
            )

            ProZebraTogglePill(
                isEnabled = uiState.isZebraStripesEnabled,
                threshold = uiState.zebraThreshold,
                isPopoverOpen = uiState.activeProAssistPopover == ProAssistPopover.ZEBRA,
                onToggle = {
                    haptic.tick()
                    onToggleZebraStripes()
                },
                onOpenOptions = {
                    haptic.confirm()
                    onSelectAssistPopover(ProAssistPopover.ZEBRA)
                }
            )
        }

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
                        topColor = GlassObsidianTop.copy(alpha = 0.56f),
                        bottomColor = GlassObsidianBottom.copy(alpha = 0.72f),
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
                        val isManualFocusSupported = proCaps.isManualFocusSupported
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ProValuePill(
                                        label = "AUTO (AF)",
                                        isSelected = !proSettings.isManualFocus || !isManualFocusSupported,
                                        onClick = { onFocusChanged(null, false) },
                                        modifier = Modifier.testTag("pro_focus_af_pill")
                                    )
                                    ProValuePill(
                                        label = "MANUAL (MF)",
                                        isSelected = proSettings.isManualFocus && isManualFocusSupported,
                                        enabled = isManualFocusSupported,
                                        onClick = { onFocusChanged(proSettings.focusDistance ?: 2.0f, true) },
                                        modifier = Modifier.testTag("pro_focus_mf_pill")
                                    )
                                }
                                if (!isManualFocusSupported) {
                                    Text(
                                        text = stringResource(R.string.pro_focus_fixed_lens),
                                        color = StuDOSilverMuted,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.testTag("pro_focus_fixed_hint")
                                    )
                                } else if (proSettings.isManualFocus) {
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

                            if (isManualFocusSupported && proSettings.isManualFocus) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Slider(
                                    value = (proSettings.focusDistance ?: 0f).coerceIn(0f, proCaps.maxFocusDistance.coerceAtLeast(0.01f)),
                                    onValueChange = { onFocusChanged(it, true) },
                                    valueRange = 0f..proCaps.maxFocusDistance.coerceAtLeast(0.01f),
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
                val focusText = when {
                    !proCaps.isManualFocusSupported -> "FIXED"
                    proSettings.isManualFocus -> "MF"
                    else -> "AF"
                }
                ProTabButton(
                    title = "FOCUS",
                    value = focusText,
                    isActive = uiState.activeProTab == ProControlTab.FOCUS,
                    isModified = proSettings.isManualFocus && proCaps.isManualFocusSupported,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProPeakingTogglePill(
    isEnabled: Boolean,
    color: FocusPeakingColor,
    sensitivity: FocusPeakingSensitivity,
    isPopoverOpen: Boolean,
    onToggle: () -> Unit,
    onOpenOptions: () -> Unit
) {
    val label = stringResource(R.string.pro_assist_peak)
    val stateLabel = if (isEnabled) "On" else "Off"
    val toggleCd = stringResource(R.string.pro_assist_peak_toggle_cd, stateLabel)
    val settingsCd = stringResource(R.string.pro_assist_peak_settings_cd)
    val textColor by animateColorAsState(
        targetValue = if (isEnabled) color.color else StuDOWhite.copy(alpha = 0.88f),
        animationSpec = tween(160),
        label = "peak_pill_text"
    )

    Row(
        modifier = Modifier
            .liquidGlassChipSurface(
                shape = RoundedCornerShape(16.dp),
                cornerRadius = 16.dp,
                isActive = isEnabled || isPopoverOpen,
                accentColor = color.color,
                inactiveTopAlpha = 0.10f,
                inactiveBottomAlpha = 0.28f,
                activeFillAlpha = 0.22f
            )
            .heightIn(min = 34.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(
                    onClick = onToggle,
                    onLongClick = onOpenOptions
                )
                .padding(start = 11.dp, end = 6.dp, top = 7.dp, bottom = 7.dp)
                .semantics { contentDescription = toggleCd }
                .testTag("pro_peak_pill"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(
                        if (isEnabled) color.color else color.color.copy(alpha = 0.45f)
                    )
            )
            Text(
                text = label,
                color = textColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.9.sp
            )
            if (isEnabled) {
                Text(
                    text = sensitivity.shortLabel.uppercase(Locale.US),
                    color = color.color.copy(alpha = 0.82f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(14.dp)
                .background(Color.White.copy(alpha = 0.14f))
        )

        Box(
            modifier = Modifier
                .clickable(onClick = onOpenOptions)
                .padding(start = 5.dp, end = 8.dp, top = 7.dp, bottom = 7.dp)
                .semantics { contentDescription = settingsCd }
                .testTag("pro_peak_chevron"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPopoverOpen) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp,
                contentDescription = null,
                tint = if (isPopoverOpen || isEnabled) color.color else StuDOSilverMuted,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProZebraTogglePill(
    isEnabled: Boolean,
    threshold: ZebraThreshold,
    isPopoverOpen: Boolean,
    onToggle: () -> Unit,
    onOpenOptions: () -> Unit
) {
    val label = stringResource(R.string.pro_assist_zebra)
    val stateLabel = if (isEnabled) "On" else "Off"
    val toggleCd = stringResource(R.string.pro_assist_zebra_toggle_cd, stateLabel)
    val settingsCd = stringResource(R.string.pro_assist_zebra_settings_cd)
    val activeAccent = StuDOAmber
    val textColor by animateColorAsState(
        targetValue = if (isEnabled) StuDOFocusYellow else StuDOWhite.copy(alpha = 0.88f),
        animationSpec = tween(160),
        label = "zebra_pill_text"
    )

    Row(
        modifier = Modifier
            .liquidGlassChipSurface(
                shape = RoundedCornerShape(16.dp),
                cornerRadius = 16.dp,
                isActive = isEnabled || isPopoverOpen,
                accentColor = activeAccent,
                inactiveTopAlpha = 0.10f,
                inactiveBottomAlpha = 0.28f,
                activeFillAlpha = 0.22f
            )
            .heightIn(min = 34.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(
                    onClick = onToggle,
                    onLongClick = onOpenOptions
                )
                .padding(start = 11.dp, end = 6.dp, top = 7.dp, bottom = 7.dp)
                .semantics { contentDescription = toggleCd }
                .testTag("pro_zebra_pill"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // Micro diagonal-stripe emblem for instant Zebra recognition
            Canvas(modifier = Modifier.size(10.dp)) {
                val stripeColor = if (isEnabled) StuDOFocusYellow else StuDOSilver.copy(alpha = 0.72f)
                val sw = 1.4.dp.toPx()
                drawLine(
                    color = stripeColor,
                    start = Offset(0f, size.height * 0.55f),
                    end = Offset(size.width * 0.55f, 0f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = stripeColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, 0f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = stripeColor,
                    start = Offset(size.width * 0.45f, size.height),
                    end = Offset(size.width, size.height * 0.45f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round
                )
            }
            Text(
                text = label,
                color = textColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.9.sp
            )
            if (isEnabled) {
                Text(
                    text = threshold.displayName,
                    color = StuDOFocusYellow.copy(alpha = 0.85f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(14.dp)
                .background(Color.White.copy(alpha = 0.14f))
        )

        Box(
            modifier = Modifier
                .clickable(onClick = onOpenOptions)
                .padding(start = 5.dp, end = 8.dp, top = 7.dp, bottom = 7.dp)
                .semantics { contentDescription = settingsCd }
                .testTag("pro_zebra_chevron"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPopoverOpen) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp,
                contentDescription = null,
                tint = if (isPopoverOpen || isEnabled) StuDOFocusYellow else StuDOSilverMuted,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
private fun ProAssistPopoverPanel(
    popover: ProAssistPopover,
    peakingColor: FocusPeakingColor,
    peakingSensitivity: FocusPeakingSensitivity,
    zebraThreshold: ZebraThreshold,
    onSelectPeakingColor: (FocusPeakingColor) -> Unit,
    onSelectPeakingSensitivity: (FocusPeakingSensitivity) -> Unit,
    onSelectZebraThreshold: (ZebraThreshold) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlassSurface(
                shape = RoundedCornerShape(18.dp),
                cornerRadius = 18.dp,
                topColor = GlassObsidianTop.copy(alpha = 0.72f),
                bottomColor = GlassObsidianBottom.copy(alpha = 0.86f),
                borderWidth = 0.9.dp,
                coolHighlightAlpha = 0.36f,
                warmHighlightAlpha = 0.24f,
                specularSheenAlpha = 0.16f
            )
            .pointerInput(Unit) { detectTapGestures { /* absorb taps inside popover */ } }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("pro_assist_popover")
    ) {
        when (popover) {
            ProAssistPopover.FOCUS_PEAKING -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.pro_assist_peak_title),
                        color = StuDOSilver,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.pro_assist_peak_color),
                            color = StuDOSilverMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.7.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FocusPeakingColor.entries.forEach { option ->
                                val isSelected = peakingColor == option
                                Box(
                                    modifier = Modifier
                                        .liquidGlassChipSurface(
                                            shape = RoundedCornerShape(12.dp),
                                            cornerRadius = 12.dp,
                                            isActive = isSelected,
                                            accentColor = option.color,
                                            activeFillAlpha = 0.24f
                                        )
                                        .clickable { onSelectPeakingColor(option) }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                        .testTag("peaking_color_${option.name.lowercase(Locale.US)}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(option.color)
                                        )
                                        Text(
                                            text = option.displayName,
                                            color = if (isSelected) option.color else StuDOWhite.copy(alpha = 0.90f),
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.pro_assist_peak_sensitivity),
                            color = StuDOSilverMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.7.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FocusPeakingSensitivity.entries.forEach { option ->
                                val isSelected = peakingSensitivity == option
                                Box(
                                    modifier = Modifier
                                        .liquidGlassChipSurface(
                                            shape = RoundedCornerShape(12.dp),
                                            cornerRadius = 12.dp,
                                            isActive = isSelected,
                                            accentColor = peakingColor.color,
                                            activeFillAlpha = 0.24f
                                        )
                                        .clickable { onSelectPeakingSensitivity(option) }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                        .testTag("peaking_sensitivity_${option.name.lowercase(Locale.US)}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = option.shortLabel,
                                        color = if (isSelected) peakingColor.color else StuDOWhite.copy(alpha = 0.90f),
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ProAssistPopover.ZEBRA -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.pro_assist_zebra_title),
                        color = StuDOSilver,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.pro_assist_zebra_threshold),
                            color = StuDOSilverMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.7.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ZebraThreshold.entries.forEach { option ->
                                val isSelected = zebraThreshold == option
                                Box(
                                    modifier = Modifier
                                        .liquidGlassChipSurface(
                                            shape = RoundedCornerShape(12.dp),
                                            cornerRadius = 12.dp,
                                            isActive = isSelected,
                                            accentColor = StuDOFocusYellow,
                                            activeFillAlpha = 0.24f
                                        )
                                        .clickable { onSelectZebraThreshold(option) }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                        .testTag("zebra_threshold_${option.name.lowercase(Locale.US)}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = option.displayName,
                                        color = if (isSelected) StuDOFocusYellow else StuDOWhite.copy(alpha = 0.90f),
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ProAssistPopover.NONE -> {}
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
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.38f)
            .liquidGlassChipSurface(
                shape = RoundedCornerShape(12.dp),
                cornerRadius = 12.dp,
                isActive = isSelected && enabled,
                accentColor = StuDOFocusYellow,
                activeFillAlpha = 0.24f
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected && enabled) StuDOFocusYellow else StuDOWhite.copy(alpha = 0.92f),
            fontSize = 11.sp,
            fontWeight = if (isSelected && enabled) FontWeight.Bold else FontWeight.Medium
        )
    }
}
