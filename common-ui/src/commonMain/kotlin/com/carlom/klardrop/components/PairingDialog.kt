package com.carlom.klardrop.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carlom.klardrop.theme.KdTheme
import com.carlom.klardrop.theme.kdElevation

// ---------------------------------------------------------------------------
// C12 · PairingDialog
// ---------------------------------------------------------------------------

/**
 * Modal pairing confirmation dialog.
 * Caller is responsible for showing this inside a Dialog composable.
 *
 * @param localDeviceName   name of the local device (left avatar label)
 * @param remoteDeviceName  name of the remote device (right avatar label)
 * @param localKind         kind of local device
 * @param remoteKind        kind of remote device
 * @param verificationCode  4-digit code derived from session key
 * @param onCancel          Cancel button tap
 * @param onConfirm         "Codes match" button tap
 * @param modifier          applied to the Surface
 */
@Composable
fun PairingDialog(
    localDeviceName: String,
    remoteDeviceName: String,
    localKind: KdDeviceKind = KdDeviceKind.Unknown,
    remoteKind: KdDeviceKind = KdDeviceKind.Unknown,
    verificationCode: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val radii = KdTheme.radii
    val spacing = KdTheme.spacing

    Surface(
        modifier = modifier.kdElevation(level = 3, shape = radii.shapeXl),
        shape = radii.shapeXl,
        color = colors.bg2,
    ) {
        Column(
            modifier = Modifier
                .padding(spacing.s6)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Two-avatar illustration
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s3),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    DeviceAvatar(
                        kind = localKind,
                        style = KdAvatarStyle.Tinted,
                        size = 64.dp,
                    )
                    Spacer(Modifier.height(spacing.s2))
                    Text(
                        text = localDeviceName,
                        style = typography.caption.copy(color = colors.text2),
                        maxLines = 1,
                    )
                }

                Text(
                    text = "↔",
                    style = typography.headline.copy(color = colors.text3),
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    DeviceAvatar(
                        kind = remoteKind,
                        style = KdAvatarStyle.Neutral,
                        size = 64.dp,
                    )
                    Spacer(Modifier.height(spacing.s2))
                    Text(
                        text = remoteDeviceName,
                        style = typography.caption.copy(color = colors.text2),
                        maxLines = 1,
                    )
                }
            }

            Spacer(Modifier.height(spacing.s5))

            Text(
                text = "Verify pairing code",
                style = typography.headline.copy(color = colors.text),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(spacing.s2))

            Text(
                text = "Make sure the code below matches on both devices before confirming.",
                style = typography.body.copy(color = colors.text2),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(spacing.s5))

            // 4-digit verification code — mono 22 sp, trust color, 0.05em tracking
            Text(
                text = verificationCode.take(4),
                style = typography.mono.copy(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = colors.trust,
                    letterSpacing = 1.1.sp, // 0.05em at 22sp
                ),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(spacing.s6))

            // Action buttons 1 : 1.4
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.s2),
            ) {
                OutlinedButton(
                    onClick = onCancel,
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
                    Text("Cancel", style = typography.body)
                }

                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .weight(1.4f)
                        .height(40.dp),
                    shape = radii.shapeMd,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.textInv,
                    ),
                ) {
                    Text("Codes match", style = typography.body)
                }
            }
        }
    }
}
