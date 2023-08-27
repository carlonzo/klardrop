import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import com.carlom.klardrop.DeviceDiscovery
import com.carlom.klardrop.DeviceUi
import com.carlom.klardrop.OnDeviceActionListener
import com.carlom.klardrop.ReceiveNotification
import com.carlom.klardrop.ReceiveNotificationsCallbacks
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.DeviceType.MOBILE

@Preview
@Composable
fun DeviceSmallPreview() {
  DeviceDiscovery(
    DeviceUi(
      deviceName = "Carlo's phone",
      deviceId = "1234567890",
      deviceType = MOBILE,
      connectionTypes = listOf(DeviceConnection.DeviceConnectionType.KLARDROP, DeviceConnection.DeviceConnectionType.NEARBY)
    ),
    isLargeScreen = false,
    object : OnDeviceActionListener {

    }
  )
}

@Preview
@Composable
fun DeviceLargePreview() {
  DeviceDiscovery(
    DeviceUi(
      deviceName = "Carlo's phone",
      deviceId = "1234567890",
      deviceType = MOBILE,
      connectionTypes = listOf(DeviceConnection.DeviceConnectionType.KLARDROP, DeviceConnection.DeviceConnectionType.NEARBY)
    ),
    isLargeScreen = true,
    object : OnDeviceActionListener {

    }
  )
}

@Preview
@Composable
fun ReceivedCardPreviewProgress() {
  ReceiveNotification(
    receiveUpdate = ReceiveMessageUpdate(
      device = DeviceInfo(
        deviceId = "123",
        name = "Carlo's phone",
        deviceType = MOBILE
      ),
      messages = listOf(FileMessage("flower.jpg", 1234, "image/jpeg"), FileMessage("banana.jpg", 1234, "image/jpeg")),
      status = ReceiveMessageStatus.Progress(
        listOf(
          FileMessage("flower.jpg", 1234, "image/jpeg") to 40,
          FileMessage("flower.jpg", 1234, "image/jpeg") to 90
        )
      ),
    ),
    callbacks = noopCallback
  )
}

@Preview
@Composable
fun ReceivedCardPreviewReceived() {
  ReceiveNotification(
    receiveUpdate = ReceiveMessageUpdate(
      device = DeviceInfo(
        deviceId = "123",
        name = "Carlo's phone",
        deviceType = MOBILE
      ),
      messages = listOf(FileMessage("flower.jpg", 1234, "image/jpeg")),
      status = ReceiveMessageStatus.Completed
    ),
    callbacks = noopCallback
  )
}

private val noopCallback = object : ReceiveNotificationsCallbacks {
  override fun onReceivedCardClicked(receiveUpdate: ReceiveMessageUpdate) {

  }

  override fun onCardDismissed(receiveUpdate: ReceiveMessageUpdate) {
  }
}