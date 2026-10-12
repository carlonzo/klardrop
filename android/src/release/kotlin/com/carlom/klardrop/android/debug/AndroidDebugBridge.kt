package com.carlom.klardrop.android.debug

import com.carlom.klardrop.DiscoveryController
import com.carlom.klardrop.common.Klardrop

object AndroidDebugBridge {
  fun onDiscoveryControllerAvailable(controller: DiscoveryController, klardrop: Klardrop) {
    // No-op in release builds: the control plane is an Android debug-only tool there (adb
    // forward reaches the app's loopback port directly), so :control-plane is not shipped.
  }
}