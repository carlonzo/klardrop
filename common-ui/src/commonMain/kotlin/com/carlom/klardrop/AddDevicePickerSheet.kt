package com.carlom.klardrop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.components.DeviceAvatar
import com.carlom.klardrop.components.DeviceRow
import com.carlom.klardrop.components.KdAvatarStyle
import com.carlom.klardrop.components.KdDeviceKind
import com.carlom.klardrop.components.KdRowState
import com.carlom.klardrop.components.SectionHead
import com.carlom.klardrop.theme.KdTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddDevicePickerSheet(
    candidates: List<DeviceUi>,
    isLargeScreen: Boolean,
    onDismiss: () -> Unit,
    onPick: (DeviceUi) -> Unit,
) {
    val colors = KdTheme.colors
    val radii = KdTheme.radii

    if (isLargeScreen) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = radii.shapeXl,
                color = colors.bg1,
            ) {
                AddDevicePickerContent(
                    candidates = candidates,
                    onDismiss = onDismiss,
                    onPick = onPick,
                )
            }
        }
    } else {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            shape = radii.shapeXl.copy(
                bottomStart = androidx.compose.foundation.shape.ZeroCornerSize,
                bottomEnd = androidx.compose.foundation.shape.ZeroCornerSize,
            ),
            containerColor = colors.bg1,
        ) {
            AddDevicePickerContent(
                candidates = candidates,
                onDismiss = onDismiss,
                onPick = onPick,
            )
        }
    }
}

@Composable
private fun AddDevicePickerContent(
    candidates: List<DeviceUi>,
    onDismiss: () -> Unit,
    onPick: (DeviceUi) -> Unit,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = spacing.s6),
    ) {
        SectionHead(
            label = "Add a device",
            modifier = Modifier.padding(horizontal = spacing.s1),
        )

        Text(
            text = "Pick a nearby device to pair with. Trusted devices auto-share clipboard, Wi-Fi logins and notifications.",
            style = typography.caption.copy(color = colors.text2),
            modifier = Modifier.padding(start = spacing.s5, end = spacing.s5, bottom = spacing.s3),
        )

        if (candidates.isEmpty()) {
            Text(
                text = "No nearby devices right now. Make sure Klardrop is open on the device you want to pair, and that both are on the same Wi-Fi.",
                style = typography.body.copy(color = colors.text2),
                modifier = Modifier.padding(horizontal = spacing.s5),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = spacing.s7 * 11),
            ) {
                items(items = candidates, key = { it.deviceId }) { device ->
                    DeviceRow(
                        name = device.deviceName,
                        subText = if (device.trustStatus == TrustStatus.Pairing) "Pairing…" else "Tap to pair",
                        kind = device.deviceType.toKdDeviceKind(),
                        avatarStyle = KdAvatarStyle.Neutral,
                        rowState = if (device.trustStatus == TrustStatus.Pairing) KdRowState.Pairing else KdRowState.Idle,
                        onClick = {
                            if (device.trustStatus != TrustStatus.Pairing) {
                                onPick(device)
                            }
                        },
                        modifier = Modifier.padding(horizontal = spacing.s3),
                    )
                }
            }
        }

        Spacer(Modifier.height(spacing.s2))

        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = spacing.s4),
        ) {
            TextButton(onClick = onDismiss) {
                Text("Close", style = typography.body.copy(color = colors.accent))
            }
        }
    }
}

@Composable
internal fun AddDevicePlaceholderSurface(
    isLargeScreen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing
    val radii = KdTheme.radii

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(radii.shapeLg)
            .background(colors.bg1)
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.s3, vertical = spacing.s3),
        contentAlignment = if (isLargeScreen) Alignment.CenterStart else Alignment.Center,
    ) {
        if (isLargeScreen) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s3),
            ) {
                DeviceAvatar(
                    kind = KdDeviceKind.Unknown,
                    style = KdAvatarStyle.Neutral,
                    size = spacing.s7,
                )
                Column(verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
                    Text(
                        text = "Add a device",
                        style = typography.body.copy(color = colors.text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Pair from nearby devices",
                        style = typography.caption.copy(color = colors.text2),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.s2),
                modifier = Modifier.padding(vertical = spacing.s3),
            ) {
                DeviceAvatar(
                    kind = KdDeviceKind.Unknown,
                    style = KdAvatarStyle.Neutral,
                    size = spacing.s7,
                )
                Text(
                    text = "Add a device",
                    style = typography.body.copy(color = colors.text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Pair from nearby",
                    style = typography.caption.copy(color = colors.text2),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun SidebarAddDeviceRow(onClick: () -> Unit) {
    val colors = KdTheme.colors
    val spacing = KdTheme.spacing

    DeviceRow(
        name = "Add a device",
        subText = "Pair from nearby",
        kind = KdDeviceKind.Unknown,
        avatarStyle = KdAvatarStyle.Neutral,
        rowState = KdRowState.Idle,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = spacing.s2),
    )
}

internal fun DeviceType.toKdDeviceKind(): KdDeviceKind = when (this) {
    DeviceType.MOBILE -> KdDeviceKind.Android
    DeviceType.DESKTOP -> KdDeviceKind.Pc
    DeviceType.UNKNOWN -> KdDeviceKind.Unknown
}
