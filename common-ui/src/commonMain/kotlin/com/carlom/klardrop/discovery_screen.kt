package com.carlom.klardrop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.common.communication.message.ConnectionInfoMessage
import com.carlom.klardrop.common.permissions.Capability
import com.carlom.klardrop.components.DeviceRow
import com.carlom.klardrop.components.KdAvatarStyle
import com.carlom.klardrop.components.KdBannerTone
import com.carlom.klardrop.components.KdDeviceKind
import com.carlom.klardrop.components.KdRowState
import com.carlom.klardrop.components.KdShareDevice
import com.carlom.klardrop.components.KdStatus
import com.carlom.klardrop.components.SectionHead
import com.carlom.klardrop.components.ShareSheet
import com.carlom.klardrop.components.VisibilityPill
import com.carlom.klardrop.components.KdVisibilityState
import com.carlom.klardrop.components.Banner
import com.carlom.klardrop.theme.KdEaseOut
import com.carlom.klardrop.theme.KdTheme
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.PickerResultLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    modifier: Modifier = Modifier,
    isLargeScreen: Boolean = false,
    discoveryController: DiscoveryController,
    uiDependencies: UiDependencies,
    onNavigateToChat: (deviceId: String, deviceName: String) -> Unit,
    onRequestCapability: (Capability) -> Unit = {},
) {
    val discoveryState by discoveryController.screenStateFlow.collectAsState()
    val permissionsState by discoveryController.permissionsState.collectAsState()

    var deviceUiForShare by remember { mutableStateOf<DeviceUi?>(null) }
    var showShareSheet by remember { mutableStateOf(false) }
    val shareSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val filePickerLauncher = rememberFilePickerLauncher(mode = FileKitMode.Multiple()) { files ->
        if (files.isNullOrEmpty()) return@rememberFilePickerLauncher
        deviceUiForShare?.let { device ->
            discoveryController.onSendData(device, OnDataToSend.FilesList(files))
        }
        showShareSheet = false
    }

    val picturesPickerLauncher = rememberFilePickerLauncher(
        mode = FileKitMode.Multiple(),
        type = FileKitType.ImageAndVideo,
    ) { files ->
        if (files.isNullOrEmpty()) return@rememberFilePickerLauncher
        deviceUiForShare?.let { device ->
            discoveryController.onSendData(device, OnDataToSend.FilesList(files))
        }
        showShareSheet = false
    }

    val dashboardListener = remember(discoveryController, onNavigateToChat) {
        object : OnDeviceActionListener by discoveryController {
            override fun onDeviceClick(deviceUi: DeviceUi) {
                discoveryController.onDeviceClick(deviceUi)
                onNavigateToChat(deviceUi.deviceId, deviceUi.deviceName)
            }
        }
    }

    DiscoveryDashboard(
        modifier = modifier,
        isLargeScreen = isLargeScreen,
        state = discoveryState,
        permissionsState = permissionsState,
        onDeviceActionListener = dashboardListener,
        receiveCallbacks = discoveryController,
        onDeviceRename = { newName -> discoveryController.saveCustomDeviceName(newName) },
        onRequestCapability = onRequestCapability,
    )

    if (showShareSheet) {
        val devices = discoveryState.devices
        val trusted = devices.filter { it.trustStatus == TrustStatus.Trusted }
        val nearby = devices.filter { it.trustStatus != TrustStatus.Trusted }

        val radii = KdTheme.radii
        val colors = KdTheme.colors

        ModalBottomSheet(
            onDismissRequest = { showShareSheet = false },
            sheetState = shareSheetState,
            shape = radii.shapeXl.copy(
                bottomStart = androidx.compose.foundation.shape.ZeroCornerSize,
                bottomEnd = androidx.compose.foundation.shape.ZeroCornerSize,
            ),
            containerColor = colors.bg1,
        ) {
            var selectedId by remember { mutableStateOf<String?>(deviceUiForShare?.deviceId) }

            ShareSheet(
                trustedDevices = trusted.map { it.toKdShareDevice() },
                nearbyDevices = nearby.map { it.toKdShareDevice() },
                selectedId = selectedId,
                onSelectDevice = { device -> selectedId = device.id },
                onSend = { selected ->
                    if (selected != null) {
                        val deviceUi = devices.firstOrNull { it.deviceId == selected.id }
                        if (deviceUi != null) {
                            filePickerLauncher.launch()
                            deviceUiForShare = deviceUi
                        }
                    }
                    showShareSheet = false
                },
            )
        }
    }

    discoveryState.pairingDialogState?.let { pairingState ->
        PairingApprovalDialog(
            state = pairingState,
            onDismiss = { discoveryController.dismissPairingDialog() }
        )
    }
}

