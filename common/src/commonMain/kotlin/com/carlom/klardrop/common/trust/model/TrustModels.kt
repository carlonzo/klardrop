package com.carlom.klardrop.common.trust.model

import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.protos.trust.DeviceType
import com.carlom.klardrop.protos.trust.Permission
import com.carlom.klardrop.protos.trust.TrustLevel

// Device identity and keypair
data class DeviceKeypair(
    val deviceId: String,
    val publicKey: ByteArray,
    val privateKey: ByteArray,
    val deviceName: String,
    val deviceType: DeviceType,
    val createdAt: Long = Clock().currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as DeviceKeypair
        return deviceId == other.deviceId &&
                publicKey.contentEquals(other.publicKey) &&
                privateKey.contentEquals(other.privateKey) &&
                deviceName == other.deviceName &&
                deviceType == other.deviceType &&
                createdAt == other.createdAt
    }
    
    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + privateKey.contentHashCode()
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + deviceType.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}

// Trust group
data class TrustGroup(
    val groupId: String,
    val groupKey: ByteArray,
    val groupName: String?,
    val devices: Map<String, TrustedDevice>,
    val createdAt: Long,
    val updatedAt: Long,
    val protocolVersion: Int = 1,
    val cloudSyncEnabled: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as TrustGroup
        return groupId == other.groupId &&
                groupKey.contentEquals(other.groupKey) &&
                groupName == other.groupName &&
                devices == other.devices &&
                createdAt == other.createdAt &&
                updatedAt == other.updatedAt &&
                protocolVersion == other.protocolVersion &&
                cloudSyncEnabled == other.cloudSyncEnabled
    }
    
    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + groupKey.contentHashCode()
        result = 31 * result + (groupName?.hashCode() ?: 0)
        result = 31 * result + devices.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + protocolVersion
        result = 31 * result + cloudSyncEnabled.hashCode()
        return result
    }
}

// Trusted device
data class TrustedDevice(
    val deviceId: String,
    val groupId: String,
    val publicKey: ByteArray,
    val deviceName: String,
    val deviceType: DeviceType,
    val addedAt: Long,
    val addedBy: String,
    val lastSeen: Long? = null,
    val trustLevel: TrustLevel = TrustLevel.TRUST_LEVEL_FULL,
    val permissions: Set<Permission> = setOf(
        Permission.PERMISSION_FILE_SEND,
        Permission.PERMISSION_FILE_RECEIVE,
        Permission.PERMISSION_CLIPBOARD_SYNC
    ),
    val expiresAt: Long? = null,
    val isActive: Boolean = true
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as TrustedDevice
        return deviceId == other.deviceId &&
                groupId == other.groupId &&
                publicKey.contentEquals(other.publicKey) &&
                deviceName == other.deviceName &&
                deviceType == other.deviceType &&
                addedAt == other.addedAt &&
                addedBy == other.addedBy &&
                lastSeen == other.lastSeen &&
                trustLevel == other.trustLevel &&
                permissions == other.permissions &&
                expiresAt == other.expiresAt &&
                isActive == other.isActive
    }
    
    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + groupId.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + deviceType.hashCode()
        result = 31 * result + addedAt.hashCode()
        result = 31 * result + addedBy.hashCode()
        result = 31 * result + (lastSeen?.hashCode() ?: 0)
        result = 31 * result + trustLevel.hashCode()
        result = 31 * result + permissions.hashCode()
        result = 31 * result + (expiresAt?.hashCode() ?: 0)
        result = 31 * result + isActive.hashCode()
        return result
    }
}

// Security event types
enum class SecurityEventType {
    AUTH_FAILED,
    KEY_ROTATION,
    DEVICE_ADDED,
    DEVICE_REMOVED,
    PAIRING_ATTEMPT,
    PAIRING_SUCCESS,
    PAIRING_FAILED,
    SUSPICIOUS_ACTIVITY
}

// Security event
data class SecurityEvent(
    val id: Long? = null,
    val eventType: SecurityEventType,
    val deviceId: String? = null,
    val ipAddress: String? = null,
    val timestamp: Long,
    val details: Map<String, String>? = null
)

// Pairing session status
enum class PairingSessionStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    EXPIRED
}

// Pairing session
data class PairingSession(
    val sessionId: String,
    val deviceId: String,
    val ephemeralPublicKey: ByteArray,
    val expiresAt: Long,
    val status: PairingSessionStatus = PairingSessionStatus.PENDING,
    val createdAt: Long = Clock().currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as PairingSession
        return sessionId == other.sessionId &&
                deviceId == other.deviceId &&
                ephemeralPublicKey.contentEquals(other.ephemeralPublicKey) &&
                expiresAt == other.expiresAt &&
                status == other.status &&
                createdAt == other.createdAt
    }
    
    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + ephemeralPublicKey.contentHashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}

// Clipboard entry
data class ClipboardEntry(
    val id: Long? = null,
    val deviceId: String,
    val content: String,
    val contentHash: String,
    val timestamp: Long,
    val signature: ByteArray,
    val synced: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as ClipboardEntry
        return id == other.id &&
                deviceId == other.deviceId &&
                content == other.content &&
                contentHash == other.contentHash &&
                timestamp == other.timestamp &&
                signature.contentEquals(other.signature) &&
                synced == other.synced
    }
    
    override fun hashCode(): Int {
        var result = id?.hashCode() ?: 0
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + contentHash.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + synced.hashCode()
        return result
    }
}

// Trust status for UI
enum class TrustStatus {
    TRUSTED,
    UNTRUSTED,
    PENDING_TRUST,
    TRUST_EXPIRED
}

// Trust notification types
sealed class TrustNotification {
    data class NewDeviceNearby(
        val device: com.carlom.klardrop.protos.trust.DeviceIdentity,
        val onAccept: () -> Unit,
        val onDecline: () -> Unit,
        val timeoutSeconds: Int = 30
    ) : TrustNotification()
    
    data class DeviceJoined(
        val device: com.carlom.klardrop.protos.trust.DeviceIdentity
    ) : TrustNotification()
    
    data class TrustedDeviceOnline(
        val device: com.carlom.klardrop.protos.trust.DeviceIdentity
    ) : TrustNotification()
}