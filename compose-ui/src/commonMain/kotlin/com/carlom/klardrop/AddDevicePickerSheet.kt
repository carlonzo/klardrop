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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.carlom.klardrop.common.discovery.ManualAddInfo
import com.carlom.klardrop.common.discovery.ManualAddResult
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.components.DeviceAvatar
import com.carlom.klardrop.components.DeviceRow
import com.carlom.klardrop.components.KdAvatarStyle
import com.carlom.klardrop.components.KdDeviceKind
import com.carlom.klardrop.components.KdRowState
import com.carlom.klardrop.components.QrCodeImage
import com.carlom.klardrop.components.SectionHead
import com.carlom.klardrop.theme.KdTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddDevicePickerSheet(
    candidates: List<DeviceUi>,
    isLargeScreen: Boolean,
    onDismiss: () -> Unit,
    onPick: (DeviceUi) -> Unit,
    manualAddInfo: suspend () -> ManualAddInfo?,
    onAddManually: suspend (String) -> ManualAddResult,
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
                    manualAddInfo = manualAddInfo,
                    onAddManually = onAddManually,
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
                manualAddInfo = manualAddInfo,
                onAddManually = onAddManually,
            )
        }
    }
}

@Composable
private fun AddDevicePickerContent(
    candidates: List<DeviceUi>,
    onDismiss: () -> Unit,
    onPick: (DeviceUi) -> Unit,
    manualAddInfo: suspend () -> ManualAddInfo?,
    onAddManually: suspend (String) -> ManualAddResult,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
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
                text = "No nearby devices right now. Make sure Klardrop is open on the device you want to pair, and that both are on the same Wi-Fi. If they still don't show up, add by code below.",
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

        ManualAddSection(manualAddInfo = manualAddInfo, onAddManually = onAddManually)

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
private fun ManualAddSection(
    manualAddInfo: suspend () -> ManualAddInfo?,
    onAddManually: suspend (String) -> ManualAddResult,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ManualAddResult?>(null) }
    var showCode by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<ManualAddInfo?>(null) }

    fun submit(text: String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val r = onAddManually(text)
                result = r
                if (r is ManualAddResult.Added || r is ManualAddResult.AlreadyVisible) input = ""
            } finally {
                busy = false
            }
        }
    }

    val scan = rememberQrScanner { code ->
        input = code
        submit(code)
    }

    LaunchedEffect(showCode) {
        if (showCode) {
            loaded = false
            info = manualAddInfo()
            loaded = true
        }
    }

    SectionHead(
        label = "Add by code or IP",
        modifier = Modifier.padding(horizontal = spacing.s1),
    )
    Text(
        text = "For networks that hide nearby devices. On the other device, open Add a device → Show this device's code.",
        style = typography.caption.copy(color = colors.text2),
        modifier = Modifier.padding(start = spacing.s5, end = spacing.s5, bottom = spacing.s3),
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.s5),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            enabled = !busy,
            label = {
                Text(
                    "Code or IP, e.g. 37 or 192.168.1.37",
                    style = typography.caption.copy(color = colors.text2),
                )
            },
            textStyle = typography.body.copy(color = colors.text),
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardActions = KeyboardActions(onDone = { submit(input) }),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.border,
                cursorColor = colors.accent,
            ),
        )
        if (scan != null) {
            TextButton(onClick = scan, enabled = !busy) {
                Text("Scan", style = typography.body.copy(color = colors.accent))
            }
        }
        TextButton(onClick = { submit(input) }, enabled = !busy) {
            Text(
                if (busy) "Checking…" else "Add",
                style = typography.body.copy(color = colors.accent),
            )
        }
    }
    result?.let {
        val ok = it is ManualAddResult.Added || it is ManualAddResult.AlreadyVisible
        Text(
            text = manualAddMessage(it),
            style = typography.caption.copy(color = if (ok) colors.text2 else colors.err),
            modifier = Modifier.padding(horizontal = spacing.s5, vertical = spacing.s1),
        )
    }

    TextButton(
        onClick = { showCode = !showCode },
        modifier = Modifier.padding(horizontal = spacing.s3),
    ) {
        Text(
            if (showCode) "Hide this device's code" else "Show this device's code",
            style = typography.body.copy(color = colors.accent),
        )
    }
    if (showCode) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.s2),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.s5),
        ) {
            val i = info
            when {
                !loaded -> Text("Loading…", style = typography.caption.copy(color = colors.text2))
                i == null -> Text(
                    "This device isn't connected to Wi-Fi or Ethernet.",
                    style = typography.caption.copy(color = colors.text2),
                )
                else -> {
                    Text(i.code, style = typography.display.copy(color = colors.text))
                    QrCodeImage(url = i.qrPayload, modifier = Modifier.size(180.dp))
                    Text(
                        "${i.address}:${i.port}",
                        style = typography.caption.copy(color = colors.text2),
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(spacing.s2))
}

internal fun manualAddMessage(result: ManualAddResult): String = when (result) {
    is ManualAddResult.Added -> "Added ${result.deviceName}. Tap it above to pair."
    is ManualAddResult.AlreadyVisible -> "${result.deviceName} is already in the list above."
    ManualAddResult.ThisDevice -> "That's this device."
    ManualAddResult.WrongDevice -> "A different device answered there. Ask for a fresh code."
    is ManualAddResult.Unreachable ->
        "No Klardrop device answered at ${result.address}. Check that Klardrop is open on it and both are on the same network."
    ManualAddResult.InvalidInput -> "Enter a code like 37 or 12.37, or an IP address like 192.168.1.37."
    ManualAddResult.NoLanAddress -> "This device isn't connected to Wi-Fi or Ethernet."
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
