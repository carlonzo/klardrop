package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.sendMessage
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.log
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class ConnectionInfoMessageHandler(
  private val serializer: MessageSerializer
) : MessageHandler<ConnectionInfoMessage, SimpleSendMessageRequest> {

  override suspend fun handleIncoming(
    message: ConnectionInfoMessage,
    readChannel: ByteReadChannel,
    receiveFlow: MutableStateFlow<ReceiveMessageUpdate>
  ) {
    log(TAG, "Received connection info for SSID '${message.ssid}' (${message.kind})")

    receiveFlow.update {
      it.copy(
        messages = listOf(message),
        status = ReceiveMessageStatus.Completed
      )
    }
  }

  override suspend fun handleOutgoing(
    toDeviceId: String,
    request: SimpleSendMessageRequest,
    writeChannel: ByteWriteChannel,
    progressFlow: MutableSharedFlow<MessengerSendProgress>
  ) {
    val connectionInfo = request.message as ConnectionInfoMessage
    log(TAG, "Sending connection info for SSID '${connectionInfo.ssid}' to $toDeviceId")
    writeChannel.sendMessage(connectionInfo, serializer)
  }

  private companion object {
    const val TAG = "ConnectionInfoMessageHandler"
  }
}
