package com.carlom.klardrop.common.trust.model

import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.trust.model.TrustLevel

/**
 * Enhanced device information that includes trust status
 * This is used to pass device info with trust metadata through the UI layer
 */
data class DeviceInfoWithTrust(
    val deviceInfo: DeviceInfo,
    val trustStatus: TrustStatus = TrustStatus.UNTRUSTED,
    val trustLevel: TrustLevel? = null,
    val isTrustGroupMember: Boolean = false,
    val trustGroupId: String? = null,
    val lastSeen: Long? = null,
    val addedAt: Long? = null,
    val addedBy: String? = null,
    val permissions: Set<Permission>? = null,
    val expiresAt: Long? = null
) {
    val deviceId: String get() = deviceInfo.deviceId
    val name: String get() = deviceInfo.name
    val deviceType: DeviceType get() = deviceInfo.deviceType
    val osType: OsType get() = deviceInfo.osType
    
    /**
     * Check if device has clipboard sync permission
     */
    fun hasClipboardSyncPermission(): Boolean {
        return permissions?.contains(Permission.CLIPBOARD_SYNC) == true
    }
    
    /**
     * Check if device has file send permission
     */
    fun hasFileSendPermission(): Boolean {
        return permissions?.contains(Permission.FILE_SEND) == true
    }
    
    /**
     * Check if device has file receive permission
     */
    fun hasFileReceivePermission(): Boolean {
        return permissions?.contains(Permission.FILE_RECEIVE) == true
    }
    
    /**
     * Check if trust has expired
     */
    fun isTrustExpired(): Boolean {
        return expiresAt?.let { it < Clock().currentTimeMillis() } == true
    }
    
    companion object {
        /**
         * Create a DeviceInfoWithTrust from DeviceInfo and TrustedDevice
         */
        fun fromTrustedDevice(deviceInfo: DeviceInfo, trustedDevice: TrustedDevice): DeviceInfoWithTrust {
            return DeviceInfoWithTrust(
                deviceInfo = deviceInfo,
                trustStatus = if (trustedDevice.isActive && (trustedDevice.expiresAt == null || trustedDevice.expiresAt > Clock().currentTimeMillis())) {
                    TrustStatus.TRUSTED
                } else {
                    TrustStatus.TRUST_EXPIRED
                },
                trustLevel = trustedDevice.trustLevel,
                isTrustGroupMember = true,
                trustGroupId = trustedDevice.groupId,
                lastSeen = trustedDevice.lastSeen,
                addedAt = trustedDevice.addedAt,
                addedBy = trustedDevice.addedBy,
                permissions = trustedDevice.permissions,
                expiresAt = trustedDevice.expiresAt
            )
        }
        
        /**
         * Create an untrusted DeviceInfoWithTrust from DeviceInfo
         */
        fun fromDeviceInfo(deviceInfo: DeviceInfo): DeviceInfoWithTrust {
            return DeviceInfoWithTrust(
                deviceInfo = deviceInfo,
                trustStatus = TrustStatus.UNTRUSTED
            )
        }
    }
}