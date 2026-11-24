package com.example.ui.components

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BrandingWatermark
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp as lerpOffset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size as CoilSize
import com.example.model.MediaItem
import com.example.model.PhotoDetails
import com.example.ui.theme.GlassCoolBlueSpecular
import com.example.ui.theme.GlassObsidianBottom
import com.example.ui.theme.GlassObsidianTop
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDOBlack
import com.example.ui.theme.StuDOCarbon
import com.example.ui.theme.StuDOFocusYellow
import com.example.ui.theme.StuDORed
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite
import com.example.util.HighResPhotoSession
import com.example.util.HighResZoomMath
import com.example.util.PhotoMetadataExtractor
import com.example.util.RichPhotoMetadata
import com.example.util.VideoThumbnailUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

@Composable
fun PhotoPreviewDialog(
    items: List<MediaItem>,
    currentIndex: Int,
    onIndexChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (MediaItem) -> Unit,
    onGetDetails: (MediaItem) -> PhotoDetails,
    onEditPhoto: ((MediaItem) -> Unit)? = null,
    onEditWatermark: ((MediaItem) -> Unit)? = null,
    isPortraitEditable: ((MediaItem) -> Boolean)? = null,
    onEditPortrait: ((MediaItem) -> Unit)? = null,
    binCount: Int = 0,
    onOpenRecycleBin: () -> Unit = {},
    mediaRevision: Long = 0L,
    watermarkUnavailableMessage: String? = null,
    onDismissWatermarkUnavailable: () -> Unit = {},
    onFallbackEditPhoto: (() -> Unit)? = null
) {
    if (items.isEmpty() || currentIndex !in items.indices) {
        return
    }

    // System back handler closes preview cleanly
    BackHandler(onBack = onDismiss)

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val currentItem = items[currentIndex]

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDetailsSheet by remember { mutableStateOf(false) }
    var richMetadata by remember { mutableStateOf<RichPhotoMetadata?>(null) }
    var isLoadingMetadata by remember { mutableStateOf(false) }
    var isCurrentPhotoZoomed by remember { mutableStateOf(false) }
    val filmstripListState = rememberLazyListState()

    // Smooth horizontal swipe pager
    val pagerState = rememberPagerState(
        initialPage = currentIndex.coerceIn(0, items.size - 1),
        pageCount = { items.size }
    )

    val latestCurrentIndex by rememberUpdatedState(currentIndex)
    val latestOnIndexChange by rememberUpdatedState(onIndexChange)

    // Sync pager swipe to parent currentIndex
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            isCurrentPhotoZoomed = false
            if (page in items.indices && page != latestCurrentIndex) {
                latestOnIndexChange(page)
            }
        }
    }

    // Sync external currentIndex changes to pager and filmstrip
    LaunchedEffect(currentIndex) {
        if (pagerState.currentPage != currentIndex && currentIndex in items.indices) {
            pagerState.animateScrollToPage(currentIndex)
        }
        filmstripListState.animateScrollToItem(currentIndex)
    }

    // Load authentic EXIF / container metadata asynchronously when Info sheet opens or item updates
    LaunchedEffect(showDetailsSheet, currentItem.uri, mediaRevision) {
        if (showDetailsSheet) {
            isLoadingMetadata = true
            val baseDetails = onGetDetails(currentItem)
            richMetadata = PhotoMetadataExtractor.extract(context, currentItem, baseDetails)
            isLoadingMetadata = false
        } else {
            richMetadata = null
        }
    }

    // Fullscreen Liquid Glass Media Browser Overlay (renders directly in Activity window for accurate edge-to-edge insets)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StuDOBlack)
    ) {
        // Main Media Content Viewport: Swipeable HorizontalPager
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 74.dp, bottom = 124.dp),
            contentAlignment = Alignment.Center
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !isCurrentPhotoZoomed && !showDetailsSheet,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("gallery_horizontal_pager")
            ) { page ->
                val pageItem = items.getOrNull(page)
                if (pageItem != null) {
                    if (pageItem.isVideo) {
                        VideoPlayerView(
                            videoUri = pageItem.uri,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // Pinchable & Zoomable Photo View with Pan and Smooth Double-Tap Toggle
                        ZoomablePhotoView(
                            photoUri = pageItem.uri,
                            mediaRevision = mediaRevision,
                            onZoomChanged = { zoomed ->
                                if (page == pagerState.currentPage) {
                                    isCurrentPhotoZoomed = zoomed
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        // Cohesive Two-Capsule Liquid Glass Top Toolbar
        val canEditPortrait = !currentItem.isVideo &&
            onEditPortrait != null &&
            (isPortraitEditable?.invoke(currentItem) == true)
        PhotoPreviewTopToolbar(
            currentIndex = currentIndex,
            totalCount = items.size,
            currentItem = currentItem,
            showDetailsSheet = showDetailsSheet,
            binCount = binCount,
            onDismiss = onDismiss,
            canEditPortrait = canEditPortrait,
            onEditPortrait = onEditPortrait,
            onEditWatermark = onEditWatermark,
            onEditPhoto = onEditPhoto,
            onToggleDetails = { showDetailsSheet = !showDetailsSheet },
            onShare = {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = if (currentItem.isVideo) "video/*" else "image/*"
                    putExtra(Intent.EXTRA_STREAM, currentItem.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    context.startActivity(Intent.createChooser(shareIntent, "Share Media"))
                } catch (_: android.content.ActivityNotFoundException) {}
            },
            onOpenRecycleBin = onOpenRecycleBin,
            onRequestDelete = { showDeleteConfirm = true }
        )

        // Bottom Filmstrip Carousel: Completely visible above the navigation bar
        if (!showDetailsSheet) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.8f),
                                Color.Black.copy(alpha = 0.98f),
                                Color.Black
                            )
                        )
                    )
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 28.dp, top = 8.dp)
            ) {
                LazyRow(
                    state = filmstripListState,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gallery_filmstrip_row")
                ) {
                    itemsIndexed(items, key = { _, item -> "${item.id}_${item.uri}" }) { index, item ->
                        val isSelected = (index == currentIndex)
                        Box(
                            modifier = Modifier
                                .size(62.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(StuDOCarbon)
                                .border(
                                    width = if (isSelected) 2.5.dp else 0.8.dp,
                                    color = if (isSelected) StuDOFocusYellow else Color.White.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable {
                                    onIndexChange(index)
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(index)
                                    }
                                }
                        ) {
                            MediaThumbnailView(
                                item = item,
                                modifier = Modifier.fillMaxSize(),
                                mediaRevision = mediaRevision
                            )
                        }
                    }
                }
            }
        }

        // Liquid Glass Photo / Video Information & Metadata Sheet
        PhotoDetailsSheetOverlay(
            visible = showDetailsSheet,
            metadata = richMetadata,
            isLoading = isLoadingMetadata,
            onDismiss = { showDetailsSheet = false },
            isVideoHint = currentItem.isVideo
        )

        // Move to StuDO Bin Confirmation Dialog
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = {
                    Text(
                        text = "Move to StuDO Bin?",
                        color = StuDOWhite,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "This item will be moved to StuDO Bin so you can restore or permanently delete it later.",
                        color = StuDOSilverMuted
                    )
                },
                containerColor = StuDOCarbon,
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirm = false
                            onDelete(currentItem)
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = StuDOAmber)
                    ) {
                        Text("Move to Bin", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDeleteConfirm = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = StuDOSilverMuted)
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Clean Original Unavailable Alert (prevents double-watermarking already stamped photos)
        if (watermarkUnavailableMessage != null) {
            AlertDialog(
                onDismissRequest = onDismissWatermarkUnavailable,
                title = {
                    Text(
                        text = "Clean Original Unavailable",
                        color = StuDOWhite,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = watermarkUnavailableMessage,
                        color = StuDOSilver
                    )
                },
                containerColor = StuDOCarbon,
                confirmButton = {
                    TextButton(
                        onClick = onDismissWatermarkUnavailable,
                        colors = ButtonDefaults.textButtonColors(contentColor = StuDOFocusYellow),
                        modifier = Modifier.testTag("watermark_unavailable_dismiss_button")
                    ) {
                        Text("OK", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = if (onFallbackEditPhoto != null) {
                    {
                        TextButton(
                            onClick = onFallbackEditPhoto,
                            colors = ButtonDefaults.textButtonColors(contentColor = StuDOAmber),
                            modifier = Modifier.testTag("watermark_unavailable_fallback_editor_button")
                        ) {
                            Text("Open Photo Editor")
                        }
                    }
                } else null
            )
        }
    }
}

@Composable
private fun PhotoPreviewTopToolbar(
    currentIndex: Int,
    totalCount: Int,
    currentItem: MediaItem,
    showDetailsSheet: Boolean,
    binCount: Int,
    onDismiss: () -> Unit,
    canEditPortrait: Boolean,
    onEditPortrait: ((MediaItem) -> Unit)?,
    onEditWatermark: ((MediaItem) -> Unit)?,
    onEditPhoto: ((MediaItem) -> Unit)?,
    onToggleDetails: () -> Unit,
    onShare: () -> Unit,
    onOpenRecycleBin: () -> Unit,
    onRequestDelete: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("preview_top_toolbar")
    ) {
        val isCompact = maxWidth < 395.dp
        val editActionCount = if (currentItem.isVideo) {
            0
        } else {
            (if (canEditPortrait && onEditPortrait != null) 1 else 0) +
                (if (onEditWatermark != null) 1 else 0) +
                (if (onEditPhoto != null) 1 else 0)
        }
        val showMediaTypeBadge = !isCompact || editActionCount < 2
        val buttonSize = when {
            isCompact && editActionCount >= 3 -> 27.dp
            isCompact -> 30.dp
            else -> 36.dp
        }
        val iconSize = when {
            isCompact && editActionCount >= 3 -> 14.dp
            isCompact -> 15.dp
            else -> 17.dp
        }
        val buttonGap = if (isCompact) 3.dp else 5.dp
        val capsuleHPad = if (isCompact) 5.dp else 6.dp

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Capsule: Close + Divider + Counter & Media Badge
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .liquidGlassSurface(
                        shape = RoundedCornerShape(24.dp),
                        cornerRadius = 24.dp,
                        topColor = GlassObsidianTop.copy(alpha = 0.56f),
                        bottomColor = GlassObsidianBottom.copy(alpha = 0.72f),
                        borderWidth = 0.8.dp,
                        coolHighlightAlpha = 0.34f,
                        warmHighlightAlpha = 0.20f,
                        specularSheenAlpha = 0.13f
                    )
                    .padding(horizontal = capsuleHPad, vertical = 4.dp)
                    .testTag("preview_top_left_capsule"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (isCompact) 5.dp else 7.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(buttonSize)
                        .liquidGlassChipSurface(
                            shape = CircleShape,
                            cornerRadius = 18.dp,
                            isActive = false
                        )
                        .clickable(onClick = onDismiss)
                        .testTag("preview_close_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close preview",
                        tint = StuDOWhite,
                        modifier = Modifier.size(iconSize)
                    )
                }

                ToolbarVerticalDivider()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(end = if (isCompact) 3.dp else 6.dp)
                ) {
                    Text(
                        text = "${currentIndex + 1} / $totalCount",
                        color = StuDOWhite,
                        fontSize = if (isCompact) 11.sp else 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.3.sp,
                        maxLines = 1,
                        softWrap = false
                    )

                    if (showMediaTypeBadge) {
                        Box(
                            modifier = Modifier
                                .liquidGlassChipSurface(
                                    shape = RoundedCornerShape(8.dp),
                                    cornerRadius = 8.dp,
                                    isActive = currentItem.isVideo,
                                    accentColor = StuDORed,
                                    activeFillAlpha = 0.24f
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (currentItem.isVideo) "VIDEO" else "PHOTO",
                                color = if (currentItem.isVideo) StuDOWhite else StuDOSilver,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.7.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(if (isCompact) 4.dp else 8.dp))

            // Right Capsule: Balanced Action Hierarchy with Restrained Glass Delete & StuDO Bin
            val hasEditActions = editActionCount > 0
            Row(
                modifier = Modifier
                    .liquidGlassSurface(
                        shape = RoundedCornerShape(24.dp),
                        cornerRadius = 24.dp,
                        topColor = GlassObsidianTop.copy(alpha = 0.56f),
                        bottomColor = GlassObsidianBottom.copy(alpha = 0.72f),
                        borderWidth = 0.8.dp,
                        coolHighlightAlpha = 0.34f,
                        warmHighlightAlpha = 0.22f,
                        specularSheenAlpha = 0.13f
                    )
                    .padding(horizontal = capsuleHPad, vertical = 4.dp)
                    .testTag("preview_top_right_capsule"),
                horizontalArrangement = Arrangement.spacedBy(buttonGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!currentItem.isVideo && canEditPortrait && onEditPortrait != null) {
                    Box(
                        modifier = Modifier
                            .size(buttonSize)
                            .liquidGlassChipSurface(
                                shape = CircleShape,
                                cornerRadius = 18.dp,
                                isActive = true,
                                accentColor = GlassCoolBlueSpecular,
                                activeFillAlpha = 0.22f
                            )
                            .clickable { onEditPortrait(currentItem) }
                            .testTag("preview_edit_portrait_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.BlurOn,
                            contentDescription = "Edit portrait blur",
                            tint = GlassCoolBlueSpecular,
                            modifier = Modifier.size(iconSize)
                        )
                    }
                }

                if (!currentItem.isVideo && onEditWatermark != null) {
                    Box(
                        modifier = Modifier
                            .size(buttonSize)
                            .liquidGlassChipSurface(
                                shape = CircleShape,
                                cornerRadius = 18.dp,
                                isActive = true,
                                accentColor = StuDOAmber,
                                activeFillAlpha = 0.20f
                            )
                            .clickable { onEditWatermark(currentItem) }
                            .testTag("preview_edit_watermark_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.BrandingWatermark,
                            contentDescription = "Edit watermark",
                            tint = StuDOAmber,
                            modifier = Modifier.size(iconSize)
                        )
                    }
                }

                if (!currentItem.isVideo && onEditPhoto != null) {
                    Box(
                        modifier = Modifier
                            .size(buttonSize)
                            .liquidGlassChipSurface(
                                shape = CircleShape,
                                cornerRadius = 18.dp,
                                isActive = true,
                                accentColor = StuDOFocusYellow,
                                activeFillAlpha = 0.18f
                            )
                            .clickable { onEditPhoto(currentItem) }
                            .testTag("preview_edit_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Edit photo",
                            tint = StuDOFocusYellow,
                            modifier = Modifier.size(iconSize)
                        )
                    }
                }

                if (hasEditActions) {
                    ToolbarVerticalDivider()
                }

                Box(
                    modifier = Modifier
                        .size(buttonSize)
                        .liquidGlassChipSurface(
                            shape = CircleShape,
                            cornerRadius = 18.dp,
                            isActive = showDetailsSheet,
                            accentColor = GlassCoolBlueSpecular,
                            activeFillAlpha = 0.22f
                        )
                        .clickable(onClick = onToggleDetails)
                        .testTag("preview_info_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "Media details",
                        tint = if (showDetailsSheet) GlassCoolBlueSpecular else StuDOWhite,
                        modifier = Modifier.size(iconSize)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(buttonSize)
                        .liquidGlassChipSurface(
                            shape = CircleShape,
                            cornerRadius = 18.dp,
                            isActive = false
                        )
                        .clickable(onClick = onShare)
                        .testTag("preview_share_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = "Share media",
                        tint = StuDOWhite,
                        modifier = Modifier.size(iconSize)
                    )
                }

                ToolbarVerticalDivider()

                Box(
                    modifier = Modifier
                        .size(buttonSize)
                        .liquidGlassChipSurface(
                            shape = CircleShape,
                            cornerRadius = 18.dp,
                            isActive = binCount > 0,
                            accentColor = StuDOAmber,
                            activeFillAlpha = 0.16f
                        )
                        .clickable(onClick = onOpenRecycleBin)
                        .testTag("gallery_bin_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.RestoreFromTrash,
                        contentDescription = "Open StuDO Bin",
                        tint = if (binCount > 0) StuDOAmber else StuDOSilver.copy(alpha = 0.88f),
                        modifier = Modifier.size(iconSize)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(buttonSize)
                        .liquidGlassChipSurface(
                            shape = CircleShape,
                            cornerRadius = 18.dp,
                            isActive = false
                        )
                        .clickable(onClick = onRequestDelete)
                        .testTag("preview_delete_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Move media to StuDO Bin",
                        tint = StuDOSilver.copy(alpha = 0.90f),
                        modifier = Modifier.size(iconSize)
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolbarVerticalDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(16.dp)
            .background(Color.White.copy(alpha = 0.14f))
    )
}

/**
 * Zoomable & Pinchable High-Resolution Photo View with:
 * - Instant EXIF-oriented base preview decoding (no thumbnail cache collision)
 * - Progressive asynchronous BitmapRegionDecoder tiling for crisp detail on pinch-to-zoom
 * - Conflict-free unified gesture handling: smooth animated double-tap toggle (1.0x <-> 2.5x),
 *   2-finger centroid-anchored pinch zoom (1.0x up to 1:1+ native pixel detail),
 *   and single-finger zoomed panning within fitted image bounds.
 * - Transparent pass-through of single-finger swipes when at 1.0x scale,
 *   allowing HorizontalPager to smoothly swipe to previous/next media without blocking.
 */
@Composable
private fun ZoomablePhotoView(
    photoUri: Uri,
    mediaRevision: Long = 0L,
    onZoomChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val latestOnZoomChanged by rememberUpdatedState(onZoomChanged)
    val doubleTapSlopPx = remember(context) {
        ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat().coerceAtLeast(48f)
    }

    var scale by remember(photoUri) { mutableFloatStateOf(1f) }
    var offset by remember(photoUri) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var zoomAnimationJob by remember(photoUri) { mutableStateOf<Job?>(null) }

    var session by remember { mutableStateOf<HighResPhotoSession?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var useFallbackAsyncImage by remember { mutableStateOf(false) }
    var tileGeneration by remember { mutableIntStateOf(0) }

    val activeSessionHolder = remember { arrayOfNulls<HighResPhotoSession>(1) }

    // Notify pager whether current photo is zoomed so zoomed drags never trigger accidental page turns
    LaunchedEffect(photoUri) {
        snapshotFlow { scale > 1.05f }
            .distinctUntilChanged()
            .collect { zoomed ->
                latestOnZoomChanged(zoomed)
            }
    }

    // Open crisp upright base preview + BitmapRegionDecoder asynchronously
    LaunchedEffect(photoUri, mediaRevision) {
        val oldForDifferentUri = if (activeSessionHolder[0] != null) activeSessionHolder[0] else null
        isLoading = session == null
        useFallbackAsyncImage = false

        val loaded = HighResPhotoSession.open(context, photoUri)
        if (loaded != null) {
            val prev = activeSessionHolder[0]
            activeSessionHolder[0] = loaded
            session = loaded
            isLoading = false
            useFallbackAsyncImage = false
            tileGeneration++

            // Re-clamp current pan offset against new image aspect/bounds (preserves zoom/position on revision reload)
            val vpW = viewportSize.width.toFloat()
            val vpH = viewportSize.height.toFloat()
            if (scale > 1f && vpW > 0f && vpH > 0f) {
                val fit = HighResZoomMath.computeFitScale(vpW, vpH, loaded.metadata.displayWidth, loaded.metadata.displayHeight)
                val maxPan = HighResZoomMath.computeMaxPanOffsets(
                    vpW,
                    vpH,
                    loaded.metadata.displayWidth * fit,
                    loaded.metadata.displayHeight * fit,
                    scale
                )
                offset = Offset(
                    x = offset.x.coerceIn(-maxPan.x, maxPan.x),
                    y = offset.y.coerceIn(-maxPan.y, maxPan.y)
                )
            }
            if (prev !== loaded) {
                prev?.close()
            }
        } else {
            // Preserve prior session if only transient; otherwise use Coil original-size fallback
            if (oldForDifferentUri == null || session == null) {
                isLoading = false
                useFallbackAsyncImage = true
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activeSessionHolder[0]?.close()
            activeSessionHolder[0] = null
        }
    }

    // Asynchronously decode higher-resolution visible regions when zoomed past base resolution
    LaunchedEffect(session, mediaRevision) {
        val activeSession = session ?: return@LaunchedEffect
        if (!activeSession.hasHigherResolutionThanBase) return@LaunchedEffect

        snapshotFlow { Triple(scale, offset, viewportSize) }
            .collectLatest { (curScale, curOffset, vpSize) ->
                val vpW = vpSize.width.toFloat()
                val vpH = vpSize.height.toFloat()
                if (vpW <= 0f || vpH <= 0f) return@collectLatest

                val meta = activeSession.metadata
                val fitScale = HighResZoomMath.computeFitScale(vpW, vpH, meta.displayWidth, meta.displayHeight)
                val targetSampleSize = HighResZoomMath.computeTargetSampleSize(
                    displayWidth = meta.displayWidth,
                    displayHeight = meta.displayHeight,
                    fitScale = fitScale,
                    zoomScale = curScale
                )

                if (targetSampleSize >= activeSession.baseSampleSize) {
                    return@collectLatest
                }

                if (activeSession.supportsRegionDecoding) {
                    val visibleTiles = HighResZoomMath.computeVisibleTiles(
                        displayWidth = meta.displayWidth,
                        displayHeight = meta.displayHeight,
                        viewportWidth = vpW,
                        viewportHeight = vpH,
                        fitScale = fitScale,
                        scale = curScale,
                        offset = curOffset,
                        sampleSize = targetSampleSize
                    )
                    if (visibleTiles.isEmpty()) return@collectLatest

                    // Skip debounce if every visible tile at this resolution is already cached
                    val cachedMap = activeSession.getCachedTilesSnapshot(targetSampleSize)
                        .filter { it.key.sampleSize == targetSampleSize }
                        .associateBy { it.key }
                    val hasMissing = visibleTiles.any { (key, _) -> !cachedMap.containsKey(key) }
                    if (!hasMissing) return@collectLatest

                    // Brief settle delay avoids repeated region decoding mid-pinch/fling
                    delay(70L)
                    val decoded = activeSession.loadMissingTiles(visibleTiles)
                    if (decoded.isNotEmpty()) {
                        tileGeneration++
                    }
                } else if (curScale > 1.25f) {
                    delay(70L)
                    val promoted = activeSession.ensureHighResFallback(context, photoUri)
                    if (promoted != null) {
                        tileGeneration++
                    }
                }
            }
    }

    val filterPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    }

    fun resolveMaxZoom(): Float {
        val activeSession = session ?: return 6f
        val vpW = viewportSize.width.toFloat()
        val vpH = viewportSize.height.toFloat()
        if (vpW <= 0f || vpH <= 0f) return 6f
        val fitScale = HighResZoomMath.computeFitScale(vpW, vpH, activeSession.metadata.displayWidth, activeSession.metadata.displayHeight)
        val fittedW = activeSession.metadata.displayWidth * fitScale
        val nativePixelScale = activeSession.metadata.displayWidth.toFloat() / fittedW.coerceAtLeast(1f)
        return maxOf(6f, nativePixelScale * 1.5f).coerceAtMost(12f)
    }

    fun clampOffsetFor(targetScale: Float, candidateOffset: Offset): Offset {
        if (targetScale <= 1f) return Offset.Zero
        val vpW = viewportSize.width.toFloat()
        val vpH = viewportSize.height.toFloat()
        val activeSession = session
        if (activeSession == null || vpW <= 0f || vpH <= 0f) {
            val maxPanX = (targetScale - 1f) * 600f
            val maxPanY = (targetScale - 1f) * 800f
            return Offset(
                x = candidateOffset.x.coerceIn(-maxPanX, maxPanX),
                y = candidateOffset.y.coerceIn(-maxPanY, maxPanY)
            )
        }
        val fitScale = HighResZoomMath.computeFitScale(
            vpW,
            vpH,
            activeSession.metadata.displayWidth,
            activeSession.metadata.displayHeight
        )
        val maxPan = HighResZoomMath.computeMaxPanOffsets(
            viewportWidth = vpW,
            viewportHeight = vpH,
            fittedWidth = activeSession.metadata.displayWidth * fitScale,
            fittedHeight = activeSession.metadata.displayHeight * fitScale,
            scale = targetScale
        )
        return Offset(
            x = candidateOffset.x.coerceIn(-maxPan.x, maxPan.x),
            y = candidateOffset.y.coerceIn(-maxPan.y, maxPan.y)
        )
    }

    fun animateZoomTo(targetScale: Float, targetOffset: Offset) {
        zoomAnimationJob?.cancel()
        val startScale = scale
        val startOffset = offset
        zoomAnimationJob = coroutineScope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
            ) { fraction, _ ->
                scale = lerp(startScale, targetScale, fraction)
                offset = lerpOffset(startOffset, targetOffset, fraction)
            }
            scale = targetScale
            offset = if (targetScale <= 1f) Offset.Zero else targetOffset
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it }
            .clipToBounds()
            .semantics {
                contentDescription = "Full photo preview"
                stateDescription = if (scale > 1.05f) {
                    String.format(Locale.US, "Zoomed %.1fx", scale)
                } else {
                    "Fit to screen"
                }
            }
            .testTag("preview_zoomable_photo")
            .pointerInput(photoUri) {
                var lastTapTimeMs = 0L
                var lastTapPosition = Offset.Zero

                awaitEachGesture {
                    var zoom = 1f
                    var pan = Offset.Zero
                    var pastTouchSlop = false
                    var wasMultiTouch = false
                    var wasCanceled = false
                    val touchSlop = viewConfiguration.touchSlop

                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    val downTimeMs = firstDown.uptimeMillis
                    val downPosition = firstDown.position
                    var lastReleaseTimeMs = downTimeMs
                    var lastReleasePosition = downPosition

                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.isConsumed }) {
                            wasCanceled = true
                            break
                        }

                        val pointerCount = event.changes.size
                        if (pointerCount >= 2) {
                            wasMultiTouch = true
                            zoomAnimationJob?.cancel()

                            // Two or more fingers: centroid-anchored pinch zoom & pan
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()

                            if (!pastTouchSlop) {
                                zoom *= zoomChange
                                pan += panChange
                                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                val zoomMotion = abs(zoom - 1f) * centroidSize
                                val panMotion = pan.getDistance()

                                if (zoomMotion > touchSlop || panMotion > touchSlop) {
                                    pastTouchSlop = true
                                }
                            }

                            if (pastTouchSlop) {
                                if (zoomChange != 1f || panChange != Offset.Zero) {
                                    val oldScale = scale
                                    val newScale = (oldScale * zoomChange).coerceIn(1f, resolveMaxZoom())
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    val vpW = viewportSize.width.toFloat()
                                    val vpH = viewportSize.height.toFloat()
                                    val focalDelta = if (vpW > 0f && vpH > 0f && centroid != Offset.Unspecified) {
                                        centroid - Offset(vpW / 2f, vpH / 2f)
                                    } else {
                                        Offset.Zero
                                    }
                                    val ratio = newScale / oldScale.coerceAtLeast(1f)
                                    val rawOffset = (offset - focalDelta) * ratio + focalDelta + panChange
                                    scale = newScale
                                    offset = clampOffsetFor(newScale, rawOffset)
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } else if (pointerCount == 1 && !wasMultiTouch) {
                            val change = event.changes[0]
                            if (!change.pressed) {
                                lastReleaseTimeMs = change.uptimeMillis
                                lastReleasePosition = change.position
                            }
                            if (scale > 1.05f) {
                                // Single finger while zoomed in: pan smoothly within image bounds after touchSlop
                                val panChange = event.calculatePan()
                                if (!pastTouchSlop) {
                                    pan += panChange
                                    val totalDist = (change.position - downPosition).getDistance()
                                    if (pan.getDistance() > touchSlop || totalDist > touchSlop) {
                                        pastTouchSlop = true
                                        zoomAnimationJob?.cancel()
                                    }
                                }
                                if (pastTouchSlop) {
                                    if (panChange != Offset.Zero) {
                                        offset = clampOffsetFor(scale, offset + panChange)
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            } else {
                                // At 1.0x scale: track slop only to distinguish tap vs pager swipe without consuming
                                val moveDist = (change.position - downPosition).getDistance()
                                if (moveDist > touchSlop) {
                                    pastTouchSlop = true
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    // Clean single-finger tap -> check for double-tap zoom in / zoom out toggle
                    val holdDurationMs = lastReleaseTimeMs - downTimeMs
                    if (!wasCanceled &&
                        !wasMultiTouch &&
                        !pastTouchSlop &&
                        holdDurationMs in 0L..viewConfiguration.longPressTimeoutMillis
                    ) {
                        val dtBetweenTaps = downTimeMs - lastTapTimeMs
                        val distBetweenTaps = (lastReleasePosition - lastTapPosition).getDistance()
                        if (lastTapTimeMs > 0L &&
                            dtBetweenTaps in 0L..viewConfiguration.doubleTapTimeoutMillis &&
                            distBetweenTaps <= doubleTapSlopPx
                        ) {
                            lastTapTimeMs = 0L
                            if (scale > 1.05f) {
                                // Second double-tap: smoothly return to original fit-to-screen scale and reset position
                                animateZoomTo(1f, Offset.Zero)
                            } else {
                                // First double-tap: smoothly zoom in anchored around tap point
                                val targetZoom = minOf(2.5f, resolveMaxZoom())
                                val vpW = viewportSize.width.toFloat()
                                val vpH = viewportSize.height.toFloat()
                                val focalDelta = if (vpW > 0f && vpH > 0f) {
                                    lastReleasePosition - Offset(vpW / 2f, vpH / 2f)
                                } else {
                                    Offset.Zero
                                }
                                val ratio = targetZoom / scale.coerceAtLeast(1f)
                                val anchoredOffset = (offset - focalDelta) * ratio + focalDelta
                                animateZoomTo(targetZoom, clampOffsetFor(targetZoom, anchoredOffset))
                            }
                        } else {
                            lastTapTimeMs = lastReleaseTimeMs
                            lastTapPosition = lastReleasePosition
                        }
                    } else if (wasMultiTouch || pastTouchSlop) {
                        lastTapTimeMs = 0L
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val activeSession = session
        if (activeSession != null && !activeSession.baseBitmap.isRecycled) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Observe tileGeneration in draw scope so decoded regions render immediately without changing zoom/pan
                @Suppress("UNUSED_VARIABLE")
                val version = tileGeneration
                val vpW = size.width
                val vpH = size.height
                if (vpW <= 0f || vpH <= 0f) return@Canvas

                val meta = activeSession.metadata
                val fitScale = HighResZoomMath.computeFitScale(vpW, vpH, meta.displayWidth, meta.displayHeight)
                val totalScale = fitScale * scale

                val drawW = meta.displayWidth * totalScale
                val drawH = meta.displayHeight * totalScale
                val imgLeft = vpW / 2f + offset.x - drawW / 2f
                val imgTop = vpH / 2f + offset.y - drawH / 2f

                val fallbackBmp = activeSession.highResFallbackBitmap
                val baseToDraw = if (fallbackBmp != null && !fallbackBmp.isRecycled) fallbackBmp else activeSession.baseBitmap
                if (!baseToDraw.isRecycled) {
                    val nativeCanvas = drawContext.canvas.nativeCanvas
                    val baseDst = android.graphics.RectF(imgLeft, imgTop, imgLeft + drawW, imgTop + drawH)
                    nativeCanvas.drawBitmap(baseToDraw, null, baseDst, filterPaint)

                    val targetSampleSize = HighResZoomMath.computeTargetSampleSize(
                        displayWidth = meta.displayWidth,
                        displayHeight = meta.displayHeight,
                        fitScale = fitScale,
                        zoomScale = scale
                    )
                    if (activeSession.supportsRegionDecoding && targetSampleSize < activeSession.baseSampleSize) {
                        val tiles = activeSession.getCachedTilesSnapshot(targetSampleSize)
                        val tileDst = android.graphics.RectF()
                        for (tile in tiles) {
                            if (tile.bitmap.isRecycled) continue
                            val tLeft = imgLeft + tile.displayRect.left * totalScale
                            val tTop = imgTop + tile.displayRect.top * totalScale
                            val tRight = imgLeft + tile.displayRect.right * totalScale
                            val tBottom = imgTop + tile.displayRect.bottom * totalScale
                            if (tRight > 0f && tLeft < vpW && tBottom > 0f && tTop < vpH) {
                                tileDst.set(tLeft, tTop, tRight, tBottom)
                                nativeCanvas.drawBitmap(tile.bitmap, null, tileDst, filterPaint)
                            }
                        }
                    }
                }
            }
        } else if (useFallbackAsyncImage) {
            val request = remember(photoUri, mediaRevision) {
                ImageRequest.Builder(context)
                    .data(photoUri)
                    .size(CoilSize.ORIGINAL)
                    .memoryCacheKey("preview_${photoUri}_$mediaRevision")
                    .diskCacheKey("preview_${photoUri}_$mediaRevision")
                    .crossfade(true)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = "Full photo preview",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit
            )
        } else if (isLoading) {
            CircularProgressIndicator(
                color = StuDOFocusYellow,
                strokeWidth = 2.dp,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

private enum class SkipDirection { REWIND, FORWARD }

private fun formatVideoTime(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val mins = totalSec / 60
    val secs = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}

// Full video playback component supporting play, pause, seekbar scrubbing, and YouTube-style multi-tap skip gestures
@Composable
private fun VideoPlayerView(
    videoUri: Uri,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var videoViewInstance by remember { mutableStateOf<VideoView?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var isCompleted by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }

    var currentPositionMs by remember { mutableIntStateOf(0) }
    var totalDurationMs by remember { mutableIntStateOf(0) }
    var isDraggingSlider by remember { mutableStateOf(false) }

    // Poster thumbnail extracted from the actual saved video URI (guarantees first frame at 0L)
    var posterBitmap by remember(videoUri) {
        mutableStateOf(VideoThumbnailUtil.getCached(videoUri))
    }

    LaunchedEffect(videoUri) {
        if (posterBitmap == null) {
            val loaded = VideoThumbnailUtil.loadThumbnail(context, videoUri)
            posterBitmap = loaded ?: VideoThumbnailUtil.createFallbackPlaceholderBitmap(512, 512)
        }
    }

    // Multi-tap skip accumulator
    var activeSkipDirection by remember { mutableStateOf<SkipDirection?>(null) }
    var skipAccumulatedSec by remember { mutableIntStateOf(0) }
    var lastSkipTimeMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(videoUri) {
        onDispose {
            videoViewInstance?.stopPlayback()
        }
    }

    // Polling job for seekbar current position
    LaunchedEffect(isPlaying, isDraggingSlider) {
        while (isPlaying && !isDraggingSlider) {
            val vv = videoViewInstance
            if (vv != null) {
                currentPositionMs = vv.currentPosition
                val dur = vv.duration
                if (dur > 0) totalDurationMs = dur
            }
            delay(200)
        }
    }

    // Reset skip ripple indicator after 750ms of inactivity
    LaunchedEffect(lastSkipTimeMs) {
        if (lastSkipTimeMs > 0L) {
            delay(750)
            activeSkipDirection = null
            skipAccumulatedSec = 0
        }
    }

    // Auto-dim controls after 4 seconds of playback
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(4000)
            showControls = false
        }
    }

    fun togglePlayback() {
        val vv = videoViewInstance ?: return
        if (vv.isPlaying) {
            vv.pause()
            isPlaying = false
            showControls = true
        } else {
            if (isCompleted) {
                vv.seekTo(0)
                currentPositionMs = 0
                isCompleted = false
            }
            vv.visibility = android.view.View.VISIBLE
            vv.start()
            isPlaying = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(totalDurationMs) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        val w = size.width
                        val now = System.currentTimeMillis()
                        val direction = if (offset.x < w * 0.42f) {
                            SkipDirection.REWIND
                        } else if (offset.x > w * 0.58f) {
                            SkipDirection.FORWARD
                        } else null

                        if (direction != null) {
                            val vv = videoViewInstance ?: return@detectTapGestures
                            val isChained = (activeSkipDirection == direction && (now - lastSkipTimeMs) < 650)
                            val newSeconds = if (isChained) skipAccumulatedSec + 10 else 10

                            activeSkipDirection = direction
                            skipAccumulatedSec = newSeconds
                            lastSkipTimeMs = now

                            val deltaMs = if (direction == SkipDirection.FORWARD) 10_000 else -10_000
                            val maxDur = if (totalDurationMs > 0) totalDurationMs else vv.duration
                            val newPos = (vv.currentPosition + deltaMs).coerceIn(0, maxDur.coerceAtLeast(0))
                            vv.seekTo(newPos)
                            currentPositionMs = newPos
                            if (isCompleted && direction == SkipDirection.REWIND) {
                                isCompleted = false
                            }
                        }
                    },
                    onTap = {
                        showControls = !showControls
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VideoView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    visibility = android.view.View.INVISIBLE
                    setVideoURI(videoUri)
                    setOnPreparedListener { mp ->
                        mp.isLooping = false
                        mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                        totalDurationMs = duration.coerceAtLeast(0)
                    }
                    setOnCompletionListener {
                        isPlaying = false
                        isCompleted = true
                        showControls = true
                        visibility = android.view.View.INVISIBLE
                    }
                    videoViewInstance = this
                }
            },
            update = { vv ->
                videoViewInstance = vv
                if (isPlaying || (!isCompleted && currentPositionMs > 0)) {
                    vv.visibility = android.view.View.VISIBLE
                } else {
                    vv.visibility = android.view.View.INVISIBLE
                }
            }
        )

        // Poster / First-frame thumbnail when video is not actively running (prevents SurfaceView window-hole punch)
        if (!isPlaying && (currentPositionMs == 0 || isCompleted)) {
            if (posterBitmap != null) {
                Image(
                    bitmap = posterBitmap!!.asImageBitmap(),
                    contentDescription = "Video preview poster",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Video placeholder",
                        tint = Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.size(64.dp)
                    )
                }
            }
        }

        // Center Play / Pause / Replay overlay button
        AnimatedVisibility(
            visible = showControls || !isPlaying,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .border(1.5.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                    .clickable { togglePlayback() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isCompleted) Icons.Default.Replay else if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isCompleted) "Replay Video" else if (isPlaying) "Pause Video" else "Play Video",
                    tint = StuDOWhite,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // Left Double-Tap Skip Ripple Feedback Indicator (Rewind -10s, -20s, ...)
        if (activeSkipDirection == SkipDirection.REWIND) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 28.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .border(1.dp, StuDOFocusYellow.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FastRewind,
                        contentDescription = "Rewind",
                        tint = StuDOFocusYellow,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "-${skipAccumulatedSec}s",
                        color = StuDOWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Right Double-Tap Skip Ripple Feedback Indicator (Fast-Forward +10s, +20s, ...)
        if (activeSkipDirection == SkipDirection.FORWARD) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 28.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .border(1.dp, StuDOFocusYellow.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "+${skipAccumulatedSec}s",
                        color = StuDOWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.Default.FastForward,
                        contentDescription = "Fast Forward",
                        tint = StuDOFocusYellow,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Interactive Bottom Seekbar Pill
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp, start = 16.dp, end = 16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.12f),
                                Color(0xFF101116).copy(alpha = 0.85f)
                            )
                        )
                    )
                    .border(
                        0.8.dp,
                        Color.White.copy(alpha = 0.2f),
                        RoundedCornerShape(18.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Quick Play/Pause mini button
                    IconButton(
                        onClick = { togglePlayback() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isCompleted) Icons.Default.Replay else if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = StuDOFocusYellow,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Current Time
                    Text(
                        text = formatVideoTime(currentPositionMs),
                        color = StuDOWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )

                    // Seek Slider
                    val maxSlider = totalDurationMs.toFloat().coerceAtLeast(1f)
                    Slider(
                        value = currentPositionMs.toFloat().coerceIn(0f, maxSlider),
                        onValueChange = { newPos ->
                            isDraggingSlider = true
                            currentPositionMs = newPos.toInt()
                        },
                        onValueChangeFinished = {
                            videoViewInstance?.seekTo(currentPositionMs)
                            isDraggingSlider = false
                        },
                        valueRange = 0f..maxSlider,
                        colors = SliderDefaults.colors(
                            thumbColor = StuDOFocusYellow,
                            activeTrackColor = StuDOFocusYellow,
                            inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    // Total Duration
                    Text(
                        text = formatVideoTime(totalDurationMs),
                        color = StuDOSilverMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
