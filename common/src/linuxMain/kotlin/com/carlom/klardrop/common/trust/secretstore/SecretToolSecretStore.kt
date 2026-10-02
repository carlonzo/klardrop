@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.carlom.klardrop.common.trust.secretstore

import com.carlom.klardrop.common.utils.execProcess
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.io.encoding.Base64

/**
 * Linux secret-storage path that shells out to `secret-tool`, the CLI
 * shipped with libsecret. Talks to the user's session keyring over D-Bus.
 */
internal class SecretToolSecretStore private constructor() : SecretStore {

  override fun get(account: String): ByteArray? {
    val result = try {
      execProcess(listOf("secret-tool", "lookup", "service", SECRET_STORE_SERVICE, "account", account))
    } catch (_: Exception) {
      return null
    }
    if (result.exitCode != 0) return null
    val encoded = result.stdoutString.trim()
    if (encoded.isEmpty()) return null
    return runCatching { Base64.decode(encoded) }.getOrNull()
  }

  override fun put(account: String, value: ByteArray) {
    val encoded = Base64.encode(value)
    // secret-tool reads password from stdin; expects a single line followed by EOF
    val result = execProcess(
      argv = listOf(
        "secret-tool",
        "store",
        "--label=Klardrop device identity",
        "service", SECRET_STORE_SERVICE,
        "account", account,
      ),
      stdin = (encoded + "\n").encodeToByteArray(),
    )
    if (result.exitCode != 0) {
      throw IllegalStateException("secret-tool store failed: ${result.stderrString.trim()}")
    }
  }

  override fun delete(account: String) {
    // Best-effort; non-zero exit when entry doesn't exist is fine
    try {
      execProcess(listOf("secret-tool", "clear", "service", SECRET_STORE_SERVICE, "account", account))
    } catch (_: Exception) {
      // Ignored
    }
  }

  companion object {
    fun tryCreate(): SecretToolSecretStore? {
      val dbus = getenv("DBUS_SESSION_BUS_ADDRESS")?.toKString()
      if (dbus.isNullOrBlank()) return null
      return try {
        val result = execProcess(listOf("secret-tool", "--help"), timeoutMillis = 2000L)
        if (result.exitCode == 0) SecretToolSecretStore() else null
      } catch (_: Exception) {
        null
      }
    }
  }
}
