package com.carlom.klardrop.cli.commands

import com.carlom.klardrop.cli.CliController
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class StatusCommand : CliktCommand(
  name = "status",
  help = "Show Klardrop service status and visible devices"
) {

  private val debug by option("--debug", help = "Enable debug output").flag()

  override fun run() = runBlocking {
    val controller = CliController

    if (!controller.initialize(debug = debug)) {
      echo("Failed to initialize Klardrop", err = true)
      return@runBlocking
    }

    echo("Klardrop CLI Status")
    echo("═".repeat(50))

    // Service status
    echo("Service:     Running ✓")
    echo("Debug mode:  ${if (debug) "Enabled" else "Disabled"}")

    // Show visible devices
    val devices = controller.getVisibleDevices().first()
    echo("Devices:     ${devices.size} visible")

    if (devices.isNotEmpty()) {
      echo("\nVisible Devices:")
      echo("─".repeat(50))

      devices.values.forEach { device ->
        val deviceInfo = device.deviceInfo
        echo("ID:   ${deviceInfo.deviceId}")
        echo("Name: ${deviceInfo.name}")
        echo("Type: ${deviceInfo.deviceType}")

        // Show connections
        echo("Connections:")
        device.deviceConnections.forEach { connection ->
          echo("  • ${connection.deviceConnectionType} - ${connection.address}:${connection.port}")
        }
        echo("─".repeat(50))
      }
    } else {
      echo("\nNo devices currently visible.")
      echo("Run 'klardrop discover' to search for devices.")
    }

    controller.shutdown()
  }
}