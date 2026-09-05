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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
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
import com.carlom.klardrop.common.connectivity.ConnectivityRestriction
import com.carlom.klardrop.common.connectivity.ConnectivityRestrictions
import com.carlom.klardrop.common.permissions.Capability
import com.carlom.klardrop.components.DeviceRow
import com.carlom.klardrop.components.KdAvatarStyle
import com.carlom.klardrop.components.KdBannerTone
import com.carlom.klardrop.components.KdDeviceKind
import com.carlom.klardrop.components.KdRowState
import com.carlom.klardrop.components.KdRowVariant
import com.carlom.klardrop.components.KdShareDevice
import com.carlom.klardrop.components.KdStatus
import com.carlom.klardrop.components.SectionHead
import com.carlom.klardrop.components.ShareSheet
import com.carlom.klardrop.components.Banner
import com.carlom.klardrop.components.UpdateBanner
import com.carlom.klardrop.components.UpdateSettingsSection
import com.carlom.klardrop.components.toKdShareDevice
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
    onRequestExemption: (ConnectivityRestriction) -> Unit = {},
) {
    val discoveryState by discoveryController.screenStateFlow.collectAsState()
    val permissionsState by discoveryController.permissionsState.collectAsState()
    val connectivityRestrictions by discoveryController.connectivityRestrictions.collectAsState()
    val backgroundDiscoveryEnabled by discoveryController.backgroundDiscoveryEnabled.collectAsState()

    val updateBannerController = remember(uiDependencies) { uiDependencies.updateBannerController() }
    val updateStatus by updateBannerController.status.collectAsState()
    val updateInstallProgress by updateBannerController.installProgress.collectAsState()

    var deviceUiForShare by remember { mutableStateOf<DeviceUi?>(null) }
    var showShareSheet by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
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
        connectivityRestrictions = connectivityRestrictions,
        onDeviceActionListener = dashboardListener,
        receiveCallbacks = discoveryController,
        onDeviceRename = { newName -> discoveryController.saveCustomDeviceName(newName) },
        onRequestCapability = onRequestCapability,
        onRequestExemption = onRequestExemption,
        onSettingsClick = { showSettings = true },
        updateBanner = {
            UpdateBanner(
                status = updateStatus,
                installProgress = updateInstallProgress,
                onAction = updateBannerController::onAction,
                onRestart = updateBannerController::onRestart,
            )
        },
    )

    if (showSettings) {
        SettingsSheet(
            backgroundDiscoveryEnabled = backgroundDiscoveryEnabled,
            showBackgroundDiscoveryToggle = discoveryController.supportsBackgroundDiscovery,
            onBackgroundDiscoveryChange = { discoveryController.setBackgroundDiscoveryEnabled(it) },
            onDismiss = { showSettings = false },
            updates = {
                UpdateSettingsSection(
                    visible = updateBannerController.updatesSupported,
                    currentVersion = updateBannerController.currentVersion,
                    releaseChannel = updateBannerController.releaseChannel,
                    status = updateStatus,
                    installProgress = updateInstallProgress,
                    onCheck = updateBannerController::recheck,
                    onAction = updateBannerController::onAction,
                    onRestart = updateBannerController::onRestart,
                    onOpenUrl = updateBannerController::openUrl,
                )
            },
        )
    }

    if (showShareSheet) {
        val devices = discoveryState.devices
        val trusted = devices.filter { it.trustStatus == TrustStatus.Trusted }
        val nearby = devices.filter { it.trustStatus != TrustStatus.Trusted }

        val radii = KdTheme.radii
        val colors = KdTheme.colors

        ModalBottomSheet(
            onDismissRequest = { showShareSheet = false },
            sheetState = shareSheetState,
            shape = radii.shapeSheet,
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
            isLargeScreen = isLargeScreen,
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
    connectivityRestrictions: ConnectivityRestrictions,
    onDeviceActionListener: OnDeviceActionListener,
    receiveCallbacks: ReceiveNotificationsCallbacks,
    onDeviceRename: (String) -> Unit,
    onRequestCapability: (Capability) -> Unit,
    onRequestExemption: (ConnectivityRestriction) -> Unit = {},
    onSettingsClick: () -> Unit = {},
    updateBanner: @Composable () -> Unit = {},
) {
    var showRenameSheet by remember { mutableStateOf(false) }
    var pendingLink by remember { mutableStateOf<DeviceUi?>(null) }
    var pendingForget by remember { mutableStateOf<DeviceUi?>(null) }
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
                onSettingsClick = onSettingsClick,
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                val spacing = KdTheme.spacing

                // Update-available banner (desktop only; renders nothing otherwise).
                updateBanner()

                // OS-level connectivity restrictions (battery saver / metered deny) —
                // T11: these silently drop Klardrop's packets while discovery keeps
                // working, so they get the loudest slot on the screen.
                if (connectivityRestrictions.restricted) {
                    ConnectivityRestrictionBanner(
                        restrictions = connectivityRestrictions,
                        onRequestExemption = onRequestExemption,
                        modifier = Modifier.padding(horizontal = spacing.s4, vertical = spacing.s2),
                    )
                }

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
                    onNotificationDismissed = onDeviceActionListener::onNotificationDismissed,
                    onNotificationPair = onDeviceActionListener::onNotificationPair,
                )

                YourDevicesSection(
                    trusted = trusted,
                    isLargeScreen = isLargeScreen,
                    onDeviceActionListener = gridListener,
                    onAddDeviceClick = { showAddDevicePicker = true },
                    onForgetClick = { device -> pendingForget = device },
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
            isLargeScreen = isLargeScreen,
            onConfirm = {
                onDeviceActionListener.onAddToTrusted(device)
                pendingLink = null
            },
            onDismiss = { pendingLink = null },
        )
    }

    pendingForget?.let { device ->
        ForgetDeviceConfirmDialog(
            device = device,
            isLargeScreen = isLargeScreen,
            onConfirm = {
                onDeviceActionListener.onForgetDevice(device)
                pendingForget = null
            },
            onDismiss = { pendingForget = null },
        )
    }

    if (showAddDevicePicker) {
        AddDevicePickerSheet(
            candidates = devices.filter { it.trustStatus != TrustStatus.Trusted },
            isLargeScreen = isLargeScreen,
            onDismiss = { showAddDevicePicker = false },
            onPick = { device ->
                showAddDevicePicker = false
                pendingLink = device
            },
        )
    }
}

