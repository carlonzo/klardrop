@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.carlom.klardrop.cli

import com.carlom.klardrop.common.posix.spawn.klardrop_install_termination_handler
import com.carlom.klardrop.common.posix.spawn.klardrop_pipe2
import com.carlom.klardrop.common.utils.execProcess
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.EINTR
import platform.posix.F_SETFL
import platform.posix.O_CLOEXEC
import platform.posix.O_NONBLOCK
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.read
import platform.posix.setenv
import platform.posix.stderr

internal actual fun cliGetEnv(name: String): String? = getenv(name)?.toKString()?.takeIf { it.isNotEmpty() }

internal actual fun cliSetDataDir(dir: String) {
  // LinuxPaths (common/src/linuxMain) reads KLARDROP_HOME directly, so propagate an
  // explicit --data-dir there instead of a JVM-only system property.
  setenv("KLARDROP_HOME", dir, 1)
}

internal actual fun cliInitFileKit(dataDir: String?) {
  if (dataDir != null) {
    val cacheDir = "$dataDir/cache"
    SystemFileSystem.createDirectories(Path(dataDir))
    SystemFileSystem.createDirectories(Path(cacheDir))
    FileKit.init(
      appId = "klardrop",
      filesDir = PlatformFile(Path(dataDir)),
      cacheDir = PlatformFile(Path(cacheDir)),
    )
  } else {
    FileKit.init("klardrop")
  }
}

internal actual fun printErr(message: String) {
  fputs(message + "\n", stderr)
}

internal actual fun cliOnShutdown(action: () -> Unit) {
  // ponytail: no native shutdown hook; this is a diagnostic-only log line for `listen`,
  // not worth wiring signal handling for. `daemon` handles SIGINT/SIGTERM itself.
}

internal actual fun runProcessCaptureStdout(argv: List<String>, timeoutMillis: Long): Pair<Int, String>? {
  return try {
    val result = execProcess(argv, timeoutMillis = timeoutMillis)
    result.exitCode to result.stdoutString.trim()
  } catch (e: Exception) {
    null
  }
}

// Self-pipe trick: the SIGINT/SIGTERM handler (implemented in C — see
// klardrop_install_termination_handler in spawn.def, since a Kotlin staticCFunction callback
// isn't guaranteed async-signal-safe) does only a write() of one byte, which wakes a blocking
// read() on a dedicated thread — zero CPU while idle, no polling. Deliberately not a
// process-wide pthread_sigmask() block: Kotlin/Native's own GC threads are created before this
// code ever runs and never inherit a mask we set here, so a signal sent to the process could
// still land on one of them and take the default (terminating) action. A custom handler has no
// such gap — it replaces the default disposition, and applies no matter which thread the signal
// is delivered to.
private var selfPipeReadFd = -1

// A dedicated OS thread, parked in a blocking read() for the life of the daemon. Lazy so
// commands other than `daemon` never pay for it.
@OptIn(ObsoleteCoroutinesApi::class)
private val signalWaitDispatcher by lazy { newSingleThreadContext("klardrop-sigwait") }

internal actual fun installTerminationSignalHandler() {
  if (selfPipeReadFd >= 0) return // already installed; a second call would leak the old pipe
  memScoped {
    val fds = allocArray<IntVar>(2)
    if (klardrop_pipe2(fds, O_CLOEXEC) != 0) {
      // A pipe is required for awaitTerminationRequest() to ever wake up: without one it
      // would return immediately and the daemon would look like it exited cleanly at
      // startup instead of failing loudly, so surface this as a real startup failure.
      throw IllegalStateException("failed to create self-pipe for termination signal handling")
    }
    selfPipeReadFd = fds[0]
    // O_NONBLOCK on the write end: the handler's write() must never be able to block (e.g. a
    // full pipe buffer from a burst of repeated signals) while it's running inside a signal.
    fcntl(fds[1], F_SETFL, O_NONBLOCK)
    klardrop_install_termination_handler(fds[1])
  }
}

internal actual suspend fun awaitTerminationRequest() {
  val readFd = selfPipeReadFd
  check(readFd >= 0) { "installTerminationSignalHandler() must be called first" }
  withContext(signalWaitDispatcher) {
    memScoped {
      val buf = allocArray<ByteVar>(1)
      while (true) {
        val n = read(readFd, buf, 1u)
        if (n >= 0) break
        if (errno != EINTR) break
      }
    }
  }
}

internal actual fun onCleanupComplete() {
  // No blocking needed: the native process simply returns from main() once cleanup finishes.
}
