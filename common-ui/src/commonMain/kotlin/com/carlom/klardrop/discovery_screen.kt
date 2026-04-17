package com.carlom.klardrop

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetState
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.chat.DeviceChatScreen
import com.carlom.klardrop.common.CommonPlatformDependencies
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.trust.TrustActionButton
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.PickerResultLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.launch

@Composable
fun DiscoveryScreen(
  modifier: Modifier = Modifier,
  isLargeScreen: Boolean = false,
  discoveryController: DiscoveryController,
  uiDependencies: UiDependencies
) {

  val discoveryState by discoveryController.screenStateFlow.collectAsState()
  val deviceUiClicked = remember<DeviceUi?> { null }

  val filePickerLauncher = rememberFilePickerLauncher(mode = FileKitMode.Multiple()) { files ->
    if (files.isNullOrEmpty()) return@rememberFilePickerLauncher

    val deviceSelected = requireNotNull(deviceUiClicked)
    discoveryController.onSendData(deviceSelected, OnDataToSend.FilesList(files))
  }

  val picturesPickerLauncher = rememberFilePickerLauncher(mode = FileKitMode.Multiple(), type = FileKitType.ImageAndVideo) { files ->
    if (files.isNullOrEmpty()) return@rememberFilePickerLauncher

    val deviceSelected = requireNotNull(deviceUiClicked)
    discoveryController.onSendData(deviceSelected, OnDataToSend.FilesList(files))
  }

  val sheetState = rememberModalBottomSheetState(initialValue = ModalBottomSheetValue.Hidden)


  if (discoveryState.navigateToChatDeviceId != null && discoveryState.navigateToChatDeviceName != null) {
    val chatViewModel = remember(discoveryState.navigateToChatDeviceId) {
      uiDependencies.deviceChatViewModelFactory(discoveryState.navigateToChatDeviceId!!)
    }
    val isOwned = discoveryState.devices
      .firstOrNull { it.deviceId == discoveryState.navigateToChatDeviceId }
      ?.trustStatus == TrustStatus.Trusted
    DeviceChatScreen(
      deviceName = discoveryState.navigateToChatDeviceName!!,
      isOwned = isOwned,
      viewModel = chatViewModel,
      onBackClicked = { discoveryController.onBackFromChat() },
      onOpenFileRequest = { filePath -> chatViewModel.openFileClicked(filePath) }
    )
  } else {
    ModalBottomSheetLayout(
      sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
      sheetBackgroundColor = MaterialTheme.colorScheme.surface,
      sheetContentColor = MaterialTheme.colorScheme.contentColorFor(MaterialTheme.colorScheme.surface),
      sheetContent = {
        if (deviceUiClicked != null) {
          ShareSheet(filePickerLauncher, picturesPickerLauncher, discoveryController, sheetState) { deviceUiClicked!! }
        }
      },
      sheetState = sheetState,
      content = {

        DiscoveryDashboard(
          modifier = modifier,
          isLargeScreen = isLargeScreen,
          currentDeviceName = discoveryState.currentDeviceName ?: discoveryState.systemDeviceName ?: "",
          devices = discoveryState.devices,
          onDeviceActionListener = discoveryController,
          onDeviceRename = { newName -> discoveryController.saveCustomDeviceName(newName) }
        )

      }
    )
  }

  discoveryState.pairingDialogState?.let { pairingState ->
    PairingApprovalDialog(
      state = pairingState,
      onDismiss = { discoveryController.dismissPairingDialog() }
    )
  }
}


@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DiscoveryDashboard(
  modifier: Modifier = Modifier,
  isLargeScreen: Boolean = false,
  currentDeviceName: String,
  devices: Collection<DeviceUi>,
  onDeviceActionListener: OnDeviceActionListener,
  onDeviceRename: (String) -> Unit
) {
  var showRenameSheet by remember { mutableStateOf(false) }
  var pendingLink by remember { mutableStateOf<DeviceUi?>(null) }

  val gridListener = remember(onDeviceActionListener) {
    object : OnDeviceActionListener by onDeviceActionListener {
      override fun onAddToTrusted(deviceUi: DeviceUi) {
        pendingLink = deviceUi
      }
    }
  }

  Column(
    modifier = modifier
      .windowInsetsPadding(WindowInsets.statusBars)
      .fillMaxSize()
  ) {

    DiscoveryHeader(
      appName = "Klardrop",
      currentDeviceName = currentDeviceName,
      onEditIdentity = { showRenameSheet = true }
    )

    Spacer(Modifier.height(24.dp))

    val trusted = devices.filter { it.trustStatus == TrustStatus.Trusted }
    val others = devices.filter { it.trustStatus != TrustStatus.Trusted }

    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {

      if (trusted.isNotEmpty()) {
        DeviceSection(
          title = "Your devices",
          devices = trusted,
          isLargeScreen = isLargeScreen,
          onDeviceActionListener = gridListener
        )
      }

      NearbySection(
        devices = others,
        isLargeScreen = isLargeScreen,
        onDeviceActionListener = gridListener
      )
    }
  }

  if (showRenameSheet) {
    RenameSheet(
      currentName = currentDeviceName,
      onDismiss = { showRenameSheet = false },
      onSave = {
        onDeviceRename(it)
        showRenameSheet = false
      }
    )
  }

  pendingLink?.let { device ->
    LinkDeviceConfirmDialog(
      device = device,
      onConfirm = {
        onDeviceActionListener.onAddToTrusted(device)
        pendingLink = null
      },
      onDismiss = { pendingLink = null }
    )
  }
}

