package com.carlom.klardrop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.carlom.klardrop.theme.KdTheme
import io.github.vinceglb.filekit.PlatformFile

/**
 * The attachment chooser shown when the paperclip is tapped on mobile/tablet.
 *
 * Offers Gallery / Files / Paste actions plus an inline rail of the user's recent
 * media (Android only — see [RecentMediaRail]). On phones it presents as a modal
 * bottom sheet; on tablets it presents as a popover anchored near the paperclip
 * (bottom-left of the chat pane), matching what messaging apps do on large screens.
 *
 * @param isLargeScreen true for tablet/wide layouts (popover), false for phones (sheet)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentChooser(
    isLargeScreen: Boolean,
    onGallery: () -> Unit,
    onFiles: () -> Unit,
    onPaste: () -> Unit,
    onPickMedia: (PlatformFile) -> Unit,
    onDismiss: () -> Unit,
) {
    if (isLargeScreen) {
        Popup(
            alignment = Alignment.BottomStart,
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true),
        ) {
            // Float the card just above the message input row (the paperclip lives
            // at the bottom-left), so it reads as popping out of the attach button.
            Box(modifier = Modifier.padding(start = KdTheme.spacing.s3, bottom = 64.dp)) {
                Surface(
                    shape = KdTheme.radii.shapeLg,
                    color = KdTheme.colors.bg1,
                    shadowElevation = 12.dp,
                    modifier = Modifier.widthIn(max = 380.dp),
                ) {
                    ChooserContent(onGallery, onFiles, onPaste, onPickMedia, onDismiss)
                }
            }
        }
    } else {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            shape = KdTheme.radii.shapeSheet,
            containerColor = KdTheme.colors.bg1,
        ) {
            ChooserContent(
                onGallery, onFiles, onPaste, onPickMedia, onDismiss,
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun ChooserContent(
    onGallery: () -> Unit,
    onFiles: () -> Unit,
    onPaste: () -> Unit,
    onPickMedia: (PlatformFile) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing

    Column(modifier = modifier.padding(bottom = spacing.s3)) {
        Text(
            text = "Add attachment",
            style = typography.headline.copy(color = colors.text),
            modifier = Modifier.padding(horizontal = spacing.s4, vertical = spacing.s3),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.s4),
            horizontalArrangement = Arrangement.spacedBy(spacing.s2),
        ) {
            ActionTile(
                icon = Icons.Default.PhotoLibrary,
                label = "Gallery",
                onClick = { onGallery(); onDismiss() },
                modifier = Modifier.weight(1f),
            )
            ActionTile(
                icon = Icons.Default.InsertDriveFile,
                label = "Files",
                onClick = { onFiles(); onDismiss() },
                modifier = Modifier.weight(1f),
            )
            ActionTile(
                icon = Icons.Default.ContentPaste,
                label = "Paste",
                onClick = { onPaste(); onDismiss() },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(spacing.s3))

        // Inline recent-media rail (Android renders thumbnails; other targets no-op).
        RecentMediaRail(onPick = { onPickMedia(it); onDismiss() })
    }
}

@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing
    val radii = KdTheme.radii

    Column(
        modifier = modifier
            .background(colors.bg2, radii.shapeMd)
            .clickable(onClick = onClick)
            .padding(vertical = spacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.s2),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = colors.text2,
            modifier = Modifier.height(24.dp),
        )
        Text(text = label, style = typography.body.copy(color = colors.text2))
    }
}
