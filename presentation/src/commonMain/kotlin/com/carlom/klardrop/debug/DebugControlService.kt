package com.carlom.klardrop.debug

import com.carlom.klardrop.DiscoveryController
import com.carlom.klardrop.common.Klardrop

/**
 * Common interface for the loopback HTTP control plane used in autonomous device testing.
 * Implemented by `DebugControl` in the `:debug-control` module, which is only shipped
 * for debug builds and excluded from production releases.
 */
interface DebugControlService {
  var windowVisibilityProvider: (() -> Boolean)?
  var windowVisibilitySetter: ((Boolean) -> Unit)?

  suspend fun start(app: Klardrop)
  suspend fun bind(discoveryController: DiscoveryController, app: Klardrop)
  fun stop()
}
