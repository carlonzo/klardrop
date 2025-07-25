package com.carlom.klardrop.common.trust.communication

import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.protos.trust.TrustMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Extension to add trust message support to Messenger
 */
suspend fun Messenger.sendTrustMessage(deviceId: String, trustMessage: TrustMessage) {
    // Wrap the trust message in a Klardrop message
    val wrappedMessage = TrustMessageWrapper(
        trustMessageType = trustMessage.type,
        payload = trustMessage.payload.toByteArray()
    )
    
    // Send using the existing messenger infrastructure
    send(deviceId, wrappedMessage.toSimpleSendRequest())
}

/**
 * Wrapper to send trust messages through the existing Klardrop protocol
 * This allows trust messages to be sent alongside regular messages
 */
@Serializable
data class TrustMessageWrapper(
    val trustMessageType: com.carlom.klardrop.protos.trust.TrustMessageType,
    val payload: ByteArray
) : Message() {
    override val type: MessageType = MessageType.TEXT // Reuse TEXT type for now
    override val hasPayload: Boolean = false
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        
        other as TrustMessageWrapper
        
        if (trustMessageType != other.trustMessageType) return false
        if (!payload.contentEquals(other.payload)) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = trustMessageType.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}