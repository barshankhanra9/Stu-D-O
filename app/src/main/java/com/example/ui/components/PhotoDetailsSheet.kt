package com.example.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.GlassCoolBlueSpecular
import com.example.ui.theme.GlassObsidianBottom
import com.example.ui.theme.GlassObsidianTop
import com.example.ui.theme.GlassWarmAmberSpecular
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDOFocusYellow
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite
import com.example.util.GpsCoordinates
import com.example.util.MetadataField
import com.example.util.PhotoMetadataExtractor
import com.example.util.RichPhotoMetadata
import java.util.Locale
import kotlin.math.cos

@Composable
fun PhotoDetailsSheetOverlay(
    visible: Boolean,
    metadata: RichPhotoMetadata?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isVideoHint: Boolean = false
) {
    if (visible) {
        BackHandler(onBack = onDismiss)
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.56f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter
            ) {
                val sheetMaxHeight = (maxHeight * 0.78f).coerceAtLeast(320.dp)
                PhotoDetailsSheetContent(
                    metadata = metadata,
                    isLoading = isLoading,
                    isVideoHint = isVideoHint,
                    onDismiss = onDismiss,
                    modifier = Modifier
                        .widthIn(max = 580.dp)
                        .fillMaxWidth()
                        .heightIn(max = sheetMaxHeight)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoDetailsSheetContent(
    metadata: RichPhotoMetadata?,
    isLoading: Boolean,
    isVideoHint: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isVideoContext = metadata?.isVideo ?: isVideoHint
    val scrollState = rememberScrollState()
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    Column(
        modifier = modifier
            .liquidGlassSurface(
                shape = sheetShape,
                cornerRadius = 28.dp,
                topColor = GlassObsidianTop.copy(alpha = 0.92f),
                bottomColor = GlassObsidianBottom.copy(alpha = 0.96f),
                borderWidth = 1.dp,
                coolHighlightAlpha = 0.36f,
                warmHighlightAlpha = 0.24f,
                specularSheenAlpha = 0.14f,
                internalShadowAlpha = 0.28f
            )
            // Intercept pointer events inside the sheet so taps don't dismiss via scrim
            // without merging TalkBack / Compose semantics across the scrollable children.
            .pointerInput(Unit) {
                detectTapGestures { }
            }
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 10.dp, bottom = 20.dp)
            .testTag("photo_details_sheet")
    ) {
        // Subtle drag bar indicator
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(38.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.24f))
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Sheet Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isVideoContext) "Video Details" else "Photo Details",
                        color = StuDOWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.2.sp
                    )
                    metadata?.formatBadge?.let { badge ->
                        Box(
                            modifier = Modifier
                                .liquidGlassChipSurface(
                                    shape = RoundedCornerShape(8.dp),
                                    cornerRadius = 8.dp,
                                    isActive = true,
                                    accentColor = GlassCoolBlueSpecular,
                                    activeFillAlpha = 0.18f
                                )
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = badge,
                                color = GlassCoolBlueSpecular,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp
                            )
                        }
                    }
                }
                metadata?.title?.takeIf { it.isNotBlank() }?.let { title ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = title,
                        color = StuDOSilverMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(36.dp)
                    .liquidGlassChipSurface(
                        shape = CircleShape,
                        cornerRadius = 18.dp,
                        isActive = false
                    )
                    .testTag("details_sheet_close_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Close details",
                    tint = StuDOSilver,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Scrollable metadata body
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .padding(top = 14.dp)
                .verticalScroll(scrollState)
                .testTag("details_sheet_scroll_container"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (isLoading && metadata == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            color = StuDOFocusYellow,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(26.dp)
                        )
                        Text(
                            text = if (isVideoContext) "Reading video metadata..." else "Reading image metadata...",
                            color = StuDOSilverMuted,
                            fontSize = 13.sp
                        )
                    }
                }
            } else if (metadata == null || metadata.isEmpty) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(18.dp),
                            cornerRadius = 18.dp,
                            isActive = false
                        )
                        .padding(18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No embedded metadata available for this file.",
                        color = StuDOSilverMuted,
                        fontSize = 13.sp
                    )
                }
            } else {
                // Quick Capture Hero Highlights (Aperture · Shutter · ISO · Focal)
                if (metadata.heroHighlights.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("details_hero_highlights"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        metadata.heroHighlights.forEach { spec ->
                            Box(
                                modifier = Modifier
                                    .liquidGlassChipSurface(
                                        shape = RoundedCornerShape(12.dp),
                                        cornerRadius = 12.dp,
                                        isActive = true,
                                        accentColor = GlassWarmAmberSpecular,
                                        activeFillAlpha = 0.14f
                                    )
                                    .padding(horizontal = 11.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = spec,
                                    color = StuDOWhite,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.3.sp
                                )
                            }
                        }
                    }
                }

                if (metadata.imageFields.isNotEmpty()) {
                    MetadataSectionCard(
                        title = if (isVideoContext) "Video" else "Image",
                        icon = if (isVideoContext) Icons.Outlined.Videocam else Icons.Outlined.Image,
                        accent = GlassCoolBlueSpecular,
                        fields = metadata.imageFields,
                        modifier = Modifier.testTag(if (isVideoContext) "details_section_video" else "details_section_image")
                    )
                }

                if (metadata.cameraFields.isNotEmpty()) {
                    MetadataSectionCard(
                        title = "Camera",
                        icon = Icons.Outlined.CameraAlt,
                        accent = StuDOAmber,
                        fields = metadata.cameraFields,
                        modifier = Modifier.testTag("details_section_camera")
                    )
                }

                if (metadata.captureFields.isNotEmpty()) {
                    MetadataSectionCard(
                        title = "Capture Settings",
                        icon = Icons.Outlined.Tune,
                        accent = StuDOFocusYellow,
                        fields = metadata.captureFields,
                        modifier = Modifier.testTag("details_section_capture")
                    )
                }

                if (metadata.gps != null || metadata.locationFields.isNotEmpty()) {
                    LocationSectionCard(
                        gps = metadata.gps,
                        fields = metadata.locationFields,
                        modifier = Modifier.testTag("details_section_location")
                    )
                }

                if (metadata.otherFields.isNotEmpty()) {
                    MetadataSectionCard(
                        title = "Additional EXIF",
                        icon = Icons.Outlined.Info,
                        accent = StuDOSilver,
                        fields = metadata.otherFields,
                        modifier = Modifier.testTag("details_section_other")
                    )
                }
            }
        }
    }
}

