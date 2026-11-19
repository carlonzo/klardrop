package com.carlom.klardrop.common.discovery

/**
 * Derives the host part of an IP address at octet granularity for manual add.
 * Trailing octets = ceil((32 - prefix)/8), clamped to 1..3.
 * If prefix is null or invalid, defaults to 2 octets.
 */
internal fun lanCode(ip: String, prefixLength: Int?): String {
  val octetCount = when {
    prefixLength == null || prefixLength !in 0..32 -> 2
    else -> ((32 - prefixLength + 7) / 8).coerceIn(1, 3)
  }
  val parts = ip.split('.')
  if (parts.size != 4) return ip
  return parts.takeLast(octetCount).joinToString(".")
}

/**
 * Result of parsing a manual device target input (code, IP, IP:port, or QR URI).
 */
internal sealed interface ManualTarget {
  data class Target(
    val addresses: List<String>,
    val port: Int,
    val expectedDeviceId: String? = null,
  ) : ManualTarget

  data object Invalid : ManualTarget
  data object NoLanAddress : ManualTarget
}

/**
 * Encodes the manual add QR payload URI (version 1):
 * klardrop://add?v=1&id=<shortDeviceId>&ip=<ipv4>[,<ipv4>...]&port=<port>
 */
internal fun encodeManualAddPayload(deviceId: String, ips: List<String>, port: Int): String {
  val idParam = if (deviceId.isNotEmpty()) "&id=$deviceId" else ""
  return "klardrop://add?v=1$idParam&ip=${ips.joinToString(",")}&port=$port"
}

/**
 * Parses user input into a [ManualTarget]:
 * a) 1..3 dot-separated octets, optional :port (combined with each of [ownIps])
 * b) full IPv4 a.b.c.d, optional :port
 * c) QR payload URI (klardrop://add?v=1...)
 */
internal fun parseManualInput(input: String, ownIps: List<String>, defaultPort: Int): ManualTarget {
  val trimmed = input.trim()
  if (trimmed.isEmpty()) return ManualTarget.Invalid

  if (trimmed.startsWith("klardrop://", ignoreCase = true)) {
    return parseQrUri(trimmed)
  }

  val hasColon = trimmed.contains(':')
  val addressPart: String
  val port: Int

  if (hasColon) {
    if (trimmed.count { it == ':' } != 1) return ManualTarget.Invalid
    addressPart = trimmed.substringBefore(':')
    val portStr = trimmed.substringAfter(':')
    if (!isAsciiDigitString(portStr)) return ManualTarget.Invalid
    port = portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: return ManualTarget.Invalid
  } else {
    addressPart = trimmed
    port = defaultPort
  }

  val octets = addressPart.split('.')
  if (octets.size !in 1..4) return ManualTarget.Invalid
  val octetInts = IntArray(octets.size)
  for (i in octets.indices) {
    if (!isValidOctet(octets[i])) return ManualTarget.Invalid
    octetInts[i] = octets[i].toInt()
  }

  if (octets.size == 4) {
    // Rebuild from parsed ints so "192.168.1.037" -> "192.168.1.37"
    return ManualTarget.Target(
      addresses = listOf(octetInts.joinToString(".")),
      port = port,
      expectedDeviceId = null,
    )
  }

  // 1..3 octets: combine with each own address (a device may sit on several networks)
  val n = octets.size
  val combined = ownIps.mapNotNull { ownIp ->
    val ownOctets = ownIp.trim().split('.')
    if (ownOctets.size != 4 || !ownOctets.all { isValidOctet(it) }) return@mapNotNull null
    // Rebuild from parsed ints (prefix from own, suffix from input)
    (ownOctets.take(4 - n).map { it.toInt() } + octetInts.toList()).joinToString(".")
  }.distinct()
  if (combined.isEmpty()) return ManualTarget.NoLanAddress
  return ManualTarget.Target(
    addresses = combined,
    port = port,
    expectedDeviceId = null,
  )
}

private fun parseQrUri(uri: String): ManualTarget {
  val prefix = "klardrop://add?"
  if (!uri.startsWith(prefix, ignoreCase = true)) return ManualTarget.Invalid

  val queryString = uri.substring(prefix.length)
  val params = mutableMapOf<String, String>()
  for (param in queryString.split('&')) {
    if (param.isEmpty()) continue
    val eq = param.indexOf('=')
    if (eq >= 0) {
      params[param.substring(0, eq)] = param.substring(eq + 1)
    } else {
      params[param] = ""
    }
  }

  val version = params["v"]
  if (version != "1") return ManualTarget.Invalid

  val portStr = params["port"] ?: return ManualTarget.Invalid
  if (!isAsciiDigitString(portStr)) return ManualTarget.Invalid
  val port = portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: return ManualTarget.Invalid

  val ipStr = params["ip"] ?: return ManualTarget.Invalid
  // Skip invalid entries instead of rejecting the whole QR.
  // Rebuild from parsed ints to normalise leading zeros
  val ips: List<String> = ipStr.split(',')
    .map { it.trim() }
    .filter { isValidIpv4(it) }
    .map { ip -> ip.split('.').joinToString(".") { it.toInt().toString() } }
    .distinct()
    .take(4)
  if (ips.isEmpty()) return ManualTarget.Invalid

  val expectedDeviceId = params["id"]?.takeIf { it.isNotEmpty() }
  return ManualTarget.Target(
    addresses = ips,
    port = port,
    expectedDeviceId = expectedDeviceId,
  )
}

/** Accepts only ASCII '0'..'9'; rejects Unicode digits (e.g. Arabic-Indic "٣٧"). */
private fun isValidOctet(part: String): Boolean {
  if (part.isEmpty()) return false
  for (c in part) if (c < '0' || c > '9') return false
  val value = part.toIntOrNull() ?: return false
  return value in 0..255
}

/** True if [s] is non-empty and every character is an ASCII decimal digit. */
private fun isAsciiDigitString(s: String): Boolean {
  if (s.isEmpty()) return false
  for (c in s) if (c < '0' || c > '9') return false
  return true
}

private fun isValidIpv4(ip: String): Boolean {
  val octets = ip.split('.')
  if (octets.size != 4) return false
  return octets.all { isValidOctet(it) }
}