/**
 * Screen header: a bare settings affordance, the wordmark as a display-size
 * title, and one small pill naming *this* device. Everything below the header
 * is other people's devices — the pill is the only place the screen talks
 * about itself, so it stays deliberately quiet.
 */
@Composable
private fun DiscoveryHeader(
    currentDeviceName: String,
    onEditIdentity: () -> Unit,
    onSettingsClick: () -> Unit = {},
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.s5)
            .padding(top = spacing.s2, bottom = spacing.s2),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    .size(spacing.s8)
                    .clip(CircleShape)
                    .clickable(onClick = onSettingsClick),
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

        Text(
            text = "Klardrop",
            style = typography.display.copy(color = colors.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(spacing.s3))

        ThisDevicePill(
            deviceName = currentDeviceName,
            onClick = onEditIdentity,
        )
    }
}

/** Identity chip — status dot, this device's name, and a hint that it renames. */
@Composable
private fun ThisDevicePill(
    deviceName: String,
    onClick: () -> Unit,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing
    val radii = KdTheme.radii

    Row(
        modifier = Modifier
            .clip(radii.shapePill)
            .background(colors.bg1)
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.s3, vertical = spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    ) {
        Box(
            modifier = Modifier
                .size(spacing.s2)
                .clip(CircleShape)
                .background(colors.trust),
        )
        Text(
            text = deviceName.ifEmpty { "This device" },
            style = typography.body.copy(color = colors.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = Icons.Filled.Edit,
            contentDescription = "Rename this device",
            tint = colors.text3,
            modifier = Modifier.size(spacing.s4),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    backgroundDiscoveryEnabled: Boolean,
    showBackgroundDiscoveryToggle: Boolean,
    onBackgroundDiscoveryChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    updates: @Composable () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = KdTheme.radii.shapeSheet,
        containerColor = colors.bg1,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.s4)
                .padding(bottom = spacing.s6),
        ) {
            Text(text = "Settings", style = typography.title.copy(color = colors.text))
            Spacer(Modifier.height(spacing.s4))

            if (showBackgroundDiscoveryToggle) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Stay discoverable in background",
                            style = typography.body.copy(color = colors.text),
                        )
                        Text(
                            text = "Keep this device visible and able to receive when the app is closed. " +
                                "Shows a persistent notification and uses more battery.",
                            style = typography.caption.copy(color = colors.text2),
                        )
                    }
                    Spacer(Modifier.width(spacing.s3))
                    Switch(
                        checked = backgroundDiscoveryEnabled,
                        onCheckedChange = onBackgroundDiscoveryChange,
                    )
                }
                Spacer(Modifier.height(spacing.s4))
            }

            ReportProblemSection()
            Spacer(Modifier.height(spacing.s4))

            updates()
        }
    }
}

