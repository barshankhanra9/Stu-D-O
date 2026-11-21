package com.example.ui.components

import android.content.Context
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QueryBuilder
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CaptureAspectRatio
import com.example.model.CapturePriority
import com.example.model.CaptureTimer
import com.example.model.DynamicResolutionOption
import com.example.model.GridOption
import com.example.model.OemEnhancementMode
import com.example.model.PhotoResolution
import com.example.model.PrivateOriginalStorageStats
import com.example.model.RawCaptureMode
import com.example.model.VideoFpsOption
import com.example.model.VideoQualityOption
import com.example.model.VolumeKeyAction
import com.example.model.WatermarkCacheLimit
import com.example.model.WatermarkConfig
import com.example.data.PrivateOriginalStorageManager
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDOBlack
import com.example.ui.theme.StuDOCarbon
import com.example.ui.theme.StuDOFocusYellow
import com.example.ui.theme.StuDORed
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite
import com.example.util.CameraMetadataUtil
import com.example.util.WatermarkPainter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

// Pre-allocated visual constants to eliminate per-recomposition object and shader churn
private val StudioButtonShape = RoundedCornerShape(12.dp)
private val PreviewInnerShape = RoundedCornerShape(8.dp)

private val CardBackgroundColor = StuDOCarbon.copy(alpha = 0.72f)
private val CardBorderColor = Color.White.copy(alpha = 0.12f)
private val CardBorderTopColor = Color.White.copy(alpha = 0.16f)
private val CardBorderSideColor = Color.White.copy(alpha = 0.11f)
private val CardBorderBottomColor = Color.White.copy(alpha = 0.06f)
private val DividerColor = Color.White.copy(alpha = 0.06f)
private val SwitchUncheckedTrackColor = Color.White.copy(alpha = 0.12f)
private val OptionSelectedBg = Color.White.copy(alpha = 0.18f)
private val OptionUnselectedBg = Color.White.copy(alpha = 0.05f)
private val OptionBorderUnselected = Color.White.copy(alpha = 0.12f)
private val ChipSelectedBg = Color.White.copy(alpha = 0.22f)
private val ChipUnselectedBg = Color.White.copy(alpha = 0.06f)
private val ChipBorderUnselected = Color.White.copy(alpha = 0.15f)
private val BadgeBackgroundColor = StuDOFocusYellow.copy(alpha = 0.15f)
private val ActiveStreamBg = Color.White.copy(alpha = 0.04f)
private val PreviewContainerBg = Color.Black.copy(alpha = 0.5f)
private val PreviewPlaceholderBg = Color(0xFF14151C)

// Pre-allocated immutable TextStyles to avoid per-item TextStyle merging/allocations during fast fling
private val SectionHeaderStyle = TextStyle(
    color = StuDOAmber,
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 1.2.sp
)
private val CardTitleStyle = TextStyle(
    color = StuDOWhite,
    fontSize = 14.sp,
    fontWeight = FontWeight.SemiBold
)
private val CardSubtitleStyle = TextStyle(
    color = StuDOSilverMuted,
    fontSize = 11.sp
)
private val SwitchSubtitleStyle = TextStyle(
    color = StuDOSilverMuted,
    fontSize = 11.sp,
    lineHeight = 15.sp
)
private val OptionTitleSelectedStyle = TextStyle(
    color = StuDOFocusYellow,
    fontSize = 13.sp,
    fontWeight = FontWeight.Bold
)
private val OptionTitleNormalStyle = TextStyle(
    color = StuDOWhite,
    fontSize = 13.sp,
    fontWeight = FontWeight.Medium
)
private val OptionSubtitleStyle = TextStyle(
    color = StuDOSilverMuted,
    fontSize = 11.sp
)
private val ActiveBadgeStyle = TextStyle(
    color = StuDOFocusYellow,
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 1.sp
)
private val FullSensorBadgeStyle = TextStyle(
    color = StuDOFocusYellow,
    fontSize = 9.sp,
    fontWeight = FontWeight.Bold
)
private val DefaultBadgeStyle = TextStyle(
    color = StuDOAmber,
    fontSize = 9.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.8.sp
)
private val StreamStatusStyle = TextStyle(
    color = StuDOSilver,
    fontSize = 11.sp
)
private val OemActiveLabelStyle = TextStyle(
    color = StuDOAmber,
    fontSize = 11.sp,
    fontWeight = FontWeight.Medium
)
private val OemChipTitleSelectedStyle = TextStyle(
    color = StuDOFocusYellow,
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold
)
private val OemChipTitleNormalStyle = TextStyle(
    color = StuDOWhite,
    fontSize = 12.sp,
    fontWeight = FontWeight.Medium
)
private val OemChipDescStyle = TextStyle(
    color = StuDOSilverMuted,
    fontSize = 10.sp,
    lineHeight = 13.sp
)
private val PreviewHeaderTitleStyle = TextStyle(
    color = StuDOFocusYellow,
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 1.sp
)
private val PreviewStyleNameStyle = TextStyle(
    color = StuDOSilver,
    fontSize = 10.sp,
    fontWeight = FontWeight.Medium
)
private val PreviewLoadingStyle = TextStyle(
    color = StuDOSilverMuted,
    fontSize = 11.sp
)
private val ButtonLabelStyle = TextStyle(
    color = Color.Black,
    fontSize = 13.sp,
    fontWeight = FontWeight.Bold
)
private val AboutTitleStyle = TextStyle(
    color = StuDOWhite,
    fontSize = 14.sp,
    fontWeight = FontWeight.Bold
)
private val AboutVersionStyle = TextStyle(
    color = StuDOFocusYellow,
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold
)

// Cached enum lists to avoid Enum.entries allocations in composition
internal val ALL_PHOTO_RESOLUTIONS: List<PhotoResolution> = PhotoResolution.entries.toList()
internal val NON_UHR_PHOTO_RESOLUTIONS: List<PhotoResolution> =
    PhotoResolution.entries.filter { it != PhotoResolution.ULTRA_RES }
internal val CAPTURE_PRIORITIES: List<CapturePriority> = CapturePriority.entries.toList()
private val RAW_CAPTURE_MODES: List<RawCaptureMode> = RawCaptureMode.entries.toList()
private val OEM_ENHANCEMENT_MODES: List<OemEnhancementMode> = OemEnhancementMode.entries.toList()
private val DEFAULT_VIDEO_QUALITIES: List<VideoQualityOption> =
    listOf(VideoQualityOption.QUALITY_1080P, VideoQualityOption.QUALITY_720P)
private val DEFAULT_VIDEO_FPS: List<VideoFpsOption> =
    listOf(VideoFpsOption.FPS_AUTO, VideoFpsOption.FPS_30)
private val VOLUME_KEY_ACTIONS: List<VolumeKeyAction> = VolumeKeyAction.entries.toList()
private val GRID_OPTIONS: List<GridOption> = GridOption.entries.toList()
private val CAPTURE_TIMERS: List<CaptureTimer> = CaptureTimer.entries.toList()
private val ASPECT_RATIO_OPTIONS: List<CaptureAspectRatio> = listOf(
    CaptureAspectRatio.RATIO_4_3,
    CaptureAspectRatio.RATIO_16_9,
    CaptureAspectRatio.RATIO_1_1,
    CaptureAspectRatio.RATIO_FULL
)

