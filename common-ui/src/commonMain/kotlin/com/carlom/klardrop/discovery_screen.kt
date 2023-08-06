package com.carlom.klardrop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun DiscoveryDashboard(
  modifier: Modifier = Modifier,
  isLargeScreen: Boolean = false,
  uiDependencies: UiDependencies,
  showVisibleDevicesController: ShowVisibleDevicesController
) {

  val scope = rememberCoroutineScope()
  val scaffoldState =
    rememberBottomSheetScaffoldState(bottomSheetState = SheetState(skipPartiallyExpanded = true, initialValue = SheetValue.Hidden))
  val filePicker = uiDependencies.filePickerFactory().createPicker() // TODO this is recreated on every recomposition

  var deviceUiClicked = remember<DeviceUi?> { null }

  var sheetState = rememberModalBottomSheetState(initialValue = ModalBottomSheetValue.Hidden)

  ModalBottomSheetLayout(
    sheetContent = {
      Text("Share Text")
      Text(
        modifier = Modifier.clickable { filePicker.openFilePicker(deviceUiClicked!!) },
        text = "Share Files"
      )

      Spacer(Modifier.height(40.dp))
    },
    sheetState = sheetState,
    content = {

      val devices by showVisibleDevicesController.flow.collectAsState(emptyList())

      filePicker.registerPicker { deviceUi, paths ->
        showVisibleDevicesController.onSendData(deviceUi, OnDataToSend.FilesList(paths))
      }

      showVisibleDevicesController.actionsFlow.collectAsEffect {
        when (it) {
          is ActionUi.OnDeviceClicked -> {


            println("event clicked $it")
            deviceUiClicked = it.deviceUi
            scope.launch {
              sheetState.show()
            }
          }
        }
      }

      DiscoveryDashboard(
        modifier = modifier,
        isLargeScreen = isLargeScreen,
        devices = devices,
        onDeviceActionListener = showVisibleDevicesController
      )

    })
}


//  BottomSheetScaffold(
//
//    scaffoldState = scaffoldState,
//    sheetContent = {
//
//      Text("Share Text")
//      Text(
//        modifier = Modifier.clickable { filePicker.openFilePicker(deviceUiClicked!!) },
//        text = "Share Files"
//      )
//
//      Spacer(Modifier.height(40.dp))
//    }
//  ) {
//
//    val devices by showVisibleDevicesController.flow.collectAsState(emptyList())
//
//    filePicker.registerPicker { deviceUi, paths ->
//      showVisibleDevicesController.onSendData(deviceUi, OnDataToSend.FilesList(paths))
//    }
//
//    showVisibleDevicesController.actionsFlow.collectAsEffect {
//      when (it) {
//        is ActionUi.OnDeviceClicked -> {
//
//
//          println("event clicked $it")
//          deviceUiClicked = it.deviceUi
//          scope.launch {
//            scaffoldState.bottomSheetState.show()
//          }
//        }
//      }
//    }
//
//    DiscoveryDashboard(
//      modifier = modifier,
//      isLargeScreen = isLargeScreen,
//      devices = devices,
//      onDeviceActionListener = showVisibleDevicesController
//    )
//
//  }


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
private fun ColumnScope.ShareSheet(deviceUi: DeviceUi?, filePicker: FilePicker) {

  Text("Share Text")
  Text(
    modifier = Modifier.clickable { filePicker.openFilePicker(deviceUi!!) },
    text = "Share Files"
  )

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