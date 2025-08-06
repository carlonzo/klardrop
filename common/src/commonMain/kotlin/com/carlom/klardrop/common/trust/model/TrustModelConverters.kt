package com.carlom.klardrop.common.trust.model

import com.carlom.klardrop.common.utils.DeviceType as LocalDeviceType
import com.carlom.klardrop.protos.trust.*

// DeviceType conversions
fun LocalDeviceType.toProtoDeviceType(): DeviceType {
    return when (this) {
        LocalDeviceType.MOBILE -> DeviceType.DEVICE_TYPE_ANDROID // Default mobile to Android
        LocalDeviceType.DESKTOP -> DeviceType.DEVICE_TYPE_LINUX // Default desktop to Linux
        LocalDeviceType.UNKNOWN -> DeviceType.DEVICE_TYPE_UNKNOWN
    }
}

fun DeviceType.toLocalDeviceType(): LocalDeviceType {
    return when (this) {
        DeviceType.DEVICE_TYPE_ANDROID, DeviceType.DEVICE_TYPE_IOS -> LocalDeviceType.MOBILE
        DeviceType.DEVICE_TYPE_MACOS, DeviceType.DEVICE_TYPE_WINDOWS, DeviceType.DEVICE_TYPE_LINUX -> LocalDeviceType.DESKTOP
        DeviceType.DEVICE_TYPE_UNKNOWN -> LocalDeviceType.UNKNOWN
    }
}

// TrustLevel conversions
fun TrustLevel.toProtoTrustLevel(): com.carlom.klardrop.protos.trust.TrustLevel {
    return when (this) {
        TrustLevel.MINIMAL -> com.carlom.klardrop.protos.trust.TrustLevel.TRUST_LEVEL_READ_ONLY
        TrustLevel.LIMITED -> com.carlom.klardrop.protos.trust.TrustLevel.TRUST_LEVEL_FILE_ONLY
        TrustLevel.FULL -> com.carlom.klardrop.protos.trust.TrustLevel.TRUST_LEVEL_FULL
    }
}

fun com.carlom.klardrop.protos.trust.TrustLevel.toLocalTrustLevel(): TrustLevel {
    return when (this) {
        com.carlom.klardrop.protos.trust.TrustLevel.TRUST_LEVEL_READ_ONLY -> TrustLevel.MINIMAL
        com.carlom.klardrop.protos.trust.TrustLevel.TRUST_LEVEL_FILE_ONLY -> TrustLevel.LIMITED
        com.carlom.klardrop.protos.trust.TrustLevel.TRUST_LEVEL_FULL -> TrustLevel.FULL
        com.carlom.klardrop.protos.trust.TrustLevel.TRUST_LEVEL_UNKNOWN -> TrustLevel.FULL
    }
}

// Permission conversions
fun Permission.toProtoPermission(): com.carlom.klardrop.protos.trust.Permission {
    return when (this) {
        Permission.FILE_SEND -> com.carlom.klardrop.protos.trust.Permission.PERMISSION_FILE_SEND
        Permission.FILE_RECEIVE -> com.carlom.klardrop.protos.trust.Permission.PERMISSION_FILE_RECEIVE
        Permission.CLIPBOARD_SYNC -> com.carlom.klardrop.protos.trust.Permission.PERMISSION_CLIPBOARD_SYNC
    }
}

fun com.carlom.klardrop.protos.trust.Permission.toLocalPermission(): Permission {
    return when (this) {
        com.carlom.klardrop.protos.trust.Permission.PERMISSION_FILE_SEND -> Permission.FILE_SEND
        com.carlom.klardrop.protos.trust.Permission.PERMISSION_FILE_RECEIVE -> Permission.FILE_RECEIVE
        com.carlom.klardrop.protos.trust.Permission.PERMISSION_CLIPBOARD_SYNC -> Permission.CLIPBOARD_SYNC
        com.carlom.klardrop.protos.trust.Permission.PERMISSION_UNKNOWN -> Permission.FILE_SEND
    }
}

// TrustedDevice conversions
fun TrustedDevice.toProtoTrustedDevice(): com.carlom.klardrop.protos.trust.TrustedDevice {
    return com.carlom.klardrop.protos.trust.TrustedDevice(
        identity = com.carlom.klardrop.protos.trust.DeviceIdentity(
            device_id = deviceId,
            public_key = okio.ByteString.of(*publicKey),
            device_name = deviceName,
            device_type = deviceType.toProtoDeviceType(),
            capabilities = permissions.map { it.toProtoPermission() }
        ),
        added_at = addedAt,
        added_by = addedBy,
        trust_level = trustLevel.toProtoTrustLevel(),
        permissions = permissions.map { it.toProtoPermission() },
        expires_at = expiresAt ?: 0
    )
}

fun com.carlom.klardrop.protos.trust.TrustedDevice.toLocalTrustedDevice(groupId: String): TrustedDevice {
    return TrustedDevice(
        deviceId = identity?.device_id ?: "",
        groupId = groupId,
        publicKey = identity?.public_key?.toByteArray() ?: byteArrayOf(),
        deviceName = identity?.device_name ?: "",
        deviceType = identity?.device_type?.toLocalDeviceType() ?: LocalDeviceType.UNKNOWN,
        addedAt = added_at,
        addedBy = added_by,
        lastSeen = null,
        trustLevel = trust_level.toLocalTrustLevel(),
        permissions = permissions.map { it.toLocalPermission() }.toSet(),
        expiresAt = if (expires_at > 0) expires_at else null,
        isActive = true
    )
}