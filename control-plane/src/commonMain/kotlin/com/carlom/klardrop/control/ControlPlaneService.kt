package com.carlom.klardrop.control

import com.carlom.klardrop.DiscoveryController
import com.carlom.klardrop.common.Klardrop

/**
 * Host-facing surface of the loopback HTTP control plane.
 *
 * Implemented by [ControlPlane] in the `:control-plane` module, which ships in every build
 * (not only debug ones): local clients such as `klardrop`, the native CLI/TUI and the Qt /
 * Omarchy frontends drive Klardrop through it instead of tapping the UI.
 */
interface ControlPlaneService {
  val boundPort: Int get() = 0
  val token: String? get() = null

  /** Debug builds only; the `/window` routes answer 403 otherwise. */
  var windowVisibilityProvider: (() -> Boolean)?
  var windowVisibilitySetter: ((Boolean) -> Unit)?

  suspend fun start(app: Klardrop)
  suspend fun bind(discoveryController: DiscoveryController, app: Klardrop)
  fun stop()
}