package com.carlom.klardrop.common.communication.message
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Trust-related message types and data classes for device pairing and secure communication.
 * Uses kotlinx.serialization with @Serializable annotations for protobuf encoding/decoding.
 */

@Serializable
data class TrustPairingRequest(
    val deviceId: String,
    val deviceName: String,
    val ecdhPublicKey: ByteArray,        // ECDH public key for key exchange
    val ecdsaPublicKey: ByteArray,       // ECDSA public key for message signing
    val timestamp: Long,
    val deviceType: String,              // Android/iOS/Desktop/macOS
    val appVersion: String,
    override val id: Int = Random.nextInt(),
) : Message() {
    override val type: MessageType = MessageType.TRUST_PAIRING_REQUEST
    override val hasPayload: Boolean = false
    
    // Implement equals and hashCode for ByteArray
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustPairingRequest) return false
        
        if (deviceId != other.deviceId) return false
        if (deviceName != other.deviceName) return false
        if (!ecdhPublicKey.contentEquals(other.ecdhPublicKey)) return false
        if (!ecdsaPublicKey.contentEquals(other.ecdsaPublicKey)) return false
        if (timestamp != other.timestamp) return false
        if (deviceType != other.deviceType) return false
        if (appVersion != other.appVersion) return false
        if (id != other.id) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + ecdhPublicKey.contentHashCode()
        result = 31 * result + ecdsaPublicKey.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + deviceType.hashCode()
        result = 31 * result + appVersion.hashCode()
        result = 31 * result + id
        return result
    }
}

@Serializable
data class TrustPairingResponse(
    val deviceId: String,
    val deviceName: String,
    val ecdhPublicKey: ByteArray,        // ECDH public key for key exchange
    val ecdsaPublicKey: ByteArray,       // ECDSA public key for message signing
    val accepted: Boolean,
    val timestamp: Long,
    val rejectionReason: String? = null,  // Optional reason for rejection
    override val id: Int = Random.nextInt(),
) : Message() {
    override val type: MessageType = MessageType.TRUST_PAIRING_RESPONSE
    override val hasPayload: Boolean = false
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustPairingResponse) return false
        
        if (deviceId != other.deviceId) return false
        if (deviceName != other.deviceName) return false
        if (!ecdhPublicKey.contentEquals(other.ecdhPublicKey)) return false
        if (!ecdsaPublicKey.contentEquals(other.ecdsaPublicKey)) return false
        if (accepted != other.accepted) return false
        if (timestamp != other.timestamp) return false
        if (rejectionReason != other.rejectionReason) return false
        if (id != other.id) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + ecdhPublicKey.contentHashCode()
        result = 31 * result + ecdsaPublicKey.contentHashCode()
        result = 31 * result + accepted.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + (rejectionReason?.hashCode() ?: 0)
        result = 31 * result + id
        return result
    }
}

@Serializable
data class TrustedMessage(
    val payload: ByteArray,              // Actual message content
    val timestamp: Long,
    val nonce: ByteArray,               // 16 random bytes for replay protection
    val signature: ByteArray,           // ECDSA signature over payload + timestamp + nonce
    val senderId: String,               // Device ID of sender
    override val id: Int = Random.nextInt(),
) : Message() {
    override val type: MessageType = MessageType.TRUSTED_MESSAGE
    override val hasPayload: Boolean = false
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustedMessage) return false
        
        if (!payload.contentEquals(other.payload)) return false
        if (timestamp != other.timestamp) return false
        if (!nonce.contentEquals(other.nonce)) return false
        if (!signature.contentEquals(other.signature)) return false
        if (senderId != other.senderId) return false
        if (id != other.id) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = payload.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + senderId.hashCode()
        result = 31 * result + id
        return result
    }
}

@Serializable
data class ClipboardSyncMessage(
    val content: String,
    val mimeType: String,               // text/plain, image/png, etc.
    val timestamp: Long,
    val signature: ByteArray,           // ECDSA signature for verification
    override val id: Int = Random.nextInt(),
) : Message() {
    override val type: MessageType = MessageType.CLIPBOARD_SYNC
    override val hasPayload: Boolean = false
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClipboardSyncMessage) return false
        
        if (content != other.content) return false
        if (mimeType != other.mimeType) return false
        if (timestamp != other.timestamp) return false
        if (!signature.contentEquals(other.signature)) return false
        if (id != other.id) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = content.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + id
        return result
    }
}

@Serializable
data class TrustRevocationMessage(
    val deviceId: String,               // Device being revoked
    val timestamp: Long,
    val reason: String? = null,         // Optional reason for revocation
    val signature: ByteArray,           // Signature from revoking device
    override val id: Int = Random.nextInt(),
) : Message() {
    override val type: MessageType = MessageType.TRUST_REVOCATION
    override val hasPayload: Boolean = false
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustRevocationMessage) return false
        
        if (deviceId != other.deviceId) return false
        if (timestamp != other.timestamp) return false
        if (reason != other.reason) return false
        if (!signature.contentEquals(other.signature)) return false
        if (id != other.id) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + (reason?.hashCode() ?: 0)
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + id
        return result
    }
}