package com.carlom.klardrop.common.communication.message

import kotlinx.serialization.Serializable

/**
 * Message wrapper for trust protocol messages
 */
@Serializable
data class TrustMessage(
    val trustMessageBytes: ByteArray, // The serialized TrustMessage protobuf
    override val id: Int = kotlin.random.Random.nextInt()
) : Message() {
    override val type: MessageType = MessageType.PAIRING
    override val hasPayload: Boolean = false
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as TrustMessage
        return trustMessageBytes.contentEquals(other.trustMessageBytes) && id == other.id
    }
    
    override fun hashCode(): Int {
        var result = trustMessageBytes.contentHashCode()
        result = 31 * result + id
        return result
    }
}