@Composable
private fun DiscoveryDashboard(
    modifier: Modifier = Modifier,
    isLargeScreen: Boolean = false,
    state: DiscoveryScreenState,
    permissionsState: com.carlom.klardrop.common.permissions.PermissionsState,
    onDeviceActionListener: OnDeviceActionListener,
    receiveCallbacks: ReceiveNotificationsCallbacks,
    onDeviceRename: (String) -> Unit,
    onRequestCapability: (Capability) -> Unit,
) {
    var showRenameSheet by remember { mutableStateOf(false) }
    var pendingLink by remember { mutableStateOf<DeviceUi?>(null) }
    var showAddDevicePicker by remember { mutableStateOf(false) }

    val currentDeviceName = state.currentDeviceName ?: state.systemDeviceName ?: ""
    val devices = state.devices
    val trusted = devices.filter { it.trustStatus == TrustStatus.Trusted }
    val others = devices.filter { it.trustStatus != TrustStatus.Trusted }

    val gridListener = remember(onDeviceActionListener) {
        object : OnDeviceActionListener by onDeviceActionListener {
            override fun onAddToTrusted(deviceUi: DeviceUi) {
                pendingLink = deviceUi
            }
        }
    }

    LaunchedEffect(trusted.isNotEmpty()) {
        if (trusted.isNotEmpty()) showAddDevicePicker = false
    }

    Box(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            DiscoveryHeader(
                currentDeviceName = currentDeviceName,
                onEditIdentity = { showRenameSheet = true },
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                val spacing = KdTheme.spacing

                VisibilityPill(
                    state = KdVisibilityState.Visible(ssid = "Wi-Fi"),
                    modifier = Modifier.padding(
                        start = spacing.s5,
                        end = spacing.s5,
                        top = spacing.s2,
                        bottom = spacing.s2,
                    ),
                )

                val motion = KdTheme.motion
                AnimatedVisibility(
                    visible = permissionsState.capabilities.any { (_, status) ->
                        status == com.carlom.klardrop.common.permissions.CapabilityStatus.Denied ||
                            status == com.carlom.klardrop.common.permissions.CapabilityStatus.Unknown
                    } || permissionsState.educationalNotes.isNotEmpty(),
                    enter = expandVertically(tween(motion.dBase, easing = KdEaseOut)),
                    exit = shrinkVertically(tween(motion.dBase)),
                ) {
                    PermissionsPanel(
                        state = permissionsState,
                        onRequestCapability = onRequestCapability,
                    )
                }

                IncomingBannerStack(
                    state = state,
                    callbacks = receiveCallbacks,
                )

                YourDevicesSection(
                    trusted = trusted,
                    isLargeScreen = isLargeScreen,
                    onDeviceActionListener = gridListener,
                    onAddDeviceClick = { showAddDevicePicker = true },
                )

                NearbySection(
                    devices = others,
                    isLargeScreen = isLargeScreen,
                    onDeviceActionListener = gridListener,
                )

                Spacer(Modifier.height(spacing.s6))
            }
        }
    }

    if (showRenameSheet) {
        RenameSheet(
            currentName = currentDeviceName,
            onDismiss = { showRenameSheet = false },
            onSave = {
                onDeviceRename(it)
                showRenameSheet = false
            },
        )
    }

    pendingLink?.let { device ->
        LinkDeviceConfirmDialog(
            device = device,
            onConfirm = {
                onDeviceActionListener.onAddToTrusted(device)
                pendingLink = null
            },
            onDismiss = { pendingLink = null },
        )
    }

    if (showAddDevicePicker) {
        AddDevicePickerSheet(
            candidates = devices.filter { it.trustStatus != TrustStatus.Trusted },
            onDismiss = { showAddDevicePicker = false },
            onPick = { device ->
                showAddDevicePicker = false
                pendingLink = device
            },
        )
    }
}

