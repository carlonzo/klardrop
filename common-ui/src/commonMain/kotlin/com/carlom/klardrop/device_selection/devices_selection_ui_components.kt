package com.carlom.klardrop.device_selection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Checkbox
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.carlom.klardrop.device_selection.DevicesSelectionController.SelectionDeviceUi

@Composable
fun DeviceSelectionDashabord(devicesSelectionController: DevicesSelectionController) {

  val devices by devicesSelectionController.flow.collectAsState(emptyList())

  DiscoveryDashboard(
    devices = devices,

    send = { device: SelectionDeviceUi ->
      devicesSelectionController.sendTo(device.deviceId)
    }
  )
}

@Composable
internal fun DiscoveryDashboard(
  devices: List<SelectionDeviceUi>,
  send: (SelectionDeviceUi) -> Unit
) {
  Surface {
    LazyColumn {
      items(devices, key = { it.deviceId }) { device ->

        Row(modifier = Modifier
          .clickable { send(device) }
        ) {
          Column(modifier = Modifier.wrapContentWidth(align = Alignment.Start)) {
            Text(device.deviceName)
            Text(device.deviceId)
          }

        }

      }
    }
  }
}