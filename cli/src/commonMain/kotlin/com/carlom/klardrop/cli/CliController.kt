package com.carlom.klardrop.cli

import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.klardrop.common.initCrashReporter
import kotlinx.coroutines.flow.StateFlow

/**
 * System property key used to override the data directory for CLI processes.
 * When set, trust storage, identity, and FileKit dirs all resolve under this path
 * instead of ~/.klardrop / ~/Library/… so two CLI processes on the same host can
 * use completely separate identities and discover each other.
 *
 * On native targets this is surfaced via the KLARDROP_HOME env var directly.
 */
const val DATA_DIR_PROPERTY = "klardrop.data.dir"

object CliController {

  private var klardrop: Klardrop? = null

  /**
   * @param dataDir When non-null (or when env KLARDROP_HOME is set), all per-process
   *   storage (identity, trust, databases, preferences) is rooted here instead of the
   *   default platform directories. This allows two CLI instances on the same host to
   *   get distinct device IDs and therefore discover each other via mDNS.
   */
  fun initialize(
    debug: Boolean = false,
    disableKlardrop: Boolean = false,
    disableNearby: Boolean = false,
    dataDir: String? = null,
  ): Boolean {
    if (klardrop != null) {
      return true // Already initialized
    }

    return try {
      // Set debug logging
      CliLogging.isDebugMode = debug

      // Resolve effective data dir: explicit arg > KLARDROP_HOME env > default (null = platform default)
      val effectiveDataDir: String? = dataDir ?: cliGetEnv("KLARDROP_HOME")

      // Expose data dir via platform mechanism so InternalPlatformDependencies can pick it up
      if (effectiveDataDir != null) {
        cliSetDataDir(effectiveDataDir)
      }

      val applicationInfo = ApplicationInfo(
        isDebug = debug,
        enableKlardropServer = !disableKlardrop,
        enableNearbyServer = !disableNearby,
      )

      // Initialize dependencies like desktop app.
      // When a custom data dir is requested, point FileKit at that directory so that
      // filesDir / databasesDir / cacheDir all resolve under the isolated path.
      initCrashReporter(
        appVersion = applicationInfo.appVersion,
        isProduction = !applicationInfo.isDebug,
      )
      cliInitFileKit(effectiveDataDir)

      klardrop = Klardrop(
        applicationInfo = applicationInfo,
        internalPlatformDependency = cliCreateInternalPlatformDependencies(applicationInfo)
      )
      klardrop!!.init()
      true
    } catch (e: Exception) {
      println("Failed to initialize Klardrop: ${e.message}")
      false
    }
  }

  fun getVisibleDevices(): StateFlow<Map<String, DiscoveryDevice>> {
    return requireKlardrop().visibleDevices().visibleDevices
  }

  fun getMessenger(): Messenger {
    return requireKlardrop().commonComponent.messenger()
  }

  private fun requireKlardrop(): Klardrop {
    return klardrop ?: throw IllegalStateException("Klardrop not initialized. Call initialize() first.")
  }

  fun shutdown() {
    // TODO: Add proper shutdown logic if needed
    klardrop = null
  }
}

/** Read an environment variable; returns null if not set or empty. */
internal expect fun cliGetEnv(name: String): String?

/** Expose the data dir to the platform so InternalPlatformDependencies can read it. */
internal expect fun cliSetDataDir(dir: String)

/** Initialise FileKit (and create dirs) for the given data dir. Null → platform default. */
internal expect fun cliInitFileKit(dataDir: String?)

/**
 * Builds the engine's platform dependencies.
 *
 * A shared source set cannot call `InternalPlatformDependencies(applicationInfo)` directly:
 * the common `expect class` declares no constructor, because Android's actual needs a
 * `Context` and no single signature serves both (see the note on `presentation`'s
 * per-platform `KlardropBootstrap`). The one-argument constructor exists on the per-target
 * actuals, so constructing it has to happen per platform as well. Both of the CLI's targets
 * — the JVM host and the native engine — take that one-argument form.
 */
internal expect fun cliCreateInternalPlatformDependencies(
  applicationInfo: ApplicationInfo,
): InternalPlatformDependencies