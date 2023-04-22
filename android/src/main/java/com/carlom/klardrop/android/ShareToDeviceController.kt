package com.carlom.klardrop.android

import com.carlom.klardrop.ActivityState
import com.carlom.klardrop.DeviceUi
import com.carlom.klardrop.OnDataToSend
import com.carlom.klardrop.OnDataToSend.FilesList
import com.carlom.klardrop.OnDataToSend.Text
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSendRequest
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.communication.untilCompleted
import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.PlatformFileSystem
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class ShareToDeviceController(
  private val coroutines: Coroutines,
  private val visibleDevices: VisibleDevices,
  private val knownDevicesRepository: KnownDevicesRepository,
  private val messenger: Messenger,
  private val platformFileSystem: PlatformFileSystem
) {

  constructor(commonComponent: CommonComponent) : this(
    coroutines = commonComponent.coroutines(),
    visibleDevices = commonComponent.visibleDevices(),
    knownDevicesRepository = commonComponent.knownDevicesRepository(),
    messenger = commonComponent.messenger(),
    platformFileSystem = commonComponent.platformFileSystem()
  )

  private val controllerScope = CoroutineScope(coroutines.mainDispatcher)

  private var onDataToSend: OnDataToSend? = null
  private val _devicesFlow = MutableStateFlow<Map<String, DeviceUi>>(mapOf())
  val devicesFlow: Flow<Collection<DeviceUi>> =
    _devicesFlow.map { it.values }.onEach { log("ShareToDeviceController", "devicesFlow emits: $it") }

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
      }.collect { _devicesFlow.emit(it.associateBy { device -> device.deviceId }.toMutableMap()) }
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

    coroutines.appScope.launch {
      messenger.send(deviceId, TextMessage(text).toSimpleSendRequest()).untilCompleted().collectProgress(deviceId)
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
        ).untilCompleted().collectProgress(deviceId)
      }
    }

  }

  private suspend fun Flow<MessengerSendProgress>.collectProgress(deviceId: String) {
    this.collect { progress ->
      _devicesFlow.update { devices ->

        val device = devices[deviceId] ?: return@collect

        val newDevices = devices.toMutableMap()

        val activityState = when (progress) {
          MessengerSendProgress.Closed -> ActivityState.SentCompleted()
          MessengerSendProgress.Completed -> ActivityState.SentCompleted()
          is MessengerSendProgress.Error -> ActivityState.SentCompleted(error = true)
          is MessengerSendProgress.InProgress -> ActivityState.Sending(progress.percentage)
          MessengerSendProgress.Pending -> ActivityState.Sending(0f)
        }

        newDevices[deviceId] = device.copy(
          activityState = activityState
        )
        newDevices
      }
    }

    // send idle state after 2 seconds
    controllerScope.launch {
      delay(2.seconds)

      _devicesFlow.update { devices ->
        val device = devices[deviceId] ?: return@launch

        val newDevices = devices.toMutableMap()

        newDevices[deviceId] = device.copy(
          activityState = ActivityState.Idle
        )

        newDevices
      }
    }
  }
}