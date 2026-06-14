package com.carlom.klardrop.cli.commands

import com.carlom.klardrop.cli.CliController
import com.carlom.klardrop.cli.CliLogging
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlin.system.exitProcess

private const val EXIT_OK = 0
private const val EXIT_INIT_FAILURE = 3

class StatusCommand : CliktCommand(
  name = "status",
) {

  private val debug by option("--debug", help = "Enable debug output").flag()
  private val json by option("--json", help = "Output result as JSON to stdout").flag()
  private val dataDir by option(
    "--data-dir",
    help = "Root directory for identity/trust/storage (overrides KLARDROP_HOME env). " +
      "Use distinct paths per process for same-host multi-node testing.",
    envvar = "KLARDROP_HOME",
  )

  override fun run() = runBlocking {
    val controller = CliController

    if (!controller.initialize(debug = debug, dataDir = dataDir)) {
      CliLogging.error("Failed to initialize Klardrop")
      exitProcess(EXIT_INIT_FAILURE)
    }

    val devices = controller.getVisibleDevices().first()

    if (json) {
      val status = StatusJson(
        running = true,
        debug = debug,
        device_count = devices.size,
        devices = devices.values.map { it.toJson() },
      )
      CliLogging.info(cliJson.encodeToString(status))
    } else {
      echo("Klardrop CLI Status")
      echo("=".repeat(50))

      echo("Service:     Running")
      echo("Debug mode:  ${if (debug) "Enabled" else "Disabled"}")
      echo("Devices:     ${devices.size} visible")

      if (devices.isNotEmpty()) {
        echo("\nVisible Devices:")
        echo("-".repeat(50))

        devices.values.forEach { device ->
          val deviceInfo = device.deviceInfo
          echo("ID:   ${deviceInfo.deviceId}")
          echo("Name: ${deviceInfo.name}")
          echo("Type: ${deviceInfo.deviceType}")

          echo("Connections:")
          device.deviceConnections.forEach { connection ->
            echo("  - ${connection.deviceConnectionType} - ${connection.address}:${connection.port}")
          }
          echo("-".repeat(50))
        }
      } else {
        echo("\nNo devices currently visible.")
        echo("Run 'klardrop discover' to search for devices.")
      }
    }

    controller.shutdown()
    exitProcess(EXIT_OK)
  }
}
