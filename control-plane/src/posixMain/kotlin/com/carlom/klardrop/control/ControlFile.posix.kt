package com.carlom.klardrop.control

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.*

/**
 * POSIX control-file storage, shared verbatim by the Linux and macOS engines.
 *
 * Both are Kotlin/Native hosts on a POSIX filesystem, so the mode bits, the mkdir/open/chmod
 * sequence and the "fail closed if the token file cannot be protected" contract are identical —
 * only *where* the file lives differs, which is what [controlFileDirectory] answers per platform
 * (`linuxMain`: XDG runtime dir, falling back to `$HOME/.cache`; `macosMain`: the sandboxed
 * app's App Group container, the only location a sandboxed app may write that an unsandboxed
 * `klardrop` CLI can also read).
 *
 * Keeping one implementation is deliberate: the token is the only thing standing between a
 * stranger on the loopback interface and this device's identity, so two hand-maintained copies
 * of "how do I narrow a file to 0600" is two chances to get one of them wrong.
 */

/** Directory the control file lives in. Null when this host has no writable location at all. */
internal expect fun controlFileDirectory(): String?

/**
 * Every POSIX host here has a real control-file location, so a null path means something broke
 * (no HOME, no App Group container). Refusing to start is the safe reading — see
 * [controlFileOptional].
 */
internal actual val controlFileOptional: Boolean = false

/**
 * `KLARDROP_CONTROL_FILE` pins the exact file, ahead of the per-platform default.
 *
 * `XDG_RUNTIME_DIR` already relocates the Linux path, but macOS has no such ambient variable —
 * its default lives inside the app's sandboxed App Group container, which a test binary has no
 * entitlement to reach. This is the hook that lets the macOS fixture exercise the real
 * write/protect/delete path, and the same knob works on both unix hosts. Unset in production.
 */
internal fun unixResolveControlFilePath(): String? {
  val pinned = readEnv("KLARDROP_CONTROL_FILE")
  if (!pinned.isNullOrEmpty()) return pinned
  return controlFileDirectory()?.let { "$it/control.json" }
}

@OptIn(ExperimentalForeignApi::class)
private fun readEnv(name: String): String? = getenv(name)?.toKString()

/**
 * `mkdir`/`chmod`/`fchmod` take a `mode_t`, which is `unsigned int` on Linux and `unsigned
 * short` on Darwin — a width that changes between the two platforms this source set covers.
 * A shared source set is not allowed to name such a type (the compiler rejects the call as
 * an inconsistent multipplatform signature), so the three calls that need one are wrapped
 * in fixed-width helpers here and implemented per platform. Only the marshalling differs;
 * the policy around it — which bits, and what a failure means — stays in one place.
 */
internal expect fun mkdirOwnerOnly(dir: String): Int

/** @see mkdirOwnerOnly */
internal expect fun chmodOwnerOnly(path: String): Int

/** @see mkdirOwnerOnly */
internal expect fun fchmodOwnerOnly(fd: Int): Int

@OptIn(ExperimentalForeignApi::class)
internal fun unixWriteControlFile(pathStr: String?, port: Int, token: String, capabilities: List<String>) {
  if (pathStr == null) return
  val content = controlFileJson(port, token, capabilities)

  val lastSlash = pathStr.lastIndexOf('/')
  if (lastSlash > 0) {
    val dir = pathStr.substring(0, lastSlash)
    // Narrow the directory ONLY when this call is what created it. Two reasons:
    //   - mkdir()'s mode is masked by the umask, so a directory we just created is not
    //     necessarily 0700 until we say so.
    //   - a directory that already existed is not ours to re-permission. The path can be
    //     pointed anywhere (KLARDROP_CONTROL_FILE), and chmod-ing a pre-existing directory
    //     to 0700 would break unrelated things — `/tmp` becoming owner-only is the obvious
    //     disaster. The token is protected by the file's own 0600 below either way, which is
    //     what actually matters; the directory mode only stops someone listing the file's
    //     existence and name.
    if (mkdirOwnerOnly(dir) == 0) {
      if (chmodOwnerOnly(dir) != 0) {
        throw IllegalStateException("cannot restrict permissions on $dir (errno $errno)")
      }
    } else if (errno != EEXIST) {
      throw IllegalStateException("cannot create control directory $dir (errno $errno)")
    }
  }

  // open() is variadic, so its mode goes through the default argument promotion to `int`
  // — a bare `mode_t` would push 16 bits on Darwin where the callee reads 32.
  val fd = open(pathStr, O_WRONLY or O_CREAT or O_TRUNC, (S_IRUSR or S_IWUSR).toInt())
  if (fd < 0) {
    throw IllegalStateException("cannot create control file $pathStr (errno $errno)")
  }
  try {
    // open()'s mode only applies when it creates the file; an existing file keeps its old
    // permissions, so set them explicitly — the token must never be readable by another user.
    if (fchmodOwnerOnly(fd) != 0) {
      throw IllegalStateException("cannot restrict permissions on $pathStr (errno $errno)")
    }
    val bytes = content.encodeToByteArray()
    val written = bytes.usePinned { pinned ->
      write(fd, pinned.addressOf(0), bytes.size.toULong())
    }
    if (written.toLong() != bytes.size.toLong()) {
      throw IllegalStateException("short write on $pathStr ($written of ${bytes.size} bytes, errno $errno)")
    }
  } finally {
    close(fd)
  }
}

@OptIn(ExperimentalForeignApi::class)
internal fun unixDeleteControlFile(pathStr: String?, expectedToken: String?) {
  if (pathStr == null) return

  if (expectedToken != null) {
    val currentToken = readControlFileToken(pathStr)
    if (currentToken != expectedToken) {
      return
    }
  }

  unlink(pathStr)
}

/** Best-effort extraction of the `"token"` field from control.json; null if unreadable/absent. */
@OptIn(ExperimentalForeignApi::class)
private fun readControlFileToken(pathStr: String): String? {
  val fd = open(pathStr, O_RDONLY)
  if (fd < 0) return null
  try {
    val bufSize = 4096
    val buffer = ByteArray(bufSize)
    val bytesRead = buffer.usePinned { pinned ->
      read(fd, pinned.addressOf(0), bufSize.toULong())
    }
    if (bytesRead <= 0) return null
    val text = buffer.decodeToString(0, bytesRead.toInt())
    return Regex(""""token"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
  } finally {
    close(fd)
  }
}