private const val PREVIEW_RENDER_WIDTH = 360
private const val PREVIEW_RENDER_HEIGHT = 225

enum class CardGroupPosition {
    SINGLE,
    TOP,
    MIDDLE,
    BOTTOM
}

internal val LocalSettingsSwitchColors = staticCompositionLocalOf<SwitchColors?> { null }

/**
 * Thread-safe LRU cache with in-flight deduplication for pre-converted Compose ImageBitmaps
 * of live watermark previews.
 * Storing ImageBitmap directly avoids calling asImageBitmap() on every scroll re-entry,
 * deduplication prevents concurrent duplicate renders between warmup and visible cards,
 * and omitting premature Bitmap.recycle() prevents Canvas recycled-bitmap crashes while
 * RenderThread/Compose draws frames.
 */
object WatermarkPreviewCache {
    private const val MAX_ENTRIES = 6
    private val cache = LruCache<WatermarkConfig, ImageBitmap>(MAX_ENTRIES)
    private val inFlight = mutableMapOf<WatermarkConfig, CompletableDeferred<ImageBitmap?>>()

    @Synchronized
    fun get(config: WatermarkConfig): ImageBitmap? = cache.get(config)

    @Synchronized
    fun put(config: WatermarkConfig, imageBitmap: ImageBitmap) {
        cache.put(config, imageBitmap)
    }

    @Synchronized
    fun clear() {
        cache.evictAll()
        inFlight.clear()
    }

    suspend fun getOrRender(context: Context, config: WatermarkConfig): ImageBitmap? {
        get(config)?.let { return it }

        var isOwner = false
        val deferred = synchronized(inFlight) {
            get(config)?.let { return it }
            inFlight[config] ?: CompletableDeferred<ImageBitmap?>().also {
                inFlight[config] = it
                isOwner = true
            }
        }

        if (!isOwner) {
            return deferred.await()
        }

        val appContext = context.applicationContext
        return try {
            val renderedBitmap = withContext(Dispatchers.Default) {
                ensureActive()
                get(config)?.let { return@withContext it }

                val base = WatermarkPainter.getReadOnlySamplePhoto(PREVIEW_RENDER_WIDTH, PREVIEW_RENDER_HEIGHT)
                val meta = CameraMetadataUtil.samplePreviewMetadata(PREVIEW_RENDER_WIDTH, PREVIEW_RENDER_HEIGHT)
                val rendered = WatermarkPainter.applyWatermark(
                    source = base,
                    config = config,
                    metadata = meta,
                    context = appContext
                )
                ensureActive()
                rendered.prepareToDraw()
                val imageBitmap = rendered.asImageBitmap()
                put(config, imageBitmap)
                imageBitmap
            }
            deferred.complete(renderedBitmap)
            renderedBitmap
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            synchronized(inFlight) {
                if (inFlight[config] === deferred) {
                    inFlight.remove(config)
                }
            }
        }
    }
}

// Draws rounded background fill + optional stroke in a single drawWithCache pass without outline clip layers
private fun Modifier.settingsRoundedSurface(
    cornerDp: Dp,
    fillColor: Color,
    borderColor: Color? = null,
    borderWidthDp: Dp = 1.dp
): Modifier = this.drawWithCache {
    val radiusPx = cornerDp.toPx()
    val cornerRadius = CornerRadius(radiusPx, radiusPx)
    val strokePx = if (borderColor != null) borderWidthDp.toPx() else 0f
    val halfStroke = strokePx * 0.5f
    val strokeStyle = if (borderColor != null) Stroke(width = strokePx) else null
    val strokeTopLeft = Offset(halfStroke, halfStroke)
    val strokeSize = Size(
        (size.width - strokePx).coerceAtLeast(0f),
        (size.height - strokePx).coerceAtLeast(0f)
    )
    onDrawBehind {
        drawRoundRect(
            color = fillColor,
            cornerRadius = cornerRadius
        )
        if (borderColor != null && strokeStyle != null && strokeSize.width > 0f && strokeSize.height > 0f) {
            drawRoundRect(
                color = borderColor,
                topLeft = strokeTopLeft,
                size = strokeSize,
                cornerRadius = cornerRadius,
                style = strokeStyle
            )
        }
    }
}

@Composable
fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = SectionHeaderStyle,
        modifier = modifier.padding(top = 8.dp, bottom = 12.dp)
    )
}

