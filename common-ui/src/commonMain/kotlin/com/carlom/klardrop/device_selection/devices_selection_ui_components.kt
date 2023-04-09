package com.carlom.klardrop.device_selection

import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.carlom.klardrop.DeviceSmall
import com.carlom.klardrop.DeviceUi

@Composable
fun DeviceSelectionDashboard(devicesSelectionController: DevicesSelectionController) {

  val devices by devicesSelectionController.flow.collectAsState(emptyList())

  DiscoveryDashboard(
    devices = devices,

    send = { device: DeviceUi ->
      devicesSelectionController.sendTo(device.deviceId)
    }
  )
}

@Composable
internal fun DiscoveryDashboard(
  devices: List<DeviceUi>,
  send: (DeviceUi) -> Unit
) {
  Surface {
    LazyRow {
      items(devices, key = { it.deviceId }) { device ->

        DeviceSmall(device, onClick = { send(device) })

      }
    }
  }
}