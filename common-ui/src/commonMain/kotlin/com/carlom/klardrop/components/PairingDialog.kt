package com.carlom.klardrop.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.sp
import com.carlom.klardrop.theme.KdTheme
import com.carlom.klardrop.theme.kdElevation

// ---------------------------------------------------------------------------
// C12 · PairingDialog
// ---------------------------------------------------------------------------

/**
 * Modal pairing confirmation dialog. Caller wraps in a Dialog composable.
 *
 * Two layouts:
 *  - Simple (default): single remote-device avatar + "Pair with {device}?".
 *  - Verification: pass `verificationCode` and `localDeviceName` to render the
 *    spec's two-avatar layout with a 4-digit code that must match on both ends.
 *    Reserved for future use when the pairing protocol exposes a session-derived code.
 */
@Composable
fun PairingDialog(
    remoteDeviceName: String,
    remoteKind: KdDeviceKind = KdDeviceKind.Unknown,
    localDeviceName: String? = null,
    localKind: KdDeviceKind = KdDeviceKind.Unknown,
    verificationCode: String? = null,
    body: String? = null,
    confirmLabel: String? = null,
    cancelLabel: String = "Cancel",
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val radii = KdTheme.radii
    val spacing = KdTheme.spacing

    val verifying = verificationCode != null && localDeviceName != null
    val title = if (verifying) "Verify pairing code" else "Pair with $remoteDeviceName?"
    val resolvedBody = body ?: if (verifying) {
        "Make sure the code below matches on both devices before confirming."
    } else {
        "Accept this device into Your devices? You'll be able to send files and messages without prompting."
    }
    val resolvedConfirm = confirmLabel ?: if (verifying) "Codes match" else "Accept"

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
            if (verifying) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
                ) {
                    AvatarLabel(localDeviceName!!, localKind, KdAvatarStyle.Tinted)
                    Text(
                        text = "↔",
                        style = typography.headline.copy(color = colors.text3),
                    )
                    AvatarLabel(remoteDeviceName, remoteKind, KdAvatarStyle.Neutral)
                }
            } else {
                DeviceAvatar(
                    kind = remoteKind,
                    style = KdAvatarStyle.Neutral,
                    size = spacing.heroAvatar,
                )
            }

            Spacer(Modifier.height(spacing.s5))

            Text(
                text = title,
                style = typography.headline.copy(color = colors.text),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(spacing.s2))

            Text(
                text = resolvedBody,
                style = typography.body.copy(color = colors.text2),
                textAlign = TextAlign.Center,
            )

            if (verifying) {
                Spacer(Modifier.height(spacing.s5))
                Text(
                    text = verificationCode!!.take(4),
                    style = typography.mono.copy(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = colors.trust,
                        letterSpacing = 1.1.sp,
                    ),
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(spacing.s6))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.s2),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .height(spacing.s8),
                    shape = radii.shapeMd,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
                ) {
                    Text(cancelLabel, style = typography.body)
                }

                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .weight(1.4f)
                        .height(spacing.s8),
                    shape = radii.shapeMd,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.textInv,
                    ),
                ) {
                    Text(resolvedConfirm, style = typography.body)
                }
            }
        }
    }
}

@Composable
private fun AvatarLabel(name: String, kind: KdDeviceKind, style: KdAvatarStyle) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        DeviceAvatar(kind = kind, style = style, size = spacing.s9 + spacing.s4)
        Spacer(Modifier.height(spacing.s2))
        Text(
            text = name,
            style = typography.caption.copy(color = colors.text2),
            maxLines = 1,
        )
    }
}
