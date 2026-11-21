package com.example.ui.components

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.model.BinMediaItem
import com.example.ui.theme.GlassCoolBlueSpecular
import com.example.ui.theme.GlassObsidianBottom
import com.example.ui.theme.GlassObsidianTop
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDOCarbon
import com.example.ui.theme.StuDOFocusYellow
import com.example.ui.theme.StuDORed
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite
import com.example.util.VideoThumbnailUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecycleBinDialog(
    binItems: List<BinMediaItem>,
    onRestore: (BinMediaItem) -> Unit,
    onPermanentDelete: (BinMediaItem) -> Unit,
    onClearBin: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onDismiss)

    var pendingClearConfirm by remember { mutableStateOf(false) }
    var pendingDeleteItem by remember { mutableStateOf<BinMediaItem?>(null) }

    val totalBytes = remember(binItems) { binItems.sumOf { it.sizeBytes.coerceAtLeast(0L) } }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("recycle_bin_dialog"),
        color = Color(0xFF0C0D12),
        contentColor = StuDOWhite
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "STU•D•O BIN",
                        color = StuDOWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    val countText = if (binItems.isEmpty()) {
                        "No deleted photos or videos"
                    } else {
                        val label = if (binItems.size == 1) "1 item" else "${binItems.size} items"
                        "$label • ${formatBinBytes(totalBytes)}"
                    }
                    Text(
                        text = countText,
                        color = StuDOSilverMuted,
                        fontSize = 12.sp
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (binItems.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .liquidGlassChipSurface(
                                    shape = RoundedCornerShape(16.dp),
                                    cornerRadius = 16.dp,
                                    isActive = false
                                )
                                .clickable { pendingClearConfirm = true }
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                                .testTag("bin_clear_all_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = "Empty StuDO Bin",
                                    tint = StuDOSilver,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "Empty",
                                    color = StuDOSilver,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .liquidGlassChipSurface(
                                shape = CircleShape,
                                cornerRadius = 19.dp
                            )
                            .clickable(onClick = onDismiss)
                            .testTag("bin_close_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close StuDO Bin",
                            tint = StuDOWhite,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.10f), thickness = 0.8.dp)

            if (binItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .liquidGlassSurface(
                                    shape = CircleShape,
                                    cornerRadius = 32.dp,
                                    topColor = GlassObsidianTop.copy(alpha = 0.56f),
                                    bottomColor = GlassObsidianBottom.copy(alpha = 0.72f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.RestoreFromTrash,
                                contentDescription = null,
                                tint = StuDOSilver,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Text(
                            text = "StuDO Bin is empty",
                            color = StuDOWhite,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Photos and videos you delete from the StuDO gallery appear here so you can restore or permanently remove them.",
                            color = StuDOSilverMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("bin_items_list"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(binItems, key = { it.id }) { item ->
                        RecycleBinItemCard(
                            item = item,
                            onRestore = { onRestore(item) },
                            onDeleteForever = { pendingDeleteItem = item }
                        )
                    }
                }
            }
        }
    }

    if (pendingDeleteItem != null) {
        val target = pendingDeleteItem!!
        AlertDialog(
            onDismissRequest = { pendingDeleteItem = null },
            containerColor = StuDOCarbon,
            title = {
                Text(
                    text = "Delete permanently?",
                    color = StuDOWhite,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "\"${target.displayName}\" will be permanently erased from StuDO Bin and cannot be recovered.",
                    color = StuDOSilverMuted
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteItem = null
                        onPermanentDelete(target)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = StuDORed),
                    modifier = Modifier.testTag("bin_permanent_delete_confirm")
                ) {
                    Text("Delete Forever", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDeleteItem = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = StuDOSilverMuted)
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (pendingClearConfirm) {
        AlertDialog(
            onDismissRequest = { pendingClearConfirm = false },
            containerColor = StuDOCarbon,
            title = {
                Text(
                    text = "Empty StuDO Bin?",
                    color = StuDOWhite,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "All ${binItems.size} items in StuDO Bin (${formatBinBytes(totalBytes)}) will be permanently deleted.",
                    color = StuDOSilverMuted
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingClearConfirm = false
                        onClearBin()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = StuDORed),
                    modifier = Modifier.testTag("bin_clear_all_confirm")
                ) {
                    Text("Empty Bin", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingClearConfirm = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = StuDOSilverMuted)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun RecycleBinItemCard(
    item: BinMediaItem,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit
) {
    val context = LocalContext.current
    val mediaFile = remember(item.binFilePath) { File(item.binFilePath) }
    var videoPoster by remember(item.binFilePath) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.binFilePath, item.isVideo) {
        if (item.isVideo && mediaFile.exists()) {
            videoPoster = VideoThumbnailUtil.loadThumbnail(context, android.net.Uri.fromFile(mediaFile))
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlassSurface(
                shape = RoundedCornerShape(18.dp),
                cornerRadius = 18.dp,
                topColor = GlassObsidianTop.copy(alpha = 0.52f),
                bottomColor = GlassObsidianBottom.copy(alpha = 0.68f),
                borderWidth = 0.8.dp
            )
            .padding(10.dp)
            .testTag("bin_item_${item.id}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail box
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF14161E))
                .border(0.6.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (item.isVideo) {
                val bmp = videoPoster
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Video",
                        tint = StuDOSilverMuted,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        tint = StuDOAmber,
                        modifier = Modifier.size(10.dp)
                    )
                }
            } else if (mediaFile.exists()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(mediaFile)
                        .crossfade(true)
                        .build(),
                    contentDescription = item.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Photo,
                    contentDescription = "Photo",
                    tint = StuDOSilverMuted,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Metadata + Action buttons
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = item.displayName,
                color = StuDOWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val dateStr = remember(item.deletedAtMillis) {
                SimpleDateFormat("MMM dd, HH:mm", Locale.US).format(Date(item.deletedAtMillis))
            }
            val metaParts = buildList {
                if (item.isVideo) add("VIDEO")
                if (item.sizeBytes > 0L) add(formatBinBytes(item.sizeBytes))
                add(dateStr)
            }.joinToString(" • ")

            Text(
                text = metaParts,
                color = StuDOSilverMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(10.dp),
                            cornerRadius = 10.dp,
                            isActive = true,
                            accentColor = GlassCoolBlueSpecular,
                            activeFillAlpha = 0.18f
                        )
                        .clickable(onClick = onRestore)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .testTag("bin_restore_${item.id}"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestoreFromTrash,
                            contentDescription = "Restore",
                            tint = GlassCoolBlueSpecular,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "Restore",
                            color = GlassCoolBlueSpecular,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .liquidGlassChipSurface(
                            shape = RoundedCornerShape(10.dp),
                            cornerRadius = 10.dp,
                            isActive = false
                        )
                        .clickable(onClick = onDeleteForever)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .testTag("bin_delete_${item.id}"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = "Delete Forever",
                            tint = StuDOSilver,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "Delete",
                            color = StuDOSilver,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

private fun formatBinBytes(bytes: Long): String {
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
