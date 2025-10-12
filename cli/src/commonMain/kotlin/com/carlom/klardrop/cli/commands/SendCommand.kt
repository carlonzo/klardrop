package com.carlom.klardrop.cli.commands

import com.carlom.klardrop.cli.CliController
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

class SendCommand : CliktCommand(
  name = "send",
) {

  private val deviceId by argument("DEVICE_ID", help = "Target device ID")
  private val content by argument("CONTENT", help = "Text message or file path to send").optional()

  private val file by option("--file", "-f", help = "Send a file (path)")
  private val text by option("--text", "-t", help = "Send text message")
  private val debug by option("--debug", help = "Enable debug output").flag()

  override fun run() = runBlocking {
    val controller = CliController

    if (!controller.initialize(debug = debug)) {
      echo("Failed to initialize Klardrop", err = true)
      return@runBlocking
    }

    // Wait for device discovery
    echo("Looking for device $deviceId...")
    delay(2000)

    val devices = controller.getVisibleDevices().first()
    val device = devices[deviceId]

    if (device == null) {
      echo("Device $deviceId not found. Available devices:")
      devices.values.forEach {
        echo("  ${it.deviceInfo.deviceId} - ${it.deviceInfo.name}")
      }
      controller.shutdown()
      return@runBlocking
    }

    echo("Found device: ${device.deviceInfo.name}")

    // Determine what to send
    val messageRequest = when {
      file != null -> {
        echo("Sending file: $file")
        val fileMessage = FileMessage(
          id = Random.nextInt(),
          fileName = file!!.substringAfterLast('/'),
          fileSize = 0L, // TODO: Get actual file size
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
        echo("No content specified. Use --file, --text, or provide content as argument", err = true)
        controller.shutdown()
        return@runBlocking
      }
    }

    // Send the message
    val messenger = controller.getMessenger()
    messenger.send(deviceId, messageRequest).untilCompleted().collect { progress ->
      when (progress) {
        is MessengerSendProgress.Pending -> echo("Preparing to send...")
        is MessengerSendProgress.InProgress -> echo("Progress: ${progress.percentage}%")
        is MessengerSendProgress.Completed -> echo("✓ Successfully sent!")
        is MessengerSendProgress.Error -> echo("✗ Error: ${progress.message}", err = true)
      }
    }

    controller.shutdown()
  }
}