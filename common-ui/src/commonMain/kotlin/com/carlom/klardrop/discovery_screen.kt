package com.carlom.klardrop

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetState
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
  uiDependencies: UiDependencies // Added
) {

  val discoveryState by discoveryController.screenStateFlow.collectAsState() // Moved up
  val deviceUiClicked = remember<DeviceUi?> { null } // Still used by bottom sheet logic if kept

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


  // --- Navigation to Chat Screen ---
  if (discoveryState.navigateToChatDeviceId != null && discoveryState.navigateToChatDeviceName != null) {
    val chatViewModel = remember(discoveryState.navigateToChatDeviceId) {
      uiDependencies.deviceChatViewModelFactory(discoveryState.navigateToChatDeviceId!!)
    }
    DeviceChatScreen(
      deviceName = discoveryState.navigateToChatDeviceName!!,
      viewModel = chatViewModel,
      onBackClicked = { discoveryController.onBackFromChat() },
      onOpenFileRequest = { filePath -> chatViewModel.openFileClicked(filePath) } // Added
    )
  } else {
    // --- Original Discovery Screen Content (ModalBottomSheet for sending) ---
    ModalBottomSheetLayout(
      sheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
      sheetBackgroundColor = MaterialTheme.colorScheme.surface,
      sheetContentColor = MaterialTheme.colorScheme.contentColorFor(MaterialTheme.colorScheme.surface),
      sheetContent = {
        if (deviceUiClicked != null) { // Ensure deviceUiClicked is not null before accessing
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

  // --- Pairing Dialog (shown on both screens) ---
  discoveryState.pairingDialogState?.let { pairingState ->
    println("🖥️ [DiscoveryScreen] About to show PairingApprovalDialog for device: ${pairingState.deviceName}")
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
  val containerShape = RoundedCornerShape(24.dp)
  var showRenameSheet by remember { mutableStateOf(false) }

  Column(
    modifier = modifier
      .windowInsetsPadding(WindowInsets.statusBars)
      .padding(horizontal = 16.dp)
  ) {

    TopAppBar(
      title = { Text("Klardrop") },
    )

    Spacer(Modifier.height(8.dp))

    Text(
      text = "You'll appear as",
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))

    androidx.compose.material3.Surface(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { showRenameSheet = true },
      shape = containerShape,
      tonalElevation = 1.dp
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text(
          text = currentDeviceName.ifEmpty { "This device" },
          style = MaterialTheme.typography.titleMedium
        )
        Icon(
          imageVector = Icons.Filled.ChevronRight,
          contentDescription = "Edit device name"
        )
      }
    }

    Spacer(Modifier.height(16.dp))

    val trusted = devices.filter { it.trustStatus == TrustStatus.Trusted }
    val others = devices.filter { it.trustStatus != TrustStatus.Trusted }

    if (trusted.isNotEmpty()) {
      Text(
        text = "Send to your devices",
        style = MaterialTheme.typography.titleMedium
      )
      Spacer(Modifier.height(8.dp))

      DeviceGrid(
        devices = trusted,
        isLargeScreen = isLargeScreen,
        onDeviceActionListener = onDeviceActionListener,
        containerShape = containerShape
      )

      Spacer(Modifier.height(16.dp))
    }

    if (others.isNotEmpty()) {
      Text(
        text = "Send to nearby devices",
        style = MaterialTheme.typography.titleMedium
      )
      Spacer(Modifier.height(8.dp))

      DeviceGrid(
        devices = others,
        isLargeScreen = isLargeScreen,
        onDeviceActionListener = onDeviceActionListener,
        containerShape = containerShape
      )
    }
  }

  // Device Rename Bottom Sheet
  if (showRenameSheet) {
    ModalBottomSheetLayout(
      sheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
      sheetBackgroundColor = MaterialTheme.colorScheme.surface,
      sheetContentColor = MaterialTheme.colorScheme.contentColorFor(MaterialTheme.colorScheme.surface),
      sheetContent = {
        var newName by remember { mutableStateOf(currentDeviceName) }

        Column(
          modifier = Modifier.padding(24.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          Text(
            "Rename Device",
            style = MaterialTheme.typography.headlineSmall
          )

          OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            label = { Text("Device Name") },
            modifier = Modifier.fillMaxWidth(),
            keyboardActions = KeyboardActions(
              onDone = {
                onDeviceRename(newName)
                showRenameSheet = false
              }
            ),
            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done)
          )

          Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth()
          ) {
            TextButton(onClick = { showRenameSheet = false }) {
              Text("Cancel")
            }
            Button(onClick = {
              onDeviceRename(newName)
              showRenameSheet = false
            }) {
              Text("Save")
            }
          }
        }
      },
      sheetState = rememberModalBottomSheetState(ModalBottomSheetValue.Expanded)
    ) { }
  }
}

@Composable
private fun DeviceGrid(
  devices: List<DeviceUi>,
  isLargeScreen: Boolean,
  onDeviceActionListener: OnDeviceActionListener,
  containerShape: RoundedCornerShape
) {
  val columns = if (isLargeScreen) GridCells.Adaptive(minSize = 400.dp) else GridCells.Adaptive(minSize = 180.dp)

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceVariant, containerShape)
      .padding(12.dp)
  ) {
    LazyVerticalGrid(
      columns = columns,
      contentPadding = PaddingValues(0.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.fillMaxWidth()
    ) {
      items(devices) { device ->
        Box(
          modifier = Modifier
        ) {
          DeviceDiscovery(device, isLargeScreen, onDeviceActionListener)

          if (device.hasUnreadMessages) {
            Box(
              modifier = Modifier
                .padding(top = 4.dp, end = 4.dp)
                .size(10.dp)
                .background(MaterialTheme.colorScheme.error, RoundedCornerShape(5.dp))
                .align(Alignment.TopEnd)
            )
          }

          if (device.trustStatus != TrustStatus.Unknown) {
            TrustActionButton(
              isTrusted = device.trustStatus == TrustStatus.Trusted,
              isLoading = device.trustStatus == TrustStatus.Pairing,
              onAddToTrusted = { onDeviceActionListener.onAddToTrusted(device) },
              onRemoveTrust = { onDeviceActionListener.onRemoveTrust(device) },
              modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
            )
          }
        }
      }
    }
  }
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

  suspend fun dismissSheet() {
    sheetState.hide()
    shareText = false
  }

  Column(
    modifier = Modifier.padding(top = 24.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Top)
  ) {


    Text(
      modifier = Modifier.clickable(enabled = !shareText) { shareText = true },
      text = "Share Text"
    )
    var inputValue by remember { mutableStateOf(discoveryController.readFromClipboard()) }

    LaunchedEffect(shareText) {
      if (shareText) {
        inputValue = discoveryController.readFromClipboard()
      }
    }

    if (shareText) {

      fun sendText(text: String) {
        if (text.isNotEmpty()) {
          discoveryController.onSendData(
            deviceUiClicked(),
            OnDataToSend.Text(text)
          )
        }
        scope.launch { dismissSheet() }
      }

      Column {

        TextField(
          modifier = Modifier.fillMaxWidth(),
          value = inputValue,
          onValueChange = { inputValue = it },
          keyboardActions = KeyboardActions(onDone = {
            sendText(inputValue)
          }),
          keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done)
        )
        TextButton(onClick = {
          sendText(inputValue)
        }) {
          Text("Send")
        }
      }
    }

    Text(
      modifier = Modifier.clickable {
        filePickerFiles.launch()

      },
      text = "Share Files"
    )

    if (CommonPlatformDependencies.deviceType() == DeviceType.MOBILE) {
      Text(
        modifier = Modifier.clickable {
          filePickerPictures.launch()

        },
        text = "Share Pictures Or Videos"
      )
    }
  }


  Spacer(Modifier.height(40.dp))
}

