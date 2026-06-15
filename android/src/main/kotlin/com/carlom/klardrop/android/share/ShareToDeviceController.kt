package com.carlom.klardrop.android.share

import com.carlom.klardrop.DeviceUi
import com.carlom.klardrop.ShowDevicesControllerHelper
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.Reachability
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.communication.untilCompleted
import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ShareToDeviceController(
  private val coroutines: Coroutines,
  private val visibleDevices: VisibleDevices,
  private val messenger: Messenger,
  private val messageRepository: MessageRepository,
  private val trustStorage: com.carlom.klardrop.common.trust.TrustStorage,
  private val reachabilitySource: StateFlow<Map<String, Reachability>>,
) {

  constructor(commonComponent: CommonComponent) : this(
    coroutines = commonComponent.coroutines(),
    visibleDevices = commonComponent.visibleDevices(),
    messenger = commonComponent.messenger(),
    messageRepository = commonComponent.messageRepository(),
    trustStorage = commonComponent.trustStorage(),
    reachabilitySource = commonComponent.reachability(),
  )

  private val controllerScope = coroutines.newScope(coroutines.mainDispatcher)
  private val showDevicesHelper = ShowDevicesControllerHelper(controllerScope, visibleDevices, messageRepository, trustStorage, reachabilitySource)

  val devicesFlow: Flow<Collection<DeviceUi>> = showDevicesHelper.devicesFlow

  fun dispose() {
    controllerScope.cancel()
  }

  /** Fire-and-forget text send. Text carries no content grant, so nothing needs to be cached. */
  fun sendText(deviceId: String, text: String) {
    coroutines.appScope.launch {
      runCatching {
        messenger.send(deviceId, TextMessage(text = text).toSimpleSendRequest()).untilCompleted().collect { }
      }.onFailure { log("ShareToDeviceController", "Text send failed", it) }
    }
  }
}
