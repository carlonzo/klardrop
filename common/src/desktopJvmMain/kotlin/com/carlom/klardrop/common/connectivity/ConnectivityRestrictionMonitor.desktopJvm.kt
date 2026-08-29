package com.carlom.klardrop.common.connectivity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop JVM has no battery-saver/metered-deny machinery that drops Klardrop
 * traffic, so the monitor never reports restricted. [initial] exists so JVM-side
 * tests can drive the restricted state through DiscoveryController.
 */
actual class ConnectivityRestrictionMonitor(
  initial: ConnectivityRestrictions = ConnectivityRestrictions.EMPTY,
) {

  private val state = MutableStateFlow(initial)

  actual fun observe(): Flow<ConnectivityRestrictions> = state.asStateFlow()

  actual fun refresh() = Unit
}