@Composable
private fun YourDevicesSection(
    trusted: List<DeviceUi>,
    isLargeScreen: Boolean,
    onDeviceActionListener: OnDeviceActionListener,
    onAddDeviceClick: () -> Unit,
    onForgetClick: (DeviceUi) -> Unit,
) {
    val spacing = KdTheme.spacing

    SectionHead(
        label = "Your devices",
        trailing = { AddDeviceButton(onClick = onAddDeviceClick) },
    )

    if (trusted.isEmpty()) {
        AddDevicePlaceholderSurface(
            isLargeScreen = isLargeScreen,
            onClick = onAddDeviceClick,
            modifier = Modifier.padding(horizontal = spacing.s5),
        )
    } else {
        Column(
            modifier = Modifier.padding(horizontal = spacing.s5),
            verticalArrangement = Arrangement.spacedBy(spacing.s2),
        ) {
            trusted.forEach { device ->
                DeviceRow(
                    name = device.deviceName,
                    subText = deviceSubText(device),
                    kind = device.deviceType.toKdDeviceKind(),
                    avatarStyle = KdAvatarStyle.Tinted,
                    rowState = deviceRowState(device),
                    status = device.reachabilityStatus(),
                    variant = KdRowVariant.Card,
                    trailing = {
                        if (device.hasUnreadMessages) {
                            UnreadBadge()
                            Spacer(Modifier.width(spacing.s2))
                        }
                        TrustedDeviceMenu(onForget = { onForgetClick(device) })
                    },
                    onClick = { onDeviceActionListener.onDeviceClick(device) },
                )
            }
        }
    }
}

/** "+ Add device" chip in the Your-devices section head. */
@Composable
private fun AddDeviceButton(onClick: () -> Unit) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing
    val radii = KdTheme.radii

    Row(
        modifier = Modifier
            .clip(radii.shapePill)
            .background(colors.bg1)
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.s3, vertical = spacing.s1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s1),
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = colors.text2,
            modifier = Modifier.size(spacing.s4),
        )
        Text(
            text = "Add device",
            style = typography.caption.copy(color = colors.text2),
        )
    }
}

