package com.carlom.klardrop.cli

import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.klardrop.common.BugsnagWrapper
import io.github.vinceglb.filekit.FileKit
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * System property key used to override the data directory for CLI processes.
 * When set, trust storage, identity, and FileKit dirs all resolve under this path
 * instead of ~/.klardrop / ~/Library/… so two CLI processes on the same host can
 * use completely separate identities and discover each other.
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
      val effectiveDataDir: String? = dataDir
        ?: System.getenv("KLARDROP_HOME")

      // Expose data dir to InternalPlatformDependencies (desktopJvm actual reads this property)
      if (effectiveDataDir != null) {
        System.setProperty(DATA_DIR_PROPERTY, effectiveDataDir)
      }

      val applicationInfo = ApplicationInfo(
        isDebug = debug,
        enableKlardropServer = !disableKlardrop,
        enableNearbyServer = !disableNearby,
      )

      // Initialize dependencies like desktop app.
      // When a custom data dir is requested, point FileKit at that directory so that
      // filesDir / databasesDir / cacheDir all resolve under the isolated path.
      BugsnagWrapper.init(applicationInfo.appVersion)
      if (effectiveDataDir != null) {
        val filesDir = File(effectiveDataDir)
        val cacheDir = File(effectiveDataDir, "cache")
        filesDir.mkdirs()
        cacheDir.mkdirs()
        FileKit.init("klardrop", filesDir, cacheDir)
      } else {
        FileKit.init("klardrop")
      }

      klardrop = Klardrop(
        applicationInfo = applicationInfo,
        internalPlatformDependency = InternalPlatformDependencies(applicationInfo)
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