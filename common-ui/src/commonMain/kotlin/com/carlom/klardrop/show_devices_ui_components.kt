package com.carlom.klardrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

@Composable
fun DiscoveryDashboard(
  modifier: Modifier = Modifier,
  showVisibleDevicesController: ShowVisibleDevicesController
) {

  val devices by showVisibleDevicesController.flow.collectAsState(emptyList())

  DiscoveryDashboard(
    modifier = modifier,
    devices = devices,
    onDeviceActionListener = showVisibleDevicesController
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiscoveryDashboard(
  modifier: Modifier = Modifier,
  devices: Collection<DeviceUi>,
  onDeviceActionListener: OnDeviceActionListener
) {
  Box(
    modifier = modifier
  ) {

    FlowRow {

      devices.forEach { device ->

        DeviceDiscovery(device, onDeviceActionListener)

      }

    }


  }
}