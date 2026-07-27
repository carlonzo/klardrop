package com.carlom.klardrop.cli.commands

import com.carlom.klardrop.cli.CliController
import com.carlom.klardrop.cli.CliLogging
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSendRequest
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.communication.untilCompleted
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random
import kotlin.system.exitProcess
import java.io.File as JvmFile

// Exit codes:
//   0 = delivery confirmed (ACK_RECEIVED)
//   1 = send failure / recipient declined / timeout
//   2 = usage error (device not found, no content)
//   3 = init failure
private const val EXIT_OK = 0
private const val EXIT_SEND_FAILURE = 1
private const val EXIT_USAGE_ERROR = 2
private const val EXIT_INIT_FAILURE = 3

class SendCommand : CliktCommand(
  name = "send",
) {

  private val deviceId by argument("DEVICE_ID", help = "Target device ID")
  private val content by argument("CONTENT", help = "Text message or file path to send").optional()

  private val file by option("--file", "-f", help = "Send a file (path)")
  private val text by option("--text", "-t", help = "Send text message")
  private val debug by option("--debug", help = "Enable debug output").flag()
  private val noKlardrop by option("--no-klardrop", help = "Disable Klardrop TCP server").flag()
  private val noNearby by option("--no-nearby", help = "Disable Nearby Share server").flag()
  private val dataDir by option(
    "--data-dir",
    help = "Root directory for identity/trust/storage (overrides KLARDROP_HOME env). " +
      "Use distinct paths per process for same-host multi-node testing.",
    envvar = "KLARDROP_HOME",
  )
  private val settleTimeout by option(
    "--settle-timeout",
    help = "Seconds to wait for mDNS discovery to settle before giving up (default: 10)",
  )

  override fun run() = runBlocking {
    val controller = CliController

    if (!controller.initialize(
        debug = debug,
        disableKlardrop = noKlardrop,
        disableNearby = noNearby,
        dataDir = dataDir,
      )
    ) {
      CliLogging.error("Failed to initialize Klardrop")
      controller.shutdown()
      exitProcess(EXIT_INIT_FAILURE)
    }

    // Await target device with a bounded settle window so a freshly-started `send`
    // doesn't fail immediately while mDNS is still resolving.
    echo("Looking for device $deviceId...")
    val settleMs = (settleTimeout?.toLongOrNull() ?: 10L) * 1_000L
    val device: DiscoveryDevice? = awaitDevice(controller.getVisibleDevices(), deviceId, settleMs)

    if (device == null) {
      CliLogging.error("Device $deviceId not found. Available devices:")
      controller.getVisibleDevices().first().values.forEach {
        CliLogging.error("  ${it.deviceInfo.deviceId} - ${it.deviceInfo.name}")
      }
      controller.shutdown()
      exitProcess(EXIT_USAGE_ERROR)
    }

    echo("Found device: ${device.deviceInfo.name}")

    // Determine what to send
    val messageRequest = when {
      file != null -> {
        buildFileRequest(file!!, controller)
      }

      text != null -> {
        echo("Sending text: $text")
        val textMessage = TextMessage(
          id = Random.nextInt(),
          text = text!!
        )
        textMessage.toSimpleSendRequest()
      }

      content != null -> {
        // Try to determine if content is a file path or text
        if (content!!.contains('/') || content!!.contains('\\')) {
          buildFileRequest(content!!, controller)
        } else {
          echo("Sending text: $content")
          val textMessage = TextMessage(
            id = Random.nextInt(),
            text = content!!
          )
          textMessage.toSimpleSendRequest()
        }
      }

      else -> {
        CliLogging.error("No content specified. Use --file, --text, or provide content as argument")
        controller.shutdown()
        exitProcess(EXIT_USAGE_ERROR)
      }
    }

    // Send the message and capture the terminal result
    val messenger = controller.getMessenger()
    var terminal: MessengerSendProgress? = null

    messenger.send(deviceId, messageRequest).untilCompleted().collect { progress ->
      when (progress) {
        is MessengerSendProgress.Pending -> echo("Preparing to send...")
        is MessengerSendProgress.AwaitingRecipient -> echo("Waiting for the recipient to accept...")
        is MessengerSendProgress.InProgress -> echo("Progress: ${progress.percentage}%")
        is MessengerSendProgress.Completed -> {
          echo("Successfully sent!")
          terminal = progress
        }
        is MessengerSendProgress.Error -> {
          CliLogging.error("Send error: ${progress.message}")
          terminal = progress
        }
      }
    }

    controller.shutdown()

    when (terminal) {
      is MessengerSendProgress.Completed -> exitProcess(EXIT_OK)
      is MessengerSendProgress.Error -> exitProcess(EXIT_SEND_FAILURE)
      else -> exitProcess(EXIT_SEND_FAILURE) // flow drained with no terminal (shouldn't happen)
    }
  }

  /**
   * Poll [devicesFlow] for [targetId] up to [timeoutMs] milliseconds.
   * Returns the [DiscoveryDevice] as soon as it appears, or null if the window expires.
   * Checks every 500 ms so the UI stays responsive while mDNS resolves.
   */
  private suspend fun awaitDevice(
    devicesFlow: StateFlow<Map<String, DiscoveryDevice>>,
    targetId: String,
    timeoutMs: Long,
  ): DiscoveryDevice? = withTimeoutOrNull(timeoutMs) {
    var elapsed = 0L
    val pollMs = 500L
    while (true) {
      val device = devicesFlow.value[targetId]
      if (device != null) return@withTimeoutOrNull device
      if (elapsed > 0 && elapsed % 2_000L == 0L) {
        echo("  still waiting for $targetId (${elapsed / 1_000}s)...")
      }
      delay(pollMs)
      elapsed += pollMs
    }
    @Suppress("UNREACHABLE_CODE")
    null
  }

  private fun buildFileRequest(
    filePath: String,
    controller: CliController,
  ): FileMessage.FileSendRequest {
    val path = Path(filePath)
    val meta = SystemFileSystem.metadataOrNull(path)
    if (meta == null || !meta.isRegularFile) {
      CliLogging.error("File not found or not a regular file: $filePath")
      controller.shutdown()
      exitProcess(EXIT_USAGE_ERROR)
    }
    val fileSize = meta.size
    val fileName = path.name
    echo("Sending file: $filePath ($fileSize bytes)")
    val fileMessage = FileMessage(
      id = Random.nextInt(),
      fileName = fileName,
      fileSize = fileSize,
      mimeType = "application/octet-stream",
    )
    return fileMessage.toSendRequest(PlatformFile(JvmFile(filePath)))
  }
}
