package com.carlom.klardrop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.chat.DeviceChatMode
import com.carlom.klardrop.chat.DeviceChatScreen
import com.carlom.klardrop.common.communication.Reachability
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.components.ChatHeader
import com.carlom.klardrop.components.DeviceRow
import com.carlom.klardrop.components.KdAvatarStyle
import com.carlom.klardrop.components.KdDeviceKind
import com.carlom.klardrop.components.KdRowState
import com.carlom.klardrop.components.KdStatus
import com.carlom.klardrop.components.SectionHead
import com.carlom.klardrop.components.Sidebar
import com.carlom.klardrop.theme.KdTheme
import com.carlom.klardrop.theme.LocalContentInsets

private val DesktopSidebarWidth = 300.dp
private val TabletSidebarWidth = 320.dp
private val WideBreakpoint = 700.dp

@Composable
fun WideLayout(
    modifier: Modifier = Modifier,
    discoveryController: DiscoveryController,
    uiDependencies: UiDependencies,
    sidebarWidth: Dp = DesktopSidebarWidth,
) {
    val state by discoveryController.screenStateFlow.collectAsState()
    var activeDeviceId by remember { mutableStateOf<String?>(null) }
    var pendingLink by remember { mutableStateOf<DeviceUi?>(null) }
    var showAddDevicePicker by remember { mutableStateOf(false) }
    var showRenameSheet by remember { mutableStateOf(false) }
    var pendingForget by remember { mutableStateOf<DeviceUi?>(null) }

    val hasTrustedDevice = state.devices.any { it.trustStatus == TrustStatus.Trusted }
    LaunchedEffect(hasTrustedDevice) {
        if (hasTrustedDevice) showAddDevicePicker = false
    }

    LaunchedEffect(activeDeviceId) {
        discoveryController.setActiveChatDeviceId(activeDeviceId)
    }

    val keyboardFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { keyboardFocus.requestFocus() }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(keyboardFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val visible = state.devices
                if (visible.isEmpty()) return@onPreviewKeyEvent false
                val direction = when (event.key) {
                    Key.DirectionDown -> 1
                    Key.DirectionUp -> -1
                    else -> return@onPreviewKeyEvent false
                }
                val currentIndex = visible.indexOfFirst { it.deviceId == activeDeviceId }
                val nextIndex = when {
                    currentIndex < 0 -> if (direction > 0) 0 else visible.lastIndex
                    else -> ((currentIndex + direction) % visible.size + visible.size) % visible.size
                }
                val next = visible[nextIndex]
                discoveryController.onDeviceClick(next)
                activeDeviceId = next.deviceId
                true
            }
    ) {
        val resolvedSidebarWidth = when {
            maxWidth <= WideBreakpoint -> 0.dp
            sidebarWidth == DesktopSidebarWidth -> DesktopSidebarWidth
            else -> TabletSidebarWidth
        }

        WideContent(
            state = state,
            activeDeviceId = activeDeviceId,
            sidebarWidth = resolvedSidebarWidth,
            showSidebar = maxWidth > WideBreakpoint,
            callbacks = discoveryController,
            onDeviceSelected = { device ->
                discoveryController.onDeviceClick(device)
                activeDeviceId = device.deviceId
            },
            onAddDeviceClick = { showAddDevicePicker = true },
            onRenameLocalDevice = { showRenameSheet = true },
            onSendData = { device, data -> discoveryController.onSendData(device, data) },
            onNotificationDismissed = discoveryController::onNotificationDismissed,
            onNotificationPair = discoveryController::onNotificationPair,
            onForgetTrustedDevice = { device -> pendingForget = device },
            uiDependencies = uiDependencies,
            modifier = Modifier.fillMaxSize(),
        )
    }

    pendingForget?.let { device ->
        ForgetDeviceConfirmDialog(
            device = device,
            isLargeScreen = true,
            onConfirm = {
                discoveryController.onForgetDevice(device)
                pendingForget = null
            },
            onDismiss = { pendingForget = null },
        )
    }

    if (showRenameSheet) {
        val currentName = state.currentDeviceName?.takeIf { it.isNotBlank() }
            ?: state.systemDeviceName
            ?: ""
        // Desktop uses a Dialog rather than a bottom sheet — sheets are a
        // touch / small-screen pattern; on a large window they feel out of
        // place.
        RenameDialog(
            currentName = currentName,
            onDismiss = { showRenameSheet = false },
            onSave = { newName ->
                discoveryController.saveCustomDeviceName(newName)
                showRenameSheet = false
            },
        )
    }

    pendingLink?.let { device ->
        LinkDeviceConfirmDialog(
            device = device,
            isLargeScreen = true,
            onConfirm = {
                discoveryController.onAddToTrusted(device)
                pendingLink = null
            },
            onDismiss = { pendingLink = null }
        )
    }

    if (showAddDevicePicker) {
        AddDevicePickerSheet(
            candidates = state.devices.filter { it.trustStatus != TrustStatus.Trusted },
            isLargeScreen = true,
            onDismiss = { showAddDevicePicker = false },
            onPick = { device ->
                showAddDevicePicker = false
                pendingLink = device
            }
        )
    }

    state.pairingDialogState?.let { pairingState ->
        PairingApprovalDialog(
            state = pairingState,
            isLargeScreen = true,
            onDismiss = { discoveryController.dismissPairingDialog() }
        )
    }
}

