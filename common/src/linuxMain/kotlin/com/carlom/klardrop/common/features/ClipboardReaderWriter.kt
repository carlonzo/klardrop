@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common.features

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

import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

actual class ClipboardReaderWriter : ClipboardSource {

  actual override fun read(): String {
    return try {
      val result = execProcess(listOf("wl-paste", "--no-newline", "--type", "text"))
      if (result.exitCode == 0) result.stdoutString else ""
    } catch (_: Exception) {
      ""
    }
  }

  actual override fun readForSync(): String = read()

  actual override fun write(text: String) {
    try {
      execProcess(listOf("wl-copy"), stdin = text.encodeToByteArray(), captureOutput = false)
    } catch (_: Exception) {
      // Best-effort
    }
  }

  actual override fun changeSignals(): Flow<Unit>? {
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
