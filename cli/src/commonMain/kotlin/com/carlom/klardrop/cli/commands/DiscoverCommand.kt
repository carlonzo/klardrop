package com.carlom.klardrop.cli.commands

import com.carlom.klardrop.cli.CliController
import com.carlom.klardrop.cli.CliLogging
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

private const val EXIT_OK = 0
private const val EXIT_INIT_FAILURE = 3

class DiscoverCommand : CliktCommand(
  name = "discover",
) {

  private val debug by option("--debug", help = "Enable debug output").flag()
  private val json by option("--json", help = "Output result as JSON to stdout").flag()
  private val timeout by option("--timeout", help = "Discovery timeout in seconds (default: 5)")
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

    if (!json) {
      echo("Discovering nearby devices...")
      if (debug) {
        CliLogging.info("Debug mode enabled - showing detailed logs")
      } else {
        echo("Press Ctrl+C to stop discovery")
      }
    }

    val timeoutMs = (timeout?.toLongOrNull() ?: 5L) * 1000
    val startTime = System.currentTimeMillis()
    val seenDevices = mutableSetOf<String>()

    coroutineScope {
      // Launch a coroutine to monitor device changes
      val discoveryJob = launch {
        controller.getVisibleDevices().collectLatest { devices ->
          devices.values.forEach { device ->
            val deviceId = device.deviceInfo.deviceId
            if (!seenDevices.contains(deviceId)) {
              seenDevices.add(deviceId)

              if (!json) {
                CliLogging.info("\nDiscovered device:")
                CliLogging.info("   Device ID: ${device.deviceInfo.deviceId}")
                CliLogging.info("   Name:      ${device.deviceInfo.name}")
                CliLogging.info("   Type:      ${device.deviceInfo.deviceType}")

                device.deviceConnections.forEach { connection ->
                  CliLogging.info("   Connection: ${connection.deviceConnectionType} at ${connection.address}:${connection.port}")
                }
                CliLogging.info("   " + "-".repeat(50))
              }
            }
          }
        }
      }

      // Wait for timeout
      var elapsed = 0L
      while (elapsed < timeoutMs) {
        if (!debug && !json) {
          echo(".", trailingNewline = false)
        }
        delay(1000)
        elapsed = System.currentTimeMillis() - startTime
      }

      discoveryJob.cancel()
    }

    val finalDevices = controller.getVisibleDevices().first()

    if (json) {
      CliLogging.info(devicesToJson(finalDevices))
    } else {
      if (finalDevices.isEmpty()) {
        echo("\nNo devices discovered")
      } else {
        echo("\nDiscovery complete - found ${finalDevices.size} device(s)")
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
