import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.message.TextEnvelope
import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.utils.Coroutines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DiscoveryUIController(
  private val coroutines: Coroutines,
  private val visibleDevices: VisibleDevices,
  private val knownDevicesRepository: KnownDevicesRepository,
  private val messenger: Messenger
) {

  constructor(commonComponent: CommonComponent) : this(
    commonComponent.coroutines(),
    commonComponent.visibleDevices(),
    commonComponent.knownDevicesRepository(),
    commonComponent.messenger()
  )

  private val controllerScope = CoroutineScope(coroutines.mainDispatcher)


  val flow: Flow<List<DiscoveryDeviceUi>> = visibleDevices.visibleDevices
    .combine(knownDevicesRepository.knownDevices) { visible, known ->
      visible.map {
        val deviceInfo = it.value
        DiscoveryDeviceUi(
          deviceInfo.deviceId,
          deviceInfo.name,
          it.value.deviceType.name,
          known.containsKey(it.key)
        )
      }
    }.stateIn(controllerScope, started = SharingStarted.Lazily, emptyList())

  fun onDeviceKnownChanged(deviceId: String, markAsKnown: Boolean) {
    controllerScope.launch(coroutines.ioDispatcher) {
      if (markAsKnown) {
        val deviceInfo = visibleDevices.getDeviceInfo(deviceId)!!
        knownDevicesRepository.addKnownDevice(deviceInfo)
      } else {
        knownDevicesRepository.removeKnownDevice(deviceId)
      }
    }

  }

  fun sendText(deviceId: String) {
    controllerScope.launch(coroutines.ioDispatcher) {
      messenger.send(deviceId, TextEnvelope("Hi from here!"))
    }

  }

  data class DiscoveryDeviceUi(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val isKnown: Boolean
  )
}