package com.carlom.klardrop.cli.commands

import com.carlom.klardrop.cli.CliController
import com.carlom.klardrop.cli.CliLogging
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

private const val EXIT_OK = 0
private const val EXIT_INIT_FAILURE = 3

class StatusCommand : CliktCommand(
  name = "status",
) {

  private val debug by option("--debug", help = "Enable debug output").flag()
  private val json by option("--json", help = "Output result as JSON to stdout").flag()

  override fun run() = runBlocking {
    val controller = CliController

    if (!controller.initialize(debug = debug)) {
      CliLogging.error("Failed to initialize Klardrop")
      exitProcess(EXIT_INIT_FAILURE)
    }

    val devices = controller.getVisibleDevices().first()

    if (json) {
      val deviceJson = devicesToJson(devices)
      val statusObj = "{\"running\":true,\"debug\":$debug,\"device_count\":${devices.size},\"devices\":$deviceJson}"
      CliLogging.info(statusObj)
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

  private fun devicesToJson(devices: Map<String, DiscoveryDevice>): String {
    val items = devices.values.joinToString(",") { device ->
      val info = device.deviceInfo
      val connections = device.deviceConnections.joinToString(",") { conn ->
        "{\"type\":${jsonString(conn.deviceConnectionType.name)},\"address\":${jsonString(conn.address)},\"port\":${conn.port}}"
      }
      "{\"device_id\":${jsonString(info.deviceId)},\"name\":${jsonString(info.name)}," +
        "\"device_type\":${jsonString(info.deviceType.name)},\"os_type\":${jsonString(info.osType.name)}," +
        "\"connections\":[$connections]}"
    }
    return "[$items]"
  }

  private fun jsonString(s: String): String {
    val escaped = s
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
    return "\"$escaped\""
  }
}
