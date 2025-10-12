package com.carlom.klardrop.cli

import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.klardrop.common.BugsnagWrapper
import io.github.vinceglb.filekit.FileKit
import kotlinx.coroutines.flow.StateFlow

object CliController {

  private var klardrop: Klardrop? = null

  fun initialize(debug: Boolean = false, disableKlardrop: Boolean = false, disableNearby: Boolean = false): Boolean {
    if (klardrop != null) {
      return true // Already initialized
    }

    return try {
      // Set debug logging
      CliLogging.isDebugMode = debug

      val applicationInfo = ApplicationInfo(
        isDebug = debug,
        enableKlardropServer = !disableKlardrop,
        enableNearbyServer = !disableNearby,
      )

      // Initialize dependencies like desktop app
      BugsnagWrapper.init(applicationInfo.appVersion)
      FileKit.init("klardrop")

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