@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .settingsRoundedSurface(
                cornerDp = 16.dp,
                fillColor = CardBackgroundColor,
                borderColor = CardBorderColor,
                borderWidthDp = 1.dp
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
private fun SettingsGroupedSurface(
    groupPosition: CardGroupPosition,
    topContentPaddingDp: Int,
    bottomContentPaddingDp: Int,
    drawBottomDivider: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val outerModifier = if (groupPosition == CardGroupPosition.SINGLE || groupPosition == CardGroupPosition.BOTTOM) {
        modifier.fillMaxWidth().padding(bottom = 10.dp)
    } else {
        modifier.fillMaxWidth()
    }

    val drawModifier = when (groupPosition) {
        CardGroupPosition.SINGLE -> Modifier.settingsRoundedSurface(
            cornerDp = 16.dp,
            fillColor = CardBackgroundColor,
            borderColor = CardBorderColor,
            borderWidthDp = 1.dp
        )
        CardGroupPosition.MIDDLE -> Modifier.drawWithCache {
            val strokePx = 1.dp.toPx()
            val halfStroke = strokePx * 0.5f
            val dividerInsetPx = 16.dp.toPx()
            val dividerStrokePx = 0.5.dp.toPx()
            onDrawBehind {
                drawRect(color = CardBackgroundColor)
                drawLine(
                    color = CardBorderSideColor,
                    start = Offset(halfStroke, 0f),
                    end = Offset(halfStroke, size.height),
                    strokeWidth = strokePx
                )
                drawLine(
                    color = CardBorderSideColor,
                    start = Offset(size.width - halfStroke, 0f),
                    end = Offset(size.width - halfStroke, size.height),
                    strokeWidth = strokePx
                )
                if (drawBottomDivider && size.width > dividerInsetPx * 2f) {
                    val dividerY = size.height - dividerStrokePx * 0.5f
                    drawLine(
                        color = DividerColor,
                        start = Offset(dividerInsetPx, dividerY),
                        end = Offset(size.width - dividerInsetPx, dividerY),
                        strokeWidth = dividerStrokePx
                    )
                }
            }
        }
        CardGroupPosition.TOP -> Modifier.drawWithCache {
            val strokePx = 1.dp.toPx()
            val halfStroke = strokePx * 0.5f
            val radiusPx = 16.dp.toPx().coerceAtMost(size.width * 0.5f).coerceAtMost(size.height)
            val corner = CornerRadius(radiusPx, radiusPx)
            val dividerInsetPx = 16.dp.toPx()
            val dividerStrokePx = 0.5.dp.toPx()
            val borderStroke = Stroke(width = strokePx)
            val fillPath = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = 0f,
                        top = 0f,
                        right = size.width,
                        bottom = size.height,
                        topLeftCornerRadius = corner,
                        topRightCornerRadius = corner
                    )
                )
            }
            val borderPath = if (size.width > strokePx && size.height > radiusPx && radiusPx > halfStroke) {
                Path().apply {
                    moveTo(halfStroke, size.height)
                    lineTo(halfStroke, radiusPx)
                    arcTo(
                        rect = Rect(halfStroke, halfStroke, radiusPx * 2f - halfStroke, radiusPx * 2f - halfStroke),
                        startAngleDegrees = 180f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false
                    )
                    lineTo(size.width - radiusPx, halfStroke)
                    arcTo(
                        rect = Rect(
                            size.width - radiusPx * 2f + halfStroke,
                            halfStroke,
                            size.width - halfStroke,
                            radiusPx * 2f - halfStroke
                        ),
                        startAngleDegrees = 270f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false
                    )
                    lineTo(size.width - halfStroke, size.height)
                }
            } else {
                null
            }
            onDrawBehind {
                drawPath(path = fillPath, color = CardBackgroundColor)
                if (borderPath != null) {
                    drawPath(path = borderPath, color = CardBorderTopColor, style = borderStroke)
                }
                if (drawBottomDivider && size.width > dividerInsetPx * 2f) {
                    val dividerY = size.height - dividerStrokePx * 0.5f
                    drawLine(
                        color = DividerColor,
                        start = Offset(dividerInsetPx, dividerY),
                        end = Offset(size.width - dividerInsetPx, dividerY),
                        strokeWidth = dividerStrokePx
                    )
                }
            }
        }
        CardGroupPosition.BOTTOM -> Modifier.drawWithCache {
            val strokePx = 1.dp.toPx()
            val halfStroke = strokePx * 0.5f
            val radiusPx = 16.dp.toPx().coerceAtMost(size.width * 0.5f).coerceAtMost(size.height)
            val corner = CornerRadius(radiusPx, radiusPx)
            val dividerInsetPx = 16.dp.toPx()
            val dividerStrokePx = 0.5.dp.toPx()
            val borderStroke = Stroke(width = strokePx)
            val fillPath = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = 0f,
                        top = 0f,
                        right = size.width,
                        bottom = size.height,
                        bottomLeftCornerRadius = corner,
                        bottomRightCornerRadius = corner
                    )
                )
            }
            val borderPath = if (size.width > strokePx && size.height > radiusPx && radiusPx > halfStroke) {
                Path().apply {
                    moveTo(halfStroke, 0f)
                    lineTo(halfStroke, size.height - radiusPx)
                    arcTo(
                        rect = Rect(
                            halfStroke,
                            size.height - radiusPx * 2f + halfStroke,
                            radiusPx * 2f - halfStroke,
                            size.height - halfStroke
                        ),
                        startAngleDegrees = 180f,
                        sweepAngleDegrees = -90f,
                        forceMoveTo = false
                    )
                    lineTo(size.width - radiusPx, size.height - halfStroke)
                    arcTo(
                        rect = Rect(
                            size.width - radiusPx * 2f + halfStroke,
                            size.height - radiusPx * 2f + halfStroke,
                            size.width - halfStroke,
                            size.height - halfStroke
                        ),
                        startAngleDegrees = 90f,
                        sweepAngleDegrees = -90f,
                        forceMoveTo = false
                    )
                    lineTo(size.width - halfStroke, 0f)
                }
            } else {
                null
            }
            onDrawBehind {
                drawPath(path = fillPath, color = CardBackgroundColor)
                if (borderPath != null) {
                    drawPath(path = borderPath, color = CardBorderBottomColor, style = borderStroke)
                }
                if (drawBottomDivider && size.width > dividerInsetPx * 2f) {
                    val dividerY = size.height - dividerStrokePx * 0.5f
                    drawLine(
                        color = DividerColor,
                        start = Offset(dividerInsetPx, dividerY),
                        end = Offset(size.width - dividerInsetPx, dividerY),
                        strokeWidth = dividerStrokePx
                    )
                }
            }
        }
    }

    Box(
        modifier = outerModifier
            .then(drawModifier)
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = topContentPaddingDp.dp,
                bottom = bottomContentPaddingDp.dp
            )
    ) {
        content()
    }
}

@Composable
private fun rememberSettingsSwitchColors(): SwitchColors {
    LocalSettingsSwitchColors.current?.let { return it }
    val thumbChecked = StuDOBlack
    val trackChecked = StuDOFocusYellow
    val thumbUnchecked = StuDOSilver
    val trackUnchecked = SwitchUncheckedTrackColor
    return SwitchDefaults.colors(
        checkedThumbColor = thumbChecked,
        checkedTrackColor = trackChecked,
        uncheckedThumbColor = thumbUnchecked,
        uncheckedTrackColor = trackUnchecked
    )
}

@Composable
fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isChecked) StuDOFocusYellow else StuDOSilver,
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(
                    text = title,
                    style = CardTitleStyle
                )
                Text(
                    text = subtitle,
                    style = SwitchSubtitleStyle
                )
            }
        }

        Switch(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
            colors = rememberSettingsSwitchColors(),
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
fun GroupedSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    groupPosition: CardGroupPosition,
    modifier: Modifier = Modifier
) {
    val topPad = if (groupPosition == CardGroupPosition.TOP || groupPosition == CardGroupPosition.SINGLE) 12 else 4
    val bottomPad = if (groupPosition == CardGroupPosition.BOTTOM || groupPosition == CardGroupPosition.SINGLE) 12 else 4
    val showDivider = groupPosition == CardGroupPosition.TOP || groupPosition == CardGroupPosition.MIDDLE

    SettingsGroupedSurface(
        groupPosition = groupPosition,
        topContentPaddingDp = topPad,
        bottomContentPaddingDp = bottomPad,
        drawBottomDivider = showDivider,
        modifier = modifier
    ) {
        SettingsSwitchRow(
            icon = icon,
            title = title,
            subtitle = subtitle,
            isChecked = isChecked,
            onCheckedChange = onCheckedChange,
            testTag = testTag
        )
    }
}

