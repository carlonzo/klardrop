package com.carlom.klardrop

import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSendRequest
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.FileResolver
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// TODO should this be composable and get the dispose callback to cancel scope?
class ShowVisibleDevicesController(
  private val coroutines: Coroutines,
  private val visibleDevices: VisibleDevices,
  private val knownDevicesRepository: KnownDevicesRepository,
  private val messenger: Messenger,
  private val fileResolver: FileResolver,
) : OnDeviceActionListener {

  constructor(commonComponent: CommonComponent) : this(
    commonComponent.coroutines(),
    commonComponent.visibleDevices(),
    commonComponent.knownDevicesRepository(),
    commonComponent.messenger(),
    commonComponent.fileResolver()
  )

  private val controllerScope = CoroutineScope(coroutines.mainDispatcher)

  val actionsFlow = MutableSharedFlow<ActionUi>()

  val flow: Flow<List<DeviceUi>> = visibleDevices.visibleDevices
    .combine(knownDevicesRepository.knownDevices) { visible, known ->
      visible.map {
        val deviceInfo = it.value
        DeviceUi(
          deviceInfo.deviceId,
          deviceInfo.name,
          it.value.deviceType,
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

  private fun sendText(deviceId: String, text: String) {
    coroutines.appScope.launch {
      messenger.send(deviceId, TextMessage(text).toSimpleSendRequest())
    }
  }

  private fun sendFiles(deviceId: String, filesPaths: List<String>) {
    coroutines.appScope.launch {
      filesPaths.forEach { filePath ->
        val fileData = fileResolver.getResolvedFileData(filePath)
        messenger.send(
          deviceId, FileMessage(
            fileData.fileName,
            fileData.fileSize,
            fileData.mimeType
          ).toSendRequest(filePath)
        )
      }
    }
  }

  override fun onDeviceClick(deviceUi: DeviceUi) {
    log("ShowVisibleDevicesController", "on device click: ${deviceUi.deviceName}")
  }

  override fun onSendData(deviceUi: DeviceUi, onDataToSend: OnDataToSend) {

    when (onDataToSend) {
      is OnDataToSend.FilesList -> sendFiles(deviceUi.deviceId, onDataToSend.filesPath)
      is OnDataToSend.Text -> sendText(deviceUi.deviceId, onDataToSend.text)
    }

  }

  override fun openFilePicker(deviceUi: DeviceUi) {

    controllerScope.launch {
      actionsFlow.emit(ActionUi.OpenFilePicker(deviceUi))
    }

  }

  fun dispose() {
    controllerScope.cancel()
  }

}

sealed interface ActionUi {

  class OpenFilePicker(val deviceUi: DeviceUi) : ActionUi

}

data class DeviceUi(
  val deviceId: String,
  val deviceName: String,
  val deviceType: DeviceType,
  val activityState: ActivityState = ActivityState.Idle
)

sealed interface ActivityState {

  object Idle : ActivityState

  data class SentCompleted(val error: Boolean = false) : ActivityState
//  data class ReceiveCompleted(val error: Boolean = false) : ActivityState

  data class Sending(val progressPercentage : Float) : ActivityState

//  data class Receiving(val progressPercentage : Int) : ActivityState

}