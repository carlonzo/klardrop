package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.mdns.RegisterServiceInfo
import com.carlom.klardrop.common.mdns.ServiceInfo
import com.carlom.klardrop.common.utils.DeviceType

internal class KlardropDiscoveryUtils {

  fun getRegisterServiceInfo(port: Int, currentDevice: CurrentDevice): RegisterServiceInfo {

    val nameBytes = currentDevice.shortDeviceId.encodeToByteArray()
    val name = urlSafeBase64EncodedString(nameBytes)

    return RegisterServiceInfo(
      port = port,
      serviceName = name,
      serviceType = KLARDROP_SERVICE_TYPE,
      attributes = mapOf(
        ATTRIBUTE_DEVICE_NAME to urlSafeBase64EncodedString(currentDevice.deviceName),
        ATTRIBUTE_DEVICE_TYPE to currentDevice.deviceType.id.toInt().toString(),
      )
    )
  }

  fun toDeviceInfo(serviceInfo: ServiceInfo): DeviceInfo = with(serviceInfo) {

    val deviceName = urlSafeBase64DecodeString(attributes.getValue(ATTRIBUTE_DEVICE_NAME)).decodeToString()
    val deviceType = DeviceType.fromId(attributes.getValue(ATTRIBUTE_DEVICE_TYPE).toInt())

    return DeviceInfo(
      name = deviceName,
      deviceId = getDeviceId(serviceInfo),
      deviceType = deviceType,
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
    internal const val ATTRIBUTE_DEVICE_TYPE = "dt"
  }
}