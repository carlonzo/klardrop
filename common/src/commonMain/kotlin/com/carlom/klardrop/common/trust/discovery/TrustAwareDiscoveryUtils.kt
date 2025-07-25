package com.carlom.klardrop.common.trust.discovery

import com.carlom.klardrop.common.discovery.CurrentDevice
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.KlardropDiscoveryUtils
import com.carlom.klardrop.common.discovery.urlSafeBase64DecodeString
import com.carlom.klardrop.common.discovery.urlSafeBase64EncodedString
import com.carlom.klardrop.common.mdns.RegisterServiceInfo
import com.carlom.klardrop.common.mdns.ServiceInfo
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.trust.model.TrustStatus
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType

/**
 * Enhanced discovery utils that includes trust information in mDNS announcements
 */
class TrustAwareDiscoveryUtils(
    private val baseUtils: KlardropDiscoveryUtils,
    private val trustManager: TrustManager
) {
    
    companion object {
        // Additional attributes for trust
        internal const val ATTRIBUTE_TRUST_ENABLED = "te"  // Trust enabled (1/0)
        internal const val ATTRIBUTE_IN_TRUST_GROUP = "tg" // In trust group (1/0)
        internal const val ATTRIBUTE_PUBLIC_KEY = "pk"     // Device public key (base64)
        internal const val ATTRIBUTE_PROTOCOL_VERSION = "pv" // Trust protocol version
    }
    
    suspend fun getRegisterServiceInfo(port: Int, currentDevice: CurrentDevice): RegisterServiceInfo {
        // Get base service info
        val baseInfo = baseUtils.getRegisterServiceInfo(port, currentDevice)
        
        // Add trust attributes
        val trustAttributes = mutableMapOf<String, String>()
        trustAttributes.putAll(baseInfo.attributes)
        
        // Add trust information
        trustAttributes[ATTRIBUTE_TRUST_ENABLED] = "1"
        
        val trustGroup = trustManager.currentTrustGroup.value
        trustAttributes[ATTRIBUTE_IN_TRUST_GROUP] = if (trustGroup != null) "1" else "0"
        
        val deviceKeypair = trustManager.currentDeviceKeypair.value
        if (deviceKeypair != null) {
            trustAttributes[ATTRIBUTE_PUBLIC_KEY] = urlSafeBase64EncodedString(deviceKeypair.publicKey)
        }
        
        trustAttributes[ATTRIBUTE_PROTOCOL_VERSION] = "1"
        
        return baseInfo.copy(attributes = trustAttributes)
    }
    
    fun toDeviceInfoWithTrust(serviceInfo: ServiceInfo): DeviceInfoWithTrust {
        // Get base device info
        val baseInfo = baseUtils.toDeviceInfo(serviceInfo)
        
        // Extract trust information
        val trustEnabled = serviceInfo.attributes[ATTRIBUTE_TRUST_ENABLED] == "1"
        val inTrustGroup = serviceInfo.attributes[ATTRIBUTE_IN_TRUST_GROUP] == "1"
        val publicKeyBase64 = serviceInfo.attributes[ATTRIBUTE_PUBLIC_KEY]
        val protocolVersion = serviceInfo.attributes[ATTRIBUTE_PROTOCOL_VERSION]?.toIntOrNull() ?: 0
        
        val publicKey = publicKeyBase64?.let {
            try {
                urlSafeBase64DecodeString(it)
            } catch (e: Exception) {
                null
            }
        }
        
        return DeviceInfoWithTrust(
            deviceInfo = baseInfo,
            trustEnabled = trustEnabled,
            inTrustGroup = inTrustGroup,
            publicKey = publicKey,
            protocolVersion = protocolVersion,
            trustStatus = TrustStatus.UNTRUSTED // Will be updated by checking trust store
        )
    }
    
    fun isValidService(serviceInfo: ServiceInfo): Boolean {
        return baseUtils.isValidService(serviceInfo)
    }
    
    fun getDeviceId(serviceInfo: ServiceInfo): String {
        return baseUtils.getDeviceId(serviceInfo)
    }
}

/**
 * Extended device info that includes trust information
 */
data class DeviceInfoWithTrust(
    val deviceInfo: DeviceInfo,
    val trustEnabled: Boolean,
    val inTrustGroup: Boolean,
    val publicKey: ByteArray?,
    val protocolVersion: Int,
    val trustStatus: TrustStatus
) {
    // Delegate to base device info
    val name: String get() = deviceInfo.name
    val deviceId: String get() = deviceInfo.deviceId
    val deviceType: DeviceType get() = deviceInfo.deviceType
    val osType: OsType get() = deviceInfo.osType
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        
        other as DeviceInfoWithTrust
        
        if (deviceInfo != other.deviceInfo) return false
        if (trustEnabled != other.trustEnabled) return false
        if (inTrustGroup != other.inTrustGroup) return false
        if (publicKey != null) {
            if (other.publicKey == null) return false
            if (!publicKey.contentEquals(other.publicKey)) return false
        } else if (other.publicKey != null) return false
        if (protocolVersion != other.protocolVersion) return false
        if (trustStatus != other.trustStatus) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = deviceInfo.hashCode()
        result = 31 * result + trustEnabled.hashCode()
        result = 31 * result + inTrustGroup.hashCode()
        result = 31 * result + (publicKey?.contentHashCode() ?: 0)
        result = 31 * result + protocolVersion
        result = 31 * result + trustStatus.hashCode()
        return result
    }
}