package com.carlom.klardrop.common.trust

import com.carlom.klardrop.common.communication.FrameCipher
import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.ClipboardSyncMessage
import com.carlom.klardrop.common.communication.message.MessageHandler
import com.carlom.klardrop.common.communication.message.SimpleSendMessageRequest
import com.carlom.klardrop.common.communication.sendMessage
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.log
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Message handler for ClipboardSyncMessage.
 * Handles incoming clipboard sync messages and delegates to ClipboardSyncManager.
 */
class ClipboardSyncMessageHandler(
    private val serializer: MessageSerializer,
    private val clipboardSyncManager: ClipboardSyncManager
) : MessageHandler<ClipboardSyncMessage, SimpleSendMessageRequest> {

    override suspend fun handleIncoming(
        message: ClipboardSyncMessage,
        readChannel: ByteReadChannel,
        receiveFlow: MutableStateFlow<ReceiveMessageUpdate>
    ) {
        log("ClipboardSyncMessageHandler", "Received clipboard sync message")
        
        // Get sender device ID from the receive flow
        val senderId = receiveFlow.value.device?.deviceId
        
        if (senderId == null) {
            log("ClipboardSyncMessageHandler", "No sender device ID found, ignoring clipboard sync")
            receiveFlow.update {
                it.copy(
                    status = ReceiveMessageStatus.Failed("No sender device ID")
                )
            }
            return
        }
        
        try {
            // Delegate to clipboard sync manager
            clipboardSyncManager.handleIncomingClipboardSync(message, senderId)
            
            // Update receive flow to indicate completion
            receiveFlow.update {
                it.copy(
                    messages = listOf(message),
                    status = ReceiveMessageStatus.Completed
                )
            }
            
            log("ClipboardSyncMessageHandler", "Clipboard sync processed successfully")
            
        } catch (e: Exception) {
            log("ClipboardSyncMessageHandler", "Failed to process clipboard sync: ${e.message}")
            receiveFlow.update {
                it.copy(
                    status = ReceiveMessageStatus.Failed("Failed to sync clipboard: ${e.message}")
                )
            }
        }
    }

    override suspend fun handleOutgoing(
        toDeviceId: String,
        request: SimpleSendMessageRequest,
        writeChannel: ByteWriteChannel,
        progressFlow: MutableSharedFlow<MessengerSendProgress>,
        cipher: FrameCipher,
    ) {
        val message = request.message as ClipboardSyncMessage
        log("ClipboardSyncMessageHandler", "Sending clipboard sync message to $toDeviceId")

        try {
            // Send the clipboard sync message
            writeChannel.sendMessage(message, serializer, cipher)
            log("ClipboardSyncMessageHandler", "Clipboard sync message sent successfully")
        } catch (e: Exception) {
            log("ClipboardSyncMessageHandler", "Failed to send clipboard sync: ${e.message}")
            throw e
        }
    }
}