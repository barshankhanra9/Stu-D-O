package com.example.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.LiquidGlassVisualTier
import com.example.ui.components.adaptiveLiquidGlassPanel
import com.example.ui.theme.GlassObsidianBottom
import com.example.ui.theme.GlassObsidianTop
import com.example.ui.theme.StuDOAmber
import com.example.ui.theme.StuDOFocusYellow
import com.example.ui.theme.StuDOSilver
import com.example.ui.theme.StuDOSilverMuted
import com.example.ui.theme.StuDOWhite

/**
 * Floating Liquid Glass top toolbar for StuDO Photo Editor.
 * Compact, tactile controls matching Watermark Studio visual hierarchy:
 * Close | Undo | Redo | Hold-to-Compare | Reset | Prominent Yellow Save.
 */
@Composable
fun PhotoEditorTopToolbar(
    visualTier: LiquidGlassVisualTier,
    canUndo: Boolean,
    canRedo: Boolean,
    canReset: Boolean,
    isComparing: Boolean,
    isExporting: Boolean,
    onClose: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onComparePress: () -> Unit,
    onCompareRelease: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .widthIn(max = 680.dp)
            .fillMaxWidth()
            .adaptiveLiquidGlassPanel(
                visualTier = visualTier,
                shape = RoundedCornerShape(26.dp),
                cornerRadius = 26.dp,
                topColor = GlassObsidianTop.copy(alpha = 0.74f),
                bottomColor = GlassObsidianBottom.copy(alpha = 0.86f),
                borderWidth = 1.dp,
                coolHighlightAlpha = 0.32f,
                warmHighlightAlpha = 0.22f,
                specularSheenAlpha = 0.14f
            )
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag("editor_top_toolbar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: Close + Undo + Redo
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(44.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("editor_close_button")
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.09f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Photo Editor",
                        tint = StuDOWhite,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            ToolbarCircleIconAction(
                icon = Icons.AutoMirrored.Filled.Undo,
                contentDescription = "Undo",
                enabled = canUndo,
                onClick = onUndo,
                testTag = "editor_undo_button"
            )

            ToolbarCircleIconAction(
                icon = Icons.AutoMirrored.Filled.Redo,
                contentDescription = "Redo",
                enabled = canRedo,
                onClick = onRedo,
                testTag = "editor_redo_button"
            )
        }

        // Right: Compare + Reset + Save
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Press-and-hold before/after compare button
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = "Hold to Compare Original" }
                    .testTag("editor_compare_button")
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onComparePress()
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    onCompareRelease()
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (isComparing) StuDOFocusYellow.copy(alpha = 0.24f)
                            else Color.White.copy(alpha = 0.08f)
                        )
                        .border(
                            width = if (isComparing) 1.dp else 0.7.dp,
                            color = if (isComparing) StuDOFocusYellow else Color.White.copy(alpha = 0.14f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Compare,
                        contentDescription = null,
                        tint = if (isComparing) StuDOFocusYellow else StuDOSilver,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            ToolbarCircleIconAction(
                icon = Icons.Default.RestartAlt,
                contentDescription = "Reset All Edits",
                enabled = canReset,
                activeTint = StuDOAmber,
                onClick = onReset,
                testTag = "editor_reset_button"
            )

            Button(
                onClick = onSave,
                enabled = !isExporting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StuDOFocusYellow,
                    disabledContainerColor = StuDOFocusYellow.copy(alpha = 0.45f)
                ),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 13.dp, vertical = 0.dp),
                modifier = Modifier
                    .height(38.dp)
                    .defaultMinSize(minWidth = 72.dp, minHeight = 48.dp)
                    .testTag("editor_save_button")
            ) {
                if (isExporting) {
                    CircularProgressIndicator(
                        color = Color.Black,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isExporting) "Saving" else "Save",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ToolbarCircleIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
    activeTint: Color = StuDOWhite
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(44.dp)
            .minimumInteractiveComponentSize()
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) Color.White.copy(alpha = 0.11f)
                    else Color.White.copy(alpha = 0.03f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) activeTint else StuDOSilverMuted.copy(alpha = 0.30f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
