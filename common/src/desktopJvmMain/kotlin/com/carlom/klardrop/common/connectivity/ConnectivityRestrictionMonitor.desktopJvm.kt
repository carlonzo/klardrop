package com.carlom.klardrop.common.connectivity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop JVM has no battery-saver/metered-deny machinery that drops Klardrop
 * traffic, so the monitor never reports restricted. [initial] exists so JVM-side
 * tests can drive the restricted state through DiscoveryController.
 */
public actual class ConnectivityRestrictionMonitor(
  initial: ConnectivityRestrictions = ConnectivityRestrictions.EMPTY,
) {

  private val state = MutableStateFlow(initial)

  public actual fun observe(): Flow<ConnectivityRestrictions> = state.asStateFlow()

  public actual fun refresh(): Unit = Unit
}