@Composable
private fun WideContent(
    state: DiscoveryScreenState,
    activeDeviceId: String?,
    sidebarWidth: Dp,
    showSidebar: Boolean,
    callbacks: ReceiveNotificationsCallbacks,
    onDeviceSelected: (DeviceUi) -> Unit,
    onAddDeviceClick: () -> Unit,
    onRenameLocalDevice: () -> Unit,
    onSendData: (DeviceUi, OnDataToSend) -> Unit,
    onNotificationDismissed: (Int) -> Unit,
    onNotificationPair: (Int) -> Unit,
    onForgetTrustedDevice: (DeviceUi) -> Unit,
    uiDependencies: UiDependencies,
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors

    val trusted = state.devices.filter { it.trustStatus == TrustStatus.Trusted }
    val nearby = state.devices.filter { it.trustStatus != TrustStatus.Trusted }
    val activeDevice = activeDeviceId?.let { id -> state.devices.firstOrNull { it.deviceId == id } }

    val localDeviceName = state.currentDeviceName?.takeIf { it.isNotBlank() }
        ?: state.systemDeviceName
        ?: ""

    val topInset = LocalContentInsets.current.calculateTopPadding()
    val bodyGutter = 10.dp
    // macOS traffic lights can't be repositioned without a JNA libdispatch
    // bridge (the AppKit main thread isn't reachable from AWT's EDT on
    // stock OpenJDK). Sidebar therefore starts below the title bar so the
    // buttons sit in empty space above it. The chat pane on the right has
    // nothing to dodge and hugs the window top with just the gutter.
    val sidebarTopOffset = (topInset - bodyGutter).coerceAtLeast(0.dp)

    Row(
        modifier = modifier.padding(bodyGutter),
        horizontalArrangement = Arrangement.spacedBy(bodyGutter),
    ) {
        if (showSidebar) {
            Sidebar(
                width = sidebarWidth,
                modifier = Modifier.padding(top = sidebarTopOffset),
                yoursSection = {
                    SectionHead(
                        label = "My devices",
                        trailing = ({
                            IconButton(
                                onClick = onAddDeviceClick,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add device",
                                    tint = colors.text2,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }),
                    )
                    if (trusted.isEmpty()) {
                        AddDevicePromptRow(onClick = onAddDeviceClick)
                    } else {
                        trusted.forEach { device ->
                            SidebarDeviceRow(
                                device = device,
                                isActive = device.deviceId == activeDeviceId,
                                onClick = { onDeviceSelected(device) },
                                onDropData = { data -> onSendData(device, data) },
                                onForget = { onForgetTrustedDevice(device) },
                            )
                        }
                    }
                },
                nearbySection = {
                    SectionHead(
                        label = "Nearby",
                    )
                    if (nearby.isEmpty()) {
                        ScanningPlaceholderRow()
                    } else {
                        nearby.forEach { device ->
                            SidebarDeviceRow(
                                device = device,
                                isActive = device.deviceId == activeDeviceId,
                                onClick = { onDeviceSelected(device) },
                                onDropData = { data -> onSendData(device, data) },
                            )
                        }
                    }
                },
                localDeviceName = localDeviceName,
                localDeviceSub = null,
                onLocalDeviceClick = onRenameLocalDevice,
            )
        }

        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            if (activeDevice != null) {
                ChatHeader(
                    deviceName = activeDevice.deviceName,
                    subText = chatHeaderSubText(activeDevice),
                    kind = activeDevice.deviceType.toKdDeviceKind(),
                    avatarStyle = if (activeDevice.trustStatus == TrustStatus.Trusted)
                        KdAvatarStyle.Tinted else KdAvatarStyle.Neutral,
                    status = activeDevice.reachability.toKdStatus(),
                    isReachable = activeDevice.reachability == Reachability.Reachable,
                    toolbarVariant = true,
                )
            }

            IncomingBannerStack(
                state = state,
                callbacks = callbacks,
                onNotificationDismissed = onNotificationDismissed,
                onNotificationPair = onNotificationPair,
            )

            Box(modifier = Modifier.fillMaxSize()) {
                if (activeDeviceId != null && activeDevice != null) {
                    val chatViewModel = remember(activeDeviceId) {
                        uiDependencies.deviceChatViewModelFactory(activeDeviceId)
                    }
                    DeviceChatScreen(
                        deviceName = activeDevice.deviceName,
                        isOwned = activeDevice.trustStatus == TrustStatus.Trusted,
                        viewModel = chatViewModel,
                        onBackClicked = {},
                        onOpenFileRequest = { path -> chatViewModel.openFileClicked(path) },
                        onOpenUrlRequest = { url -> chatViewModel.openUrlClicked(url) },
                        mode = DeviceChatMode.Pane,
                    )
                } else {
                    WideEmptyPane()
                }
            }
        }
    }
}

