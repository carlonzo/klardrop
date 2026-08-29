package com.carlom.klardrop.common.connectivity

import kotlinx.coroutines.flow.Flow

/**
 * Per-platform view of the OS-level connectivity restrictions that silently drop
 * Klardrop's packets — the 2026-08-28 diagnosis (draft D11 + T1 finding) showed
 * Battery Saver's netd powersave chain and the user's per-app metered deny both
 * blackhole TCP while discovery (mDNS) keeps working, so the app looks fine and
 * every pairing just times out.
 *
 * Mirrors [com.carlom.klardrop.common.permissions.PermissionsMonitor]: the monitor
 * stays passive and reports state; acting on it (the OS exemption dialog, the
 * network-settings deep link) is a platform concern the UI wires up as a callback.
 *
 * Platforms with no such restriction story (desktop, Apple) report [ConnectivityRestrictions.EMPTY].
 */
expect class ConnectivityRestrictionMonitor {
  fun observe(): Flow<ConnectivityRestrictions>

  /**
   * Force a re-read of the current restriction state. The battery-optimization
   * exemption dialog (and the network-settings page) produce no event our
   * receivers hear on every OS version, so the platform app calls this right
   * after such a prompt returns — same contract as PermissionsMonitor.refresh().
   */
  fun refresh()
}

/** The restrictions that can independently block Klardrop's connectivity. */
enum class ConnectivityRestriction {
  /** Power Save mode is ON and this app is not exempt — netd's powersave chain default-denies our uid. */
  BatterySaverBlocking,

  /** This app is not on the battery-optimization whitelist — the next Power Save cycle will block it. */
  BatteryOptimizationNotExempt,

  /** Active network is metered AND the user denied this app on metered nets (metered_deny_user chain). */
  MeteredNetworkDenied,
}

data class ConnectivityRestrictions(
  val batterySaverBlocking: Boolean = false,
  val batteryOptimizationNotExempt: Boolean = false,
  val meteredNetworkDenied: Boolean = false,
) {
  val restricted: Boolean
    get() = batterySaverBlocking || batteryOptimizationNotExempt || meteredNetworkDenied

  val reasons: Set<ConnectivityRestriction>
    get() = buildSet {
      if (batterySaverBlocking) add(ConnectivityRestriction.BatterySaverBlocking)
      if (batteryOptimizationNotExempt) add(ConnectivityRestriction.BatteryOptimizationNotExempt)
      if (meteredNetworkDenied) add(ConnectivityRestriction.MeteredNetworkDenied)
    }

  /**
   * Human-readable notice for the restriction currently blocking connections —
   * used to prefix pairing-failure reasons so "connect failed" stops reading as
   * a peer bug when it's this device's OS doing the blocking. Only ACTIVE
   * blockers qualify: not being exempt yet never blocked anything.
   */
  fun activeBlockerNotice(): String? = when {
    batterySaverBlocking -> "Battery saver is blocking Klardrop"
    meteredNetworkDenied -> "Klardrop is blocked on metered networks"
    else -> null
  }

  companion object {
    val EMPTY = ConnectivityRestrictions()

    /**
     * Pure derivation from raw platform state so the mapping is unit-testable
     * without an OS (android/src/test). [userDeniedOnMetered] is the per-app
     * "denied on metered networks" signal; metered alone is not enough (T1 conjunct).
     */
    fun derive(
      powerSaveMode: Boolean,
      batteryOptimizationExempt: Boolean,
      activeNetworkMetered: Boolean,
      userDeniedOnMetered: Boolean,
    ): ConnectivityRestrictions = ConnectivityRestrictions(
      batterySaverBlocking = powerSaveMode && !batteryOptimizationExempt,
      batteryOptimizationNotExempt = !batteryOptimizationExempt,
      meteredNetworkDenied = activeNetworkMetered && userDeniedOnMetered,
    )
  }
}
