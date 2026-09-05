package com.carlom.klardrop.android.debug

import com.carlom.klardrop.DiscoveryController
import com.carlom.klardrop.common.Klardrop

object AndroidDebugBridge {
  fun onDiscoveryControllerAvailable(controller: DiscoveryController, klardrop: Klardrop) {
    // No-op in release builds. DebugControl is excluded and not shipped.
  }
}
