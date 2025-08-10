package com.carlom.klardrop.common.trust.models

/**
 * Data classes and models for the trust system.
 */

/**
 * Represents a trusted device relationship.
 */
data class TrustedDeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val publicKey: ByteArray,
    val pairingTimestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustedDeviceInfo) return false
        if (deviceId != other.deviceId) return false
        if (deviceName != other.deviceName) return false
        if (deviceType != other.deviceType) return false
        if (!publicKey.contentEquals(other.publicKey)) return false
        if (pairingTimestamp != other.pairingTimestamp) return false
        return true
    }
    
    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + deviceType.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + pairingTimestamp.hashCode()
        return result
    }
}

/**
 * Result types for trust operations.
 */
sealed class TrustResult<out T> {
    data class Success<T>(val data: T) : TrustResult<T>()
    data class Error(val message: String, val cause: Throwable? = null) : TrustResult<Nothing>()
}

/**
 * Trust operation types for logging and monitoring.
 */
enum class TrustOperation {
    PAIRING_INITIATED,
    PAIRING_REQUEST_RECEIVED,
    PAIRING_ACCEPTED,
    PAIRING_REJECTED,
    PAIRING_COMPLETED,
    TRUST_REVOKED,
    MESSAGE_SIGNED,
    MESSAGE_VERIFIED
}