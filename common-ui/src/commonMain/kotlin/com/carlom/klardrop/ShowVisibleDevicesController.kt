package com.carlom.klardrop

import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ShowVisibleDevicesController(
  private val coroutines: Coroutines,
  private val visibleDevices: VisibleDevices,
  private val knownDevicesRepository: KnownDevicesRepository,
  private val messenger: Messenger,
) : OnDeviceActionListener {

  constructor(commonComponent: CommonComponent) : this(
    commonComponent.coroutines(),
    commonComponent.visibleDevices(),
    commonComponent.knownDevicesRepository(),
    commonComponent.messenger(),
  )

  private val controllerScope = CoroutineScope(coroutines.mainDispatcher)


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

  private suspend fun sendText(deviceId: String, text: String) {
    messenger.send(deviceId, TextMessage(text).toSimpleSendRequest())
  }

  private suspend fun sendFiles(deviceId: String, filesPaths: List<String>) {
    log("ShowVisibleDevicesController", "TODO sendFiles: $filesPaths")
  }

  override fun onDeviceClick(deviceUi: DeviceUi) {
    log("ShowVisibleDevicesController", "on device click: ${deviceUi.deviceName}")
  }

  override fun onSendData(deviceUi: DeviceUi, onDataToSend: OnDeviceActionListener.OnDataToSend) {

    coroutines.appScope.launch {

      when (onDataToSend) {
        is OnDeviceActionListener.OnDataToSend.FilesList -> sendFiles(deviceUi.deviceId, onDataToSend.filesPath)
        is OnDeviceActionListener.OnDataToSend.Text -> sendText(deviceUi.deviceId, onDataToSend.text)
      }

    }

  }

  override fun openFilePicker(deviceUi: DeviceUi) {

//    coroutines.appScope.launch {
//
//      val filesPath = platformActions.openFileChooser()
//      sendFiles(deviceUi.deviceId, filesPath)
//
//    }

  }

}

data class DeviceUi(
  val deviceId: String,
  val deviceName: String,
  val deviceType: DeviceType
)