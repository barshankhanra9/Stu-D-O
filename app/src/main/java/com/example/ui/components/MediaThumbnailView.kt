package com.example.ui.components

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.example.model.MediaItem
import com.example.ui.theme.StuDOWhite
import com.example.util.VideoThumbnailUtil

@Composable
fun MediaThumbnailView(
    item: MediaItem,
    modifier: Modifier = Modifier,
    mediaRevision: Long = 0L
) {
    val context = LocalContext.current
    var videoBitmap by remember(item.uri, item.isVideo) {
        mutableStateOf(if (item.isVideo) VideoThumbnailUtil.getCached(item.uri) else null)
    }

    // For videos, extract first decodable frame (time 0L) via VideoThumbnailUtil
    if (item.isVideo) {
        LaunchedEffect(item.uri) {
            val cached = VideoThumbnailUtil.getCached(item.uri)
            if (cached != null) {
                videoBitmap = cached
                return@LaunchedEffect
            }
            val loaded = VideoThumbnailUtil.loadThumbnail(context, item.uri)
            videoBitmap = loaded ?: VideoThumbnailUtil.createFallbackPlaceholderBitmap(256, 256)
        }
    }

    Box(
        modifier = modifier
            .background(Color(0xFF14151B)),
        contentAlignment = Alignment.Center
    ) {
        if (item.isVideo) {
            if (videoBitmap != null) {
                Image(
                    bitmap = videoBitmap!!.asImageBitmap(),
                    contentDescription = item.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                // Secondary fallback: Coil with first frame (0 millis) decoder
                val request = remember(item.uri) {
                    ImageRequest.Builder(context)
                        .data(item.uri)
                        .videoFrameMillis(0)
                        .decoderFactory { result, options, _ -> VideoFrameDecoder(result.source, options) }
                        .crossfade(true)
                        .build()
                }

                AsyncImage(
                    model = request,
                    contentDescription = item.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        } else {
            // Photos
            val request = remember(item.uri, mediaRevision) {
                ImageRequest.Builder(context)
                    .data(item.uri)
                    .memoryCacheKey("thumb_${item.uri}_$mediaRevision")
                    .diskCacheKey("thumb_${item.uri}_$mediaRevision")
                    .size(256)
                    .crossfade(true)
                    .build()
            }

            AsyncImage(
                model = request,
                contentDescription = item.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // Overlay Video Camera badge
        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 3.dp, vertical = 1.5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = "Video",
                    tint = StuDOWhite,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}
