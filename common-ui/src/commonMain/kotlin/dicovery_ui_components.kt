import DiscoveryUIController.DiscoveryDeviceUi
import androidx.compose.foundation.ExperimentalFoundationApi
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

@Composable
fun DiscoveryDashboard(discoveryUIController: DiscoveryUIController) {

  val devices by discoveryUIController.flow.collectAsState(emptyList())

  DiscoveryDashboard(
    devices = devices,
    onDeviceClicked = { markAsKnown: Boolean, device: DiscoveryDeviceUi ->
      discoveryUIController.onDeviceKnownChanged(device.deviceId, markAsKnown)
    },

    sendText = { device: DiscoveryDeviceUi ->
      discoveryUIController.sendText(device.deviceId)
    }
  )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DiscoveryDashboard(
  devices: List<DiscoveryDeviceUi>,
  onDeviceClicked: ((Boolean, DiscoveryDeviceUi) -> Unit),
  sendText: (DiscoveryDeviceUi) -> Unit
) {
  Surface {
    LazyColumn {
      items(devices, key = { it.deviceId }) { device ->

        Row(modifier = Modifier
          .clickable { sendText(device) }
        ) {
          Column(modifier = Modifier.wrapContentWidth(align = Alignment.Start)) {
            Text(device.deviceName)
            Text(device.deviceId)
          }

          Checkbox(
            onCheckedChange = { checked -> onDeviceClicked(checked, device) },
            checked = device.isKnown,
          )

        }

      }
    }
  }
}