@Composable
fun GroupedInfoItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badgeText: String,
    testTag: String,
    groupPosition: CardGroupPosition,
    modifier: Modifier = Modifier
) {
    val topPad = if (groupPosition == CardGroupPosition.TOP || groupPosition == CardGroupPosition.SINGLE) 12 else 4
    val bottomPad = if (groupPosition == CardGroupPosition.BOTTOM || groupPosition == CardGroupPosition.SINGLE) 12 else 4
    val showDivider = groupPosition == CardGroupPosition.TOP || groupPosition == CardGroupPosition.MIDDLE

    SettingsGroupedSurface(
        groupPosition = groupPosition,
        topContentPaddingDp = topPad,
        bottomContentPaddingDp = bottomPad,
        drawBottomDivider = showDivider,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .defaultMinSize(minHeight = 48.dp)
                .testTag(testTag),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = StuDOSilver,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = title,
                        style = CardTitleStyle
                    )
                    Text(
                        text = subtitle,
                        style = SwitchSubtitleStyle
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .settingsRoundedSurface(
                        cornerDp = 10.dp,
                        fillColor = Color.White.copy(alpha = 0.08f),
                        borderColor = Color.White.copy(alpha = 0.18f),
                        borderWidthDp = 0.8.dp
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeText,
                    color = StuDOSilver,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

internal fun buildFallbackResolutionTitle(
    res: PhotoResolution,
    uhrMegapixels: Double,
    uhrSize: android.util.Size?,
    standardSensorText: String
): String {
    return if (res == PhotoResolution.ULTRA_RES && uhrMegapixels > 0) {
        val mpStr = String.format(java.util.Locale.US, "%.0f", uhrMegapixels)
        val dimStr = uhrSize?.let { " (${it.width} × ${it.height})" } ?: ""
        "$mpStr MP Ultra Resolution$dimStr"
    } else if (res == PhotoResolution.MAX && standardSensorText.isNotBlank()) {
        "Maximum Quality ($standardSensorText)"
    } else {
        res.displayName
    }
}

@Composable
fun PhotoResolutionHeaderItem(
    modifier: Modifier = Modifier
) {
    SettingsGroupedSurface(
        groupPosition = CardGroupPosition.TOP,
        topContentPaddingDp = 12,
        bottomContentPaddingDp = 8,
        drawBottomDivider = false,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.HighQuality,
                contentDescription = "Photo Resolution",
                tint = StuDOSilver,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = "Photo Resolution",
                    style = CardTitleStyle
                )
                Text(
                    text = "Sensor output resolution and pixel binning",
                    style = CardSubtitleStyle
                )
            }
        }
    }
}

@Composable
fun PhotoResolutionDynamicRowItem(
    option: DynamicResolutionOption,
    isSelected: Boolean,
    isBottomItem: Boolean,
    onSelectResolution: (PhotoResolution) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsGroupedSurface(
        groupPosition = if (isBottomItem) CardGroupPosition.BOTTOM else CardGroupPosition.MIDDLE,
        topContentPaddingDp = 3,
        bottomContentPaddingDp = if (isBottomItem) 12 else 3,
        drawBottomDivider = false,
        modifier = modifier
    ) {
        PhotoResolutionDynamicRow(
            option = option,
            isSelected = isSelected,
            onSelectResolution = onSelectResolution
        )
    }
}

@Composable
fun PhotoResolutionFallbackRowItem(
    resolution: PhotoResolution,
    title: String,
    isSelected: Boolean,
    isBottomItem: Boolean,
    onSelectResolution: (PhotoResolution) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsGroupedSurface(
        groupPosition = if (isBottomItem) CardGroupPosition.BOTTOM else CardGroupPosition.MIDDLE,
        topContentPaddingDp = 3,
        bottomContentPaddingDp = if (isBottomItem) 12 else 3,
        drawBottomDivider = false,
        modifier = modifier
    ) {
        PhotoResolutionFallbackRow(
            resolution = resolution,
            title = title,
            isSelected = isSelected,
            onSelectResolution = onSelectResolution
        )
    }
}

@Composable
fun PhotoResolutionStreamFooterItem(
    actualConfiguredSize: android.util.Size,
    modifier: Modifier = Modifier
) {
    SettingsGroupedSurface(
        groupPosition = CardGroupPosition.BOTTOM,
        topContentPaddingDp = 4,
        bottomContentPaddingDp = 12,
        drawBottomDivider = false,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .settingsRoundedSurface(cornerDp = 8.dp, fillColor = ActiveStreamBg)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = StuDOFocusYellow,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "Active Session Stream: ${actualConfiguredSize.width} × ${actualConfiguredSize.height}",
                style = StreamStatusStyle
            )
        }
    }
}

@Composable
fun PhotoResolutionCard(
    availableOptions: List<DynamicResolutionOption>,
    selectedResolution: PhotoResolution,
    isUhrSupported: Boolean,
    uhrMegapixels: Double,
    uhrSize: android.util.Size?,
    standardSensorText: String,
    actualConfiguredSize: android.util.Size?,
    onSelectResolution: (PhotoResolution) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.HighQuality,
                contentDescription = "Photo Resolution",
                tint = StuDOSilver,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = "Photo Resolution",
                    style = CardTitleStyle
                )
                Text(
                    text = "Sensor output resolution and pixel binning",
                    style = CardSubtitleStyle
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (availableOptions.isNotEmpty()) {
                availableOptions.forEach { opt ->
                    key(opt.resolution) {
                        PhotoResolutionDynamicRow(
                            option = opt,
                            isSelected = opt.resolution == selectedResolution,
                            onSelectResolution = onSelectResolution
                        )
                    }
                }
            } else {
                val availableResolutions = if (isUhrSupported) {
                    ALL_PHOTO_RESOLUTIONS
                } else {
                    NON_UHR_PHOTO_RESOLUTIONS
                }

                availableResolutions.forEach { res ->
                    key(res) {
                        val title = remember(res, uhrMegapixels, uhrSize, standardSensorText) {
                            buildFallbackResolutionTitle(res, uhrMegapixels, uhrSize, standardSensorText)
                        }

                        PhotoResolutionFallbackRow(
                            resolution = res,
                            title = title,
                            isSelected = res == selectedResolution,
                            onSelectResolution = onSelectResolution
                        )
                    }
                }
            }

            actualConfiguredSize?.let { actualRes ->
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .settingsRoundedSurface(cornerDp = 8.dp, fillColor = ActiveStreamBg)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = StuDOFocusYellow,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Active Session Stream: ${actualRes.width} × ${actualRes.height}",
                        style = StreamStatusStyle
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoResolutionDynamicRow(
    option: DynamicResolutionOption,
    isSelected: Boolean,
    onSelectResolution: (PhotoResolution) -> Unit
) {
    val onClick = remember(option.resolution, onSelectResolution) {
        { onSelectResolution(option.resolution) }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .settingsRoundedSurface(
                cornerDp = 10.dp,
                fillColor = if (isSelected) OptionSelectedBg else OptionUnselectedBg,
                borderColor = if (isSelected) StuDOFocusYellow else OptionBorderUnselected,
                borderWidthDp = if (isSelected) 1.5.dp else 0.5.dp
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = option.title,
                        style = if (isSelected) OptionTitleSelectedStyle else OptionTitleNormalStyle
                    )
                    if (option.isStallStream) {
                        Text(
                            text = "FULL SENSOR",
                            style = FullSensorBadgeStyle,
                            modifier = Modifier
                                .settingsRoundedSurface(cornerDp = 4.dp, fillColor = BadgeBackgroundColor)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                Text(
                    text = "${option.dimensionsText} • ${option.description}",
                    style = OptionSubtitleStyle
                )
            }
            if (isSelected) {
                Text(
                    text = "ACTIVE",
                    style = ActiveBadgeStyle
                )
            }
        }
    }
}

@Composable
private fun PhotoResolutionFallbackRow(
    resolution: PhotoResolution,
    title: String,
    isSelected: Boolean,
    onSelectResolution: (PhotoResolution) -> Unit
) {
    val onClick = remember(resolution, onSelectResolution) {
        { onSelectResolution(resolution) }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .settingsRoundedSurface(
                cornerDp = 10.dp,
                fillColor = if (isSelected) OptionSelectedBg else OptionUnselectedBg,
                borderColor = if (isSelected) StuDOFocusYellow else OptionBorderUnselected,
                borderWidthDp = if (isSelected) 1.5.dp else 0.5.dp
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = title,
                    style = if (isSelected) OptionTitleSelectedStyle else OptionTitleNormalStyle
                )
                Text(
                    text = resolution.description,
                    style = OptionSubtitleStyle
                )
            }
            if (isSelected) {
                Text(
                    text = "ACTIVE",
                    style = ActiveBadgeStyle
                )
            }
        }
    }
}

