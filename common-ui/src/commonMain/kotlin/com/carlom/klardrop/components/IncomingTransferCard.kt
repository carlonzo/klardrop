package com.carlom.klardrop.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.theme.KdTheme

// ---------------------------------------------------------------------------
// C10 · IncomingTransferCard
// ---------------------------------------------------------------------------

/**
 * Floating card that appears when a stranger requests a transfer.
 *
 * @param senderName    display name of the sending device
 * @param fileName      the file being sent (optional)
 * @param fileSize      human-readable size string (optional)
 * @param onAccept      Accept button tap
 * @param onDecline     Decline button tap
 * @param modifier      applied to the Surface
 */
@Composable
fun IncomingTransferCard(
    senderName: String,
    fileName: String? = null,
    fileSize: String? = null,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val radii = KdTheme.radii
    val spacing = KdTheme.spacing

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = radii.shapeLg,
        color = colors.bg2,
        tonalElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(spacing.s4)) {
            // Sender info row
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceAvatar(
                    kind = KdDeviceKind.Unknown,
                    style = KdAvatarStyle.Neutral,
                    size = 40.dp,
                )

                Spacer(Modifier.width(spacing.s3))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = senderName,
                        style = typography.body.copy(color = colors.text),
                    )
                    Text(
                        text = "wants to send you a file",
                        style = typography.caption.copy(color = colors.text2),
                    )
                }
            }

            // File info
            if (fileName != null) {
                Spacer(Modifier.height(spacing.s3))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = fileName,
                        style = typography.mono.copy(color = colors.text),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    if (fileSize != null) {
                        Spacer(Modifier.width(spacing.s2))
                        Text(
                            text = fileSize,
                            style = typography.caption.copy(color = colors.text3),
                        )
                    }
                }
            }

            Spacer(Modifier.height(spacing.s4))

            // Action buttons at 1 : 1.4 width ratio
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.s2),
            ) {
                // Decline — ghost, weight 1
                OutlinedButton(
                    onClick = onDecline,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    shape = radii.shapeMd,
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = colors.border,
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = colors.text,
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                ) {
                    Text("Decline", style = typography.body)
                }

                // Accept — primary, weight 1.4
                Button(
                    onClick = onAccept,
                    modifier = Modifier
                        .weight(1.4f)
                        .height(40.dp),
                    shape = radii.shapeMd,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.textInv,
                    ),
                ) {
                    Text("Accept", style = typography.body)
                }
            }
        }
    }
}
