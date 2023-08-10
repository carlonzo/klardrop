package com.carlom.klardrop

import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.ExperimentalMaterialApi
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalMaterialApi::class, ExperimentalFoundationApi::class)
@Composable
fun DiscoveryScreen(
  modifier: Modifier = Modifier,
  isLargeScreen: Boolean = false,
  uiDependencies: UiDependencies,
  discoveryController: DiscoveryController
) {

  val scope = rememberCoroutineScope()
  val filePicker = uiDependencies.filePickerFactory().createPicker() // TODO this is recreated on every recomposition
  filePicker.registerPicker { deviceUi, paths ->
    discoveryController.onSendData(deviceUi, OnDataToSend.FilesList(paths))
  }

  var deviceUiClicked = remember<DeviceUi?> { null }

  val sheetState = rememberModalBottomSheetState(initialValue = ModalBottomSheetValue.Hidden)

  ModalBottomSheetLayout(
    sheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    sheetBackgroundColor = MaterialTheme.colorScheme.surface,
    sheetContentColor = MaterialTheme.colorScheme.contentColorFor(MaterialTheme.colorScheme.surface),
    sheetContent = {
      ShareSheet(filePicker, discoveryController, sheetState) { deviceUiClicked!! }
    },
    sheetState = sheetState,
    content = {

      val discoveryState by discoveryController.screenStateFlow.collectAsState()

      discoveryController.actionsFlow.collectAsEffect {
        when (it) {
          is ActionUi.OnDeviceClicked -> {

            deviceUiClicked = it.deviceUi
            scope.launch {
              sheetState.show()
            }
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
              Modifier.animateItemPlacement(tween()).align(Alignment.BottomCenter),
              item.second
            ) { discoveryController.removeReceivedMessage(item.first) }
          }

        }

      }

    })
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

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun ColumnScope.ShareSheet(
  filePicker: FilePicker,
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
        filePicker.openFilePicker(deviceUiClicked())
        scope.launch { dismissSheet() }
      },
      text = "Share Files"
    )
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