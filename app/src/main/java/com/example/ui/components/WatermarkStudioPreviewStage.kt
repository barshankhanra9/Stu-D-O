package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector3D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.GlassObsidianBottom
import com.example.ui.theme.GlassObsidianTop
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDOFocusYellow
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

internal const val STUDIO_MIN_ZOOM = 1.0f
internal const val STUDIO_MAX_ZOOM = 4.5f

private data class PreviewTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float
)

private val PreviewTransformConverter = TwoWayConverter<PreviewTransform, AnimationVector3D>(
    convertToVector = { AnimationVector3D(it.scale, it.offsetX, it.offsetY) },
    convertFromVector = { PreviewTransform(it.v1, it.v2, it.v3) }
)

/**
 * Clamps pan offsets strictly within the zoomed image bounds inside [containerSize].
 * At 1x fit scale, forces zero offset so the image always stays centered cleanly.
 */
internal fun clampPreviewOffset(
    offset: Offset,
    scale: Float,
    containerSize: IntSize
): Offset {
    if (scale <= STUDIO_MIN_ZOOM + 0.001f || containerSize.width <= 0 || containerSize.height <= 0) {
        return Offset.Zero
    }
    val maxOffsetX = ((containerSize.width * scale - containerSize.width) / 2f).coerceAtLeast(0f)
    val maxOffsetY = ((containerSize.height * scale - containerSize.height) / 2f).coerceAtLeast(0f)
    return Offset(
        x = offset.x.coerceIn(-maxOffsetX, maxOffsetX),
        y = offset.y.coerceIn(-maxOffsetY, maxOffsetY)
    )
}

/**
 * Computes the next focal-point-anchored zoom scale and clamped pan offset.
 */
internal fun computeFocalZoomTransform(
    currentScale: Float,
    currentOffset: Offset,
    zoomChange: Float,
    panChange: Offset,
    centroid: Offset,
    containerSize: IntSize,
    minScale: Float = STUDIO_MIN_ZOOM,
    maxScale: Float = STUDIO_MAX_ZOOM
): Pair<Float, Offset> {
    val safeCurrentScale = currentScale.coerceIn(minScale, maxScale)
    val nextScale = (safeCurrentScale * zoomChange).coerceIn(minScale, maxScale)
    if (nextScale <= minScale + 0.001f) {
        return minScale to Offset.Zero
    }
    val zoomFactor = nextScale / safeCurrentScale
    val boxCenter = Offset(containerSize.width / 2f, containerSize.height / 2f)
    val focalFromCenter = centroid - boxCenter
    val unClampedOffset = (currentOffset - focalFromCenter) * zoomFactor + focalFromCenter + panChange
    return nextScale to clampPreviewOffset(unClampedOffset, nextScale, containerSize)
}

