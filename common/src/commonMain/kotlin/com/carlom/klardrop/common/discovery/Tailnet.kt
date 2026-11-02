package com.carlom.klardrop.common.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** This device's own Tailscale IPv4 addresses (100.64.0.0/10 on any up interface). */
internal expect fun localTailnetIpv4s(): List<String>

/**
 * IPv4 addresses of the online tailnet peers owned by the same Tailscale user, read from the
 * local `tailscale` CLI. Null when this platform can't ask Tailscale (mobile apps can't read
 * another app's VPN state) or the CLI isn't installed — callers then rely on remembered peers.
 */
internal expect suspend fun tailscalePeerIpv4s(): List<String>?

internal fun List<String>.tailnetOnly(): List<String> = filter { it.isCgnatIpv4() }.distinct()

/**
 * Tailscale's addresses from (interface name, IPv4) pairs. The range alone isn't enough: mobile
 * carriers hand out 100.64.0.0/10 CGNAT addresses on the cellular interface (rmnet*, pdp_ip*), so
 * only the VPN interface counts — tun* (Android), utun* (Apple), tailscale* (Linux, Windows).
 */
internal fun tailnetIpv4sOf(interfaces: List<Pair<String, String>>): List<String> =
  interfaces.filter { (name, _) -> name.isTailnetInterfaceName() }.map { it.second }.tailnetOnly()

private fun String.isTailnetInterfaceName(): Boolean {
  val lower = lowercase()
  return lower.startsWith("tun") || lower.startsWith("utun") || lower.contains("tailscale")
}

/**
 * Parses `tailscale status --json`. Only online peers of the same user are returned: on a
 * shared or company tailnet, probing every node once a minute would look like a port scan.
 */
internal fun parseTailscaleStatus(json: String): List<String> {
  val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return emptyList()
  val selfUser = root["Self"]?.jsonObject?.get("UserID")?.jsonPrimitive?.contentOrNull
  val peers = root["Peer"] as? JsonObject ?: return emptyList()
  return peers.values.mapNotNull { it as? JsonObject }
    .filter { it["Online"]?.jsonPrimitive?.booleanOrNull == true }
    .filter { selfUser == null || it["UserID"]?.jsonPrimitive?.contentOrNull == selfUser }
    .flatMap { peer -> peer["TailscaleIPs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty() }
    .tailnetOnly()
}

/** Where the `tailscale` CLI usually lives; the first entry relies on PATH. */
internal val TAILSCALE_CLI_CANDIDATES: List<String> = listOf(
  "tailscale",
  "/usr/bin/tailscale",
  "/usr/local/bin/tailscale",
  "/opt/homebrew/bin/tailscale",
  "/Applications/Tailscale.app/Contents/MacOS/Tailscale",
  "C:\\Program Files\\Tailscale\\tailscale.exe",
)
