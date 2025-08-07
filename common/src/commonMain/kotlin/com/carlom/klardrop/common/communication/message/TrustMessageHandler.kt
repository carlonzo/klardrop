package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.trust.model.TrustMessage as ProtoTrustMessage
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.protobuf.ProtoBuf
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate

/**
 * Handler for trust protocol messages
 */
class TrustMessageHandler(
    private val trustManagerProvider: () -> TrustManager?
) : MessageHandler<TrustMessage, SendMessageRequest> {
    
    override suspend fun handleOutgoing(
        toDeviceId: String,
        request: SendMessageRequest,
        writeChannel: ByteWriteChannel,
        progressFlow: MutableSharedFlow<MessengerSendProgress>
    ) {
        // Trust messages are serialized normally like other messages
        progressFlow.emit(MessengerSendProgress.Completed)
    }
    
    override suspend fun handleIncoming(
        message: TrustMessage,
        readChannel: ByteReadChannel,
        receiveFlow: MutableStateFlow<ReceiveMessageUpdate>
    ) {
        val trustManager = trustManagerProvider()
        if (trustManager == null) {
            receiveFlow.value = ReceiveMessageUpdate(
                status = ReceiveMessageStatus.Failed("Trust system not initialized")
            )
            return
        }
        
        try {
            // Parse the trust protocol message using kotlinx.serialization
            val proto = ProtoBuf { }
            val trustProtocolMessage = proto.decodeFromByteArray(ProtoTrustMessage.serializer(), message.trustMessageBytes)
            
            // Handle the trust message
            trustManager.handleTrustMessage(trustProtocolMessage, "")  // TODO: get deviceId from somewhere
            
            // Update status
            receiveFlow.value = ReceiveMessageUpdate(
                messages = listOf(message),
                status = ReceiveMessageStatus.Completed
            )
        } catch (e: Exception) {
            receiveFlow.value = ReceiveMessageUpdate(
                status = ReceiveMessageStatus.Failed("Failed to handle trust message: ${e.message}")
            )
        }
    }
}