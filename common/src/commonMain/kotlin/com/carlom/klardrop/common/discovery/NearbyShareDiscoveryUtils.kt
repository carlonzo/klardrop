package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.mdns.RegisterServiceInfo
import com.carlom.klardrop.common.mdns.ServiceInfo
import com.carlom.klardrop.common.mdns.createEndpointInfo
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import com.google.security.cryptauth.lib.securegcm.DeviceType.ANDROID
import com.google.security.cryptauth.lib.securegcm.DeviceType.BROWSER
import com.google.security.cryptauth.lib.securegcm.DeviceType.CHROME
import com.google.security.cryptauth.lib.securegcm.DeviceType.IOS
import com.google.security.cryptauth.lib.securegcm.DeviceType.OSX
import com.google.security.cryptauth.lib.securegcm.DeviceType.UNKNOWN
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal class NearbyShareDiscoveryUtils {

  fun getRegisterServiceInfo(port: Int, currentDevice: CurrentDevice): RegisterServiceInfo {

    val nameBytes = byteArrayOf(
      0x23.toByte(), // PCP
      *buildDeviceId(currentDevice), // 4 bytes unique device id
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

        // attribute 'di' is not included in original nearbyshare protocol. Adding from klardrop to recognize the device
        "di" to urlSafeBase64EncodedString(currentDevice.shortDeviceId),
      )
    )
  }

  fun toDeviceInfo(serviceInfo: ServiceInfo): DeviceInfo = with(serviceInfo) {

    val endpointInfo = attributes.getValue("n")
    val endpointInfoBytes = urlSafeBase64DecodeString(endpointInfo)

    // 1 byte: Version(3 bits)|Visibility(1 bit)|Device Type(3 bits)|Reserved(1 bits)
    val deviceInfoByte = endpointInfoBytes[0].toInt()

    val deviceTypeId = deviceInfoByte and 0b0000_1110
    val deviceType = deviceTypeFromId(deviceTypeId shr 1) // 0000 ddd0 (d == devicetype)
    val osType = osTypeFromId(deviceTypeId shr 1) // 0000 ddd0 (d == devicetype)
    val deviceNameLength = if (endpointInfoBytes.size > 18) {
      endpointInfoBytes[17].toInt()
    } else {
      0
    }


    val deviceName = if (deviceNameLength > 0) {
      endpointInfoBytes.sliceArray(18 until 18 + deviceNameLength).decodeToString()
    } else {
      "unknown device name"
    }

    return DeviceInfo(
      name = deviceName,
      deviceId = getDeviceId(serviceInfo),
      deviceType = deviceType,
      osType = osType
    )
  }

  fun getDeviceId(serviceInfo: ServiceInfo): String {
    val encodedServiceId = serviceInfo.attributes["di"] ?: return serviceInfo.serviceNameClean()

    return urlSafeBase64DecodeString(encodedServiceId).decodeToString()
  }

  fun isValidService(serviceInfo: ServiceInfo): Boolean {
    return serviceInfo.addresses.isNotEmpty() && serviceInfo.attributes.isNotEmpty()
  }

  private fun buildDeviceId(currentDevice: CurrentDevice): ByteArray {
    val deviceId = currentDevice.shortDeviceId

    return deviceId.take(4).encodeToByteArray()
  }

  private fun deviceTypeFromId(id: Int): DeviceType {
//    https://github.com/google/nearby/blob/0d83625766a0be92e713d592a3c8bcc7fd6d3307/internal/proto/metadata.proto#L69
    // Device types: unknown=0, phone=1, tablet=2, laptop=3,4
    return when (id) {
      0 -> DeviceType.UNKNOWN
      1 -> DeviceType.MOBILE
      2 -> DeviceType.MOBILE
      3,4,7 -> DeviceType.DESKTOP
      else -> DeviceType.UNKNOWN
    }
  }

  private fun osTypeFromId(id: Int): OsType {
    val ukey2DeviceType = com.google.security.cryptauth.lib.securegcm.DeviceType.fromValue(id)
    return ukey2DeviceType.toOsType()
  }

  companion object {
    const val NEARBY_SERVICE_TYPE = "_FC9F5ED42C8A._tcp."
  }
}

fun com.google.security.cryptauth.lib.securegcm.DeviceType?.toDeviceType(): DeviceType {
  return when (this) {
    ANDROID, IOS -> DeviceType.MOBILE
    CHROME, BROWSER, OSX -> DeviceType.DESKTOP
    null, UNKNOWN -> DeviceType.UNKNOWN
  }
}

fun com.google.security.cryptauth.lib.securegcm.DeviceType?.toOsType(): OsType {
  return when (this) {
    ANDROID, CHROME -> OsType.ANDROID
    IOS, OSX -> OsType.APPLE
    null, BROWSER, UNKNOWN -> OsType.UNKNOWN
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

/**
 * Jmdns append a number at the end of the name if there are 2 services with the same name.
 * This method remove the number at the end of the name
 *
 * From "Izc3Nzf8n14AAA (2)" to "Izc3Nzf8n14AAA"
 */
fun ServiceInfo.serviceNameClean(): String {
  val name = serviceName

  return if (name.endsWith(")")) {
    val index = name.lastIndexOf("(")
    name.substring(0, index).trimEnd()
  } else {
    name
  }
}