@Composable
fun CapturePriorityHeaderItem(
    modifier: Modifier = Modifier
) {
    SettingsGroupedSurface(
        groupPosition = CardGroupPosition.TOP,
        topContentPaddingDp = 12,
        bottomContentPaddingDp = 8,
        drawBottomDivider = false,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "Capture Priority",
                tint = StuDOSilver,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = "Capture Priority",
                    style = CardTitleStyle
                )
                Text(
                    text = "Still-photo capture strategy & pipeline tuning",
                    style = CardSubtitleStyle
                )
            }
        }
    }
}

@Composable
fun CapturePriorityRowItem(
    priority: CapturePriority,
    isSelected: Boolean,
    isBottomItem: Boolean,
    onSelectPriority: (CapturePriority) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsGroupedSurface(
        groupPosition = if (isBottomItem) CardGroupPosition.BOTTOM else CardGroupPosition.MIDDLE,
        topContentPaddingDp = 3,
        bottomContentPaddingDp = if (isBottomItem) 12 else 3,
        drawBottomDivider = false,
        modifier = modifier
    ) {
        CapturePriorityOptionRow(
            priority = priority,
            isSelected = isSelected,
            onSelectPriority = onSelectPriority
        )
    }
}

@Composable
fun CapturePriorityCard(
    selectedPriority: CapturePriority,
    onSelectPriority: (CapturePriority) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "Capture Priority",
                tint = StuDOSilver,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = "Capture Priority",
                    style = CardTitleStyle
                )
                Text(
                    text = "Still-photo capture strategy & pipeline tuning",
                    style = CardSubtitleStyle
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CAPTURE_PRIORITIES.forEach { priority ->
                CapturePriorityOptionRow(
                    priority = priority,
                    isSelected = priority == selectedPriority,
                    onSelectPriority = onSelectPriority
                )
            }
        }
    }
}

@Composable
private fun CapturePriorityOptionRow(
    priority: CapturePriority,
    isSelected: Boolean,
    onSelectPriority: (CapturePriority) -> Unit
) {
    val onClick = remember(priority, onSelectPriority) { { onSelectPriority(priority) } }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .settingsRoundedSurface(
                cornerDp = 10.dp,
                fillColor = if (isSelected) OptionSelectedBg else OptionUnselectedBg,
                borderColor = if (isSelected) StuDOFocusYellow else OptionBorderUnselected,
                borderWidthDp = if (isSelected) 1.5.dp else 0.5.dp
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = priority.displayName,
                        style = if (isSelected) OptionTitleSelectedStyle else OptionTitleNormalStyle
                    )
                    if (priority == CapturePriority.QUALITY) {
                        Text(
                            text = "DEFAULT",
                            style = DefaultBadgeStyle
                        )
                    }
                }
                Text(
                    text = priority.description,
                    style = OptionSubtitleStyle
                )
            }
            if (isSelected) {
                Text(
                    text = "ACTIVE",
                    style = ActiveBadgeStyle,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
fun RawCaptureCard(
    rawCaptureMode: RawCaptureMode,
    onSelectRawMode: (RawCaptureMode) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "RAW Capture Mode",
                tint = StuDOSilver,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = "RAW_SENSOR Capture (DNG)",
                    style = CardTitleStyle
                )
                Text(
                    text = "Save authentic uncompressed sensor data",
                    style = CardSubtitleStyle
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RAW_CAPTURE_MODES.forEach { mode ->
                val label = when (mode) {
                    RawCaptureMode.OFF -> "JPEG Only"
                    RawCaptureMode.JPEG_AND_DNG -> "JPEG + RAW"
                    RawCaptureMode.DNG_ONLY -> "RAW Only"
                }
                SettingsChoiceChip(
                    item = mode,
                    label = label,
                    isSelected = mode == rawCaptureMode,
                    selectedColor = StuDOFocusYellow,
                    fontSize = 10.sp,
                    cornerDp = 8.dp,
                    selectedBackground = OptionSelectedBg,
                    unselectedBackground = OptionUnselectedBg,
                    unselectedBorderColor = OptionBorderUnselected,
                    onSelect = onSelectRawMode
                )
            }
        }
    }
}

@Composable
fun HdrSwitchCard(
    isHdrEnabled: Boolean,
    onToggleHdr: () -> Unit,
    modifier: Modifier = Modifier
) {
    val onCheckedChange = remember(onToggleHdr) { { _: Boolean -> onToggleHdr() } }
    SettingsCard(modifier = modifier) {
        SettingsSwitchRow(
            icon = Icons.Default.HdrOn,
            title = "HDR (High Dynamic Range)",
            subtitle = "Multi-exposure synthesis for rich highlights and shadow details",
            isChecked = isHdrEnabled,
            onCheckedChange = onCheckedChange,
            testTag = "settings_toggle_hdr"
        )
    }
}

private val WATERMARK_CACHE_LIMITS: List<WatermarkCacheLimit> = WatermarkCacheLimit.entries.toList()

