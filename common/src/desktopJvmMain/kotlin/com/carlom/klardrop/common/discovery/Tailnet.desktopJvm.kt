package com.carlom.klardrop.common.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

internal actual fun localTailnetIpv4s(): List<String> = runCatching {
  tailnetIpv4sOf(
    NetworkInterface.getNetworkInterfaces().toList()
      .filter { runCatching { it.isUp }.getOrDefault(false) }
      .flatMap { iface ->
        // Windows names adapters ethN; "Tailscale" is only in the display name.
        val name = if (iface.displayName.orEmpty().contains("tailscale", ignoreCase = true)) iface.displayName else iface.name
        iface.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { a -> a.hostAddress?.let { name to it } }
      },
  )
}.getOrDefault(emptyList())

internal actual suspend fun tailscalePeerIpv4s(): List<String>? = withContext(Dispatchers.IO) {
  for (cli in TAILSCALE_CLI_CANDIDATES) {
    val process = try {
      ProcessBuilder(cli, "status", "--json")
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    } catch (_: IOException) {
      continue // not installed at this path
    }
    // Read on a side thread: a child that fills the pipe would otherwise never exit.
    val stdout = async { process.inputStream.bufferedReader().readText() }
    if (!process.waitFor(TAILSCALE_CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      return@withContext null
    }
    val json = stdout.await()
    return@withContext if (process.exitValue() == 0) parseTailscaleStatus(json) else null
  }
  null
}

private const val TAILSCALE_CLI_TIMEOUT_SECONDS = 3L
