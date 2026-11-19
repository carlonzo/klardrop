package com.carlom.klardrop.common.qrshare

import com.carlom.klardrop.common.discovery.isCgnatIpv4
import kotlinx.coroutines.flow.Flow

internal data class LanInterfaceAddress(
  val ifaceName: String,
  val address: String,
  val prefixLength: Int? = null,
)

internal data class ManualLanAddress(
  val address: String,
  val prefixLength: Int?,
  val allAddresses: List<String>,
)

internal interface LanAddressSelector {
  suspend fun selectIpv4(): String?
  suspend fun selectManualLanAddress(): ManualLanAddress? = selectIpv4()?.let {
    ManualLanAddress(it, null, listOf(it))
  }
  fun observeChanges(): Flow<String?>
}

internal expect class PlatformLanAddressSelector() : LanAddressSelector {
  override suspend fun selectIpv4(): String?
  override suspend fun selectManualLanAddress(): ManualLanAddress?
  override fun observeChanges(): Flow<String?>
}

internal fun LanAddressSelector(): LanAddressSelector = PlatformLanAddressSelector()

/**
 * Selects the best LAN address for manual add:
 * 1. [selectLanAddress] result (RFC1918), if present.
 * 2. Else the first IPv4 on a Wi-Fi/Ethernet interface that is not dropped even if NOT RFC1918 (e.g. campus Wi-Fi).
 *
 * The QR `ip` list = that chosen address first, then other non-dropped Wi-Fi/Ethernet IPv4s (distinct, max 4).
 */
internal fun selectManualLanAddress(candidates: Iterable<LanInterfaceAddress>): ManualLanAddress? {
  val pairs = candidates.map { it.ifaceName to it.address }
  val rfc1918Chosen = selectLanAddress(pairs)

  val chosenCandidate: LanInterfaceAddress? = if (rfc1918Chosen != null) {
    candidates.firstOrNull { it.address == rfc1918Chosen }
  } else {
    candidates.firstOrNull { candidate ->
      val clean = candidate.address.trim().trim('[', ']').substringBefore('%')
      !isDroppedAddress(clean) &&
        !isDroppedInterface(candidate.ifaceName) &&
        (isWifiOrApInterface(candidate.ifaceName) || isEthernetInterface(candidate.ifaceName))
    }
  }

  if (chosenCandidate == null) return null

  val cleanChosen = chosenCandidate.address.trim().trim('[', ']').substringBefore('%')

  val otherIps = candidates.mapNotNull { candidate ->
    val clean = candidate.address.trim().trim('[', ']').substringBefore('%')
    if (!isDroppedAddress(clean) &&
      !isDroppedInterface(candidate.ifaceName) &&
      (isWifiOrApInterface(candidate.ifaceName) || isEthernetInterface(candidate.ifaceName))
    ) {
      clean
    } else {
      null
    }
  }

  val allAddresses = (listOf(cleanChosen) + otherIps).distinct().take(4)

  return ManualLanAddress(
    address = cleanChosen,
    prefixLength = chosenCandidate.prefixLength,
    allAddresses = allAddresses,
  )
}

/**
 * Selects the best IPv4 address for QR LAN sharing from candidate interfaces.
 *
 * Total order:
 * 1. Wi-Fi / AP RFC1918 (including hotspot subnets regardless of interface name)
 * 2. Ethernet RFC1918
 * 3. Else null (no generic RFC1918 fallback)
 */
internal fun selectLanAddress(candidates: Iterable<Pair<String, String>>): String? {
  var staWifi: String? = null
  var hotspot: String? = null
  var ethernet: String? = null

  for ((ifaceName, rawAddress) in candidates) {
    val clean = rawAddress.trim().trim('[', ']').substringBefore('%')
    if (isDroppedAddress(clean) || isDroppedInterface(ifaceName)) {
      continue
    }
    if (!clean.isRfc1918Ipv4()) {
      continue
    }
    val hotspotSubnet = clean.isHotspotSubnetIpv4()
    val wifi = isWifiOrApInterface(ifaceName)
    when {
      wifi && !hotspotSubnet -> if (staWifi == null) staWifi = clean
      hotspotSubnet -> if (hotspot == null) hotspot = clean
      isEthernetInterface(ifaceName) -> if (ethernet == null) ethernet = clean
    }
  }

  // STA Wi-Fi first: a leftover/soft-AP 192.168.43.1 must not beat the LAN
  // address the receiver is actually on (that QR is unreachable).
  return staWifi ?: hotspot ?: ethernet
}

internal fun selectLanAddressOf(candidates: Iterable<LanInterfaceAddress>): String? =
  selectLanAddress(candidates.map { it.ifaceName to it.address })

internal fun selectLanIpv4(candidates: Iterable<Pair<String, String>>): String? = selectLanAddress(candidates)

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
