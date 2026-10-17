package com.carlom.klardrop.common.communication.message
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Trust-related message types and data classes for device pairing and secure communication.
 * Uses kotlinx.serialization with @Serializable annotations for protobuf encoding/decoding.
 */

@Serializable
internal data class TrustPairingRequest(
    internal val deviceId: String,
    internal val deviceName: String,
    internal val ecdhPublicKey: ByteArray,        // ECDH public key for key exchange
    internal val ecdsaPublicKey: ByteArray,       // ECDSA public key for message signing
    internal val timestamp: Long,
    internal val deviceType: String,              // Android/iOS/Desktop/macOS
    internal val appVersion: String,
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
internal data class TrustPairingResponse(
    internal val deviceId: String,
    internal val deviceName: String,
    internal val ecdhPublicKey: ByteArray,        // ECDH public key for key exchange
    internal val ecdsaPublicKey: ByteArray,       // ECDSA public key for message signing
    internal val accepted: Boolean,
    internal val timestamp: Long,
    internal val rejectionReason: String? = null,  // Optional reason for rejection
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
internal data class TrustedMessage(
    internal val payload: ByteArray,              // Actual message content
    internal val timestamp: Long,
    internal val nonce: ByteArray,               // 16 random bytes for replay protection
    internal val signature: ByteArray,           // ECDSA signature over payload + timestamp + nonce
    internal val senderId: String,               // Device ID of sender
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
internal data class ClipboardSyncMessage(
    internal val content: String,
    internal val mimeType: String,               // text/plain, image/png, etc.
    internal val timestamp: Long,
    internal val signature: ByteArray,           // ECDSA signature for verification
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

/**
 * Notifies a peer that the sender has revoked the trust relationship with [targetDeviceId].
 *
 * Signed with the SENDER'S ECDSA private key over `(senderId || targetDeviceId || timestamp || nonce)`.
 * The recipient verifies using the sender's stored ECDSA public key — so the recipient must still
 * hold a pairing for [senderId] at verify time, otherwise the message is silently dropped.
 * [nonce] + [timestamp] protect against replay (same window/check used for [TrustedMessage]).
 */
@Serializable
internal data class TrustRevocationMessage(
    internal val senderId: String,               // Device sending the revocation (us)
    internal val targetDeviceId: String,         // Device whose trust we are revoking (the recipient)
    internal val timestamp: Long,
    internal val nonce: ByteArray,               // 16 random bytes for replay protection
    internal val signature: ByteArray,           // ECDSA signature from sender over (senderId||targetDeviceId||timestamp||nonce)
    internal val reason: String? = null,         // Optional reason for revocation
    override val id: Int = Random.nextInt(),
) : Message() {
    override val type: MessageType = MessageType.TRUST_REVOCATION
    override val hasPayload: Boolean = false

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustRevocationMessage) return false

        if (senderId != other.senderId) return false
        if (targetDeviceId != other.targetDeviceId) return false
        if (timestamp != other.timestamp) return false
        if (!nonce.contentEquals(other.nonce)) return false
        if (!signature.contentEquals(other.signature)) return false
        if (reason != other.reason) return false
        if (id != other.id) return false

        return true
    }

    override fun hashCode(): Int {
        var result = senderId.hashCode()
        result = 31 * result + targetDeviceId.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + (reason?.hashCode() ?: 0)
        result = 31 * result + id
        return result
    }
}