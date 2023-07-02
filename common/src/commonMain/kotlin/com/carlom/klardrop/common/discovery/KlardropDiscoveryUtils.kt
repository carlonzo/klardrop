package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.mdns.RegisterServiceInfo
import com.carlom.klardrop.common.mdns.ServiceInfo
import com.carlom.klardrop.common.utils.DeviceType
import kotlin.io.encoding.Base64

internal class KlardropDiscoveryUtils {

  fun provideRegisterServiceInfo(port: Int, currentDevice: CurrentDevice): RegisterServiceInfo {

    val nameBytes = currentDevice.deviceId.take(8).encodeToByteArray()
    val name = urlSafeBase64EncodedString(nameBytes)

    return RegisterServiceInfo(
      port = port,
      serviceName = name,
      serviceType = KLARDROP_SERVICE_TYPE,
      attributes = mapOf(
        "dn" to urlSafeBase64EncodedString(currentDevice.deviceName),
        "dt" to currentDevice.deviceType.id.toInt().toString(),
      )
    )
  }

  fun toDeviceInfo(serviceInfo: ServiceInfo): DeviceInfo = with(serviceInfo) {

    val deviceName = urlSafeBase64DecodeString(attributes.getValue("dn")).decodeToString()
    val deviceType = DeviceType.fromId(attributes.getValue("dt").toInt())

    return DeviceInfo(
      name = deviceName,
      deviceId = serviceName,
      deviceType = deviceType,
    )
  }

  fun isValidService(serviceInfo: ServiceInfo): Boolean {
    return (serviceInfo.serviceType == KLARDROP_SERVICE_TYPE || serviceInfo.serviceType == KLARDROP_SERVICE_TYPE_LOCAL)
        && serviceInfo.addresses.isNotEmpty() && serviceInfo.attributes.isNotEmpty()
  }

  companion object {
    // 1f5d5f63a522 == sha256("klardrop").take(12)
    const val KLARDROP_SERVICE_TYPE = "_0681dfce5269._tcp."
    const val KLARDROP_SERVICE_TYPE_LOCAL ="${KLARDROP_SERVICE_TYPE}local."
  }
}