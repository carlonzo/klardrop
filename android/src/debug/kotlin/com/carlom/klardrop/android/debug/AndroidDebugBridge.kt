package com.carlom.klardrop.android.debug

import com.carlom.klardrop.DiscoveryController
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.debug.DebugControl
import kotlinx.coroutines.launch

object AndroidDebugBridge {
  fun onDiscoveryControllerAvailable(controller: DiscoveryController, klardrop: Klardrop) {
    klardrop.commonComponent.coroutines().appScope.launch {
      DebugControl.bind(controller, klardrop)
    }
  }
}
