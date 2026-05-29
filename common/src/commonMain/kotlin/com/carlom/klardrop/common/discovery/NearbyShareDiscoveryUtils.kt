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
    val hasPlaintextName = (deviceInfoByte and 0x10) == 0

    val deviceTypeId = (deviceInfoByte and 0b0000_1110) shr 1
    val deviceType = deviceTypeFromId(deviceTypeId)
    val osType = osTypeFromId(deviceTypeId)

    val deviceName = if (hasPlaintextName && endpointInfoBytes.size > 18) {
      val nameLength = endpointInfoBytes[17].toInt() and 0xFF
      if (nameLength > 0 && 18 + nameLength <= endpointInfoBytes.size) {
        endpointInfoBytes.sliceArray(18 until 18 + nameLength).decodeToString()
      } else {
        "unknown device name"
      }
    } else {
      // Visibility-restricted services (and any malformed buffer) fall back to a
      // placeholder; we still surface the device so users see something.
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
    if (serviceInfo.attributes.isEmpty()) return false
    if (serviceInfo.port <= 0) return false
    if (!serviceInfo.hasReachableAddress()) return false

    // Require a parseable, non-empty device name in the endpoint info.
    // Without it, we can only fall back to a generic label and the peer is
    // almost always unreachable in practice.
    val endpointInfo = serviceInfo.attributes["n"] ?: return false
    val bytes = runCatching { urlSafeBase64DecodeString(endpointInfo) }.getOrNull() ?: return false
    if (bytes.size <= 18) return false
    val nameLength = bytes[17].toInt()
    if (nameLength <= 0) return false
    if (18 + nameLength > bytes.size) return false
    return true
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
  // Android Quick Share refuses to decode the mDNS service name and "n" TXT
  // record when '=' padding is present; NearDrop strips it too.
  return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(data)
}

internal fun urlSafeBase64EncodedString(data: String): String {
  return urlSafeBase64EncodedString(data.encodeToByteArray())
}

@OptIn(ExperimentalEncodingApi::class)
internal fun urlSafeBase64DecodeString(data: String): ByteArray {
  return Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(data)
//  return data.map {
//    when (it) {
//      '-' -> '+'
//      '_' -> '/'
//      else -> it
//    }
//  }.joinToString(separator = "").let {
//    Base64.UrlSafe.decode(it.encodeToByteArray())
//  }
}

/**
 * Returns true if the service advertises at least one address that could
 * represent a remote peer (i.e. not the unspecified or loopback address).
 */
internal fun ServiceInfo.hasReachableAddress(): Boolean = addresses.any { it.isReachableAddress() }

internal fun String.isReachableAddress(): Boolean {
  if (isBlank()) return false
  if (this == "0.0.0.0" || this == "::" || this == "::0") return false
  if (startsWith("127.")) return false
  // IPv6 loopback, tolerating an optional zone id like "::1%eth0".
  if (substringBefore('%').equals("::1", ignoreCase = true)) return false
  // Tailscale / CGNAT: 100.64.0.0/10 (IPv4) and Tailscale's fd7a:115c:a1e0::/48 (IPv6 ULA). These
  // are only reachable over the tailnet, never on the LAN — dialing a peer's tailnet address just
  // wastes connect attempts and can introduce asymmetric-routing resets on multi-homed peers.
  if (isCgnatIpv4()) return false
  if (substringBefore('%').lowercase().startsWith("fd7a:115c:a1e0")) return false
  return true
}

/** True for the 100.64.0.0/10 carrier-grade-NAT range that Tailscale (and CGNAT) use. */
private fun String.isCgnatIpv4(): Boolean {
  val octets = substringBefore('%').split('.')
  if (octets.size != 4) return false
  val first = octets[0].toIntOrNull() ?: return false
  val second = octets[1].toIntOrNull() ?: return false
  return first == 100 && second in 64..127
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