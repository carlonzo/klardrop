package com.carlom.klardrop.cli.commands

import com.carlom.klardrop.DiscoveryController
import com.carlom.klardrop.cli.CliLogging
import com.carlom.klardrop.cli.cliGetEnv
import com.carlom.klardrop.cli.cliInitFileKit
import com.carlom.klardrop.cli.awaitTerminationRequest
import com.carlom.klardrop.cli.cliSetDataDir
import com.carlom.klardrop.cli.installTerminationSignalHandler
import com.carlom.klardrop.cli.onCleanupComplete
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.control.ControlPlane
import com.carlom.klardrop.control.ControlPortInUseException
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.klardrop.common.initCrashReporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

// Exit codes
private const val EXIT_FAILURE = 1
private const val EXIT_CONTROL_PORT_IN_USE = 3

class DaemonCommand : CliktCommand(
  name = "daemon",
) {
  private val port by option(
    "--port", "-p",
    help = "Control API port (default: 0 for ephemeral)",
  ).int().default(0)

  private val debug by option("--debug", help = "Enable debug output").flag()
  private val noKlardrop by option("--no-klardrop", help = "Disable Klardrop TCP server").flag()
  private val noNearby by option("--no-nearby", help = "Disable Nearby Share server").flag()
  private val noBle by option("--no-ble", help = "Disable BLE transport").flag()
  private val dataDir by option(
    "--data-dir",
    help = "Root directory for identity/trust/storage (overrides KLARDROP_HOME env).",
    envvar = "KLARDROP_HOME",
  )

  override fun run() = runBlocking {
    // On JVM, installTerminationSignalHandler() arms a shutdown hook that blocks on the
    // cleanup latch, so ANY exit path from here on — a caught error or exitProcess() below —
    // must release it or the JVM hangs forever. exitProcess()/System.exit() does not unwind
    // the stack, so a `finally` placed around it would never run; exitCode is computed here and
    // exitProcess() is only called once the try/finally below has fully completed instead.
    // installTerminationSignalHandler() itself is inside the try so a failure there (e.g. the
    // native self-pipe couldn't be created) gets the same "daemon failed: ..." + exit 1 handling
    // as everything else, rather than escaping run() uncaught.
    var exitCode = 0
    try {
      // Arms the graceful-shutdown trigger before anything else runs.
      installTerminationSignalHandler()

      CliLogging.isDebugMode = debug

      val effectiveDataDir: String? = dataDir ?: cliGetEnv("KLARDROP_HOME")
      if (effectiveDataDir != null) {
        cliSetDataDir(effectiveDataDir)
      }
      cliInitFileKit(effectiveDataDir)

      val applicationInfo = ApplicationInfo(
        isDebug = debug,
        enableKlardropServer = !noKlardrop,
        enableNearbyServer = !noNearby,
        enableBle = !noBle,
        controlPort = port,
      )

      initCrashReporter(
        appVersion = applicationInfo.appVersion,
        isProduction = !applicationInfo.isDebug,
      )

      val klardrop = Klardrop(
        applicationInfo = applicationInfo,
        internalPlatformDependency = InternalPlatformDependencies(applicationInfo),
      )
      klardrop.init()

      val discoveryController = DiscoveryController(klardrop.commonComponent)
      var portInUse: ControlPortInUseException? = null
      try {
        ControlPlane.bind(discoveryController, klardrop)
      } catch (e: ControlPortInUseException) {
        portInUse = e
      }

      if (portInUse != null) {
        CliLogging.error("control port ${portInUse.port} already in use")
        runCatching { klardrop.commonComponent.server().stopServer() }
        exitCode = EXIT_CONTROL_PORT_IN_USE
      } else {
        val boundPort = ControlPlane.boundPort
        echo("Klardrop daemon running on port $boundPort (token auth enabled)")

        awaitTerminationRequest()
        ControlPlane.stop()
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      CliLogging.error("daemon failed: ${e.message ?: e::class.simpleName ?: "unknown error"}")
      exitCode = EXIT_FAILURE
    } finally {
      onCleanupComplete()
    }

    if (exitCode != 0) {
      exitProcess(exitCode)
    }
  }
}
