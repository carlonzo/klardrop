package com.carlom.klardrop

import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class ShowDevicesControllerHelper(
  private val coroutineScope: CoroutineScope,
  private val visibleDevices: VisibleDevices,
  private val messageRepository: MessageRepository
) {

  private val _devicesFlow = MutableStateFlow<Map<String, DeviceUi>>(mapOf())
  val devicesFlow: Flow<Collection<DeviceUi>> = _devicesFlow.map { it.values }

  init {
    coroutineScope.launch {
      combine(
        visibleDevices.visibleDevices.onEach { log("VisibleDevices", "emitting: $it") },
        messageRepository.getAllDevicesWithUnreadCounts()
      ) { devices, unreadCounts ->
        devices.values.map { device ->
          val deviceInfo = device.deviceInfo
          val unreadCount = unreadCounts[deviceInfo.deviceId] ?: 0L
          DeviceUi(
            deviceId = deviceInfo.deviceId,
            deviceName = deviceInfo.name,
            deviceType = deviceInfo.deviceType,
            connectionTypes = device.deviceConnections.map { it.deviceConnectionType }.distinct(),
            hasUnreadMessages = unreadCount > 0
          )
        }
      }.collect { deviceList ->
        _devicesFlow.emit(deviceList.associateBy { device -> device.deviceId }.toMutableMap())
      }
    }
  }

  suspend fun collectProgress(flow: Flow<MessengerSendProgress>, deviceId: String) {
    flow.collect { progress ->
      _devicesFlow.update { devices ->

        val device = devices[deviceId] ?: return@collect

        val newDevices = devices.toMutableMap()

        val activityState = when (progress) {
          MessengerSendProgress.Completed -> ActivityState.SentCompleted()
          is MessengerSendProgress.Error -> ActivityState.SentCompleted(error = true)
          is MessengerSendProgress.InProgress -> ActivityState.Sending(progress.percentage)
          MessengerSendProgress.Pending -> ActivityState.Sending(0)
        }

        newDevices[deviceId] = device.copy(
          activityState = activityState
        )
        newDevices
      }
    }

    // send idle state after 2 seconds
    coroutineScope.launch {
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