package com.carlom.klardrop.android

import com.carlom.klardrop.DeviceUi
import com.carlom.klardrop.OnDataToSend
import com.carlom.klardrop.OnDataToSend.FilesList
import com.carlom.klardrop.OnDataToSend.Text
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSendRequest
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.FileResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ShareToDeviceController(
  private val coroutines: Coroutines,
  private val visibleDevices: VisibleDevices,
  private val knownDevicesRepository: KnownDevicesRepository,
  private val messenger: Messenger,
  private val fileResolver: FileResolver
) {

  constructor(commonComponent: CommonComponent) : this(
    coroutines = commonComponent.coroutines(),
    visibleDevices = commonComponent.visibleDevices(),
    knownDevicesRepository = commonComponent.knownDevicesRepository(),
    messenger = commonComponent.messenger(),
    fileResolver = commonComponent.fileResolver()
  )

  private val controllerScope = CoroutineScope(coroutines.mainDispatcher)

  private var onDataToSend: OnDataToSend? = null
  private val _devicesFlow = MutableStateFlow<Collection<DeviceUi>>(emptyList())
  val devicesFlow = _devicesFlow.asStateFlow()

  init {
    controllerScope.launch {
      visibleDevices.visibleDevices.map {
        it.values.map { deviceInfo ->
          DeviceUi(
            deviceInfo.deviceId,
            deviceInfo.name,
            deviceInfo.deviceType
          )
        }
      }.collect { _devicesFlow.emit(it) }
    }
  }

  fun initializeItemToShare(onDataToSend: OnDataToSend) {
    this.onDataToSend = onDataToSend
  }

  fun dispose() {
    controllerScope.cancel()
  }

  fun onDeviceClick(deviceUi: DeviceUi) {
    val data = onDataToSend ?: throw IllegalStateException("onDataToSend is null")

    when (data) {
      is FilesList -> sendFiles(deviceUi.deviceId, data.filesPath)
      is Text -> sendText(deviceUi.deviceId, data.text)
    }
  }

  private fun sendText(deviceId: String, text: String) {
    coroutines.appScope.launch { messenger.send(deviceId, TextMessage(text).toSimpleSendRequest()) }
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

}