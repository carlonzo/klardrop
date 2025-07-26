package com.carlom.klardrop

import androidx.compose.animation.core.tween
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
import com.carlom.klardrop.common.CommonPlatformDependencies
import com.carlom.klardrop.common.utils.DeviceType
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.PickerResultLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

@Composable
fun DiscoveryScreen(
  modifier: Modifier = Modifier,
  isLargeScreen: Boolean = false,
  discoveryController: DiscoveryController
) {

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

  ModalBottomSheetLayout(
    sheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    sheetBackgroundColor = MaterialTheme.colorScheme.surface,
    sheetContentColor = MaterialTheme.colorScheme.contentColorFor(MaterialTheme.colorScheme.surface),
    sheetContent = {
      ShareSheet(filePickerLauncher, picturesPickerLauncher, discoveryController, sheetState) { deviceUiClicked!! }
    },
    sheetState = sheetState,
    content = {

      val discoveryState by discoveryController.screenStateFlow
        .collectAsState()

      discoveryController.actionsFlow.collectAsEffect {
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
            // Handle trust notifications - this will be implemented when we update the controller
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

      Box {

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

    })
    
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

        DeviceDiscovery(device, isLargeScreen, onDeviceActionListener)

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
fun <T> Flow<T>.collectAsEffect(
  context: CoroutineContext = EmptyCoroutineContext,
  block: (T) -> Unit
) {
  LaunchedEffect(key1 = Unit) {
    onEach(block).flowOn(context).launchIn(this)
  }
}