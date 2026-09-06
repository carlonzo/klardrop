package com.carlom.klardrop.common.communication

import io.ktor.network.sockets.InetSocketAddress

/**
 * Extracts the numeric IP host string from an [InetSocketAddress], avoiding reverse-DNS
 * lookups that occur when accessing [InetSocketAddress.hostname] on JVM / Android.
 *
 * Sockets on Android / JVM format [InetSocketAddress.toString] as `[hostname]/<numeric-ip>:<port>`.
 * Accessing `.hostname` triggers reverse DNS and returns the peer's local hostname (e.g. "omarchy"),
 * which other devices on the LAN cannot resolve via standard unicast DNS.
 */
fun InetSocketAddress.extractNumericHost(): String {
  val str = toString()
  val withoutSlash = if ("/" in str) str.substringAfter("/") else str
  val host = if (":" in withoutSlash) withoutSlash.substringBeforeLast(":") else withoutSlash
  val cleaned = host.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
  return cleaned.ifBlank { hostname.trim().removePrefix("/").substringBefore('%') }
}
