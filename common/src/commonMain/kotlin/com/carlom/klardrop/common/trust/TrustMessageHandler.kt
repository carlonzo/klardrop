package com.carlom.klardrop.common.trust

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.communication.message.MessageHandler
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.SimpleSendMessageRequest
import com.carlom.klardrop.common.communication.message.TrustPairingRequest
import com.carlom.klardrop.common.communication.message.TrustPairingResponse
import com.carlom.klardrop.common.communication.message.TrustedMessage
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.communication.sendMessage
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.utils.log
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Message handler for TrustPairingRequest messages.
 * Handles incoming pairing requests and delegates to TrustManager.
 */
class TrustPairingRequestHandler(
    private val serializer: MessageSerializer,
    private val trustManager: TrustManager
) : MessageHandler<TrustPairingRequest, SimpleSendMessageRequest> {

    override suspend fun handleIncoming(
        message: TrustPairingRequest,
        readChannel: ByteReadChannel,
        receiveFlow: MutableStateFlow<ReceiveMessageUpdate>
    ) {
        println("🔐 [TrustPairingRequestHandler] ✅ Received pairing request from ${message.deviceName} (${message.deviceId})")
        log("TrustPairingRequestHandler", "Received pairing request from ${message.deviceName}")
        
        // Extract sender address from context (this would normally come from the connection)
        // For now, we'll use the device ID as the address
        val senderAddress = message.deviceId
        println("🔐 [TrustPairingRequestHandler] Using sender address: $senderAddress")
        
        // Delegate to TrustManager
        println("🔐 [TrustPairingRequestHandler] Delegating to TrustManager.handlePairingRequest()")
        trustManager.handlePairingRequest(message, senderAddress)
        
        // Update receive flow 
        receiveFlow.update {
            it.copy(
                messages = listOf(message),
                status = ReceiveMessageStatus.Completed
            )
        }
        println("🔐 [TrustPairingRequestHandler] ✅ Completed handling pairing request")
    }

    override suspend fun handleOutgoing(
        toDeviceId: String,
        request: SimpleSendMessageRequest,
        writeChannel: ByteWriteChannel,
        progressFlow: MutableSharedFlow<MessengerSendProgress>
    ) {
        val message = request.message as TrustPairingRequest
        log("TrustPairingRequestHandler", "Sending pairing request to ${message.deviceName}")
        
        // Send the message
        writeChannel.sendMessage(message, serializer)
    }
}

/**
 * Message handler for TrustPairingResponse messages.
 * Handles incoming pairing responses and delegates to TrustManager.
 */
class TrustPairingResponseHandler(
    private val serializer: MessageSerializer,
    private val trustManager: TrustManager
) : MessageHandler<TrustPairingResponse, SimpleSendMessageRequest> {

    override suspend fun handleIncoming(
        message: TrustPairingResponse,
        readChannel: ByteReadChannel,
        receiveFlow: MutableStateFlow<ReceiveMessageUpdate>
    ) {
        log("TrustPairingResponseHandler", "Received pairing response from ${message.deviceName}")
        
        // Delegate to TrustManager
        trustManager.handlePairingResponse(message)
        
        // Update receive flow
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
        val message = request.message as TrustPairingResponse
        log("TrustPairingResponseHandler", "Sending pairing response to ${message.deviceName}")
        
        // Send the message
        writeChannel.sendMessage(message, serializer)
    }
}

/**
 * Message handler for TrustedMessage - verifies signatures and processes trusted content.
 */
class TrustedMessageHandler(
    private val serializer: MessageSerializer,
    private val trustManager: TrustManager
) : MessageHandler<TrustedMessage, SimpleSendMessageRequest> {

    override suspend fun handleIncoming(
        message: TrustedMessage,
        readChannel: ByteReadChannel,
        receiveFlow: MutableStateFlow<ReceiveMessageUpdate>
    ) {
        log("TrustedMessageHandler", "Received trusted message from ${message.senderId}")
        
        // Verify the message signature
        val isValid = trustManager.verifyMessage(message)
        
        if (!isValid) {
            log("TrustedMessageHandler", "Message verification failed for ${message.senderId}")
            receiveFlow.update {
                it.copy(
                    status = ReceiveMessageStatus.Failed("Message signature verification failed")
                )
            }
            return
        }
        
        log("TrustedMessageHandler", "Message verification succeeded for ${message.senderId}")
        
        // Update receive flow with the verified trusted message
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
        val message = request.message as TrustedMessage
        log("TrustedMessageHandler", "Sending trusted message to $toDeviceId")
        
        // Send the signed message
        writeChannel.sendMessage(message, serializer)
    }
}

/**
 * Utility class for wrapping regular messages in trusted envelopes.
 */
class TrustMessageWrapper(
    private val trustManager: TrustManager,
    private val serializer: MessageSerializer
) {
    
    /**
     * Wrap a regular message in a trusted envelope if the target device is trusted.
     * @param originalMessage The message to wrap
     * @param targetDeviceId The device ID to send to
     * @return TrustedMessage if device is trusted, null otherwise
     */
    suspend fun wrapMessage(
        originalMessage: Message,
        targetDeviceId: String
    ): TrustedMessage? {
        // Only wrap if target is trusted
        if (!trustManager.isTrusted(targetDeviceId)) {
            return null
        }
        
        // Serialize original message to bytes
        val payload = serializer.serialize(originalMessage)
        
        // Sign and wrap the message
        return trustManager.signMessage(payload)
    }
    
    /**
     * Unwrap a trusted message and deserialize the original payload.
     * @param trustedMessage The trusted message to unwrap
     * @return The original message if verification succeeds, null otherwise
     */
    suspend fun unwrapMessage(
        trustedMessage: TrustedMessage
    ): Message? {
        // Verify the message signature
        val isValid = trustManager.verifyMessage(trustedMessage)
        if (!isValid) {
            return null
        }
        
        // Deserialize the original message from payload
        return try {
            serializer.deserialize(trustedMessage.payload)
        } catch (e: Exception) {
            log("TrustMessageWrapper", "Failed to deserialize trusted message payload: ${e.message}")
            null
        }
    }
}

/**
 * Extension functions to create send requests for trust messages.
 */
fun TrustPairingRequest.toSendRequest(): SimpleSendMessageRequest = this.toSimpleSendRequest() as SimpleSendMessageRequest
fun TrustPairingResponse.toSendRequest(): SimpleSendMessageRequest = this.toSimpleSendRequest() as SimpleSendMessageRequest
fun TrustedMessage.toSendRequest(): SimpleSendMessageRequest = this.toSimpleSendRequest() as SimpleSendMessageRequest