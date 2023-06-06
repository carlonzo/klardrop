package com.carlom.klardrop

import androidx.compose.ui.window.ComposeUIViewController
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop

class DiscoveryBridge(){

  val klardrop = Klardrop(internalPlatformDependency = InternalPlatformDependencies())

  init {
    klardrop.init()
  }

  fun DiscoveryDashboardController() = ComposeUIViewController {
    val showVisibleDevicesController = ShowVisibleDevicesController(klardrop.commonComponent)
    DiscoveryDashboard(showVisibleDevicesController = showVisibleDevicesController)
  }
}

