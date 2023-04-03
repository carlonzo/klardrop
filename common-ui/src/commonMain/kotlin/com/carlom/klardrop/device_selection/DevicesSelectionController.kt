package com.carlom.klardrop.device_selection

import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.toSendRequest
import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.utils.Coroutines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DevicesSelectionController(
  private val coroutines: Coroutines,
  private val visibleDevices: VisibleDevices,
  private val knownDevicesRepository: KnownDevicesRepository,
  private val messenger: Messenger
) {

  constructor(commonComponent: CommonComponent) : this(
    commonComponent.coroutines(),
    commonComponent.visibleDevices(),
    commonComponent.knownDevicesRepository(),
    commonComponent.messenger()
  )

  private val controllerScope = CoroutineScope(coroutines.mainDispatcher)

  lateinit var stringUri: String
  lateinit var filename: String
  var fileSize: Long = 0


  val flow: Flow<List<SelectionDeviceUi>> = visibleDevices.visibleDevices
    .combine(knownDevicesRepository.knownDevices) { visible, known ->
      visible.map {
        val deviceInfo = it.value
        SelectionDeviceUi(
          deviceInfo.deviceId,
          deviceInfo.name,
          it.value.deviceType.name,
          known.containsKey(it.key)
        )
      }
    }.stateIn(controllerScope, started = SharingStarted.Lazily, emptyList())

  fun sendTo(deviceId: String) {
    coroutines.appScope.launch {
      messenger.send(
        deviceId,
        FileMessage(
          filename,
          fileSize
        ).toSendRequest(stringUri)
      )
    }
  }

  data class SelectionDeviceUi(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val isKnown: Boolean
  )
}