@Composable
private fun PairingApprovalDialog(
  state: PairingDialogState,
  onDismiss: () -> Unit
) {
  println("🖥️ [PairingApprovalDialog] Rendering pairing dialog for device: ${state.deviceName}")

  AlertDialog(
    onDismissRequest = {
      println("🖥️ [PairingApprovalDialog] Dialog dismissed - calling onDismiss")
      onDismiss()
    },
    title = {
      Text("Pairing Request")
    },
    text = {
      if (state.isError) {
        Text("Error: ${state.errorMessage ?: "Unknown error occurred during pairing"}")
      } else {
        Text("Device '${state.deviceName}' (${state.deviceType}) wants to pair with this device. Do you want to accept?")
      }
    },
    confirmButton = {
      if (state.isError) {
        Button(onClick = {
          println("🖥️ [PairingApprovalDialog] Error dialog - Close button clicked")
          onDismiss()
        }) {
          Text("Close")
        }
      } else {
        Button(onClick = {
          println("🖥️ [PairingApprovalDialog] Accept button clicked for ${state.deviceName}")
          state.onAccept()
        }) {
          Text("Accept")
        }
      }
    },
    dismissButton = {
      if (!state.isError) {
        Button(onClick = {
          println("🖥️ [PairingApprovalDialog] Reject button clicked for ${state.deviceName}")
          state.onReject()
        }) {
          Text("Reject")
        }
      }
    }
  )
}