@Composable
private fun TrustedDeviceMenu(onForget: () -> Unit) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing

    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(spacing.s6),
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "Device options",
                tint = colors.text2,
                modifier = Modifier.size(spacing.s4),
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
        trailing = {
            ScanningTicker()
        },
    )

    if (devices.isEmpty()) {
        NearbyEmptyHint(modifier = Modifier.padding(horizontal = spacing.s5))
    } else {
        Column(
            modifier = Modifier.padding(horizontal = spacing.s5),
            verticalArrangement = Arrangement.spacedBy(spacing.s2),
        ) {
            devices.forEach { device ->
                Column(verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
                    DeviceRow(
                        name = device.deviceName,
                        subText = deviceSubText(device),
                        kind = device.deviceType.toKdDeviceKind(),
                        avatarStyle = KdAvatarStyle.Neutral,
                        rowState = deviceRowState(device),
                        status = device.reachabilityStatus(),
                        variant = KdRowVariant.Card,
                        trailing = {
                            if (device.trustStatus == TrustStatus.Untrusted || device.trustStatus == TrustStatus.Unknown) {
                                PairButton(onClick = { onDeviceActionListener.onAddToTrusted(device) })
                            }
                        },
                        onClick = { onDeviceActionListener.onDeviceClick(device) },
                    )
                    // Pairing failure surfaced next to the pair button: the row itself is a
                    // fixed-height card, so the red helper text sits directly beneath it.
                    device.pairingError?.let { error ->
                        Text(
                            text = error,
                            style = typography.caption.copy(color = colors.err),
                            modifier = Modifier.padding(start = spacing.s4, bottom = spacing.s1),
                        )
                    }
                }
            }
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

    // Same filled-card language as a device row, so the empty slot reads as
    // "this is where devices land" rather than as an error box.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(radii.shapeLg)
            .background(colors.bg1)
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
        modifier = Modifier
            .clip(radii.shapePill)
            .background(colors.bg3)
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.s3, vertical = spacing.s1),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Pair",
            style = typography.caption.copy(color = colors.text),
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

/**
 * Desktop / large-screen rename UI — a Dialog rather than a bottom sheet.
 *
 * Bottom sheets are a touch idiom; on a windowed desktop a sheet that slides
 * up from the bottom of the OS window looks wrong. A centered Dialog matches
 * the rest of the desktop UX (LinkDeviceConfirmDialog, PairingApprovalDialog).
 */
@Composable
internal fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val colors = KdTheme.colors
    val spacing = KdTheme.spacing
    val radii = KdTheme.radii

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .clip(radii.shapeLg)
                .background(colors.bg1)
                .border(width = 1.dp, color = colors.border, shape = radii.shapeLg)
                .padding(spacing.s6),
        ) {
            RenameForm(currentName = currentName, onDismiss = onDismiss, onSave = onSave)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RenameSheet(
    currentName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val radii = KdTheme.radii
    val spacing = KdTheme.spacing
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = radii.shapeSheet,
        containerColor = KdTheme.colors.bg1,
    ) {
        RenameForm(
            currentName = currentName,
            onDismiss = onDismiss,
            onSave = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.s6, end = spacing.s6, bottom = spacing.s6),
        )
    }
}

@Composable
private fun RenameForm(
    currentName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing
    var newName by remember { mutableStateOf(currentName) }

    Column(
        modifier = modifier,
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

/**
 * T11 banner for OS-level connectivity restrictions. Tapping requests the
 * OS-standard fix: the battery-optimization exemption dialog for the battery
 * variants, the app's network settings for the metered variant. The platform
 * app owns the intent launch — same split as PermissionsPanel/onRequestCapability.
 */
@Composable
internal fun ConnectivityRestrictionBanner(
    restrictions: ConnectivityRestrictions,
    onRequestExemption: (ConnectivityRestriction) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Only an active blocker is worth a banner, and it names the blocker that is actually
    // dropping packets — never "battery saver" while battery saver is off.
    val restriction = restrictions.activeBlocker ?: return
    val text = when (restriction) {
        ConnectivityRestriction.MeteredNetworkDenied ->
            "Klardrop is blocked on metered networks — Tap to check settings"
        else -> "Battery saver is blocking Klardrop connections — Tap to allow"
    }
    Banner(
        tone = KdBannerTone.Warn,
        title = text,
        modifier = modifier.clickable(onClick = { onRequestExemption(restriction) }),
    )
}

// Sub-line policy: only show text when something is happening or wrong. The
// green/red status dot is the canonical "is this device reachable" signal;
// writing "Online" / "Nearby" next to it is noise.
private fun deviceSubText(device: DeviceUi): String? = when (device.trustStatus) {
    TrustStatus.Trusted -> when (val a = device.activityState) {
        is ActivityState.Sending -> "Sending…"
        is ActivityState.SentCompleted -> if (a.error) "Failed" else null
        ActivityState.Idle -> when (device.reachability) {
            com.carlom.klardrop.common.communication.Reachability.Unreachable -> "Offline"
            com.carlom.klardrop.common.communication.Reachability.Probing -> "Connecting…"
            else -> null
        }
    }
    TrustStatus.Pairing -> "Pairing…"
    TrustStatus.Untrusted, TrustStatus.Unknown -> null
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
