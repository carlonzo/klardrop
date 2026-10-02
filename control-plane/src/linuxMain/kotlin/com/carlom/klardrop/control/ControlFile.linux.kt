package com.carlom.klardrop.control

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/** `$XDG_RUNTIME_DIR/klardrop`, falling back to `$HOME/.cache/klardrop`. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun controlFileDirectory(): String? {
  val xdg = getenv("XDG_RUNTIME_DIR")?.toKString()
  return if (!xdg.isNullOrEmpty()) {
    "$xdg/klardrop"
  } else {
    val home = getenv("HOME")?.toKString() ?: "."
    "$home/.cache/klardrop"
  }
}

internal actual fun writeControlFile(port: Int, token: String, capabilities: List<String>) =
  unixWriteControlFile(resolveControlFilePath(), port, token, capabilities)

internal actual fun deleteControlFile(expectedToken: String?) =
  unixDeleteControlFile(resolveControlFilePath(), expectedToken)

actual fun resolveControlFilePath(): String? = unixResolveControlFilePath()

/**
 * The Linux engine renders QR by shelling out to the system `qrencode` (see
 * `QrMatrixRenderer.linux.kt`), so the route is available here — it 500s loudly when qrencode
 * is not installed, which is a better failure than advertising a capability the user cannot use.
 */
internal actual val platformUnavailableCapabilities: Set<String> = emptySet()