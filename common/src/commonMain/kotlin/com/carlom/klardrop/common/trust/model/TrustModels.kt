package com.carlom.klardrop.common.trust.model

import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.DeviceType
import kotlinx.serialization.Serializable

// Simplified TrustLevel enum - keeping compatibility with existing code
enum class TrustLevel {
    TRUSTED,    // Device is in trust group (was FULL)
    UNTRUSTED,  // Device is not in trust group
    // Keep old values for compatibility during migration
    FULL,
    LIMITED,
    MINIMAL
}

// Keep Permission enum as is
enum class Permission {
    FILE_SEND,
    FILE_RECEIVE,
    CLIPBOARD_SYNC
}

// Device identity with @Serializable for protobuf support
@Serializable
data class DeviceIdentity(
    val deviceId: String,
    val deviceName: String,
    val deviceType: DeviceType,
    val publicKey: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as DeviceIdentity
        return deviceId == other.deviceId &&
                deviceName == other.deviceName &&
                deviceType == other.deviceType &&
                publicKey?.contentEquals(other.publicKey) == true
    }
    
    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + deviceType.hashCode()
        result = 31 * result + (publicKey?.contentHashCode() ?: 0)
        return result
    }
}

// Protocol message classes from ProtobufStubs.kt
@Serializable
data class DiscoveryAnnouncement(
    val deviceId: String,
    val deviceName: String,
    val deviceType: DeviceType,
    val publicKey: ByteArray,
    val isInTrustGroup: Boolean = false,
    val supportsAutoTrust: Boolean = false,
    val timestamp: Long = Clock().currentTimeMillis(),
    val signature: ByteArray = byteArrayOf()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as DiscoveryAnnouncement
        return deviceId == other.deviceId &&
                deviceName == other.deviceName &&
                deviceType == other.deviceType &&
                publicKey.contentEquals(other.publicKey) &&
                isInTrustGroup == other.isInTrustGroup &&
                supportsAutoTrust == other.supportsAutoTrust &&
                timestamp == other.timestamp &&
                signature.contentEquals(other.signature)
    }
    
    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + deviceType.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + isInTrustGroup.hashCode()
        result = 31 * result + supportsAutoTrust.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

@Serializable
data class ECDHInitiation(
    val sessionId: String,
    val deviceId: String,
    val ephemeralPublicKey: ByteArray,
    val encryptedGroupId: ByteArray,
    val timestamp: Long,
    val nonce: ByteArray,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as ECDHInitiation
        return sessionId == other.sessionId &&
                deviceId == other.deviceId &&
                ephemeralPublicKey.contentEquals(other.ephemeralPublicKey) &&
                encryptedGroupId.contentEquals(other.encryptedGroupId) &&
                timestamp == other.timestamp &&
                nonce.contentEquals(other.nonce) &&
                signature.contentEquals(other.signature)
    }
    
    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + ephemeralPublicKey.contentHashCode()
        result = 31 * result + encryptedGroupId.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

@Serializable
data class ECDHResponse(
    val sessionId: String,
    val deviceId: String,
    val ephemeralPublicKey: ByteArray,
    val encryptedDeviceInfo: ByteArray,
    val timestamp: Long,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as ECDHResponse
        return sessionId == other.sessionId &&
                deviceId == other.deviceId &&
                ephemeralPublicKey.contentEquals(other.ephemeralPublicKey) &&
                encryptedDeviceInfo.contentEquals(other.encryptedDeviceInfo) &&
                timestamp == other.timestamp &&
                signature.contentEquals(other.signature)
    }
    
    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + ephemeralPublicKey.contentHashCode()
        result = 31 * result + encryptedDeviceInfo.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

@Serializable
data class GroupInvitation(
    val sessionId: String,
    val encryptedPayload: ByteArray,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as GroupInvitation
        return sessionId == other.sessionId &&
                encryptedPayload.contentEquals(other.encryptedPayload) &&
                signature.contentEquals(other.signature)
    }
    
    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + encryptedPayload.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

@Serializable
data class JoinConfirmation(
    val sessionId: String,
    val deviceId: String,
    val accepted: Boolean,
    val timestamp: Long,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as JoinConfirmation
        return sessionId == other.sessionId &&
                deviceId == other.deviceId &&
                accepted == other.accepted &&
                timestamp == other.timestamp &&
                signature.contentEquals(other.signature)
    }
    
    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + accepted.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

enum class UpdateAction {
    ADD,
    REMOVE,
    UPDATE
}

@Serializable
data class MemberUpdate(
    val groupId: String,
    val action: UpdateAction,
    val device: TrustedDevice,
    val version: Int,
    val timestamp: Long,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as MemberUpdate
        return groupId == other.groupId &&
                action == other.action &&
                device == other.device &&
                version == other.version &&
                timestamp == other.timestamp &&
                signature.contentEquals(other.signature)
    }
    
    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + action.hashCode()
        result = 31 * result + device.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

@Serializable
data class ClipboardSyncMessage(
    val deviceId: String,
    val encryptedContent: ByteArray,
    val timestamp: Long,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as ClipboardSyncMessage
        return deviceId == other.deviceId &&
                encryptedContent.contentEquals(other.encryptedContent) &&
                timestamp == other.timestamp &&
                signature.contentEquals(other.signature)
    }
    
    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + encryptedContent.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

// Simple ClipboardSync for backward compatibility
@Serializable
data class ClipboardSync(
    val content: String,
    val deviceId: String
)

// Trust message types
enum class TrustMessageType {
    DISCOVERY_ANNOUNCEMENT,
    ECDH_INITIATION,
    ECDH_RESPONSE,
    GROUP_INVITATION,
    JOIN_CONFIRMATION,
    MEMBER_UPDATE,
    CLIPBOARD_SYNC,
    HEARTBEAT
}

// Trust protocol wrapper message
@Serializable
data class TrustMessage(
    val type: TrustMessageType,
    val payload: ByteArray // Serialized message based on type
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as TrustMessage
        return type == other.type && payload.contentEquals(other.payload)
    }
    
    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

// Device identity and keypair
@Serializable
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
@Serializable
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
@Serializable
data class TrustedDevice(
    val deviceId: String,
    val groupId: String,
    val publicKey: ByteArray,
    val deviceName: String,
    val deviceType: DeviceType,
    val addedAt: Long,
    val addedBy: String,
    val lastSeen: Long? = null,
    val trustLevel: TrustLevel = TrustLevel.FULL,
    val permissions: Set<Permission> = setOf(Permission.FILE_SEND), // Minimal default permission
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
        val device: DeviceIdentity,
        val onAccept: () -> Unit,
        val onDecline: () -> Unit,
        val timeoutSeconds: Int = 30
    ) : TrustNotification()
    
    data class DeviceJoined(
        val device: DeviceIdentity
    ) : TrustNotification()
    
    data class TrustedDeviceOnline(
        val device: DeviceIdentity
    ) : TrustNotification()
}