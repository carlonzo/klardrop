import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import com.carlom.klardrop.DeviceSmall
import com.carlom.klardrop.DeviceUi
import com.carlom.klardrop.OnDeviceActionListener
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.utils.DeviceType.MOBILE

@Preview
@Composable
fun DevicePreview() {
  DeviceSmall(
    DeviceUi(
      deviceName = "Carlo's phone",
      deviceId = "1234567890",
      deviceType = MOBILE,
      connectionTypes = listOf(DeviceConnection.DeviceConnectionType.KLARDROP, DeviceConnection.DeviceConnectionType.NEARBY)
    ),
    object : OnDeviceActionListener {

    }
  )
}