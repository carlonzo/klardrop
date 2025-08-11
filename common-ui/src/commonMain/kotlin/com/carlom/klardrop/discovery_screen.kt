package com.carlom.klardrop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetState
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
      deviceId = discoveryState.navigateToChatDeviceId!!,
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
          devices = discoveryState.devices,
          onDeviceActionListener = discoveryController // This now triggers chat navigation
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


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoveryDashboard(
  modifier: Modifier = Modifier,
  isLargeScreen: Boolean = false,
  devices: Collection<DeviceUi>,
  onDeviceActionListener: OnDeviceActionListener
) {
  Box(
    modifier = modifier
  ) {

    FlowRow {
      devices.forEach { device ->
        Box { // Wrap DeviceDiscovery to allow overlaying indicators
          DeviceDiscovery(device, isLargeScreen, onDeviceActionListener)
          
          // Unread messages indicator
          if (device.hasUnreadMessages) {
            Box(
              modifier = Modifier
                .padding(top = 4.dp, end = 4.dp)
                .size(10.dp)
                .background(MaterialTheme.colorScheme.error, RoundedCornerShape(5.dp))
                .align(Alignment.TopEnd)
            )
          }
          
          // Trust action button (positioned at bottom-end)
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
private fun ColumnScope.ShareSheet(
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