@Composable
private fun LinkDeviceConfirmDialog(
  device: DeviceUi,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Add ${device.deviceName} to your devices?") },
    text = {
      Text(
        text = "Only do this if it's your own device. Your devices share clipboard, files, and message history with each other automatically.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    },
    confirmButton = {
      Button(onClick = onConfirm) { Text("Yes, it's mine") }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    }
  )
}

@Composable
private fun DiscoveryHeader(
  appName: String,
  currentDeviceName: String,
  onEditIdentity: () -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    Text(
      text = appName,
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(4.dp))

    Surface(
      shape = RoundedCornerShape(24.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
      modifier = Modifier.clickable { onEditIdentity() }
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Text(
          text = currentDeviceName.ifEmpty { "This device" },
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface
        )
        Icon(
          imageVector = Icons.Filled.Edit,
          contentDescription = "Edit device name",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(16.dp)
        )
      }
    }

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Icon(
        imageVector = Icons.Filled.Wifi,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(14.dp)
      )
      Text(
        text = "Visible to devices on your Wi-Fi",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}

@Composable
private fun DeviceSection(
  title: String,
  devices: List<DeviceUi>,
  isLargeScreen: Boolean,
  onDeviceActionListener: OnDeviceActionListener
) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    SectionLabel(title)
    DeviceGrid(
      devices = devices,
      isLargeScreen = isLargeScreen,
      onDeviceActionListener = onDeviceActionListener
    )
  }
}

@Composable
private fun NearbySection(
  devices: List<DeviceUi>,
  isLargeScreen: Boolean,
  onDeviceActionListener: OnDeviceActionListener
) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    SectionLabel("Nearby")
    if (devices.isEmpty()) {
      ScanningEmptyState()
    } else {
      DeviceGrid(
        devices = devices,
        isLargeScreen = isLargeScreen,
        onDeviceActionListener = onDeviceActionListener
      )
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text = text.uppercase(),
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 4.dp)
  )
}

