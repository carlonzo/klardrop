package com.carlom.klardrop.common.trust.model

/**
 * Minimal UI-specific stubs to resolve common-ui compilation issues.
 * These are separate from the main trust models to avoid conflicts.
 */

// UI-specific device identity
data class UiDeviceIdentity(
    val deviceId: String,
    val deviceName: String,
    val deviceType: com.carlom.klardrop.common.utils.DeviceType
)

// UI-specific permission enum
enum class UiPermission {
    FILE_SEND,
    FILE_RECEIVE,
    CLIPBOARD_SYNC
}

// UI-specific trust level
enum class UiTrustLevel {
    MINIMAL,
    LIMITED,
    FULL
}

// UI-specific trust notification for new devices
data class UiNewDeviceNearby(
    val device: UiDeviceIdentity,
    val onAccept: () -> Unit,
    val onDecline: () -> Unit
)

// UI-specific clipboard entry
data class UiClipboardEntry(
    val content: String,
    val timestamp: Long,
    val deviceId: String
)

// UI-specific security event
data class UiSecurityEvent(
    val message: String,
    val timestamp: Long
)