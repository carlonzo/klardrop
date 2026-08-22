package com.carlom.klardrop.common.trust

import com.carlom.klardrop.common.communication.FrameCipher
import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.MessageHandler
import com.carlom.klardrop.common.communication.message.SimpleSendMessageRequest
import com.carlom.klardrop.common.communication.message.TrustPairingRequest
import com.carlom.klardrop.common.communication.message.TrustPairingResponse
import com.carlom.klardrop.common.communication.message.TrustRevocationMessage
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
        progressFlow: MutableSharedFlow<MessengerSendProgress>,
        cipher: FrameCipher,
    ) {
        val message = request.message as TrustPairingRequest
        log("TrustPairingRequestHandler", "Sending pairing request to ${message.deviceName}")
        
        // Send the message
        writeChannel.sendMessage(message, serializer, cipher)
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
        progressFlow: MutableSharedFlow<MessengerSendProgress>,
        cipher: FrameCipher,
    ) {
        val message = request.message as TrustPairingResponse
        log("TrustPairingResponseHandler", "Sending pairing response to ${message.deviceName}")
        
        // Send the message
        writeChannel.sendMessage(message, serializer, cipher)
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
        progressFlow: MutableSharedFlow<MessengerSendProgress>,
        cipher: FrameCipher,
    ) {
        val message = request.message as TrustRevocationMessage
        log("TrustRevocationMessageHandler", "Sending revocation to $toDeviceId")
        writeChannel.sendMessage(message, serializer, cipher)
    }
}

