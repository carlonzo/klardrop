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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetState
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.tween
import com.carlom.klardrop.chat.DeviceChatScreen
import com.carlom.klardrop.common.CommonPlatformDependencies
import com.carlom.klardrop.common.utils.DeviceType
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
  var deviceUiClicked = remember<DeviceUi?> { null }
  val scope = rememberCoroutineScope()
  
  // Trust UI states
  var showQuickTrustDialog by remember { mutableStateOf<DeviceUi?>(null) }
  var showPairingProgress by remember { mutableStateOf(false) }
  var pairingResult by remember { mutableStateOf<Pair<Boolean, String?>?>(null) }

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
      onOpenFileRequest = { filePath -> chatViewModel.openFileClicked(filePath) }
    )
  } else {
    // --- Original Discovery Screen Content (ModalBottomSheet for sending) ---
    ModalBottomSheetLayout(
      sheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
      sheetBackgroundColor = MaterialTheme.colorScheme.surface,
      sheetContentColor = MaterialTheme.colorScheme.contentColorFor(MaterialTheme.colorScheme.surface),
      sheetContent = {
        if (deviceUiClicked != null) {
          ShareSheet(filePickerLauncher, picturesPickerLauncher, discoveryController, sheetState) { deviceUiClicked!! }
        }
      },
      sheetState = sheetState,
      content = {
        
        // Handle actions from the controller
        LaunchedEffect(Unit) {
          discoveryController.actionsFlow.collect {
            when (it) {
              is ActionUi.OnDeviceClicked -> {
                val device = it.deviceUi
                
                // Check if device is untrusted and show trust dialog
                if (device.trustStatus == com.carlom.klardrop.common.trust.model.TrustStatus.UNTRUSTED) {
                  showQuickTrustDialog = device
                } else {
                  // For trusted devices, show the share sheet
                  deviceUiClicked = device
                  scope.launch {
                    sheetState.show()
                  }
                }
              }
              is ActionUi.TrustNotification -> {
                // Handle trust notifications
              }
              is ActionUi.PairingStarted -> {
                showPairingProgress = true
              }
              is ActionUi.PairingCompleted -> {
                showPairingProgress = false
                pairingResult = it.success to it.errorMessage
              }
            }
          }
        }

        DiscoveryDashboard(
          modifier = modifier,
          isLargeScreen = isLargeScreen,
          devices = discoveryState.devices,
          onDeviceActionListener = discoveryController
        )

        LazyColumn(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
          items(
            items = discoveryState.receivingMessages.toList(),
            key = { it.first },
          ) { item ->
            ReceiveNotification(
              Modifier.animateItem(placementSpec = tween()).align(Alignment.BottomCenter),
              item.second,
              discoveryController
            )
          }
        }
        
        // Trust notifications
        discoveryState.trustNotifications.forEach { notification ->
          TrustPairingNotification(
            notification = notification,
            modifier = Modifier
              .align(Alignment.TopCenter)
              .padding(top = 8.dp)
          )
        }
      }
    )
    
    // Quick trust dialog
    showQuickTrustDialog?.let { device ->
      QuickTrustDialog(
        deviceUi = device,
        onApprove = {
          showQuickTrustDialog = null
          discoveryController.onTrustDevice(device.deviceId)
        },
        onDecline = {
          showQuickTrustDialog = null
        }
      )
    }
    
    // Pairing progress dialog
    if (showPairingProgress) {
      PairingProgressDialog(
        deviceName = deviceUiClicked?.deviceName ?: "device",
        onDismiss = { showPairingProgress = false }
      )
    }
    
    // Pairing result dialog
    pairingResult?.let { (success, errorMessage) ->
      PairingResultDialog(
        success = success,
        deviceName = deviceUiClicked?.deviceName ?: "device",
        errorMessage = errorMessage,
        onDismiss = { pairingResult = null }
      )
    }
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
        Box { // Wrap DeviceDiscovery to allow overlaying the dot
          DeviceDiscovery(device, isLargeScreen, onDeviceActionListener)
          if (device.hasUnreadMessages) {
            Box(
              modifier = Modifier
                .padding(top = 4.dp, end = 4.dp) // Adjust padding as needed
                .size(10.dp)
                .background(MaterialTheme.colorScheme.error, RoundedCornerShape(5.dp))
                .align(Alignment.TopEnd)
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