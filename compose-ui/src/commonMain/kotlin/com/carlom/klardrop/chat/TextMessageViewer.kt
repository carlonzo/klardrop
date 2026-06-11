package com.carlom.klardrop.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.components.SectionHead
import com.carlom.klardrop.theme.KdTheme

/**
 * Shows the full text of a message that's too long to fit a bubble.
 *
 * Wide layouts (desktop / tablet pane) get a centered [androidx.compose.ui.window.Dialog];
 * mobile gets a [ModalBottomSheet] — mirroring the device-picker / pairing pattern
 * used elsewhere in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextMessageViewer(
    text: String,
    isLargeScreen: Boolean,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KdTheme.colors
    val radii = KdTheme.radii

    if (isLargeScreen) {
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = radii.shapeXl,
                color = colors.bg1,
                modifier = Modifier.widthIn(max = 520.dp),
            ) {
                TextMessageViewerContent(text = text, onCopy = onCopy, onDismiss = onDismiss)
            }
        }
    } else {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            shape = radii.shapeXl.copy(
                bottomStart = ZeroCornerSize,
                bottomEnd = ZeroCornerSize,
            ),
            containerColor = colors.bg1,
        ) {
            TextMessageViewerContent(text = text, onCopy = onCopy, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun TextMessageViewerContent(
    text: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.s4, vertical = spacing.s3),
    ) {
        SectionHead(label = "Message")

        Spacer(Modifier.size(spacing.s2))

        SelectionContainer {
            Text(
                text = text,
                style = typography.body.copy(color = colors.text),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }

        Spacer(Modifier.size(spacing.s3))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colors.accent,
                )
                Spacer(Modifier.width(spacing.s1))
                Text("Copy", style = typography.body.copy(color = colors.accent))
            }
            TextButton(onClick = onDismiss) {
                Text("Close", style = typography.body.copy(color = colors.text2))
            }
        }
    }
}
