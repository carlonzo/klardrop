package com.carlom.klardrop.android.share

import com.carlom.klardrop.DeviceUi
import com.carlom.klardrop.OnDataToSend
import com.carlom.klardrop.OnDataToSend.FilesList
import com.carlom.klardrop.OnDataToSend.Text
import com.carlom.klardrop.ShowDevicesControllerHelper
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSendRequest
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.communication.untilCompleted
import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.PlatformFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class ShareToDeviceController(
  private val coroutines: Coroutines,
  private val visibleDevices: VisibleDevices,
  private val messenger: Messenger,
  private val platformFileSystem: PlatformFileSystem
) {

  constructor(commonComponent: CommonComponent) : this(
    coroutines = commonComponent.coroutines(),
    visibleDevices = commonComponent.visibleDevices(),
    messenger = commonComponent.messenger(),
    platformFileSystem = commonComponent.platformFileSystem()
  )

  private val controllerScope = coroutines.newScope(coroutines.mainDispatcher)
  private val showDevicesHelper = ShowDevicesControllerHelper(controllerScope, visibleDevices)

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
      is FilesList -> sendFiles(deviceUi.deviceId, data.filesPath)
      is Text -> sendText(deviceUi.deviceId, data.text)
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


}