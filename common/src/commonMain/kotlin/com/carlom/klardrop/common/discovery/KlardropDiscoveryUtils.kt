package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.mdns.RegisterServiceInfo
import com.carlom.klardrop.common.mdns.ServiceInfo
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType

class KlardropDiscoveryUtils {

  fun getRegisterServiceInfo(port: Int, currentDevice: CurrentDevice): RegisterServiceInfo {

    val nameBytes = currentDevice.shortDeviceId.encodeToByteArray()
    val name = urlSafeBase64EncodedString(nameBytes)

    // dddd oooo  (d= devicetype, o=ostype)
    val deviceType = currentDevice.deviceType.nearbyId.toInt().shl(4)
    val osType = currentDevice.osType.nearbyId.toInt()
    val deviceInfos = deviceType.or(osType)

    require(deviceInfos < 0xFF) { "Device type and os type must be less than 255" }

    return RegisterServiceInfo(
      port = port,
      serviceName = name,
      serviceType = KLARDROP_SERVICE_TYPE,
      attributes = mapOf(
        ATTRIBUTE_DEVICE_NAME to urlSafeBase64EncodedString(currentDevice.deviceName),
        ATTRIBUTE_DEVICE to deviceInfos.toString(),
      )
    )
  }

  fun toDeviceInfo(serviceInfo: ServiceInfo): DeviceInfo = with(serviceInfo) {

    val deviceName = urlSafeBase64DecodeString(attributes.getValue(ATTRIBUTE_DEVICE_NAME)).decodeToString()
    val deviceInfo = attributes.getValue(ATTRIBUTE_DEVICE).toInt()


    val deviceType = DeviceType.fromId(deviceInfo.shr(4))
    val osType = OsType.fromId(deviceInfo.and(0x0F))

    return DeviceInfo(
      name = deviceName,
      deviceId = getDeviceId(serviceInfo),
      deviceType = deviceType,
      osType = osType
    )
  }

  fun isValidService(serviceInfo: ServiceInfo): Boolean {
    return serviceInfo.addresses.isNotEmpty() && serviceInfo.attributes.isNotEmpty()
  }

  fun getDeviceId(serviceInfo: ServiceInfo): String {
    return urlSafeBase64DecodeString(serviceInfo.serviceNameClean()).decodeToString()
  }

  companion object {
    const val KLARDROP_SERVICE_TYPE = "_klardrop._tcp."
    internal const val ATTRIBUTE_DEVICE_NAME = "dn"
    internal const val ATTRIBUTE_DEVICE = "d"
  }
}