package com.carlom.klardrop.common.trust

import com.carlom.klardrop.common.discovery.DiscoveryDevice

/**
 * Utility functions and extensions for the trust system.
 * Provides convenient methods for checking trust status and working with trusted devices.
 */

/**
 * Trust status for a device.
 */
enum class TrustStatus {
    TRUSTED,        // Device is paired and trusted
    UNTRUSTED,      // Device is not trusted
    PAIRING_PENDING // Pairing is in progress
}

/**
 * Device information with trust status.
 */
data class DeviceWithTrustStatus(
    val device: DiscoveryDevice,
    val trustStatus: TrustStatus,
    val isPairingInProgress: Boolean = false
) {
    val isTrusted: Boolean
        get() = trustStatus == TrustStatus.TRUSTED
    
    val canInitiatePairing: Boolean
        get() = trustStatus == TrustStatus.UNTRUSTED && !isPairingInProgress
}

/**
 * Extension functions for working with trust status.
 */
suspend fun DiscoveryDevice.getTrustStatus(trustManager: TrustManager): TrustStatus {
    return if (trustManager.isTrusted(this.deviceInfo.deviceId)) {
        TrustStatus.TRUSTED
    } else {
        TrustStatus.UNTRUSTED
    }
}

suspend fun DiscoveryDevice.withTrustStatus(trustManager: TrustManager): DeviceWithTrustStatus {
    val trustStatus = this.getTrustStatus(trustManager)
    return DeviceWithTrustStatus(
        device = this,
        trustStatus = trustStatus
    )
}

/**
 * Trust verification utilities.
 */
class TrustVerificationUtils(
    private val trustManager: TrustManager
) {
    
    /**
     * Check if a device can be trusted for secure communication.
     */
    suspend fun isDeviceTrusted(deviceId: String): Boolean {
        return trustManager.isTrusted(deviceId)
    }
    
    /**
     * Get all trusted devices with their metadata.
     */
    suspend fun getTrustedDevices(): List<TrustedDevice> {
        return trustManager.getTrustedDevices()
    }
    
    /**
     * Verify if a message signature is valid for a given device.
     * This is useful for pre-verification before processing.
     */
    suspend fun verifyMessageSignature(
        trustedMessage: com.carlom.klardrop.common.communication.message.TrustedMessage
    ): Boolean {
        return trustManager.verifyMessage(trustedMessage)
    }
    
    /**
     * Remove trust relationship with a device.
     * This will prevent future secure communication until re-paired.
     */
    suspend fun removeTrust(deviceId: String) {
        trustManager.removeTrust(deviceId)
    }
    
    /**
     * Clear all trust relationships.
     * This is a nuclear option that removes all trusted devices.
     */
    suspend fun clearAllTrust() {
        val trustedDevices = trustManager.getTrustedDevices()
        trustedDevices.forEach { device ->
            trustManager.removeTrust(device.deviceId)
        }
    }
}

/**
 * Trust policy utilities for determining when to require trust.
 */
object TrustPolicy {
    
    /**
     * Determine if a message type requires trust verification.
     */
    fun requiresTrust(messageType: com.carlom.klardrop.common.communication.message.MessageType): Boolean {
        return when (messageType) {
            // Regular file/text transfers don't require trust by default
            com.carlom.klardrop.common.communication.message.MessageType.TEXT,
            com.carlom.klardrop.common.communication.message.MessageType.FILE -> false
            
            // Trust system messages
            com.carlom.klardrop.common.communication.message.MessageType.TRUST_PAIRING_REQUEST,
            com.carlom.klardrop.common.communication.message.MessageType.TRUST_PAIRING_RESPONSE -> false
            
            // These require trust
            com.carlom.klardrop.common.communication.message.MessageType.TRUSTED_MESSAGE,
            com.carlom.klardrop.common.communication.message.MessageType.CLIPBOARD_SYNC,
            com.carlom.klardrop.common.communication.message.MessageType.TRUST_REVOCATION -> true
            
            // Handshake, ack and heartbeat messages don't require trust
            com.carlom.klardrop.common.communication.message.MessageType.HANDSHAKE,
            com.carlom.klardrop.common.communication.message.MessageType.ACK_READY,
            com.carlom.klardrop.common.communication.message.MessageType.ACK_RECEIVED,
            com.carlom.klardrop.common.communication.message.MessageType.PING,
            com.carlom.klardrop.common.communication.message.MessageType.PONG -> false
        }
    }
    
    /**
     * Determine if automatic message wrapping should be applied.
     * This could be configurable in the future.
     */
    fun shouldAutoWrapForTrust(
        messageType: com.carlom.klardrop.common.communication.message.MessageType,
        targetDeviceId: String
    ): Boolean {
        // For now, don't auto-wrap any messages
        // This can be enabled later based on user preferences
        return false
    }
}

/**
 * Trust indicator data for UI components.
 */
data class TrustIndicator(
    val deviceId: String,
    val isTrusted: Boolean,
    val trustLevel: TrustLevel = if (isTrusted) TrustLevel.VERIFIED else TrustLevel.NONE,
    val lastVerified: Long? = null,
    val pairingTimestamp: Long? = null
)

/**
 * Trust level indicators for UI.
 */
enum class TrustLevel {
    NONE,           // No trust relationship
    VERIFIED,       // Device is paired and verified
    EXPIRED         // Trust relationship may be stale (not implemented yet)
}

/**
 * Extension functions for creating trust indicators.
 */
suspend fun List<DiscoveryDevice>.withTrustIndicators(
    trustManager: TrustManager
): List<Pair<DiscoveryDevice, TrustIndicator>> {
    return this.map { device ->
        val isTrusted = trustManager.isTrusted(device.deviceInfo.deviceId)
        val indicator = TrustIndicator(
            deviceId = device.deviceInfo.deviceId,
            isTrusted = isTrusted
        )
        device to indicator
    }
}

suspend fun DiscoveryDevice.createTrustIndicator(trustManager: TrustManager): TrustIndicator {
    val isTrusted = trustManager.isTrusted(this.deviceInfo.deviceId)
    return TrustIndicator(
        deviceId = this.deviceInfo.deviceId,
        isTrusted = isTrusted
    )
}