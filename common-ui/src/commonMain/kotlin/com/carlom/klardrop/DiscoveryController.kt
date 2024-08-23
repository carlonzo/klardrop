package com.carlom.klardrop

import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSendRequest
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.communication.untilCompleted
import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.features.ClipboardManager
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.PlatformFileSystem
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

// TODO should this be composable and get the dispose callback to cancel scope?
class DiscoveryController(
  private val coroutines: Coroutines,
  visibleDevices: VisibleDevices,
  private val messenger: Messenger,
  private val platformFileSystem: PlatformFileSystem,
  private val clipboardManager: ClipboardManager,
) : OnDeviceActionListener, ReceiveNotificationsCallbacks {

  constructor(commonComponent: CommonComponent) : this(
    commonComponent.coroutines(),
    commonComponent.visibleDevices(),
    commonComponent.messenger(),
    commonComponent.platformFileSystem(),
    commonComponent.clipboardManager()
  )

  private val controllerScope = coroutines.newScope(coroutines.mainDispatcher + SupervisorJob())
  private val showDevicesHelper = ShowDevicesControllerHelper(controllerScope, visibleDevices)

  val actionsFlow = MutableSharedFlow<ActionUi>()
  val screenStateFlow = MutableStateFlow(DiscoveryScreenState())

  init {
    controllerScope.launch {
      messenger.receive().collect {
        listenNewMessagesReceived(it)
      }
    }

    controllerScope.launch {
      showDevicesHelper.devicesFlow.collect {
        screenStateFlow.update { state ->
          state.copy(devices = it.toList())
        }
      }
    }
  }

  private fun sendText(deviceId: String, text: String) {
    coroutines.appScope.launch {
      messenger.send(deviceId, TextMessage(text = text).toSimpleSendRequest())
        .untilCompleted().let { showDevicesHelper.collectProgress(it, deviceId) }
    }
  }

  private fun sendFiles(deviceId: String, filesPaths: List<String>) {
    coroutines.appScope.launch {
      filesPaths.forEach { filePath ->

        val fileData = runCatching { platformFileSystem.getResolvedFileData(filePath) }
          .onFailure { log("DiscoveryController", "Unable to resolve file at path $filePath. File cannot be sent!", it) }
          .getOrNull() ?: return@forEach

        messenger.send(
          deviceId, FileMessage(
            fileData.fileName,
            fileData.fileSize,
            fileData.mimeType
          ).toSendRequest(filePath)
        ).untilCompleted().let { showDevicesHelper.collectProgress(it, deviceId) }
      }
    }
  }

  override fun onDeviceClick(deviceUi: DeviceUi) {
    log("DiscoveryController", "on device click: ${deviceUi.deviceName}")
    controllerScope.launch {
      actionsFlow.emit(ActionUi.OnDeviceClicked(deviceUi))
    }
  }

  override fun onSendData(deviceUi: DeviceUi, onDataToSend: OnDataToSend) {

    when (onDataToSend) {
      is OnDataToSend.FilesList -> sendFiles(deviceUi.deviceId, onDataToSend.filesPath)
      is OnDataToSend.Text -> sendText(deviceUi.deviceId, onDataToSend.text)
    }

  }

  private fun listenNewMessagesReceived(flow: Flow<ReceiveMessageUpdate>) {

    val receiveId = Random.nextInt()

    controllerScope.launch {

      flow.transformWhile {
        emit(it)

        !it.status.isFinished()
      }.collect { receiveMessageUpdate ->
        screenStateFlow.update {
          val messages = it.receivingMessages.toMutableMap()
          messages[receiveId] = receiveMessageUpdate

          it.copy(
            receivingMessages = messages
          )
        }
      }

    }

  }

  fun dispose() {
    controllerScope.cancel()
  }

  fun readFromClipboard(): String {
    return clipboardManager.read()
  }

  override fun onReceivedCardClicked(receiveUpdate: ReceiveMessageUpdate) {

    if (receiveUpdate.status is ReceiveMessageStatus.Completed) {

      val firstMessage = receiveUpdate.messages.first()

      if (firstMessage is TextMessage) {
        val text = firstMessage.text
        clipboardManager.write(text)
      }

    }

  }

  override fun onCardDismissed(receiveUpdate: ReceiveMessageUpdate) {
    screenStateFlow.update {
      val key = it.receivingMessages.entries.firstOrNull { entry -> entry.value == receiveUpdate }?.key

      if (key == null) it
      else it.copy(
        receivingMessages = it.receivingMessages - key
      )
    }

  }
}

data class DiscoveryScreenState(
  val devices: List<DeviceUi> = emptyList(),
  val receivingMessages: Map<Int, ReceiveMessageUpdate> = emptyMap()
)

sealed interface ActionUi {

  class OnDeviceClicked(val deviceUi: DeviceUi) : ActionUi

}

data class DeviceUi(
  val deviceId: String,
  val deviceName: String,
  val deviceType: DeviceType,
  val activityState: ActivityState = ActivityState.Idle,
  val connectionTypes: List<DeviceConnection.DeviceConnectionType>
)

sealed interface ActivityState {

  data object Idle : ActivityState

  data class SentCompleted(val error: Boolean = false) : ActivityState
//  data class ReceiveCompleted(val error: Boolean = false) : ActivityState

  data class Sending(val progressPercentage: Int) : ActivityState

//  data class Receiving(val progressPercentage : Int) : ActivityState

}