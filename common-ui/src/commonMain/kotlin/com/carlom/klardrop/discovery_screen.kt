package com.carlom.klardrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

@Composable
fun DiscoveryDashboard(
  modifier: Modifier = Modifier,
  isLargeScreen: Boolean = false,
  uiDependencies: UiDependencies,
  showVisibleDevicesController: ShowVisibleDevicesController
) {

  val devices by showVisibleDevicesController.flow.collectAsState(emptyList())
  val filePicker = uiDependencies.filePickerFactory().createPicker()

  filePicker.registerPicker { deviceUi, paths ->
    showVisibleDevicesController.onSendData(deviceUi, OnDataToSend.FilesList(paths))
  }


  showVisibleDevicesController.actionsFlow.collectAsEffect {
    when (it) {
      is ActionUi.OpenFilePicker -> {
        println("open picker")
        filePicker.openFilePicker(it.deviceUi)
      }
    }
  }


  DiscoveryDashboard(
    modifier = modifier,
    isLargeScreen = isLargeScreen,
    devices = devices,
    onDeviceActionListener = showVisibleDevicesController
  )
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
fun <T> Flow<T>.collectAsEffect(
  context: CoroutineContext = EmptyCoroutineContext,
  block: (T) -> Unit
) {
  LaunchedEffect(key1 = Unit) {
    onEach(block).flowOn(context).launchIn(this)
  }
}