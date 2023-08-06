import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.carlom.klardrop.ActivityState
import com.carlom.klardrop.DeviceDiscovery
import com.carlom.klardrop.DeviceUi
import com.carlom.klardrop.OnDeviceActionListener
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.utils.DeviceType

@Preview
@Composable
fun PreviewDeviceDiscovery() {
  DeviceDiscovery(
    deviceUi = DeviceUi(
      deviceName = "Device Name",
      deviceType = DeviceType.MOBILE,
      deviceId = "Device Id",
      activityState = ActivityState.Idle,
      connectionTypes = listOf(DeviceConnection.DeviceConnectionType.KLARDROP, DeviceConnection.DeviceConnectionType.NEARBY)
    ),
    isLargeScreen = false,
    onDeviceActionListener = object : OnDeviceActionListener {}
  )
}