@Composable
internal fun StudioTopFloatingToolbar(
    visualTier: StudioVisualTier,
    isPostCaptureMode: Boolean,
    canUndo: Boolean,
    isSaveEnabled: Boolean,
    onClose: () -> Unit,
    onUndo: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .widthIn(max = 680.dp)
            .fillMaxWidth()
            .studioGlassPanel(
                visualTier = visualTier,
                shape = RoundedCornerShape(26.dp),
                cornerRadius = 26.dp,
                topColor = GlassObsidianTop.copy(alpha = 0.72f),
                bottomColor = GlassObsidianBottom.copy(alpha = 0.84f),
                borderWidth = 1.dp,
                coolHighlightAlpha = 0.32f,
                warmHighlightAlpha = 0.22f,
                specularSheenAlpha = 0.14f
            )
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag("studio_top_toolbar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("studio_close_button")
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Watermark Studio",
                        tint = StuDOWhite,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            Column(modifier = Modifier.padding(end = 6.dp)) {
                Text(
                    text = "WATERMARK STUDIO",
                    color = StuDOWhite,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.3.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isPostCaptureMode) "Post-Capture Edit" else "Frame & Brand Lab",
                    color = StuDOAmber,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onUndo,
                enabled = canUndo,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("studio_undo_button")
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (canUndo) Color.White.copy(alpha = 0.12f)
                            else Color.White.copy(alpha = 0.04f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Undo",
                        tint = if (canUndo) StuDOWhite else StuDOSilverMuted.copy(alpha = 0.4f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Button(
                onClick = onSave,
                enabled = isSaveEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StuDOFocusYellow,
                    disabledContainerColor = StuDOFocusYellow.copy(alpha = 0.35f)
                ),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .testTag("studio_save_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Save",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
internal fun StudioPhotoPreviewStage(
    previewImageBitmap: ImageBitmap?,
    photoSourceKey: String,
    isEnabled: Boolean,
    styleDisplayName: String,
    visualTier: StudioVisualTier,
    isPostCaptureMode: Boolean,
    postCaptureDimensionsText: String?,
    hasLoadedPhoto: Boolean,
    isExporting: Boolean,
    onToggleWatermarkEnabled: (Boolean) -> Unit,
    onPickPhoto: () -> Unit,
    onClearPhoto: () -> Unit,
    onExportCopy: () -> Unit,
    postCaptureLabelOverride: String? = null,
    postCaptureSubtitleOverride: String? = null
) {
    val coroutineScope = rememberCoroutineScope()

    var previewScale by remember(photoSourceKey) { mutableFloatStateOf(STUDIO_MIN_ZOOM) }
    var previewOffsetX by remember(photoSourceKey) { mutableFloatStateOf(0f) }
    var previewOffsetY by remember(photoSourceKey) { mutableFloatStateOf(0f) }
    var frameSizePx by remember { mutableStateOf(IntSize.Zero) }
    var activeResetJob by remember { mutableStateOf<Job?>(null) }

    fun cancelActiveReset() {
        activeResetJob?.cancel()
        activeResetJob = null
    }

    fun animateResetToFit() {
        cancelActiveReset()
        if (previewScale <= STUDIO_MIN_ZOOM + 0.005f &&
            abs(previewOffsetX) < 0.5f &&
            abs(previewOffsetY) < 0.5f
        ) {
            previewScale = STUDIO_MIN_ZOOM
            previewOffsetX = 0f
            previewOffsetY = 0f
            return
        }
        val animatable = Animatable(
            PreviewTransform(previewScale, previewOffsetX, previewOffsetY),
            PreviewTransformConverter
        )
        activeResetJob = coroutineScope.launch {
            val spec = if (visualTier.isLowEndDevice) {
                tween<PreviewTransform>(durationMillis = 160)
            } else {
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            }
            animatable.animateTo(
                PreviewTransform(STUDIO_MIN_ZOOM, 0f, 0f),
                animationSpec = spec
            ) {
                previewScale = value.scale.coerceIn(STUDIO_MIN_ZOOM, STUDIO_MAX_ZOOM)
                previewOffsetX = value.offsetX
                previewOffsetY = value.offsetY
            }
            previewScale = STUDIO_MIN_ZOOM
            previewOffsetX = 0f
            previewOffsetY = 0f
        }
    }

    LaunchedEffect(frameSizePx, previewScale) {
        if (frameSizePx.width > 0 && frameSizePx.height > 0) {
            val clamped = clampPreviewOffset(Offset(previewOffsetX, previewOffsetY), previewScale, frameSizePx)
            previewOffsetX = clamped.x
            previewOffsetY = clamped.y
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Large centered photo preview preserving original aspect ratio and orientation
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("studio_photo_preview_stage"),
            contentAlignment = Alignment.Center
        ) {
            if (previewImageBitmap != null) {
                val bmpWidth = previewImageBitmap.width.coerceAtLeast(1).toFloat()
                val bmpHeight = previewImageBitmap.height.coerceAtLeast(1).toFloat()
                val bmpAspect = bmpWidth / bmpHeight
                val boxAspect = if (maxHeight > 0.dp) maxWidth / maxHeight else 1f

                val frameWidth = if (bmpAspect > boxAspect) maxWidth else maxHeight * bmpAspect
                val frameHeight = if (bmpAspect > boxAspect) maxWidth / bmpAspect else maxHeight

                Box(
                    modifier = Modifier
                        .size(width = frameWidth, height = frameHeight)
                        .clip(RoundedCornerShape(16.dp))
                        .clipToBounds()
                        .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
                        .onSizeChanged { frameSizePx = it }
                        .pointerInput(photoSourceKey) {
                            detectTapGestures(
                                onDoubleTap = { animateResetToFit() }
                            )
                        }
                        .pointerInput(photoSourceKey) {
                            awaitEachGesture {
                                var zoomDeltaAcc = 1f
                                var panDeltaAcc = Offset.Zero
                                var gestureLocked = false
                                val touchSlop = viewConfiguration.touchSlop

                                awaitFirstDown(requireUnconsumed = false)
                                cancelActiveReset()

                                try {
                                    do {
                                        val event = awaitPointerEvent()
                                        val pressedCount = event.changes.count { it.pressed }
                                        if (pressedCount == 0 || event.changes.any { it.isConsumed }) {
                                            break
                                        }

                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()
                                        val centroid = event.calculateCentroid(useCurrent = false)
                                        val centroidSize = event.calculateCentroidSize(useCurrent = false)

                                        if (!gestureLocked) {
                                            if (pressedCount >= 2) {
                                                zoomDeltaAcc *= zoomChange
                                                panDeltaAcc += panChange
                                                val zoomMotion = abs(1f - zoomDeltaAcc) * centroidSize
                                                if (zoomMotion > touchSlop || panDeltaAcc.getDistance() > touchSlop || abs(zoomChange - 1f) > 0.01f) {
                                                    gestureLocked = true
                                                }
                                            } else if (previewScale > STUDIO_MIN_ZOOM + 0.01f) {
                                                panDeltaAcc += panChange
                                                if (panDeltaAcc.getDistance() > touchSlop) {
                                                    gestureLocked = true
                                                }
                                            }
                                        }

                                        if (gestureLocked) {
                                            val applyZoom = pressedCount >= 2 || abs(zoomChange - 1f) > 0.001f
                                            val applyPan = previewScale > STUDIO_MIN_ZOOM + 0.01f || pressedCount >= 2
                                            if (applyZoom || applyPan) {
                                                val safeCentroid = if (centroid == Offset.Unspecified) {
                                                    Offset(size.width / 2f, size.height / 2f)
                                                } else {
                                                    centroid
                                                }
                                                val (nextScale, nextOffset) = computeFocalZoomTransform(
                                                    currentScale = previewScale,
                                                    currentOffset = Offset(previewOffsetX, previewOffsetY),
                                                    zoomChange = if (applyZoom) zoomChange else 1f,
                                                    panChange = if (applyPan) panChange else Offset.Zero,
                                                    centroid = safeCentroid,
                                                    containerSize = size
                                                )
                                                previewScale = nextScale
                                                previewOffsetX = nextOffset.x
                                                previewOffsetY = nextOffset.y
                                                event.changes.forEach { change ->
                                                    if (change.positionChanged()) change.consume()
                                                }
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                } finally {
                                    // Re-clamp cleanly even when gesture cancels unexpectedly
                                    val clamped = clampPreviewOffset(
                                        Offset(previewOffsetX, previewOffsetY),
                                        previewScale,
                                        size
                                    )
                                    previewOffsetX = clamped.x
                                    previewOffsetY = clamped.y
                                }
                            }
                        }
                        .testTag("studio_photo_preview_frame"),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = previewImageBitmap,
                        contentDescription = "Realistic Watermark Preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = previewScale
                                scaleY = previewScale
                                translationX = previewOffsetX
                                translationY = previewOffsetY
                            }
                            .testTag("studio_photo_preview_image")
                    )

                    // Subtle status tag in top-left corner
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.62f))
                            .border(0.6.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (!isEnabled) "WATERMARK OFF" else styleDisplayName.uppercase(),
                            color = if (!isEnabled) StuDOSilver else StuDOAmber,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.9.sp
                        )
                    }

                    // Transient magnification pill appears only while zoomed in; tap to reset to fit
                    if (previewScale > STUDIO_MIN_ZOOM + 0.02f) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.68f))
                                .border(0.8.dp, StuDOAmber.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                                .clickable { animateResetToFit() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .semantics {
                                    contentDescription = "Reset preview zoom to fit"
                                }
                                .testTag("studio_zoom_badge")
                        ) {
                            Text(
                                text = String.format(Locale.US, "%.1f×", previewScale),
                                color = StuDOFocusYellow,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "Rendering Studio Preview...",
                    color = StuDOSilverMuted,
                    fontSize = 12.sp
                )
            }
        }

        // Compact floating utility bar under preview (post-capture toggle or gallery test/export pills)
        Box(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .studioGlassPanel(
                    visualTier = visualTier,
                    shape = RoundedCornerShape(16.dp),
                    cornerRadius = 16.dp,
                    topColor = GlassObsidianTop.copy(alpha = 0.60f),
                    bottomColor = GlassObsidianBottom.copy(alpha = 0.74f),
                    borderWidth = 0.8.dp,
                    coolHighlightAlpha = 0.22f,
                    warmHighlightAlpha = 0.14f,
                    specularSheenAlpha = 0.08f
                )
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            if (isPostCaptureMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = postCaptureLabelOverride ?: "Clean Original Active (${postCaptureDimensionsText ?: ""})",
                            color = StuDOWhite,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isEnabled) {
                                postCaptureSubtitleOverride ?: "Rendering cleanly from unwatermarked original"
                            } else {
                                "Watermark disabled — renders unwatermarked"
                            },
                            color = StuDOSilverMuted,
                            fontSize = 9.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (isEnabled) "ON" else "OFF",
                            color = if (isEnabled) StuDOFocusYellow else StuDOSilverMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = onToggleWatermarkEnabled,
                            colors = LocalStudioSwitchColors.current
                                ?: SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = StuDOFocusYellow),
                            modifier = Modifier.testTag("studio_post_capture_watermark_toggle")
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onPickPhoto,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StuDOAmber),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .heightIn(min = 36.dp)
                                .testTag("studio_pick_photo_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (hasLoadedPhoto) "Change Photo" else "Test with Gallery Photo",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (hasLoadedPhoto) {
                            IconButton(
                                onClick = onClearPhoto,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .semantics { contentDescription = "Clear Photo" }
                                    .testTag("studio_clear_photo_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear Photo",
                                    tint = StuDOSilver,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onExportCopy,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.12f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        enabled = !isExporting,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .heightIn(min = 36.dp)
                            .testTag("studio_export_copy_button")
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(
                                color = StuDOFocusYellow,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Exporting...", fontSize = 10.sp, color = StuDOWhite)
                        } else {
                            Icon(
                                imageVector = Icons.Default.HighQuality,
                                contentDescription = null,
                                tint = StuDOAmber,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Export Full-Res Copy",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = StuDOWhite
                            )
                        }
                    }
                }
            }
        }
    }
}
