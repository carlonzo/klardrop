package com.carlom.klardrop.common.trust.model

/**
 * Stub implementations for protobuf classes to resolve compilation issues.
 * These should be replaced with proper protobuf implementations when available.
 */

// Device identity stub
data class DeviceIdentity(
    val deviceId: String,
    val deviceName: String,
    val deviceType: com.carlom.klardrop.common.utils.DeviceType,
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

// Permission enum stub
enum class Permission {
    FILE_SEND,
    FILE_RECEIVE,
    CLIPBOARD_SYNC
}

// Trust level enum stub
enum class TrustLevel {
    MINIMAL,
    LIMITED,
    FULL
}

// Protocol message stubs
data class DiscoveryAnnouncement(
    val deviceId: String = "",
    val deviceName: String = "",
    val deviceType: com.carlom.klardrop.common.utils.DeviceType = com.carlom.klardrop.common.utils.DeviceType.UNKNOWN,
    val publicKey: ByteArray = byteArrayOf()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as DiscoveryAnnouncement
        return deviceId == other.deviceId &&
                deviceName == other.deviceName &&
                deviceType == other.deviceType &&
                publicKey.contentEquals(other.publicKey)
    }
    
    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + deviceType.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}

data class ECDHInitiation(
    val sessionId: String = "",
    val ephemeralPublicKey: ByteArray = byteArrayOf()
) {
    override fun equals(other: Any?) = other is ECDHInitiation && sessionId == other.sessionId && ephemeralPublicKey.contentEquals(other.ephemeralPublicKey)
    override fun hashCode() = sessionId.hashCode() * 31 + ephemeralPublicKey.contentHashCode()
}

data class ECDHResponse(
    val sessionId: String = "",
    val ephemeralPublicKey: ByteArray = byteArrayOf()
) {
    override fun equals(other: Any?) = other is ECDHResponse && sessionId == other.sessionId && ephemeralPublicKey.contentEquals(other.ephemeralPublicKey)
    override fun hashCode() = sessionId.hashCode() * 31 + ephemeralPublicKey.contentHashCode()
}

data class GroupInvitation(
    val groupId: String = "",
    val groupName: String = ""
)

data class JoinConfirmation(
    val success: Boolean = false
)

data class MemberUpdate(
    val deviceId: String = "",
    val action: UpdateAction = UpdateAction.ADD
)

enum class UpdateAction {
    ADD,
    REMOVE,
    UPDATE
}

data class ClipboardSync(
    val content: String = "",
    val deviceId: String = ""
)