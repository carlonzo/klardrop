package com.carlom.klardrop.cli.commands

import com.carlom.klardrop.cli.CliController
import com.carlom.klardrop.cli.CliLogging
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.communication.untilCompleted
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.system.exitProcess

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

  override fun run() = runBlocking {
    val controller = CliController

    if (!controller.initialize(debug = debug, disableKlardrop = noKlardrop, disableNearby = noNearby)) {
      CliLogging.error("Failed to initialize Klardrop")
      controller.shutdown()
      exitProcess(EXIT_INIT_FAILURE)
    }

    // Wait for device discovery
    echo("Looking for device $deviceId...")
    delay(2000)

    val devices = controller.getVisibleDevices().first()
    val device = devices[deviceId]

    if (device == null) {
      CliLogging.error("Device $deviceId not found. Available devices:")
      devices.values.forEach {
        CliLogging.error("  ${it.deviceInfo.deviceId} - ${it.deviceInfo.name}")
      }
      controller.shutdown()
      exitProcess(EXIT_USAGE_ERROR)
    }

    echo("Found device: ${device.deviceInfo.name}")

    // Determine what to send
    // NOTE: file sending (--file / path argument) is wired at the protocol level but
    // fileSize=0L and no PlatformFile are passed here, so actual byte streaming is broken.
    // Text send is fully functional. Tracked as a known limitation.
    val messageRequest = when {
      file != null -> {
        echo("Sending file: $file")
        val fileMessage = FileMessage(
          id = Random.nextInt(),
          fileName = file!!.substringAfterLast('/'),
          fileSize = 0L, // known limitation: receiver allocates against this
          mimeType = "application/octet-stream"
        )
        fileMessage.toSimpleSendRequest()
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
          echo("Sending file: $content")
          val fileMessage = FileMessage(
            id = Random.nextInt(),
            fileName = content!!.substringAfterLast('/'),
            fileSize = 0L,
            mimeType = "application/octet-stream"
          )
          fileMessage.toSimpleSendRequest()
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
}
