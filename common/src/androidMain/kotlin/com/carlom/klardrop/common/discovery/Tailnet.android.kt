package com.carlom.klardrop.common.discovery

import java.net.Inet4Address
import java.net.NetworkInterface

/** The Tailscale app's VPN is a `tun` interface; carrier CGNAT addresses on `rmnet*` are skipped. */
internal actual fun localTailnetIpv4s(): List<String> = runCatching {
  tailnetIpv4sOf(
    NetworkInterface.getNetworkInterfaces().toList()
      .filter { runCatching { it.isUp }.getOrDefault(false) }
      .flatMap { iface ->
        iface.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { a -> a.hostAddress?.let { iface.name to it } }
      },
  )
}.getOrDefault(emptyList())

/** Another app's VPN peer list isn't readable on Android; remembered peers are all we have. */
internal actual suspend fun tailscalePeerIpv4s(): List<String>? = null
