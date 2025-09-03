package com.carlom.klardrop.cli.commands

import com.carlom.klardrop.cli.CliController
import com.carlom.klardrop.cli.CliLogging
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class DiscoverCommand : CliktCommand(
  name = "discover",
  help = "Discover nearby devices available for file sharing"
) {

  private val debug by option("--debug", help = "Enable debug output").flag()
  private val timeout by option("--timeout", help = "Discovery timeout in seconds (default: 5)")

  override fun run() = runBlocking {
    val controller = CliController

    if (!controller.initialize(debug = debug)) {
      echo("Failed to initialize Klardrop", err = true)
      return@runBlocking
    }

    echo("Discovering nearby devices...")
    if (debug) {
      CliLogging.info("Debug mode enabled - showing detailed logs")
    } else {
      echo("Press Ctrl+C to stop discovery")
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

              CliLogging.info("\n🔍 Discovered device:")
              CliLogging.info("   Device ID: ${device.deviceInfo.deviceId}")
              CliLogging.info("   Name:      ${device.deviceInfo.name}")
              CliLogging.info("   Type:      ${device.deviceInfo.deviceType}")

              device.deviceConnections.forEach { connection ->
                CliLogging.info("   Connection: ${connection.deviceConnectionType} at ${connection.address}:${connection.port}")
              }
              CliLogging.info("   " + "─".repeat(50))
            }
          }
        }
      }

      // Wait for timeout
      var elapsed = 0L
      while (elapsed < timeoutMs) {
        if (!debug) {
          echo(".", trailingNewline = false)
        }
        delay(1000)
        elapsed = System.currentTimeMillis() - startTime
      }

      discoveryJob.cancel()
    }

    val finalDevices = controller.getVisibleDevices().first()
    if (finalDevices.isEmpty()) {
      echo("\n❌ No devices discovered")
    } else {
      echo("\n✅ Discovery complete - found ${finalDevices.size} device(s)")
    }

    controller.shutdown()
  }
}