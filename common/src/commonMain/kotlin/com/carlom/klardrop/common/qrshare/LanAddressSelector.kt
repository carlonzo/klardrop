package com.carlom.klardrop.common.qrshare

import com.carlom.klardrop.common.discovery.isCgnatIpv4
import kotlinx.coroutines.flow.Flow

interface LanAddressSelector {
  suspend fun selectIpv4(): String?
  fun observeChanges(): Flow<String?>
}

expect class PlatformLanAddressSelector() : LanAddressSelector {
  override suspend fun selectIpv4(): String?
  override fun observeChanges(): Flow<String?>
}

fun LanAddressSelector(): LanAddressSelector = PlatformLanAddressSelector()

/**
 * Selects the best IPv4 address for QR LAN sharing from candidate interfaces.
 *
 * Total order:
 * 1. Wi-Fi / AP RFC1918 (including hotspot subnets regardless of interface name)
 * 2. Ethernet RFC1918
 * 3. Else null (no generic RFC1918 fallback)
 */
fun selectLanAddress(candidates: Iterable<Pair<String, String>>): String? {
  var firstWifiOrHotspot: String? = null
  var firstEthernet: String? = null

  for ((ifaceName, rawAddress) in candidates) {
    val clean = rawAddress.trim().trim('[', ']').substringBefore('%')
    if (isDroppedAddress(clean) || isDroppedInterface(ifaceName)) {
      continue
    }
    if (!clean.isRfc1918Ipv4()) {
      continue
    }
    if (isWifiOrApInterface(ifaceName) || clean.isHotspotSubnetIpv4()) {
      if (firstWifiOrHotspot == null) {
        firstWifiOrHotspot = clean
      }
    } else if (isEthernetInterface(ifaceName)) {
      if (firstEthernet == null) {
        firstEthernet = clean
      }
    }
  }

  return firstWifiOrHotspot ?: firstEthernet
}

fun selectLanIpv4(candidates: Iterable<Pair<String, String>>): String? = selectLanAddress(candidates)

internal fun isDroppedAddress(address: String): Boolean {
  if (address.isBlank() || address.contains(':') || address == "0.0.0.0" || address.startsWith("127.")) {
    return true
  }
  if (address.isCgnatIpv4() || address.isLinkLocalIpv4()) {
    return true
  }
  val octets = address.split('.')
  if (octets.size != 4) return true
  for (part in octets) {
    val value = part.toIntOrNull() ?: return true
    if (value !in 0..255) return true
  }
  return false
}

internal fun isDroppedInterface(name: String): Boolean {
  val lower = name.lowercase().trim()
  if (lower.startsWith("tun") ||
    lower.startsWith("utun") ||
    lower.startsWith("ppp") ||
    lower.startsWith("wg") ||
    lower.startsWith("tailscale") ||
    lower.startsWith("ipsec")
  ) {
    return true
  }
  if (lower.startsWith("rmnet") ||
    lower.startsWith("ccmni") ||
    lower.startsWith("v4-rmnet") ||
    lower.startsWith("pdp") ||
    lower.startsWith("wwan")
  ) {
    return true
  }
  return false
}

internal fun isWifiOrApInterface(name: String): Boolean {
  val lower = name.lowercase().trim()
  return lower.startsWith("wlan") ||
    lower == "en0" ||
    lower.startsWith("ap") ||
    lower.startsWith("swlan") ||
    lower.startsWith("wl")
}

internal fun isEthernetInterface(name: String): Boolean {
  val lower = name.lowercase().trim()
  return lower.startsWith("eth") ||
    (lower.startsWith("en") && lower != "en0") ||
    lower.startsWith("lan")
}

internal fun String.isLinkLocalIpv4(): Boolean {
  val octets = substringBefore('%').split('.')
  if (octets.size != 4) return false
  val first = octets[0].toIntOrNull() ?: return false
  val second = octets[1].toIntOrNull() ?: return false
  return first == 169 && second == 254
}

internal fun String.isRfc1918Ipv4(): Boolean {
  val octets = substringBefore('%').split('.')
  if (octets.size != 4) return false
  val first = octets[0].toIntOrNull() ?: return false
  val second = octets[1].toIntOrNull() ?: return false
  val third = octets[2].toIntOrNull() ?: return false
  val fourth = octets[3].toIntOrNull() ?: return false
  if (first !in 0..255 || second !in 0..255 || third !in 0..255 || fourth !in 0..255) return false
  return when (first) {
    10 -> true
    172 -> second in 16..31
    192 -> second == 168
    else -> false
  }
}

internal fun String.isHotspotSubnetIpv4(): Boolean {
  val octets = substringBefore('%').split('.')
  if (octets.size != 4) return false
  val first = octets[0].toIntOrNull() ?: return false
  val second = octets[1].toIntOrNull() ?: return false
  val third = octets[2].toIntOrNull() ?: return false
  val fourth = octets[3].toIntOrNull() ?: return false
  if (first !in 0..255 || second !in 0..255 || third !in 0..255 || fourth !in 0..255) return false
  return (first == 192 && second == 168 && third == 43) ||
    (first == 172 && second == 20 && third == 10) ||
    (first == 192 && second == 168 && third == 137)
}
