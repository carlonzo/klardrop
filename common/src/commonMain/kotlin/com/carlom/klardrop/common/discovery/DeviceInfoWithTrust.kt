package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.trust.TrustManager

/**
 * Wrapper for DeviceInfo that includes trust status.
 * This avoids modifying the core DeviceInfo serialization while providing trust information for UI.
 */
data class DeviceInfoWithTrust(
    val deviceInfo: DeviceInfo,
    val isTrusted: Boolean
) {
    val deviceId: String get() = deviceInfo.deviceId
    val name: String get() = deviceInfo.name
    val deviceType: com.carlom.klardrop.common.utils.DeviceType get() = deviceInfo.deviceType
    val osType: com.carlom.klardrop.common.utils.OsType get() = deviceInfo.osType
}

/**
 * Enhanced DiscoveryDevice that includes trust information.
 */
data class DiscoveryDeviceWithTrust(
    val deviceInfoWithTrust: DeviceInfoWithTrust,
    val deviceConnections: List<DeviceConnection> = emptyList()
) {
    val deviceInfo: DeviceInfo get() = deviceInfoWithTrust.deviceInfo
    val isTrusted: Boolean get() = deviceInfoWithTrust.isTrusted
    
    fun hasNearbyConnection(): Boolean {
        return deviceConnections.any { it.deviceConnectionType == DeviceConnection.DeviceConnectionType.NEARBY }
    }

    fun hasKlardropConnection(): Boolean {
        return deviceConnections.any { it.deviceConnectionType == DeviceConnection.DeviceConnectionType.KLARDROP }
    }

    fun getKlardropConnection(): List<DeviceConnection.KlardropConnection> {
        return deviceConnections.filterIsInstance<DeviceConnection.KlardropConnection>()
    }

    fun getNearbyConnection(): List<DeviceConnection.NearbyConnection> {
        return deviceConnections.filterIsInstance<DeviceConnection.NearbyConnection>()
    }
}

/**
 * Utility class to add trust status to discovery devices.
 */
class TrustAwareDiscoveryUtils(
    private val trustManager: TrustManager
) {
    
    /**
     * Convert a regular DiscoveryDevice to one with trust information.
     */
    suspend fun withTrustStatus(discoveryDevice: DiscoveryDevice): DiscoveryDeviceWithTrust {
        val isTrusted = try {
            trustManager.isTrusted(discoveryDevice.deviceInfo.deviceId)
        } catch (e: Exception) {
            false // Default to not trusted on error
        }
        
        val deviceInfoWithTrust = DeviceInfoWithTrust(
            deviceInfo = discoveryDevice.deviceInfo,
            isTrusted = isTrusted
        )
        
        return DiscoveryDeviceWithTrust(
            deviceInfoWithTrust = deviceInfoWithTrust,
            deviceConnections = discoveryDevice.deviceConnections
        )
    }
    
    /**
     * Convert a DeviceInfo to DeviceInfoWithTrust.
     */
    suspend fun withTrustStatus(deviceInfo: DeviceInfo): DeviceInfoWithTrust {
        val isTrusted = try {
            trustManager.isTrusted(deviceInfo.deviceId)
        } catch (e: Exception) {
            false // Default to not trusted on error
        }
        
        return DeviceInfoWithTrust(
            deviceInfo = deviceInfo,
            isTrusted = isTrusted
        )
    }
    
    /**
     * Batch convert multiple devices to include trust status.
     */
    suspend fun withTrustStatus(discoveryDevices: List<DiscoveryDevice>): List<DiscoveryDeviceWithTrust> {
        return discoveryDevices.map { withTrustStatus(it) }
    }
}