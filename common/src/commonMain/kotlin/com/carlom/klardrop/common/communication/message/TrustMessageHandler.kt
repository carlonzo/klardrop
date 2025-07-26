package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.trust.TrustManager
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate

/**
 * Handler for trust protocol messages
 */
class TrustMessageHandler(
    private val trustManagerProvider: () -> TrustManager?
) : MessageHandler<TrustMessage, SendMessageRequest> {
    
    override suspend fun handleSend(
        message: TrustMessage,
        writeChannel: ByteWriteChannel,
        progress: MutableSharedFlow<MessengerSendProgress>
    ) {
        // Trust messages are serialized normally like other messages
        progress.emit(MessengerSendProgress.Completed)
    }
    
    override suspend fun handleReceive(
        message: TrustMessage,
        deviceId: String,
        status: MutableStateFlow<ReceiveMessageUpdate>
    ) {
        val trustManager = trustManagerProvider()
        if (trustManager == null) {
            status.value = ReceiveMessageUpdate(
                status = ReceiveMessageStatus.ERROR,
                error = "Trust system not initialized"
            )
            return
        }
        
        try {
            // Parse the trust protocol message
            val trustProtocolMessage = com.carlom.klardrop.protos.trust.TrustMessage.parseFrom(message.trustMessageBytes)
            
            // Handle the trust message
            trustManager.handleTrustMessage(trustProtocolMessage, deviceId)
            
            // Update status
            status.value = ReceiveMessageUpdate(
                status = ReceiveMessageStatus.COMPLETED,
                message = message
            )
        } catch (e: Exception) {
            status.value = ReceiveMessageUpdate(
                status = ReceiveMessageStatus.ERROR,
                error = "Failed to handle trust message: ${e.message}"
            )
        }
    }
    
    override suspend fun onWriteReady(message: TrustMessage, channel: ByteWriteChannel): Boolean {
        // Trust messages are always ready to write
        return true
    }
}