package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.network.getLinuxNetworkInterfaces
import com.carlom.klardrop.common.utils.ExecException
import com.carlom.klardrop.common.utils.execProcess
import com.carlom.klardrop.common.utils.sharedNativeIoDispatcher
import kotlinx.coroutines.withContext

internal actual fun localTailnetIpv4s(): List<String> =
  tailnetIpv4sOf(getLinuxNetworkInterfaces().filter { it.isUp }.flatMap { i -> i.ipv4Addresses.map { i.name to it } })

internal actual suspend fun tailscalePeerIpv4s(): List<String>? = withContext(sharedNativeIoDispatcher) {
  for (cli in TAILSCALE_CLI_CANDIDATES) {
    val result = try {
      execProcess(listOf(cli, "status", "--json"), timeoutMillis = TAILSCALE_CLI_TIMEOUT_MS)
    } catch (_: ExecException) {
      continue // not installed at this path
    } catch (_: Exception) {
      return@withContext null
    }
    return@withContext if (result.exitCode == 0) parseTailscaleStatus(result.stdoutString) else null
  }
  null
}

private const val TAILSCALE_CLI_TIMEOUT_MS = 3_000L
