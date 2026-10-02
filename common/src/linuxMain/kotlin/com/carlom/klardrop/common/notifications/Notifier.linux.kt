@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common.notifications

import com.carlom.klardrop.common.utils.execProcess
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.SIGTERM
import platform.posix.kill

actual class Notifier {

  private val flow = MutableSharedFlow<NotificationAction>(
    extraBufferCapacity = 16,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  actual val actions: Flow<NotificationAction> = flow.asSharedFlow()

  // Raw Dispatchers.IO, NOT the shared capped pool: notify-send --wait parks this scope's
  // thread for up to 120s per lingering prompt, and two unanswered prompts would otherwise
  // stall every other user of the daemon's 2 capped IO slots (including pairing storage
  // writes). Prompts are rare and threads exist only while one is displayed.
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val activeJobsMutex = Mutex()
  private val activeJobs = mutableMapOf<String, NotificationHandle>()

  private data class NotificationHandle(
    val job: Job,
    var pid: Int? = null,
  )

  actual fun show(notification: AppNotification) {
    cancel(notification.id)

    when (notification) {
      is AppNotification.IncomingPairing -> showIncomingPairing(notification)
    }
  }

  private fun showIncomingPairing(notification: AppNotification.IncomingPairing) {
    val job = scope.launch {
      try {
        val argv = listOf(
          "notify-send",
          "-a", "Klardrop",
          "-i", "klardrop",
          "--action=accept=Accept",
          "--action=reject=Reject",
          "--wait",
          "Pairing request",
          "${notification.deviceName} wants to pair with this device.",
        )
        val result = execProcess(
          argv = argv,
          timeoutMillis = 120_000L,
          onPid = { pid ->
            scope.launch {
              activeJobsMutex.withLock {
                activeJobs[notification.id]?.pid = pid
              }
            }
          }
        )

        if (result.exitCode == 0) {
          when (result.stdoutString.trim()) {
            "accept" -> {
              log("Notifier", "Pairing accepted from notification id=${notification.id}")
              flow.emit(NotificationAction.PairingAccepted(notification.id, notification.deviceId))
            }
            "reject" -> {
              log("Notifier", "Pairing rejected from notification id=${notification.id}")
              flow.emit(NotificationAction.PairingRejected(notification.id, notification.deviceId))
            }
          }
        }
      } catch (e: Exception) {
        log("Notifier", "notify-send failed or timed out: ${e.message}")
      } finally {
        activeJobsMutex.withLock {
          activeJobs.remove(notification.id)
        }
      }
    }

    scope.launch {
      activeJobsMutex.withLock {
        activeJobs[notification.id] = NotificationHandle(job)
      }
    }
  }

  actual fun cancel(id: String) {
    scope.launch {
      val handle = activeJobsMutex.withLock { activeJobs.remove(id) } ?: return@launch
      handle.job.cancel()
      handle.pid?.let { pid ->
        kill(pid, SIGTERM)
      }
    }
  }
}
