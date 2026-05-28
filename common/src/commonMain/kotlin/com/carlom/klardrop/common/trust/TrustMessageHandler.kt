package com.carlom.klardrop.common.trust

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.communication.message.MessageHandler
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.message.SimpleSendMessageRequest
import com.carlom.klardrop.common.communication.message.TrustPairingRequest
import com.carlom.klardrop.common.communication.message.TrustPairingResponse
import com.carlom.klardrop.common.communication.message.TrustRevocationMessage
import com.carlom.klardrop.common.communication.message.TrustedMessage
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
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
        
        // Delegate to TrustManager - it will emit events that PairingProtocolCoordinator can listen to
        println("🔐 [TrustPairingRequestHandler] Delegating to TrustManager.processPairingRequest()")
        trustManager.handleIncomingPairingRequest(message, senderAddress)
        
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
        trustManager.finalizePairing(message)
        
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
 * Message handler for [TrustRevocationMessage].
 *
 * Verifies the signature against the sender's stored ECDSA public key. If verification
 * succeeds, removes the peer from trust storage and emits a [PairingEvent.PeerRevokedTrust]
 * via [TrustManager]. If verification fails (bad signature, unknown sender, replay, stale
 * timestamp) the message is dropped silently — accepting unverifiable revocations would let
 * any LAN attacker forge "you've been unpaired" frames and tear down every pairing.
 */
class TrustRevocationMessageHandler(
    private val serializer: MessageSerializer,
    private val trustManager: TrustManager,
) : MessageHandler<TrustRevocationMessage, SimpleSendMessageRequest> {

    override suspend fun handleIncoming(
        message: TrustRevocationMessage,
        readChannel: ByteReadChannel,
        receiveFlow: MutableStateFlow<ReceiveMessageUpdate>
    ) {
        log("TrustRevocationMessageHandler", "Received revocation from ${message.senderId}")
        val verified = trustManager.verifyRevocationMessage(message)
        if (!verified) {
            log("TrustRevocationMessageHandler", "Dropping unverifiable revocation from ${message.senderId}")
            receiveFlow.update {
                it.copy(status = ReceiveMessageStatus.Failed("Invalid revocation signature"))
            }
            return
        }
        trustManager.applyVerifiedRevocation(message)
        receiveFlow.update {
            it.copy(messages = listOf(message), status = ReceiveMessageStatus.Completed)
        }
    }

    override suspend fun handleOutgoing(
        toDeviceId: String,
        request: SimpleSendMessageRequest,
        writeChannel: ByteWriteChannel,
        progressFlow: MutableSharedFlow<MessengerSendProgress>
    ) {
        val message = request.message as TrustRevocationMessage
        log("TrustRevocationMessageHandler", "Sending revocation to $toDeviceId")
        writeChannel.sendMessage(message, serializer)
    }
}

/**
 * Message handler for TrustedMessage - verifies signatures and processes trusted content.
 */
class TrustedMessageHandler(
    private val serializer: MessageSerializer,
    private val trustManager: TrustManager,
    private val messageHandlers: com.carlom.klardrop.common.communication.message.MessageHandlers
) : MessageHandler<TrustedMessage, SimpleSendMessageRequest> {
    
    private val trustMessageWrapper = TrustMessageWrapper(trustManager, serializer)

    override suspend fun handleIncoming(
        message: TrustedMessage,
        readChannel: ByteReadChannel,
        receiveFlow: MutableStateFlow<ReceiveMessageUpdate>
    ) {
        log("TrustedMessageHandler", "Received trusted message from ${message.senderId}")
        
        // Use TrustMessageWrapper to verify and unwrap the message
        val unwrappedMessage = trustMessageWrapper.unwrapMessage(message)
        
        if (unwrappedMessage == null) {
            log("TrustedMessageHandler", "Message verification failed for ${message.senderId}")
            receiveFlow.update {
                it.copy(
                    status = ReceiveMessageStatus.Failed("Message signature verification failed")
                )
            }
            return
        }
        
        log("TrustedMessageHandler", "Message verification succeeded, unwrapped ${unwrappedMessage.type}")
        
        // CRITICAL SAFEGUARD: Prevent infinite dispatch loops 
        if (unwrappedMessage.type == MessageType.TRUSTED_MESSAGE) {
            log("TrustedMessageHandler", "ERROR: Nested TRUSTED_MESSAGE detected. Dropping to prevent infinite loop.")
            receiveFlow.update {
                it.copy(
                    status = ReceiveMessageStatus.Failed("Invalid nested trusted message")
                )
            }
            return
        }
        
        // Delegate to the appropriate handler for the unwrapped message
        val targetHandler = messageHandlers[unwrappedMessage.type]
        if (targetHandler != null) {
            log("TrustedMessageHandler", "Delegating to ${unwrappedMessage.type} handler")
            @Suppress("UNCHECKED_CAST")
            val handler = targetHandler
            handler.handleIncoming(unwrappedMessage, readChannel, receiveFlow)
        } else {
            log("TrustedMessageHandler", "No handler found for unwrapped message type ${unwrappedMessage.type}")
            receiveFlow.update {
                it.copy(
                    status = ReceiveMessageStatus.Failed("No handler for message type ${unwrappedMessage.type}")
                )
            }
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