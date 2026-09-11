package com.carlom.klardrop.android.share

import com.carlom.klardrop.DeviceUi
import com.carlom.klardrop.ShowDevicesControllerHelper
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.Reachability
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.communication.untilCompleted
import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ShareToDeviceController(
  private val coroutines: Coroutines,
  private val visibleDevices: StateFlow<Map<String, DiscoveryDevice>>,
  private val messenger: Messenger,
  private val messageRepository: MessageRepository,
  private val trustedDevices: StateFlow<Map<String, DeviceInfo>>,
  private val reachabilitySource: StateFlow<Map<String, Reachability>>,
) {

  constructor(commonComponent: CommonComponent) : this(
    coroutines = commonComponent.coroutines(),
    visibleDevices = commonComponent.visibleDevices().visibleDevices,
    messenger = commonComponent.messenger(),
    messageRepository = commonComponent.messageRepository(),
    trustedDevices = commonComponent.trustedDevicesDirectory().trustedDevices,
    reachabilitySource = commonComponent.reachability(),
  )

  private val controllerScope = coroutines.newScope(coroutines.mainDispatcher)
  private val showDevicesHelper = ShowDevicesControllerHelper(
    controllerScope,
    visibleDevices,
    messageRepository,
    trustedDevices,
    reachabilitySource,
  )

  val devicesFlow: Flow<Collection<DeviceUi>> = showDevicesHelper.devicesFlow

  fun dispose() {
    controllerScope.cancel()
  }

  /**
   * Fire-and-forget text send. [onProgress] is invoked for every [MessengerSendProgress]
   * including the initial Pending, so the share sheet can show Connecting immediately
   * instead of dismissing into a silent wait.
   */
  fun sendText(
    deviceId: String,
    text: String,
    onProgress: ((MessengerSendProgress) -> Unit)? = null,
  ) {
    coroutines.appScope.launch {
      runCatching {
        messenger.send(deviceId, TextMessage(text = text).toSimpleSendRequest())
          .untilCompleted()
          .collect { progress -> onProgress?.invoke(progress) }
      }.onFailure { log("ShareToDeviceController", "Text send failed", it) }
    }
  }
}