@Composable
private fun SidebarDeviceRow(
    device: DeviceUi,
    isActive: Boolean,
    onClick: () -> Unit,
    onDropData: (OnDataToSend) -> Unit,
    onForget: (() -> Unit)? = null,
) {
    var dropHovered by remember { mutableStateOf(false) }

    val rowState = when {
        isActive -> KdRowState.Active
        dropHovered -> KdRowState.Hover
        device.reachability == Reachability.Unreachable -> KdRowState.Unreachable
        device.trustStatus == TrustStatus.Pairing -> KdRowState.Pairing
        else -> KdRowState.Idle
    }

    DeviceRow(
        name = device.deviceName,
        subText = deviceSubLabel(device),
        kind = device.deviceType.toKdDeviceKind(),
        avatarStyle = if (device.trustStatus == TrustStatus.Trusted)
            KdAvatarStyle.Tinted else KdAvatarStyle.Neutral,
        rowState = rowState,
        status = device.reachability.toKdStatus(),
        trailing = if (onForget != null) {
            { SidebarRowMenu(onForget = onForget) }
        } else null,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KdTheme.spacing.s2, vertical = 1.dp)
            .dropTargetForSending(
                onDataDropped = onDropData,
                onDragStateChange = { dropHovered = it },
            ),
    )
}

@Composable
private fun SidebarRowMenu(onForget: () -> Unit) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Device options",
                tint = colors.text2,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = "Forget this device",
                        style = typography.body.copy(color = colors.err),
                    )
                },
                onClick = {
                    expanded = false
                    onForget()
                },
            )
        }
    }
}

@Composable
private fun AddDevicePromptRow(onClick: () -> Unit) {
    val spacing = KdTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.s5, vertical = spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = KdTheme.colors.accent,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "Add a device",
            style = KdTheme.typography.caption.copy(color = KdTheme.colors.accent),
        )
    }
}

@Composable
private fun ScanningPlaceholderRow() {
    val spacing = KdTheme.spacing
    Text(
        text = "Scanning for nearby devices…",
        style = KdTheme.typography.caption.copy(color = KdTheme.colors.text3),
        modifier = Modifier.padding(horizontal = spacing.s5, vertical = spacing.s2),
    )
}

@Composable
private fun WideEmptyPane(modifier: Modifier = Modifier) {
    val spacing = KdTheme.spacing
    val colors = KdTheme.colors

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(spacing.s7),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.s5),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(colors.trustBg, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Devices,
                    contentDescription = null,
                    tint = colors.trust,
                    modifier = Modifier.size(36.dp),
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.s2),
            ) {
                Text(
                    text = "Pick a device to start",
                    style = KdTheme.typography.title.copy(color = colors.text),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Select any device from the sidebar to open its chat and share text or files.",
                    style = KdTheme.typography.body.copy(color = colors.text2),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

internal fun deviceIcon(type: DeviceType): ImageVector = when (type) {
    DeviceType.MOBILE -> Icons.Filled.Smartphone
    DeviceType.DESKTOP -> Icons.Filled.LaptopMac
    else -> Icons.Filled.Devices
}

private fun Reachability.toKdStatus(): KdStatus? = when (this) {
    Reachability.Reachable -> KdStatus.Ok
    Reachability.Unreachable -> KdStatus.Err
    Reachability.Probing,
    Reachability.Unknown -> null
}

// Chat header subline: only show when the connection is *not* healthy. The
// green dot already says "reachable", so writing "Reachable" next to it just
// repeats the same bit twice. Offline / connecting / pairing earn the line.
private fun chatHeaderSubText(device: DeviceUi): String = when {
    device.trustStatus == TrustStatus.Pairing -> "Pairing…"
    device.reachability == Reachability.Unreachable -> "Offline"
    device.reachability == Reachability.Probing -> "Connecting…"
    else -> ""
}

// Same rule for the sidebar row: nothing under the name when the device is
// trusted and reachable — the status dot is enough. Untrusted devices keep a
// hint because they don't show the dot.
private fun deviceSubLabel(device: DeviceUi): String? = when (device.trustStatus) {
    TrustStatus.Trusted -> when (device.reachability) {
        Reachability.Reachable, Reachability.Unknown -> null
        Reachability.Unreachable -> "Offline"
        Reachability.Probing -> "Connecting…"
    }
    TrustStatus.Pairing -> "Pairing…"
    TrustStatus.Untrusted, TrustStatus.Unknown -> null
}