@Composable
private fun DiscoveryHeader(
    currentDeviceName: String,
    onEditIdentity: () -> Unit,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.s4, vertical = spacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(spacing.s8))
                .clickable(onClick = onEditIdentity)
                .padding(vertical = spacing.s2, horizontal = spacing.s2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s2),
        ) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "Edit device name",
                tint = colors.text2,
                modifier = Modifier.size(spacing.s4),
            )
            Text(
                text = currentDeviceName.ifEmpty { "This device" },
                style = typography.title.copy(color = colors.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(spacing.s2))

        Box(
            modifier = Modifier
                .size(spacing.s8)
                .clip(CircleShape)
                .background(colors.bg2)
                .clickable { },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = colors.text2,
                modifier = Modifier.size(spacing.s5),
            )
        }
    }
}

@Composable
private fun YourDevicesSection(
    trusted: List<DeviceUi>,
    isLargeScreen: Boolean,
    onDeviceActionListener: OnDeviceActionListener,
    onAddDeviceClick: () -> Unit,
) {
    val spacing = KdTheme.spacing
    val colors = KdTheme.colors
    val typography = KdTheme.typography

    SectionHead(
        label = "Your devices",
        count = trusted.size,
        trailing = if (trusted.isEmpty()) {
            {
                Text(
                    text = "Pair a device",
                    style = typography.caption.copy(color = colors.accent),
                    modifier = Modifier
                        .clickable(onClick = onAddDeviceClick)
                        .padding(horizontal = spacing.s1),
                )
            }
        } else {
            null
        },
    )

    if (trusted.isEmpty()) {
        AddDevicePlaceholderSurface(
            isLargeScreen = isLargeScreen,
            onClick = onAddDeviceClick,
            modifier = Modifier.padding(start = spacing.s3, end = spacing.s3, bottom = spacing.s2),
        )
    } else {
        trusted.forEach { device ->
            DeviceRow(
                name = device.deviceName,
                subText = deviceSubText(device),
                kind = device.deviceType.toKdDeviceKind(),
                avatarStyle = KdAvatarStyle.Tinted,
                rowState = deviceRowState(device),
                status = device.reachabilityStatus(),
                trailing = if (device.hasUnreadMessages) {
                    {
                        UnreadBadge()
                    }
                } else null,
                onClick = { onDeviceActionListener.onDeviceClick(device) },
                modifier = Modifier.padding(horizontal = spacing.s3),
            )
        }
    }
}

@Composable
private fun NearbySection(
    devices: List<DeviceUi>,
    isLargeScreen: Boolean,
    onDeviceActionListener: OnDeviceActionListener,
) {
    val spacing = KdTheme.spacing
    val colors = KdTheme.colors
    val typography = KdTheme.typography

    SectionHead(
        label = "Nearby",
        count = devices.size,
        trailing = {
            ScanningTicker()
        },
    )

    if (devices.isEmpty()) {
        NearbyEmptyHint(modifier = Modifier.padding(start = spacing.s3, end = spacing.s3, bottom = spacing.s2))
    } else {
        devices.forEach { device ->
            DeviceRow(
                name = device.deviceName,
                subText = deviceSubText(device),
                kind = device.deviceType.toKdDeviceKind(),
                avatarStyle = KdAvatarStyle.Neutral,
                rowState = deviceRowState(device),
                status = device.reachabilityStatus(),
                trailing = {
                    if (device.trustStatus == TrustStatus.Untrusted || device.trustStatus == TrustStatus.Unknown) {
                        PairButton(onClick = { onDeviceActionListener.onAddToTrusted(device) })
                    }
                },
                onClick = { onDeviceActionListener.onDeviceClick(device) },
                modifier = Modifier.padding(horizontal = spacing.s3),
            )
        }
    }
}

@Composable
private fun ScanningTicker() {
    val colors = KdTheme.colors
    val typography = KdTheme.typography

    val transition = rememberInfiniteTransition()
    val dotAlpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse,
        ),
    )

    Text(
        text = "scanning",
        style = typography.caption.copy(
            color = colors.text3.copy(alpha = dotAlpha),
        ),
    )
}

