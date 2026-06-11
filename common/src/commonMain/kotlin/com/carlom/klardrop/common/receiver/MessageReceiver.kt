package com.carlom.klardrop.common.receiver

import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Internal manager used to communicate received messages between servers and Messenger
 */
interface MessageReceiver {

  fun onReceiveMessage(deviceId: String): MutableStateFlow<ReceiveMessageUpdate>

  val notifier: Flow<Pair<String, StateFlow<ReceiveMessageUpdate>>> // Changed

  /**
   * Flow that emits updates when a message is received and the status is completed.
   */
  val messageReceivedNotifier: Flow<ReceiveMessageUpdate>

  /**
   * Aggregated, retained latest [ReceiveMessageUpdate] per device id.
   *
   * Unlike [notifier] (a replay-less SharedFlow), this StateFlow always holds the CURRENT
   * receive state for each device, so a consumer that starts observing late — e.g. a
   * per-device chat screen opened after an incoming-transfer prompt already fired — still
   * sees the pending authorization. The discovery/home screen banner uses [notifier]; the
   * chat screen uses this so the accept/reject banner appears on both regardless of timing.
   *
   * Default is an empty constant for lightweight test fakes that don't exercise it.
   */
  val latestUpdates: StateFlow<Map<String, ReceiveMessageUpdate>>
    get() = MutableStateFlow(emptyMap())

}

internal class MessageReceiverImpl(
  coroutines: Coroutines,
  private val visibleDevices: VisibleDevices,
) : MessageReceiver {

  private val receiverScope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)
  private val _notifier = MutableSharedFlow<Pair<String, StateFlow<ReceiveMessageUpdate>>>()

  private val _latestUpdates = MutableStateFlow<Map<String, ReceiveMessageUpdate>>(emptyMap())
  override val latestUpdates: StateFlow<Map<String, ReceiveMessageUpdate>> = _latestUpdates.asStateFlow()

  init {
    // Mirror every per-device receive flow into [latestUpdates] so consumers (the per-device
    // chat screen) can read the CURRENT receive state for a device at any time — [notifier]
    // is replay-less and is missed by a screen opened after the prompt fired. Each produced
    // flow is followed until its transfer finishes, after which the collector ends (bounding
    // collector growth); the device's last update is retained in the map.
    receiverScope.launch {
      _notifier.collect { (deviceId, flow) ->
        launch {
          flow.transformWhile { update ->
            emit(update)
            !update.status.isFinished()
          }.collect { update ->
            _latestUpdates.update { it + (deviceId to update) }
          }
        }
      }
    }
  }

  override val notifier: Flow<Pair<String, StateFlow<ReceiveMessageUpdate>>>
    get() = _notifier.asSharedFlow()

  override val messageReceivedNotifier: Flow<ReceiveMessageUpdate>
    get() = _notifier.flatMapMerge {
      it.second.mapNotNull { update ->
        if (update.status is ReceiveMessageStatus.Completed) update
        else null
      }
    }

  override fun onReceiveMessage(deviceId: String): MutableStateFlow<ReceiveMessageUpdate> {

    val device = visibleDevices.getDevice(deviceId)?.deviceInfo ?: DeviceInfo(
      deviceId = deviceId, name = "unknown", deviceType = DeviceType.UNKNOWN
    )

    val flow = MutableStateFlow(
      ReceiveMessageUpdate(
        device = device, status = ReceiveMessageStatus.Started
      )
    )

    receiverScope.launch {
      _notifier.emit(deviceId to flow.asStateFlow()) // Changed: emit Pair
    }

    return flow
  }

}

data class ReceiveMessageUpdate(
  val device: DeviceInfo? = null,
  val messages: List<Message> = emptyList(),
  val status: ReceiveMessageStatus
)

sealed interface ReceiveMessageStatus {

  data object Started : ReceiveMessageStatus

  data class PendingAuthorization(val acceptTransfer: (Boolean) -> Unit) : ReceiveMessageStatus

  data class Progress(val messages: List<Pair<Message, Int>>) : ReceiveMessageStatus

  data class Failed(val reason: String) : ReceiveMessageStatus

  data object Completed : ReceiveMessageStatus

  fun isFinished(): Boolean = this is Completed || this is Failed
}
