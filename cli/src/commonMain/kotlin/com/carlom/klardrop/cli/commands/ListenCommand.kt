package com.carlom.klardrop.cli.commands

import com.carlom.klardrop.cli.CliController
import com.carlom.klardrop.cli.CliLogging
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlin.system.exitProcess

// Exit codes
private const val EXIT_OK = 0
private const val EXIT_INIT_FAILURE = 3

class ListenCommand : CliktCommand(
  name = "listen",
) {

  private val timeout by option(
    "--timeout",
    help = "How many seconds to listen (0 = run forever until Ctrl-C, default 600)"
  ).long().default(600L)

  private val json by option("--json", help = "Output received messages as JSONL to stdout").flag()
  private val debug by option("--debug", help = "Enable debug output").flag()
  private val noKlardrop by option("--no-klardrop", help = "Disable Klardrop TCP server").flag()
  private val noNearby by option("--no-nearby", help = "Disable Nearby Share server").flag()
  private val dataDir by option(
    "--data-dir",
    help = "Root directory for identity/trust/storage (overrides KLARDROP_HOME env). " +
      "Use distinct paths per process for same-host multi-node testing.",
    envvar = "KLARDROP_HOME",
  )

  override fun run() {
    if (!CliController.initialize(
        debug = debug,
        disableKlardrop = noKlardrop,
        disableNearby = noNearby,
        dataDir = dataDir,
      )
    ) {
      CliLogging.error("Failed to initialize Klardrop")
      exitProcess(EXIT_INIT_FAILURE)
    }

    // Shutdown hook: flush on Ctrl-C
    Runtime.getRuntime().addShutdownHook(Thread {
      CliLogging.error("[listen] shutting down")
    })

    if (!json) {
      CliLogging.info("[listen] Klardrop listener started. Waiting for incoming transfers...")
      if (timeout > 0L) {
        CliLogging.info("[listen] Will exit after ${timeout}s (--timeout=0 to run forever).")
      }
    }

    runBlocking {
      val messenger = CliController.getMessenger()

      coroutineScope {
        // Collect incoming transfers
        val collectJob = launch {
          messenger.receive().collect { (deviceId, perDeviceFlow) ->
            launch {
              perDeviceFlow
                .transformWhile { update ->
                  emit(update)
                  !update.status.isFinished()
                }
                .collect { update ->
                  handleUpdate(deviceId, update)
                }
            }
          }
        }

        // Wait for timeout or forever
        if (timeout > 0L) {
          delay(timeout * 1000L)
          collectJob.cancel()
        } else {
          // Block forever until Ctrl-C / shutdown hook triggers cancellation
          collectJob.join()
        }
      }
    }

    exitProcess(EXIT_OK)
  }

  private fun handleUpdate(deviceId: String, update: ReceiveMessageUpdate) {
    val status = update.status

    // Auto-accept: if we're pending authorization, accept immediately
    if (status is ReceiveMessageStatus.PendingAuthorization) {
      if (debug) {
        CliLogging.error("[listen] PendingAuthorization from $deviceId — auto-accepting")
      }
      status.acceptTransfer(true)
      return
    }

    // Only log in debug for non-terminal statuses
    if (debug) {
      when (status) {
        is ReceiveMessageStatus.Started -> CliLogging.error("[listen] Started transfer from $deviceId")
        is ReceiveMessageStatus.Progress -> {
          val pct = if (status.messages.isNotEmpty()) {
            status.messages.firstOrNull()?.second ?: 0
          } else 0
          CliLogging.error("[listen] Progress from $deviceId: $pct%")
        }
        is ReceiveMessageStatus.Failed -> CliLogging.error("[listen] Failed transfer from $deviceId: ${status.reason}")
        is ReceiveMessageStatus.Completed -> { /* handled below */ }
        is ReceiveMessageStatus.PendingAuthorization -> { /* handled above */ }
      }
    }

    if (status is ReceiveMessageStatus.Failed) {
      if (!debug) {
        CliLogging.error("[listen] Transfer from $deviceId failed: ${status.reason}")
      }
      return
    }

    if (status !is ReceiveMessageStatus.Completed) return

    // Emit structured log on Completed
    val nowMs = System.currentTimeMillis()
    val isoTs = formatIso8601(nowMs)
    val senderName = update.device?.name ?: "unknown"
    val senderType = update.device?.deviceType?.toString() ?: "UNKNOWN"
    val msg = update.messages.firstOrNull()

    if (json) {
      val received = when (msg) {
        is TextMessage -> baseReceived(nowMs, isoTs, deviceId, senderName, senderType, "TEXT")
          .copy(content = msg.text)
        is FileMessage -> baseReceived(nowMs, isoTs, deviceId, senderName, senderType, "FILE")
          .copy(filename = msg.fileName, size = msg.fileSize)
        else -> baseReceived(nowMs, isoTs, deviceId, senderName, senderType, "UNKNOWN")
      }
      CliLogging.info(cliJson.encodeToString(received))
    } else {
      val typeLine = when (msg) {
        is TextMessage -> "type=TEXT content=${escapeText(msg.text)}"
        is FileMessage -> "type=FILE filename=${msg.fileName} size=${msg.fileSize}"
        null -> "type=UNKNOWN"
        else -> "type=UNKNOWN"
      }
      CliLogging.info("[$isoTs] RECEIVED sender=$deviceId name=$senderName $typeLine")
    }
  }

  private fun baseReceived(
    timestampMs: Long,
    timestampIso: String,
    senderDeviceId: String,
    senderName: String,
    senderType: String,
    type: String,
  ) = ReceivedJson(
    timestamp_ms = timestampMs,
    timestamp = timestampIso,
    event = "received",
    sender_id = senderDeviceId,
    sender_name = senderName,
    sender_type = senderType,
    type = type,
  )

  private fun escapeText(s: String): String = s.replace(" ", "_").take(120)

  // Instant.toString() is ISO-8601 UTC with trailing Z, e.g. 2026-06-14T12:00:00.123Z (JVM-only CLI).
  private fun formatIso8601(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs).toString()
}
