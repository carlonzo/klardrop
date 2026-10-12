package com.carlom.klardrop.control

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.S_IRUSR
import platform.posix.S_IRWXU
import platform.posix.S_IWUSR
import platform.posix.chmod
import platform.posix.fchmod
import platform.posix.getenv
import platform.posix.mkdir

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

// `mode_t` is `unsigned int` here, so the shared source set's fixed-width helpers widen the
// bits to it — see the declarations in ControlFile.posix.kt for why these three are wrapped.
@OptIn(ExperimentalForeignApi::class)
internal actual fun mkdirOwnerOnly(dir: String): Int = mkdir(dir, (S_IRWXU).toUInt())

@OptIn(ExperimentalForeignApi::class)
internal actual fun chmodOwnerOnly(path: String): Int = chmod(path, (S_IRWXU).toUInt())

@OptIn(ExperimentalForeignApi::class)
internal actual fun fchmodOwnerOnly(fd: Int): Int = fchmod(fd, (S_IRUSR or S_IWUSR).toUInt())

/**
 * The Linux engine renders QR by shelling out to the system `qrencode` (see
 * `QrMatrixRenderer.linux.kt`), so the route is available here — it 500s loudly when qrencode
 * is not installed, which is a better failure than advertising a capability the user cannot use.
 */
internal actual val platformUnavailableCapabilities: Set<String> = emptySet()