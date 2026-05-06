package com.carlom.klardrop.android.share

import com.carlom.klardrop.DeviceUi
import com.carlom.klardrop.OnDataToSend
import com.carlom.klardrop.OnDataToSend.FilesList
import com.carlom.klardrop.OnDataToSend.Text
import com.carlom.klardrop.OnDataToSend.WifiCredentials
import com.carlom.klardrop.ShowDevicesControllerHelper
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.Reachability
import com.carlom.klardrop.common.communication.message.ConnectionInfoMessage
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSendRequest
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.communication.untilCompleted
import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.PlatformFileSystem
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ShareToDeviceController(
  private val coroutines: Coroutines,
  private val visibleDevices: VisibleDevices,
  private val messenger: Messenger,
  private val platformFileSystem: PlatformFileSystem,
  private val messageRepository: MessageRepository,
  private val trustStorage: com.carlom.klardrop.common.trust.TrustStorage,
  private val reachabilitySource: StateFlow<Map<String, Reachability>>,
) {

  constructor(commonComponent: CommonComponent) : this(
    coroutines = commonComponent.coroutines(),
    visibleDevices = commonComponent.visibleDevices(),
    messenger = commonComponent.messenger(),
    platformFileSystem = commonComponent.platformFileSystem(),
    messageRepository = commonComponent.messageRepository(),
    trustStorage = commonComponent.trustStorage(),
    reachabilitySource = commonComponent.reachability(),
  )

  private val controllerScope = coroutines.newScope(coroutines.mainDispatcher)
  private val showDevicesHelper = ShowDevicesControllerHelper(controllerScope, visibleDevices, messageRepository, trustStorage, reachabilitySource)

  private var onDataToSend: OnDataToSend? = null

  val devicesFlow: Flow<Collection<DeviceUi>> = showDevicesHelper.devicesFlow

  fun initializeItemToShare(onDataToSend: OnDataToSend) {
    this.onDataToSend = onDataToSend
  }

  fun dispose() {
    controllerScope.cancel()
  }

  fun onDeviceClick(deviceUi: DeviceUi) {
    val data = onDataToSend ?: throw IllegalStateException("onDataToSend is null")

    when (data) {
      is FilesList -> sendFiles(deviceUi.deviceId, data.files)
      is Text -> sendText(deviceUi.deviceId, data.text)
      is WifiCredentials -> sendWifiCredentials(deviceUi.deviceId, data)
    }
  }

  private fun sendText(deviceId: String, text: String) {

    coroutines.appScope.launch {
      messenger.send(deviceId, TextMessage(text = text).toSimpleSendRequest())
        .untilCompleted().let { showDevicesHelper.collectProgress(it, deviceId) }
    }

  }

  private fun sendFiles(deviceId: String, files: List<PlatformFile>) {
    coroutines.appScope.launch {
      files.forEach { file ->
        val fileData = platformFileSystem.getResolvedFileData(file)
        messenger.send(
          deviceId, FileMessage(
            fileData.fileName,
            fileData.fileSize,
            fileData.mimeType
          ).toSendRequest(file)
        ).untilCompleted().let { showDevicesHelper.collectProgress(it, deviceId) }
      }
    }

  }

  private fun sendWifiCredentials(deviceId: String, data: WifiCredentials) {
    coroutines.appScope.launch {
      messenger.send(
        deviceId,
        ConnectionInfoMessage(
          kind = data.kind,
          ssid = data.ssid,
          password = data.password,
          hidden = data.hidden,
        ).toSimpleSendRequest()
      ).untilCompleted().let { showDevicesHelper.collectProgress(it, deviceId) }
    }
  }

}
