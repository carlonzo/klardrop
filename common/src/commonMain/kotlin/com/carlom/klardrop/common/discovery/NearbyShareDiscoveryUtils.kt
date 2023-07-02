package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.mdns.RegisterServiceInfo
import com.carlom.klardrop.common.mdns.ServiceInfo
import com.carlom.klardrop.common.mdns.createEndpointInfo
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.log
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal class NearbyShareDiscoveryUtils {

  fun buildServiceInfo(port: Int, currentDevice: CurrentDevice): RegisterServiceInfo {

    val nameBytes = byteArrayOf(
      0x23.toByte(), // PCP
      *getDeviceId(currentDevice), // 4 bytes unique device id
      0xFC.toByte(), 0x9F.toByte(), 0x5E.toByte(), // Service ID hash
      0.toByte(), 0.toByte(),
    )

    val endpointInfo = createEndpointInfo(currentDevice)

    // urlsafe base64
    val name = urlSafeBase64EncodedString(nameBytes)

    return RegisterServiceInfo(
      port = port,
      serviceName = name,
      serviceType = NEARBY_SERVICE_TYPE,
      attributes = mapOf(
        "n" to urlSafeBase64EncodedString(endpointInfo),
        "di" to urlSafeBase64EncodedString(currentDevice.deviceId.take(8)),
      )
    )
  }

  fun toDeviceInfo(serviceInfo: ServiceInfo): DeviceInfo = with(serviceInfo) {

    val endpointInfo = attributes.getValue("n")
    val endpointInfoBytes = urlSafeBase64DecodeString(endpointInfo)

    // 1 byte: Version(3 bits)|Visibility(1 bit)|Device Type(3 bits)|Reserved(1 bits)
    // Device types: unknown=0, phone=1, tablet=2, laptop=3
    val deviceInfoByte = endpointInfoBytes[0].toInt()

    val deviceTypeId = deviceInfoByte and 0b0000_1110
    val deviceType = deviceTypeFromId(deviceTypeId shr 1) // 0000 ddd0 (d == devicetype)
    val deviceNameLength = endpointInfoBytes[17]
    val deviceName = endpointInfoBytes.sliceArray(18 until 18 + deviceNameLength.toInt()).decodeToString()

    // attribute 'di' is not included in original nearbyshare protocol. Adding from klardrop to recognize the device
    val deviceId = attributes["di"] ?: serviceName


    return DeviceInfo(
      name = deviceName,
      deviceId = deviceId,
      deviceType = deviceType,
    )
  }

  fun isValidService(serviceInfo: ServiceInfo): Boolean {
    return (serviceInfo.serviceType == NEARBY_SERVICE_TYPE || serviceInfo.serviceType == NEARBY_SERVICE_TYPE_LOCAL)
        && serviceInfo.addresses.isNotEmpty() && serviceInfo.attributes.isNotEmpty()
  }

  private fun getDeviceId(currentDevice: CurrentDevice): ByteArray {
    val deviceId = currentDevice.deviceId

    return deviceId.take(4).encodeToByteArray()
  }

  private fun deviceTypeFromId(id: Int): DeviceType {
    return when (id) {
      1 -> DeviceType.MOBILE
      2 -> DeviceType.TABLET
      3 -> DeviceType.DESKTOP
      else -> {
        log("NearbyShare", "Unknown device type id: $id")
        DeviceType.MOBILE
      }
    }
  }


  companion object {
    const val NEARBY_SERVICE_TYPE = "_FC9F5ED42C8A._tcp."
    private const val NEARBY_SERVICE_TYPE_LOCAL = "${NEARBY_SERVICE_TYPE}local."
  }
}

@OptIn(ExperimentalEncodingApi::class)
internal fun urlSafeBase64EncodedString(data: ByteArray): String {
  return Base64.encode(data).map {
    when (it) {
      '+' -> '-'
      '/' -> '_'
      '=' -> ""
      else -> it
    }
  }.joinToString(separator = "")
}

internal fun urlSafeBase64EncodedString(data: String): String {
  return urlSafeBase64EncodedString(data.encodeToByteArray())
}

@OptIn(ExperimentalEncodingApi::class)
internal fun urlSafeBase64DecodeString(data: String): ByteArray {
  return data.map {
    when (it) {
      '-' -> '+'
      '_' -> '/'
      else -> it
    }
  }.joinToString(separator = "").let {
    Base64.decode(it.encodeToByteArray())
  }
}