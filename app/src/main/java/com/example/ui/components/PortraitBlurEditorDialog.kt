package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.PortraitApertureFormatter
import com.example.model.PostCapturePortraitTarget
import com.example.portrait.BokehRenderOptions
import com.example.portrait.PortraitBokehRenderer
import com.example.portrait.PortraitSegmentationResult
import com.example.portrait.PortraitSidecarStorage
import com.example.ui.theme.GlassCoolBlueSpecular
import com.example.ui.theme.GlassObsidianBottom
import com.example.ui.theme.GlassObsidianTop
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDOBlack
import com.example.ui.theme.StuDOFocusYellow
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Non-destructive post-capture Portrait Depth Studio editor.
 * Loads the untouched clean original + PNG depth/alpha maps from PortraitSidecarStorage,
 * renders fluid real-time bokeh adjustments + tap-to-refocus previews,
 * and allows updating the gallery photo in-place or saving a separate high-res copy.
 */
@Composable
fun PortraitBlurEditorDialog(
    target: PostCapturePortraitTarget,
    sidecarStorage: PortraitSidecarStorage,
    isSaving: Boolean,
    hapticsEnabled: Boolean,
    onUpdateExisting: (blurIntensity: Float, focusX: Float, focusY: Float) -> Unit,
    onSaveCopy: (blurIntensity: Float, focusX: Float, focusY: Float) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(enabled = !isSaving, onBack = onDismiss)
    val haptic = rememberStuDOHaptic(hapticsEnabled)

    var blurIntensity by remember(target.originalId) { mutableFloatStateOf(target.blurIntensity) }
    var focusX by remember(target.originalId) { mutableFloatStateOf(target.focusX) }
    var focusY by remember(target.originalId) { mutableFloatStateOf(target.focusY) }

    var cleanPreviewBitmap by remember(target.originalId) { mutableStateOf<Bitmap?>(null) }
    var segmentation by remember(target.originalId) { mutableStateOf<PortraitSegmentationResult?>(null) }
    var renderedPreviewBitmap by remember(target.originalId) { mutableStateOf<Bitmap?>(null) }
    var isComparingOriginal by remember { mutableStateOf(false) }
    var isLoadingAssets by remember(target.originalId) { mutableStateOf(true) }

    // Load clean original downscaled safely for preview + lossless matte/depth sidecars
    LaunchedEffect(target.originalId) {
        isLoadingAssets = true
        val loaded = withContext(Dispatchers.IO) {
            val file = File(target.originalFilePath)
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
            val maxDim = max(boundsOpts.outWidth, boundsOpts.outHeight).coerceAtLeast(1)
            var sample = 1
            while (maxDim / sample > 1280) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
            val seg = sidecarStorage.loadSegmentationForTarget(target)
            bmp to seg
        }
        cleanPreviewBitmap = loaded.first
        segmentation = loaded.second
        isLoadingAssets = false
    }

    // Re-render interactive preview cleanly when slider or refocus point moves
    LaunchedEffect(cleanPreviewBitmap, segmentation, blurIntensity, focusX, focusY) {
        val base = cleanPreviewBitmap ?: return@LaunchedEffect
        val seg = segmentation
        if (seg == null) {
            renderedPreviewBitmap = base
            return@LaunchedEffect
        }
        // Small debounce during rapid scrubbing to avoid wasted CPU frames
        delay(24L)
        val preview = withContext(Dispatchers.Default) {
            PortraitBokehRenderer.renderFullResolutionPortrait(
                originalUpright = base,
                segmentation = seg,
                options = BokehRenderOptions(
                    blurIntensity = blurIntensity,
                    focusX = focusX,
                    focusY = focusY
                )
            )
        }
        renderedPreviewBitmap = preview
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StuDOBlack)
            .testTag("portrait_blur_editor_dialog")
    ) {
        // Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .liquidGlassSurface(
                        shape = RoundedCornerShape(22.dp),
                        cornerRadius = 22.dp,
                        topColor = GlassObsidianTop.copy(alpha = 0.64f),
                        bottomColor = GlassObsidianBottom.copy(alpha = 0.80f),
                        borderWidth = 0.85.dp
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .minimumInteractiveComponentSize()
                        .liquidGlassChipSurface(
                            shape = CircleShape,
                            cornerRadius = 18.dp,
                            isActive = false,
                            isEnabled = !isSaving
                        )
                        .clickable(enabled = !isSaving, onClick = onDismiss)
                        .testTag("portrait_editor_close_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Portrait Editor",
                        tint = StuDOWhite,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Text(
                    text = stringResource(R.string.portrait_editor_title),
                    color = StuDOWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            // Hold / Toggle Compare Original Button
            Box(
                modifier = Modifier
                    .liquidGlassChipSurface(
                        shape = RoundedCornerShape(18.dp),
                        cornerRadius = 18.dp,
                        isActive = isComparingOriginal,
                        accentColor = StuDOAmber,
                        activeFillAlpha = 0.24f
                    )
                    .clickable {
                        haptic.tick()
                        isComparingOriginal = !isComparingOriginal
                    }
                    .padding(horizontal = 11.dp, vertical = 8.dp)
                    .testTag("portrait_editor_compare_button")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Compare,
                        contentDescription = "Compare original unblurred image",
                        tint = if (isComparingOriginal) StuDOAmber else StuDOSilver,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = if (isComparingOriginal) "ORIGINAL" else "COMPARE",
                        color = if (isComparingOriginal) StuDOAmber else StuDOSilver,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Interactive Preview Stage with Tap-to-Refocus
        val displayBmp = if (isComparingOriginal) cleanPreviewBitmap else (renderedPreviewBitmap ?: cleanPreviewBitmap)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 74.dp, bottom = 216.dp, start = 14.dp, end = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isLoadingAssets || displayBmp == null || displayBmp.isRecycled) {
                CircularProgressIndicator(
                    color = GlassCoolBlueSpecular,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(36.dp)
                )
            } else {
                val imgAspect = displayBmp.width.toFloat() / displayBmp.height.coerceAtLeast(1).toFloat()
                val boxAspect = maxWidth / maxHeight.coerceAtLeast(1.dp)
                val fittedModifier = if (imgAspect > boxAspect) {
                    Modifier.fillMaxWidth().height(maxWidth / imgAspect)
                } else {
                    Modifier.height(maxHeight).width(maxHeight * imgAspect)
                }
                Box(
                    modifier = fittedModifier
                        .pointerInput(displayBmp.width, displayBmp.height, isSaving) {
                            detectTapGestures { offset ->
                                if (!isSaving && size.width > 0 && size.height > 0) {
                                    haptic.tick()
                                    focusX = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    focusY = (offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                                    isComparingOriginal = false
                                }
                            }
                        }
                        .testTag("portrait_editor_preview_stage")
                ) {
                    Image(
                        bitmap = displayBmp.asImageBitmap(),
                        contentDescription = "Portrait depth preview. Tap any subject to refocus.",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Refocus reticle indicator
                    if (!isComparingOriginal) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val cx = focusX * size.width
                            val cy = focusY * size.height
                            drawCircle(
                                color = StuDOFocusYellow,
                                radius = 22.dp.toPx(),
                                center = Offset(cx, cy),
                                style = Stroke(width = 1.6.dp.toPx())
                            )
                            drawCircle(
                                color = StuDOFocusYellow.copy(alpha = 0.85f),
                                radius = 3.dp.toPx(),
                                center = Offset(cx, cy)
                            )
                        }
                    }
                }
            }
        }

        // Bottom Liquid Glass Control & Save Panel
        val fStopText = PortraitApertureFormatter.formatFStop(blurIntensity)
        val blurPct = (blurIntensity * 100f).roundToInt().coerceIn(0, 100)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .liquidGlassSurface(
                    shape = RoundedCornerShape(24.dp),
                    cornerRadius = 24.dp,
                    topColor = GlassObsidianTop.copy(alpha = 0.72f),
                    bottomColor = GlassObsidianBottom.copy(alpha = 0.88f),
                    borderWidth = 0.9.dp,
                    coolHighlightAlpha = 0.42f,
                    warmHighlightAlpha = 0.24f,
                    specularSheenAlpha = 0.16f
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BlurOn,
                        contentDescription = stringResource(R.string.portrait_blur_label),
                        tint = GlassCoolBlueSpecular,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "$fStopText · $blurPct%",
                        color = StuDOWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("portrait_editor_aperture_readout")
                    )
                }

                Box(
                    modifier = Modifier
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(10.dp),
                            cornerRadius = 10.dp,
                            isActive = true,
                            accentColor = GlassCoolBlueSpecular,
                            activeFillAlpha = 0.16f
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = target.depthSource.badgeLabel,
                        color = GlassCoolBlueSpecular,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                text = stringResource(R.string.portrait_editor_hint),
                color = StuDOSilverMuted,
                fontSize = 11.sp
            )

            val editorSliderCd = stringResource(R.string.portrait_aperture_cd, fStopText)
            Slider(
                value = blurIntensity,
                onValueChange = { if (!isSaving) blurIntensity = it },
                valueRange = 0f..1f,
                enabled = !isSaving,
                colors = SliderDefaults.colors(
                    thumbColor = StuDOWhite,
                    activeTrackColor = GlassCoolBlueSpecular,
                    inactiveTrackColor = Color.White.copy(alpha = 0.18f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .semantics { contentDescription = editorSliderCd }
                    .testTag("portrait_editor_blur_slider")
            )

            Text(
                text = stringResource(R.string.portrait_editor_original_preserved),
                color = StuDOSilver.copy(alpha = 0.78f),
                fontSize = 10.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(16.dp),
                            cornerRadius = 16.dp,
                            isActive = false,
                            isEnabled = !isSaving && !isLoadingAssets
                        )
                        .clickable(enabled = !isSaving && !isLoadingAssets) {
                            haptic.confirm()
                            onSaveCopy(blurIntensity, focusX, focusY)
                        }
                        .testTag("portrait_editor_save_copy_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileCopy,
                            contentDescription = stringResource(R.string.portrait_editor_save_copy),
                            tint = StuDOWhite,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.portrait_editor_save_copy),
                            color = StuDOWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(16.dp),
                            cornerRadius = 16.dp,
                            isActive = true,
                            isEnabled = !isSaving && !isLoadingAssets,
                            accentColor = GlassCoolBlueSpecular,
                            activeFillAlpha = 0.28f
                        )
                        .clickable(enabled = !isSaving && !isLoadingAssets) {
                            haptic.confirm()
                            onUpdateExisting(blurIntensity, focusX, focusY)
                        }
                        .testTag("portrait_editor_update_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.portrait_editor_update),
                            tint = StuDOWhite,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.portrait_editor_update),
                            color = StuDOWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (isSaving) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        color = GlassCoolBlueSpecular,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        text = "Rendering full-resolution bokeh…",
                        color = StuDOWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