@Composable
fun WatermarkCard(
    watermarkConfig: WatermarkConfig,
    isPostCaptureWatermarkEditingEnabled: Boolean = false,
    isWatermarkCacheLimitConfigured: Boolean = false,
    watermarkCacheLimit: WatermarkCacheLimit = WatermarkCacheLimit.GB_1,
    storageStats: PrivateOriginalStorageStats = PrivateOriginalStorageStats(),
    onToggleWatermark: () -> Unit,
    onOpenWatermarkStudio: () -> Unit,
    onSetPostCaptureWatermarkEditing: (Boolean, WatermarkCacheLimit?) -> Unit = { _, _ -> },
    onSetWatermarkCacheLimit: (WatermarkCacheLimit) -> Unit = {},
    onClearAllPrivateOriginals: () -> Unit = {},
    isScrollInProgress: () -> Boolean = { false },
    modifier: Modifier = Modifier
) {
    var showFirstTimeSetupDialog by remember { mutableStateOf(false) }
    var showStorageManagementDialog by remember { mutableStateOf(false) }

    val onCheckedChange = remember(onToggleWatermark) { { _: Boolean -> onToggleWatermark() } }
    val onPostCaptureCheckedChange = remember(
        isPostCaptureWatermarkEditingEnabled,
        isWatermarkCacheLimitConfigured,
        onSetPostCaptureWatermarkEditing
    ) {
        { wantEnable: Boolean ->
            if (wantEnable && !isWatermarkCacheLimitConfigured) {
                showFirstTimeSetupDialog = true
            } else {
                onSetPostCaptureWatermarkEditing(wantEnable, null)
            }
        }
    }

    SettingsCard(modifier = modifier) {
        SettingsSwitchRow(
            icon = Icons.Default.PhotoCamera,
            title = "Watermark",
            subtitle = "Stamp trending studio badges and custom metadata",
            isChecked = watermarkConfig.isEnabled,
            onCheckedChange = onCheckedChange,
            testTag = "settings_toggle_watermark"
        )

        AnimatedVisibility(
            visible = watermarkConfig.isEnabled,
            enter = expandVertically(tween(180)) + fadeIn(tween(150)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(120))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(8.dp))

                LiveWatermarkPreviewCard(
                    watermarkConfig = watermarkConfig,
                    isScrollInProgress = isScrollInProgress
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onOpenWatermarkStudio,
                    colors = ButtonDefaults.buttonColors(containerColor = StuDOFocusYellow),
                    shape = StudioButtonShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("open_watermark_studio_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Customize in Watermark Studio",
                        style = ButtonLabelStyle
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = DividerColor, thickness = 0.8.dp)
                Spacer(modifier = Modifier.height(6.dp))

                SettingsSwitchRow(
                    icon = Icons.Default.Edit,
                    title = "Post-Capture Watermark Editing",
                    subtitle = if (isPostCaptureWatermarkEditingEnabled) {
                        "ON: Clean unwatermarked originals are saved privately so you can edit or remove watermarks later."
                    } else {
                        "OFF: Only the final watermarked JPEG is saved. Turn on to keep a private clean original for later edits."
                    },
                    isChecked = isPostCaptureWatermarkEditingEnabled,
                    onCheckedChange = onPostCaptureCheckedChange,
                    testTag = "settings_toggle_post_capture_watermark"
                )

                Spacer(modifier = Modifier.height(6.dp))

                val usedText = PrivateOriginalStorageManager.formatStorageSize(storageStats.usedBytes)
                val maxText = PrivateOriginalStorageManager.formatStorageSize(watermarkCacheLimit.limitBytes)
                val countLabel = if (storageStats.originalCount == 1) "1 original" else "${storageStats.originalCount} originals"

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .settingsRoundedSurface(
                            cornerDp = 12.dp,
                            fillColor = OptionUnselectedBg,
                            borderColor = OptionBorderUnselected,
                            borderWidthDp = 0.8.dp
                        )
                        .clickable { showStorageManagementDialog = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("open_watermark_storage_button"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = "Private Original Cache Storage",
                            tint = if (isPostCaptureWatermarkEditingEnabled) StuDOFocusYellow else StuDOSilver,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = "Private Originals Storage",
                                style = CardTitleStyle
                            )
                            Text(
                                text = "$usedText / $maxText · $countLabel cached",
                                style = CardSubtitleStyle
                            )
                        }
                    }
                    Text(
                        text = "MANAGE",
                        color = StuDOFocusYellow,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    if (showFirstTimeSetupDialog) {
        PostCaptureWatermarkSetupDialog(
            initialLimit = watermarkCacheLimit,
            onConfirm = { chosenLimit ->
                showFirstTimeSetupDialog = false
                onSetPostCaptureWatermarkEditing(true, chosenLimit)
            },
            onDismiss = {
                showFirstTimeSetupDialog = false
            }
        )
    }

    if (showStorageManagementDialog) {
        WatermarkStorageManagementDialog(
            currentLimit = watermarkCacheLimit,
            storageStats = storageStats,
            onSelectLimit = onSetWatermarkCacheLimit,
            onClearAll = onClearAllPrivateOriginals,
            onDismiss = { showStorageManagementDialog = false }
        )
    }
}

@Composable
fun PostCaptureWatermarkSetupDialog(
    initialLimit: WatermarkCacheLimit,
    onConfirm: (WatermarkCacheLimit) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedLimit by remember { mutableStateOf(initialLimit) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF181A22),
        title = {
            Text(
                text = "Enable Post-Capture Watermark Editing",
                color = StuDOWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "When enabled, StuDO keeps a clean, unwatermarked original inside app-private storage (hidden from the system gallery) alongside your watermarked JPEG so you can adjust or remove the watermark anytime.",
                    color = StuDOSilver,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Text(
                    text = "Select maximum private cache storage limit:",
                    color = StuDOWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                CacheLimitSelectorGrid(
                    selectedLimit = selectedLimit,
                    onSelectLimit = { selectedLimit = it }
                )
                Text(
                    text = "Note: StuDO automatically deletes the oldest cached private originals after reaching this limit. Your saved gallery photos remain intact.",
                    color = StuDOAmber,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedLimit) },
                colors = ButtonDefaults.buttonColors(containerColor = StuDOFocusYellow),
                modifier = Modifier.testTag("post_capture_setup_confirm_button")
            ) {
                Text(
                    text = "Enable (${selectedLimit.displayName})",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("post_capture_setup_cancel_button")
            ) {
                Text("Cancel", color = StuDOSilverMuted)
            }
        }
    )
}

@Composable
fun WatermarkStorageManagementDialog(
    currentLimit: WatermarkCacheLimit,
    storageStats: PrivateOriginalStorageStats,
    onSelectLimit: (WatermarkCacheLimit) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    var showClearConfirm by remember { mutableStateOf(false) }

    val usedStr = PrivateOriginalStorageManager.formatStorageSize(storageStats.usedBytes)
    val maxStr = PrivateOriginalStorageManager.formatStorageSize(currentLimit.limitBytes)
    val progress = if (currentLimit.limitBytes > 0L) {
        (storageStats.usedBytes.toFloat() / currentLimit.limitBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF181A22),
        title = {
            Text(
                text = "Private Originals Storage",
                color = StuDOWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .settingsRoundedSurface(
                            cornerDp = 12.dp,
                            fillColor = Color.White.copy(alpha = 0.05f),
                            borderColor = Color.White.copy(alpha = 0.10f),
                            borderWidthDp = 0.8.dp
                        )
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Maximum allocated storage", color = StuDOSilverMuted, fontSize = 11.sp)
                        Text(maxStr, color = StuDOFocusYellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Currently used storage", color = StuDOSilverMuted, fontSize = 11.sp)
                        Text(usedStr, color = StuDOWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Retained original photos", color = StuDOSilverMuted, fontSize = 11.sp)
                        Text(
                            text = "${storageStats.originalCount}",
                            color = StuDOWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (progress > 0.85f) StuDOAmber else StuDOFocusYellow,
                        trackColor = Color.White.copy(alpha = 0.12f)
                    )
                }

                Text(
                    text = "Cache Limit (oldest private originals are automatically deleted after reaching this limit; gallery photos stay safe):",
                    color = StuDOSilver,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                CacheLimitSelectorGrid(
                    selectedLimit = currentLimit,
                    onSelectLimit = onSelectLimit
                )

                OutlinedButton(
                    onClick = { showClearConfirm = true },
                    enabled = storageStats.originalCount > 0,
                    shape = StudioButtonShape,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (storageStats.originalCount > 0) StuDORed else StuDOSilverMuted
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("clear_private_originals_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (storageStats.originalCount > 0) {
                            "Clear All Private Originals (${storageStats.originalCount})"
                        } else {
                            "No Private Originals Stored"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = StuDOFocusYellow),
                modifier = Modifier.testTag("watermark_storage_management_close_button")
            ) {
                Text("Done", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    )

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = Color(0xFF1F212B),
            title = {
                Text(
                    text = "Clear all private originals?",
                    color = StuDOWhite,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "This removes all ${storageStats.originalCount} cached clean originals (${usedStr}) from StuDO private storage. Your existing gallery photos will NOT be deleted, but their watermarks can no longer be non-destructively re-edited.",
                    color = StuDOSilver,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        onClearAll()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StuDORed),
                    modifier = Modifier.testTag("clear_private_originals_confirm_button")
                ) {
                    Text("Clear All", color = StuDOWhite, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel", color = StuDOSilverMuted)
                }
            }
        )
    }
}

@Composable
private fun CacheLimitSelectorGrid(
    selectedLimit: WatermarkCacheLimit,
    onSelectLimit: (WatermarkCacheLimit) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        WATERMARK_CACHE_LIMITS.chunked(3).forEach { rowLimits ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowLimits.forEach { limit ->
                    val isSelected = limit == selectedLimit
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .settingsRoundedSurface(
                                cornerDp = 10.dp,
                                fillColor = if (isSelected) ChipSelectedBg else ChipUnselectedBg,
                                borderColor = if (isSelected) StuDOFocusYellow else ChipBorderUnselected,
                                borderWidthDp = if (isSelected) 1.5.dp else 0.8.dp
                            )
                            .clickable { onSelectLimit(limit) }
                            .padding(vertical = 10.dp)
                            .testTag("cache_limit_option_${limit.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = limit.displayName,
                            color = if (isSelected) StuDOFocusYellow else StuDOSilver,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LiveWatermarkPreviewCard(
    watermarkConfig: WatermarkConfig,
    modifier: Modifier = Modifier,
    isScrollInProgress: () -> Boolean = { false }
) {
    val context = LocalContext.current
    var sampleImage by remember(watermarkConfig) {
        mutableStateOf(WatermarkPreviewCache.get(watermarkConfig))
    }

    LaunchedEffect(watermarkConfig) {
        val cached = WatermarkPreviewCache.get(watermarkConfig)
        if (cached != null) {
            if (sampleImage !== cached) sampleImage = cached
            return@LaunchedEffect
        }
        // Wait for active scroll/fling to settle so preview rendering doesn't drop fling frames
        if (isScrollInProgress()) {
            snapshotFlow { isScrollInProgress() }.first { !it }
        }
        delay(40L)
        if (isScrollInProgress()) {
            snapshotFlow { isScrollInProgress() }.first { !it }
        }
        val rendered = WatermarkPreviewCache.getOrRender(context, watermarkConfig)
        if (rendered != null) {
            sampleImage = rendered
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .settingsRoundedSurface(
                cornerDp = 14.dp,
                fillColor = PreviewContainerBg,
                borderColor = ChipBorderUnselected,
                borderWidthDp = 1.dp
            )
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LIVE WATERMARK PREVIEW",
                style = PreviewHeaderTitleStyle
            )
            Text(
                text = watermarkConfig.style.displayName,
                style = PreviewStyleNameStyle
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Stable fixed 16:10 aspect ratio container guarantees ZERO layout shifts during scrolling
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(PreviewInnerShape)
                .background(PreviewPlaceholderBg),
            contentAlignment = Alignment.Center
        ) {
            val image = sampleImage
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = "Live Watermark Preview",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = "Loading watermark preview...",
                    style = PreviewLoadingStyle
                )
            }
        }
    }
}

@Composable
fun OemEnhancementCard(
    activeAdapterName: String,
    oemEnhancementMode: OemEnhancementMode,
    onSelectMode: (OemEnhancementMode) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "OEM Processing Engine",
                tint = StuDOFocusYellow,
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(
                    text = "Camera Processing Engine",
                    style = CardTitleStyle
                )
                Text(
                    text = "Active: $activeAdapterName",
                    style = OemActiveLabelStyle
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OEM_ENHANCEMENT_MODES.forEach { mode ->
                OemModeChip(
                    mode = mode,
                    isSelected = mode == oemEnhancementMode,
                    onSelectMode = onSelectMode
                )
            }
        }
    }
}

@Composable
private fun RowScope.OemModeChip(
    mode: OemEnhancementMode,
    isSelected: Boolean,
    onSelectMode: (OemEnhancementMode) -> Unit
) {
    val onClick = remember(mode, onSelectMode) { { onSelectMode(mode) } }
    Box(
        modifier = Modifier
            .weight(1f)
            .settingsRoundedSurface(
                cornerDp = 10.dp,
                fillColor = if (isSelected) ChipSelectedBg else ChipUnselectedBg,
                borderColor = if (isSelected) StuDOFocusYellow else ChipBorderUnselected,
                borderWidthDp = if (isSelected) 1.5.dp else 0.5.dp
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp)
            .testTag("settings_oem_mode_${mode.name.lowercase()}")
    ) {
        Column {
            Text(
                text = mode.displayName,
                style = if (isSelected) OemChipTitleSelectedStyle else OemChipTitleNormalStyle
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = mode.description,
                style = OemChipDescStyle
            )
        }
    }
}

@Composable
fun VideoQualityCard(
    supportedQualities: List<VideoQualityOption>,
    selectedQuality: VideoQualityOption,
    onSelectQuality: (VideoQualityOption) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = "Video Quality",
                tint = StuDOSilver,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = "Video Resolution Quality",
                    style = CardTitleStyle
                )
                Text(
                    text = "Framerate and pixel density per recording",
                    style = CardSubtitleStyle
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        val availableQualities = supportedQualities.ifEmpty { DEFAULT_VIDEO_QUALITIES }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            availableQualities.forEach { q ->
                SettingsChoiceChip(
                    item = q,
                    label = q.shortLabel,
                    isSelected = q == selectedQuality,
                    selectedColor = StuDOAmber,
                    fontSize = 12.sp,
                    onSelect = onSelectQuality
                )
            }
        }
    }
}

@Composable
fun VideoFpsCard(
    supportedFps: List<VideoFpsOption>,
    selectedFps: VideoFpsOption,
    onSelectFps: (VideoFpsOption) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.QueryBuilder,
                contentDescription = "Video FPS",
                tint = StuDOSilver,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = "Target Framerate (FPS)",
                    style = CardTitleStyle
                )
                Text(
                    text = "Hardware auto-exposure target recording framerate",
                    style = CardSubtitleStyle
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        val availableFps = supportedFps.ifEmpty { DEFAULT_VIDEO_FPS }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            availableFps.forEach { fpsOption ->
                SettingsChoiceChip(
                    item = fpsOption,
                    label = fpsOption.displayName,
                    isSelected = fpsOption == selectedFps,
                    selectedColor = StuDOAmber,
                    fontSize = 12.sp,
                    onSelect = onSelectFps
                )
            }
        }
    }
}

@Composable
fun VolumeKeyCard(
    volumeKeyAction: VolumeKeyAction,
    onSelectVolumeKeyAction: (VolumeKeyAction) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeDown,
                contentDescription = "Volume Key Action",
                tint = StuDOSilver,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = "Volume Buttons Function",
                    style = CardTitleStyle
                )
                Text(
                    text = "Physical button action when viewfinder is visible",
                    style = CardSubtitleStyle
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VOLUME_KEY_ACTIONS.forEach { action ->
                val shortTitle = when (action) {
                    VolumeKeyAction.SHUTTER -> "Shutter"
                    VolumeKeyAction.ZOOM -> "Zoom"
                    VolumeKeyAction.SYSTEM -> "Volume"
                }
                SettingsChoiceChip(
                    item = action,
                    label = shortTitle,
                    isSelected = action == volumeKeyAction,
                    selectedColor = StuDOFocusYellow,
                    fontSize = 12.sp,
                    onSelect = onSelectVolumeKeyAction
                )
            }
        }
    }
}

@Composable
fun FramingGridCard(
    selectedGrid: GridOption,
    onSelectGrid: (GridOption) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.GridOn,
                contentDescription = "Framing Grid",
                tint = StuDOSilver,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = "Framing Grid",
                    style = CardTitleStyle
                )
                Text(
                    text = "Composition rule guides",
                    style = CardSubtitleStyle
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GRID_OPTIONS.chunked(3).forEach { rowOptions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowOptions.forEach { option ->
                        SettingsChoiceChip(
                            item = option,
                            label = option.shortLabel,
                            isSelected = option == selectedGrid,
                            selectedColor = StuDOFocusYellow,
                            fontSize = 11.sp,
                            onSelect = onSelectGrid,
                            testTag = "settings_grid_option_${option.name.lowercase()}"
                        )
                    }
                    repeat(3 - rowOptions.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun CaptureTimerCard(
    selectedTimer: CaptureTimer,
    onSelectTimer: (CaptureTimer) -> Unit,
    customTimerSeconds: Int = CaptureTimer.DEFAULT_CUSTOM_SECONDS,
    onSetCustomTimerSeconds: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showCustomTimerDialog by remember { mutableStateOf(false) }

    SettingsCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.QueryBuilder,
                contentDescription = "Self-Timer",
                tint = StuDOSilver,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = "Capture Timer",
                    style = CardTitleStyle
                )
                Text(
                    text = if (selectedTimer == CaptureTimer.CUSTOM) {
                        "Custom delay: ${CaptureTimer.formatDuration(customTimerSeconds)}"
                    } else {
                        "Delayed photo capture countdown duration"
                    },
                    style = CardSubtitleStyle
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CAPTURE_TIMERS.forEach { timer ->
                val isSelected = timer == selectedTimer
                val chipLabel = if (timer == CaptureTimer.CUSTOM && isSelected) {
                    CaptureTimer.formatDuration(customTimerSeconds)
                } else {
                    timer.displayName
                }
                SettingsChoiceChip(
                    item = timer,
                    label = chipLabel,
                    isSelected = isSelected,
                    selectedColor = StuDOAmber,
                    fontSize = 11.sp,
                    onSelect = { chosen ->
                        if (chosen == CaptureTimer.CUSTOM && isSelected) {
                            showCustomTimerDialog = true
                        } else {
                            onSelectTimer(chosen)
                        }
                    },
                    testTag = "settings_timer_option_${timer.name.lowercase()}"
                )
            }
        }

        AnimatedVisibility(
            visible = selectedTimer == CaptureTimer.CUSTOM,
            enter = expandVertically(tween(180)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(150))
        ) {
            Column {
                Spacer(modifier = Modifier.height(8.dp))
                CustomTimerInlineAdjuster(
                    customSeconds = customTimerSeconds,
                    onCustomSecondsChanged = onSetCustomTimerSeconds,
                    onOpenExactInputDialog = { showCustomTimerDialog = true },
                    tagPrefix = "settings_custom_timer"
                )
            }
        }
    }

    if (showCustomTimerDialog) {
        CustomTimerPickerDialog(
            initialSeconds = customTimerSeconds,
            onConfirmSeconds = { seconds ->
                onSetCustomTimerSeconds(seconds)
                showCustomTimerDialog = false
            },
            onDismiss = { showCustomTimerDialog = false }
        )
    }
}

@Composable
fun AspectRatioCard(
    selectedRatio: CaptureAspectRatio,
    onSelectAspectRatio: (CaptureAspectRatio) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsCard(modifier = modifier) {
        Text(
            text = "Aspect Ratio",
            style = CardTitleStyle
        )
        Text(
            text = "Framing ratio for live viewfinder & output photos",
            style = CardSubtitleStyle
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ASPECT_RATIO_OPTIONS.forEach { ratio ->
                SettingsChoiceChip(
                    item = ratio,
                    label = ratio.displayName,
                    isSelected = ratio == selectedRatio,
                    selectedColor = StuDOFocusYellow,
                    fontSize = 11.sp,
                    onSelect = onSelectAspectRatio
                )
            }
        }
    }
}

@Composable
private fun <T> RowScope.SettingsChoiceChip(
    item: T,
    label: String,
    isSelected: Boolean,
    selectedColor: Color,
    fontSize: TextUnit,
    onSelect: (T) -> Unit,
    cornerDp: Dp = 10.dp,
    selectedBackground: Color = ChipSelectedBg,
    unselectedBackground: Color = ChipUnselectedBg,
    unselectedBorderColor: Color = ChipBorderUnselected,
    testTag: String? = null
) {
    val baseModifier = Modifier
        .weight(1f)
        .settingsRoundedSurface(
            cornerDp = cornerDp,
            fillColor = if (isSelected) selectedBackground else unselectedBackground,
            borderColor = if (isSelected) selectedColor else unselectedBorderColor,
            borderWidthDp = if (isSelected) 1.5.dp else 0.5.dp
        )
        .clickable { onSelect(item) }
        .padding(vertical = 8.dp)
    Box(
        modifier = if (testTag != null) baseModifier.testTag(testTag) else baseModifier,
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) selectedColor else StuDOSilver,
            fontSize = fontSize,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
fun RecycleBinSettingsCard(
    binCount: Int,
    onOpenRecycleBin: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsCard(modifier = modifier.testTag("settings_recycle_bin_card")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenRecycleBin)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.RestoreFromTrash,
                    contentDescription = "StuDO Bin",
                    tint = if (binCount > 0) StuDOAmber else StuDOSilver,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = "StuDO Bin",
                        style = CardTitleStyle
                    )
                    val subtitleText = when (binCount) {
                        0 -> "No deleted photos or videos"
                        1 -> "1 item waiting in local recovery bin"
                        else -> "$binCount items waiting in local recovery bin"
                    }
                    Text(
                        text = subtitleText,
                        style = CardSubtitleStyle
                    )
                }
            }

            Box(
                modifier = Modifier
                    .settingsRoundedSurface(
                        cornerDp = 10.dp,
                        fillColor = if (binCount > 0) BadgeBackgroundColor else ChipUnselectedBg,
                        borderColor = if (binCount > 0) StuDOAmber else ChipBorderUnselected,
                        borderWidthDp = 1.dp
                    )
                    .clickable(onClick = onOpenRecycleBin)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("settings_open_recycle_bin_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (binCount > 0) "Open ($binCount)" else "Open Bin",
                    color = if (binCount > 0) StuDOAmber else StuDOSilver,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun AboutHardwareCard(
    onVersionTapped: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onVersionTapped)
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "STU•D•O Pro Camera",
                    style = AboutTitleStyle
                )
                Text(
                    text = "Tap version 5× for Camera Diagnostics",
                    style = CardSubtitleStyle
                )
            }
            Text(
                text = "v2.0 Pro",
                style = AboutVersionStyle
            )
        }
    }
}
