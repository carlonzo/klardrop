package com.carlom.klardrop.common.receiver

import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Internal manager used to communicate received messages between servers and Messenger
 */
interface MessageReceiver {

  fun onReceiveMessage(deviceId: String = ""): MutableStateFlow<ReceiveMessageUpdate>

  val notifier: Flow<Flow<ReceiveMessageUpdate>>

}


internal class MessageReceiverImpl(
  private val coroutines: Coroutines,
  private val visibleDevices: VisibleDevices
) : MessageReceiver {

  private val receiverScope = CoroutineScope(coroutines.ioDispatcher)
  private val _notifier = MutableSharedFlow<StateFlow<ReceiveMessageUpdate>>()

  override val notifier: Flow<Flow<ReceiveMessageUpdate>>
    get() = _notifier.asSharedFlow()

  override fun onReceiveMessage(deviceId: String): MutableStateFlow<ReceiveMessageUpdate> {

    val device = visibleDevices.getDevice(deviceId)?.deviceInfo ?: DeviceInfo(
      deviceId = deviceId,
      name = "",
      deviceType = DeviceType.UNKNOWN
    )

    val flow = MutableStateFlow(
      ReceiveMessageUpdate(
        device = device,
        status = ReceiveMessageStatus.Started
      )
    )

    receiverScope.launch {
      _notifier.emit(flow.asStateFlow())
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

  object Started : ReceiveMessageStatus

  data class PendingAuthorization(val acceptTransfer: (Boolean) -> Unit) : ReceiveMessageStatus

  data class Progress(val messages: List<Pair<Message, Int>>) : ReceiveMessageStatus

  data class Failed(val reason: String) : ReceiveMessageStatus

  object Completed : ReceiveMessageStatus

  fun isFinished(): Boolean = this is Completed || this is Failed
}