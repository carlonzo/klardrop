@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common.features

import com.carlom.klardrop.common.update.listDirectory
import com.carlom.klardrop.common.utils.execProcess
import com.carlom.klardrop.common.utils.execStreamingLines
import com.carlom.klardrop.common.utils.log
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import platform.posix.getenv
import platform.posix.setenv

import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal actual class ClipboardReaderWriter : ClipboardSource {

  actual override fun read(): String {
    ensureWaylandDisplay()
    return try {
      val result = execProcess(listOf("wl-paste", "--no-newline", "--type", "text"))
      if (result.exitCode == 0) result.stdoutString else ""
    } catch (_: Exception) {
      ""
    }
  }

  actual override fun readForSync(): String = read()

  actual override fun write(text: String) {
    ensureWaylandDisplay()
    try {
      execProcess(listOf("wl-copy"), stdin = text.encodeToByteArray(), captureOutput = false)
    } catch (_: Exception) {
      // Best-effort
    }
  }

  actual override fun changeSignals(): Flow<Unit>? {
    ensureWaylandDisplay()
    val waylandDisplay = getenv("WAYLAND_DISPLAY")?.toKString()
    if (waylandDisplay.isNullOrEmpty()) {
      return null
    }

    return flow {
      if (!canSpawnWlPaste()) {
        throw IllegalStateException("wl-paste is not available")
      }
      var backoffMs = 500L
      var quickDeaths = 0
      while (currentCoroutineContext().isActive) {
        val startTime = TimeSource.Monotonic.markNow()
        try {
          // Permanent tail: runs for the whole session on raw Dispatchers.IO, NOT the shared
          // capped pool — its poll loop would otherwise hold one of the daemon's 2 IO slots
          // forever. One-shot wl-paste/wl-copy calls below stay on the capped pool (brief).
          execStreamingLines(listOf("wl-paste", "--watch", "echo", "x"), dispatcher = Dispatchers.IO).collect {
            emit(Unit)
          }
          log("wl-paste --watch exited")
        } catch (e: CancellationException) {
          throw e
        } catch (t: Throwable) {
          log("wl-paste --watch error: ${t.message}")
        }
        val elapsed = startTime.elapsedNow()
        if (elapsed >= 10.seconds) {
          quickDeaths = 0
          backoffMs = 500L
        } else {
          quickDeaths++
          if (quickDeaths >= 3) {
            throw IllegalStateException("wl-paste --watch failed/exited quickly $quickDeaths times in a row")
          }
        }
        log("Backing off ${backoffMs}ms before restart")
        delay(backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
      }
    }
  }

  private fun canSpawnWlPaste(): Boolean {
    return try {
      execProcess(listOf("wl-paste", "--version"), timeoutMillis = 1000L).exitCode == 0
    } catch (_: Exception) {
      false
    }
  }
}

/**
 * The systemd user service can start before the compositor exports WAYLAND_DISPLAY
 * (e.g. at boot), leaving wl-paste/wl-copy unable to reach the clipboard for the
 * daemon's whole lifetime. When it's unset, adopt the session's socket from
 * XDG_RUNTIME_DIR so child processes inherit it.
 */
internal fun ensureWaylandDisplay() {
  if (!getenv("WAYLAND_DISPLAY")?.toKString().isNullOrEmpty()) return
  val runtimeDir = getenv("XDG_RUNTIME_DIR")?.toKString()?.takeIf { it.isNotEmpty() } ?: return
  val socket = waylandSocketName(runCatching { listDirectory(runtimeDir) }.getOrDefault(emptyList())) ?: return
  // ponytail: process-wide setenv can race a concurrent posix_spawn reading environ; it runs
  // once (afterwards the getenv check returns early). Pass env per-spawn if that ever bites.
  setenv("WAYLAND_DISPLAY", socket, 0)
}

/** The lowest-numbered `wayland-N` socket name, ignoring `.lock` files. */
internal fun waylandSocketName(entries: List<String>): String? =
  entries.filter { Regex("wayland-\\d+").matches(it) }.minByOrNull { it.removePrefix("wayland-").toInt() }