@Composable
private fun DeviceGrid(
  devices: List<DeviceUi>,
  isLargeScreen: Boolean,
  onDeviceActionListener: OnDeviceActionListener
) {
  val columns = if (isLargeScreen) {
    GridCells.Adaptive(minSize = 320.dp)
  } else {
    GridCells.Adaptive(minSize = 104.dp)
  }

  Surface(
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier.fillMaxWidth()
  ) {
    LazyVerticalGrid(
      columns = columns,
      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
      modifier = Modifier.fillMaxWidth()
    ) {
      items(devices) { device ->
        Box(modifier = Modifier.fillMaxWidth()) {
          Box(modifier = Modifier.align(Alignment.Center)) {
            DeviceDiscovery(device, isLargeScreen, onDeviceActionListener)
          }

          if (device.trustStatus == TrustStatus.Untrusted || device.trustStatus == TrustStatus.Pairing) {
            TrustActionButton(
              isTrusted = false,
              isLoading = device.trustStatus == TrustStatus.Pairing,
              onAddToTrusted = { onDeviceActionListener.onAddToTrusted(device) },
              onRemoveTrust = { onDeviceActionListener.onRemoveTrust(device) },
              modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(28.dp)
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ScanningEmptyState() {
  Surface(
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 40.dp, horizontal = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      PulsingRadar()
      Spacer(Modifier.height(4.dp))
      Text(
        text = "Looking for devices nearby…",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface
      )
      Text(
        text = "Make sure Klardrop is open and on the same Wi-Fi.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}

@Composable
private fun PulsingRadar() {
  val transition = rememberInfiniteTransition()
  val accent = MaterialTheme.colorScheme.primary

  val radius1 by transition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 2200, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    )
  )
  val radius2 by transition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 2200, easing = LinearEasing, delayMillis = 1100),
      repeatMode = RepeatMode.Restart
    )
  )

  Box(
    modifier = Modifier.size(96.dp),
    contentAlignment = Alignment.Center
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val maxR = size.minDimension / 2f
      listOf(radius1, radius2).forEach { t ->
        val alpha = (1f - t) * 0.45f
        drawCircle(
          color = accent.copy(alpha = alpha),
          radius = maxR * t
        )
      }
    }

    Box(
      modifier = Modifier
        .size(28.dp)
        .background(accent, CircleShape)
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameSheet(
  currentName: String,
  onDismiss: () -> Unit,
  onSave: (String) -> Unit
) {
  ModalBottomSheetLayout(
    sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    sheetBackgroundColor = MaterialTheme.colorScheme.surface,
    sheetContentColor = MaterialTheme.colorScheme.contentColorFor(MaterialTheme.colorScheme.surface),
    sheetContent = {
      var newName by remember { mutableStateOf(currentName) }

      Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        Text(
          "Rename device",
          style = MaterialTheme.typography.titleLarge
        )
        Text(
          "This is how others will see you when sharing.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
          value = newName,
          onValueChange = { newName = it },
          label = { Text("Device name") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          keyboardActions = KeyboardActions(onDone = { onSave(newName) }),
          keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done)
        )

        Row(
          horizontalArrangement = Arrangement.End,
          modifier = Modifier.fillMaxWidth()
        ) {
          TextButton(onClick = onDismiss) { Text("Cancel") }
          Button(onClick = { onSave(newName) }) { Text("Save") }
        }
      }
    },
    sheetState = rememberModalBottomSheetState(ModalBottomSheetValue.Expanded)
  ) { }
}

@Composable
private fun ShareSheet(
  filePickerFiles: PickerResultLauncher,
  filePickerPictures: PickerResultLauncher,
  discoveryController: DiscoveryController,
  sheetState: ModalBottomSheetState,
  deviceUiClicked: () -> DeviceUi
) {
  val scope = rememberCoroutineScope()
  var shareText by remember { mutableStateOf(false) }
  var inputValue by remember { mutableStateOf("") }
  var prefilledFromClipboard by remember { mutableStateOf(false) }

  suspend fun dismissSheet() {
    sheetState.hide()
    shareText = false
    prefilledFromClipboard = false
    inputValue = ""
  }

  fun sendText(text: String) {
    if (text.isNotEmpty()) {
      discoveryController.onSendData(
        deviceUiClicked(),
        OnDataToSend.Text(text)
      )
    }
    scope.launch { dismissSheet() }
  }

  Column(
    modifier = Modifier.padding(top = 24.dp, start = 24.dp, end = 24.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp, alignment = Alignment.Top)
  ) {

    Text(
      text = "Share with ${deviceUiClicked().deviceName}",
      style = MaterialTheme.typography.titleLarge
    )

    ShareRow(
      label = "Send text",
      enabled = !shareText,
      onClick = {
        val clip = discoveryController.readFromClipboard()
        if (clip.isNotEmpty()) {
          inputValue = clip
          prefilledFromClipboard = true
        } else {
          prefilledFromClipboard = false
        }
        shareText = true
      }
    )

    if (shareText) {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (prefilledFromClipboard) {
          Text(
            text = "From your clipboard",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        TextField(
          modifier = Modifier.fillMaxWidth(),
          value = inputValue,
          onValueChange = {
            inputValue = it
            prefilledFromClipboard = false
          },
          placeholder = { Text("Type or paste a message") },
          keyboardActions = KeyboardActions(onDone = { sendText(inputValue) }),
          keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done)
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
          TextButton(onClick = { sendText(inputValue) }) { Text("Send") }
        }
      }
    }

    ShareRow(label = "Send files", onClick = { filePickerFiles.launch() })

    if (CommonPlatformDependencies.deviceType() == DeviceType.MOBILE) {
      ShareRow(label = "Send photos or videos", onClick = { filePickerPictures.launch() })
    }
  }
}

@Composable
private fun ShareRow(
  label: String,
  enabled: Boolean = true,
  onClick: () -> Unit
) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = if (enabled) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
    modifier = Modifier
      .fillMaxWidth()
      .clickable(enabled = enabled, onClick = onClick)
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
    )
  }
}

@Composable
private fun PairingApprovalDialog(
  state: PairingDialogState,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = if (state.isError) "Couldn't link device" else "Is this your device?"
      )
    },
    text = {
      if (state.isError) {
        Text(state.errorMessage ?: "Something went wrong while linking. Please try again.")
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(
            text = "${state.deviceName} wants to link with this device.",
            style = MaterialTheme.typography.bodyLarge
          )
          Text(
            text = "Only accept if it's your own device. Linked devices stay in sync — clipboard, files, and message history are shared between them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    },
    confirmButton = {
      if (state.isError) {
        Button(onClick = onDismiss) { Text("Close") }
      } else {
        Button(onClick = { state.onAccept() }) { Text("Yes, it's mine") }
      }
    },
    dismissButton = {
      if (!state.isError) {
        TextButton(onClick = { state.onReject() }) { Text("Not mine") }
      }
    }
  )
}