@Composable
private fun MetadataSectionCard(
    title: String,
    icon: ImageVector,
    accent: Color,
    fields: List<MetadataField>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlassSurface(
                shape = RoundedCornerShape(18.dp),
                cornerRadius = 18.dp,
                topColor = Color.White.copy(alpha = 0.07f),
                bottomColor = Color.Black.copy(alpha = 0.32f),
                borderWidth = 0.75.dp,
                coolHighlightAlpha = 0.24f,
                warmHighlightAlpha = 0.14f,
                specularSheenAlpha = 0.08f,
                internalShadowAlpha = 0.12f
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        SectionHeaderRow(title = title, icon = icon, accent = accent)
        Spacer(modifier = Modifier.height(10.dp))

        fields.forEachIndexed { index, field ->
            MetadataKeyValueRow(label = field.label, value = field.value)
            if (index < fields.lastIndex) {
                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.07f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun LocationSectionCard(
    gps: GpsCoordinates?,
    fields: List<MetadataField>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlassSurface(
                shape = RoundedCornerShape(18.dp),
                cornerRadius = 18.dp,
                topColor = Color.White.copy(alpha = 0.07f),
                bottomColor = Color.Black.copy(alpha = 0.32f),
                borderWidth = 0.75.dp,
                coolHighlightAlpha = 0.28f,
                warmHighlightAlpha = 0.18f,
                specularSheenAlpha = 0.08f,
                internalShadowAlpha = 0.12f
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        SectionHeaderRow(
            title = "Location",
            icon = Icons.Outlined.LocationOn,
            accent = StuDOAmber
        )

        if (gps != null) {
            Spacer(modifier = Modifier.height(12.dp))
            LiquidGlassLocationMapCard(gps = gps)
        }

        if (fields.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            fields.forEachIndexed { index, field ->
                MetadataKeyValueRow(label = field.label, value = field.value)
                if (index < fields.lastIndex) {
                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.07f),
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LiquidGlassLocationMapCard(
    gps: GpsCoordinates
) {
    val context = LocalContext.current
    val coordText = remember(gps) {
        PhotoMetadataExtractor.formatCoordinatesDecimal(gps.latitude, gps.longitude)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(148.dp)
            .liquidGlassChipSurface(
                shape = RoundedCornerShape(14.dp),
                cornerRadius = 14.dp,
                isActive = false
            )
            .testTag("details_gps_map_preview")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Deep night ocean optical backdrop
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF0A1424), Color(0xFF060C16))
                )
            )

            // Regional map viewport centered on GPS coordinate (+/- 26 deg lat span)
            val latSpan = 26.0
            val cosLat = cos(Math.toRadians(gps.latitude)).coerceIn(0.25, 1.0)
            val lonSpan = (latSpan * (w / h.coerceAtLeast(1f))) / cosLat

            fun project(lat: Double, lon: Double): Offset {
                var dLon = lon - gps.longitude
                if (dLon > 180.0) dLon -= 360.0
                if (dLon < -180.0) dLon += 360.0
                val x = (w * 0.5f) + ((dLon / lonSpan) * w).toFloat()
                val y = (h * 0.5f) - (((lat - gps.latitude) / latSpan) * h).toFloat()
                return Offset(x, y)
            }

            // Lat/Lon coordinate graticule lines every 5 degrees
            val gridPaintColor = Color.White.copy(alpha = 0.08f)
            val minLon = gps.longitude - lonSpan
            val maxLon = gps.longitude + lonSpan
            val minLat = (gps.latitude - latSpan).coerceAtLeast(-85.0)
            val maxLat = (gps.latitude + latSpan).coerceAtMost(85.0)

            val startLonTick = (kotlin.math.floor(minLon / 5.0) * 5).toInt()
            val endLonTick = (kotlin.math.ceil(maxLon / 5.0) * 5).toInt()
            for (deg in startLonTick..endLonTick step 5) {
                val pTop = project(maxLat, deg.toDouble())
                val pBot = project(minLat, deg.toDouble())
                drawLine(gridPaintColor, pTop, pBot, strokeWidth = 1f)
            }

            val startLatTick = (kotlin.math.floor(minLat / 5.0) * 5).toInt()
            val endLatTick = (kotlin.math.ceil(maxLat / 5.0) * 5).toInt()
            for (deg in startLatTick..endLatTick step 5) {
                val pLeft = project(deg.toDouble(), minLon)
                val pRight = project(deg.toDouble(), maxLon)
                drawLine(gridPaintColor, pLeft, pRight, strokeWidth = 1f)
            }

            // Simplified world continent polygons projected into regional viewport
            val landFill = GlassCoolBlueSpecular.copy(alpha = 0.14f)
            val landStroke = GlassCoolBlueSpecular.copy(alpha = 0.35f)
            SIMPLIFIED_CONTINENTS.forEach { polygon ->
                // Draw at base and wrapped +/- 360 longitude for antimeridian continuity
                for (lonShift in listOf(-360.0, 0.0, 360.0)) {
                    val path = Path()
                    polygon.forEachIndexed { idx, (lat, lon) ->
                        val pt = project(lat, lon + lonShift)
                        if (idx == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                    }
                    path.close()
                    drawPath(path, color = landFill, style = Fill)
                    drawPath(path, color = landStroke, style = Stroke(width = 1.4f))
                }
            }

            // Optical reticle & GPS pin at center
            val center = Offset(w * 0.5f, h * 0.5f)
            drawLine(
                color = StuDOAmber.copy(alpha = 0.22f),
                start = Offset(0f, center.y),
                end = Offset(w, center.y),
                strokeWidth = 1f
            )
            drawLine(
                color = StuDOAmber.copy(alpha = 0.22f),
                start = Offset(center.x, 0f),
                end = Offset(center.x, h),
                strokeWidth = 1f
            )
            drawCircle(
                color = StuDOAmber.copy(alpha = 0.16f),
                radius = 26.dp.toPx(),
                center = center
            )
            drawCircle(
                color = StuDOAmber.copy(alpha = 0.36f),
                radius = 14.dp.toPx(),
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
            drawCircle(
                color = StuDOAmber,
                radius = 5.5.dp.toPx(),
                center = center
            )
            drawCircle(
                color = Color.White,
                radius = 2.dp.toPx(),
                center = center
            )
        }

        // Top-left coordinate pill
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = coordText,
                color = StuDOWhite,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Bottom-right "Open in Maps" action pill
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp)
                .liquidGlassChipSurface(
                    shape = RoundedCornerShape(10.dp),
                    cornerRadius = 10.dp,
                    isActive = true,
                    accentColor = StuDOAmber,
                    activeFillAlpha = 0.24f
                )
                .clickable {
                    val geoUri = Uri.parse(
                        String.format(
                            Locale.US,
                            "geo:%.6f,%.6f?q=%.6f,%.6f",
                            gps.latitude,
                            gps.longitude,
                            gps.latitude,
                            gps.longitude
                        )
                    )
                    val intent = Intent(Intent.ACTION_VIEW, geoUri)
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {}
                }
                .padding(horizontal = 10.dp, vertical = 5.dp)
                .testTag("details_open_maps_button"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Open in Maps",
                color = StuDOAmber,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = "Open coordinates in Maps",
                tint = StuDOAmber,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun SectionHeaderRow(
    title: String,
    icon: ImageVector,
    accent: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(14.dp)
            )
        }
        Text(
            text = title,
            color = StuDOWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp
        )
    }
}

@Composable
private fun MetadataKeyValueRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = StuDOSilverMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(
            text = value,
            color = StuDOWhite,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

// Low-polygon continent coastline outlines (lat, lon) for offline GPS regional map preview
private val SIMPLIFIED_CONTINENTS: List<List<Pair<Double, Double>>> = listOf(
    // North & Central America
    listOf(
        70.0 to -162.0, 71.0 to -130.0, 62.0 to -94.0, 52.0 to -56.0, 44.0 to -66.0,
        30.0 to -81.0, 25.0 to -80.0, 29.0 to -95.0, 19.0 to -96.0, 21.0 to -87.0,
        9.0 to -79.0, 15.0 to -93.0, 23.0 to -106.0, 32.0 to -117.0, 48.0 to -125.0,
        60.0 to -148.0, 55.0 to -162.0
    ),
    // South America
    listOf(
        11.0 to -75.0, 10.0 to -62.0, 0.0 to -49.0, -6.0 to -35.0, -23.0 to -42.0,
        -35.0 to -57.0, -54.0 to -68.0, -52.0 to -75.0, -35.0 to -72.0, -18.0 to -71.0,
        -5.0 to -81.0, 4.0 to -77.0
    ),
    // Europe
    listOf(
        36.0 to -9.0, 43.0 to -9.0, 49.0 to -4.0, 54.0 to 8.0, 62.0 to 5.0,
        70.0 to 25.0, 68.0 to 44.0, 55.0 to 40.0, 46.0 to 34.0, 41.0 to 29.0,
        37.0 to 22.0, 40.0 to 18.0, 44.0 to 12.0, 41.0 to 2.0, 36.0 to -5.0
    ),
    // UK & Ireland
    listOf(
        50.0 to -5.5, 58.5 to -5.0, 57.5 to -1.8, 51.0 to 1.3, 50.5 to -2.0
    ),
    // Africa
    listOf(
        36.0 to -6.0, 37.0 to 10.0, 31.0 to 32.0, 12.0 to 51.0, -1.0 to 42.0,
        -15.0 to 40.0, -34.0 to 26.0, -34.0 to 18.0, -17.0 to 12.0, 4.0 to 9.0,
        5.0 to -8.0, 15.0 to -17.0, 28.0 to -13.0
    ),
    // Madagascar
    listOf(
        -12.0 to 49.0, -25.0 to 47.0, -25.0 to 44.0, -16.0 to 45.0
    ),
    // Asia
    listOf(
        41.0 to 29.0, 68.0 to 44.0, 72.0 to 80.0, 74.0 to 125.0, 66.0 to 170.0,
        54.0 to 140.0, 40.0 to 129.0, 31.0 to 122.0, 22.0 to 114.0, 10.0 to 107.0,
        1.5 to 104.0, 16.0 to 96.0, 22.0 to 90.0, 8.0 to 77.0, 23.0 to 68.0,
        25.0 to 56.0, 13.0 to 45.0, 31.0 to 33.0
    ),
    // Japan
    listOf(
        31.0 to 130.0, 35.0 to 140.0, 44.0 to 145.0, 41.0 to 140.0, 34.0 to 131.0
    ),
    // Australia
    listOf(
        -12.0 to 131.0, -11.0 to 142.0, -26.0 to 153.0, -38.0 to 149.0, -35.0 to 135.0,
        -34.0 to 115.0, -21.0 to 114.0
    ),
    // Greenland
    listOf(
        60.0 to -44.0, 76.0 to -20.0, 82.0 to -30.0, 76.0 to -68.0
    )
)
