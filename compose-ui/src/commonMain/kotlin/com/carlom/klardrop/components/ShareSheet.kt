package com.carlom.klardrop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.DeviceUi
import com.carlom.klardrop.TrustStatus
import com.carlom.klardrop.common.communication.Reachability
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.theme.KdTheme
import com.carlom.klardrop.toKdDeviceKind

// ---------------------------------------------------------------------------
// C11 · ShareSheet
// ---------------------------------------------------------------------------

data class KdShareDevice(
    val id: String,
    val name: String,
    val kind: KdDeviceKind,
    val isTrusted: Boolean,
    val status: KdStatus? = null,
)

fun DeviceUi.toKdShareDevice(): KdShareDevice = KdShareDevice(
    id = deviceId,
    name = deviceName,
    kind = deviceType.toKdDeviceKind(),
    isTrusted = trustStatus == TrustStatus.Trusted,
    status = reachability.toKdStatus(),
)

fun Reachability.toKdStatus(): KdStatus? = when (this) {
    Reachability.Reachable -> KdStatus.Ok
    Reachability.Unreachable -> KdStatus.Err
    Reachability.Probing,
    Reachability.Unknown -> null
}

/**
 * Bottom-sheet content for sharing files.
 * Caller wraps this in a ModalBottomSheet.
 *
 * @param trustedDevices    horizontal scrollable row of "Yours" devices
 * @param nearbyDevices     vertical list of Nearby strangers
 * @param selectedId        currently selected device id
 * @param onSelectDevice    called when user taps a device
 * @param onSend            primary CTA tap — called with the selected device
 * @param modifier          applied to root Column
 */
@Composable
fun ShareSheet(
    trustedDevices: List<KdShareDevice>,
    nearbyDevices: List<KdShareDevice>,
    selectedId: String? = null,
    onSelectDevice: (KdShareDevice) -> Unit = {},
    onSend: (KdShareDevice?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val radii = KdTheme.radii
    val spacing = KdTheme.spacing

    val selectedDevice = (trustedDevices + nearbyDevices).firstOrNull { it.id == selectedId }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = spacing.s2),
    ) {
        // Handle
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 4.dp)
                .clip(radii.shapePill)
                .background(colors.text3.copy(alpha = 0.40f))
                .align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(spacing.s4))

        // "Your devices" section
        if (trustedDevices.isNotEmpty()) {
            SectionHead(label = "Your Devices", count = trustedDevices.size)

            LazyRow(
                contentPadding = PaddingValues(horizontal = spacing.s4),
                horizontalArrangement = Arrangement.spacedBy(spacing.s3),
            ) {
                items(trustedDevices) { device ->
                    TrustedDeviceTile(
                        device = device,
                        isSelected = device.id == selectedId,
                        onClick = { onSelectDevice(device) },
                    )
                }
            }

            Spacer(Modifier.height(spacing.s4))
        }

        // "Nearby" section
        if (nearbyDevices.isNotEmpty()) {
            SectionHead(label = "Nearby", count = nearbyDevices.size)

            Column {
                nearbyDevices.forEach { device ->
                    DeviceRow(
                        name = device.name,
                        subText = "1-time send · accept on receiver",
                        kind = device.kind,
                        avatarStyle = KdAvatarStyle.Neutral,
                        status = device.status,
                        rowState = if (device.id == selectedId) KdRowState.Active else KdRowState.Idle,
                        onClick = { onSelectDevice(device) },
                        modifier = Modifier.padding(horizontal = spacing.s3),
                    )
                }
            }
        }

        Spacer(Modifier.height(spacing.s4))

        // Primary CTA
        Button(
            onClick = { onSend(selectedDevice) },
            enabled = selectedDevice != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = spacing.s4),
            shape = radii.shapeMd,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.textInv,
                disabledContainerColor = colors.bg3,
                disabledContentColor = colors.text3,
            ),
        ) {
            Text(
                text = if (selectedDevice != null) "Send to ${selectedDevice.name}" else "Select a device",
                style = typography.body,
            )
        }

        Spacer(Modifier.height(spacing.s7))
    }
}

@Composable
private fun TrustedDeviceTile(
    device: KdShareDevice,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val radii = KdTheme.radii
    val spacing = KdTheme.spacing

    Column(
        modifier = Modifier
            .width(92.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.s2),
    ) {
        // Avatar with optional selected border
        Box(
            modifier = Modifier
                .size(48.dp)
                .then(
                    if (isSelected) {
                        Modifier.border(
                            width = 2.dp,
                            color = colors.trust,
                            shape = CircleShape,
                        )
                    } else Modifier,
                )
                .padding(if (isSelected) 2.dp else 0.dp),
        ) {
            DeviceAvatar(
                kind = device.kind,
                style = KdAvatarStyle.Tinted,
                status = device.status,
                size = 48.dp,
            )
        }

        Text(
            text = device.name,
            style = typography.caption.copy(color = colors.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
