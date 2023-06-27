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
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.PlatformFileSystem
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// TODO should this be composable and get the dispose callback to cancel scope?
class ShowVisibleDevicesController(
  private val coroutines: Coroutines,
  private val visibleDevices: VisibleDevices,
  private val messenger: Messenger,
  private val platformFileSystem: PlatformFileSystem,
) : OnDeviceActionListener {

  constructor(commonComponent: CommonComponent) : this(
    commonComponent.coroutines(),
    commonComponent.visibleDevices(),
    commonComponent.messenger(),
    commonComponent.platformFileSystem()
  )

  private val controllerScope = CoroutineScope(coroutines.mainDispatcher)
  private val showDevicesHelper = ShowDevicesControllerHelper(controllerScope, visibleDevices)

  val actionsFlow = MutableSharedFlow<ActionUi>()
  val flow: Flow<Collection<DeviceUi>> = showDevicesHelper.devicesFlow

  private fun sendText(deviceId: String, text: String) {
    coroutines.appScope.launch {
      messenger.send(deviceId, TextMessage(text).toSimpleSendRequest())
        .untilCompleted().let { showDevicesHelper.collectProgress(it, deviceId) }
    }
  }

  private fun sendFiles(deviceId: String, filesPaths: List<String>) {
    coroutines.appScope.launch {
      filesPaths.forEach { filePath ->
        val fileData = platformFileSystem.getResolvedFileData(filePath)
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
  val activityState: ActivityState = ActivityState.Idle,
  val connectionTypes: List<DeviceConnection.DeviceConnectionType>
)

sealed interface ActivityState {

  object Idle : ActivityState {
    override fun toString(): String {
      return "Idle"
    }
  }

  data class SentCompleted(val error: Boolean = false) : ActivityState
//  data class ReceiveCompleted(val error: Boolean = false) : ActivityState

  data class Sending(val progressPercentage: Int) : ActivityState

//  data class Receiving(val progressPercentage : Int) : ActivityState

}