@Composable
private fun NearbyEmptyHint(modifier: Modifier = Modifier) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val radii = KdTheme.radii
    val spacing = KdTheme.spacing

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(radii.shapeLg)
            .border(width = 1.dp, color = colors.border, shape = radii.shapeLg)
            .padding(spacing.s4),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No devices nearby. Make sure Klardrop is open on the same Wi-Fi.",
            style = typography.caption.copy(color = colors.text3),
        )
    }
}

@Composable
private fun PairButton(onClick: () -> Unit) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val radii = KdTheme.radii
    val spacing = KdTheme.spacing

    Box(
        modifier = androidx.compose.ui.Modifier
            .clip(radii.shapeMd)
            .background(colors.bg2)
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.s2, vertical = spacing.s1),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+",
            style = typography.body.copy(color = colors.text2),
        )
    }
}

@Composable
private fun UnreadBadge() {
    val colors = KdTheme.colors
    val spacing = KdTheme.spacing

    Box(
        modifier = androidx.compose.ui.Modifier
            .size(spacing.s2)
            .clip(CircleShape)
            .background(colors.accent),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameSheet(
    currentName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing
    val radii = KdTheme.radii

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var newName by remember { mutableStateOf(currentName) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = radii.shapeXl.copy(
            bottomStart = androidx.compose.foundation.shape.ZeroCornerSize,
            bottomEnd = androidx.compose.foundation.shape.ZeroCornerSize,
        ),
        containerColor = colors.bg1,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.s6, end = spacing.s6, bottom = spacing.s6),
            verticalArrangement = Arrangement.spacedBy(spacing.s4),
        ) {
            Text(
                text = "Rename device",
                style = typography.headline.copy(color = colors.text),
            )
            Text(
                text = "This is how others will see you when sharing.",
                style = typography.body.copy(color = colors.text2),
            )

            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = {
                    Text("Device name", style = typography.caption.copy(color = colors.text2))
                },
                textStyle = typography.body.copy(color = colors.text),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardActions = KeyboardActions(onDone = { onSave(newName) }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.border,
                    cursorColor = colors.accent,
                ),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.s2, Alignment.End),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", style = typography.body.copy(color = colors.text2))
                }
                TextButton(onClick = { onSave(newName) }) {
                    Text("Save", style = typography.body.copy(color = colors.accent))
                }
            }
        }
    }
}

private fun deviceSubText(device: DeviceUi): String = when (device.trustStatus) {
    TrustStatus.Trusted -> when (device.activityState) {
        is ActivityState.Sending -> "Sending…"
        is ActivityState.SentCompleted -> if ((device.activityState as ActivityState.SentCompleted).error) "Failed" else "Done"
        ActivityState.Idle -> null
    } ?: when (device.reachability) {
        com.carlom.klardrop.common.communication.Reachability.Reachable -> "Online"
        com.carlom.klardrop.common.communication.Reachability.Unreachable -> "Offline"
        else -> null
    } ?: "Trusted"
    TrustStatus.Pairing -> "Pairing…"
    TrustStatus.Untrusted -> "Nearby"
    TrustStatus.Unknown -> "Nearby"
}

private fun DeviceUi.reachabilityStatus(): KdStatus? = when (reachability) {
    com.carlom.klardrop.common.communication.Reachability.Reachable -> KdStatus.Ok
    com.carlom.klardrop.common.communication.Reachability.Unreachable -> KdStatus.Err
    else -> null
}

private fun deviceRowState(device: DeviceUi): KdRowState = when {
    device.activityState is ActivityState.Sending -> KdRowState.Active
    device.trustStatus == TrustStatus.Pairing -> KdRowState.Pairing
    device.reachability == com.carlom.klardrop.common.communication.Reachability.Unreachable -> KdRowState.Unreachable
    device.trustStatus == TrustStatus.Trusted -> KdRowState.Idle
    else -> KdRowState.PairPrompt
}

private fun DeviceUi.toKdShareDevice(): KdShareDevice = KdShareDevice(
    id = deviceId,
    name = deviceName,
    kind = deviceType.toKdDeviceKind(),
    isTrusted = trustStatus == TrustStatus.Trusted,
    status = reachabilityStatus(),
)
