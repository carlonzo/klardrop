package com.carlom.klardrop.common.connectivity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Apple platforms have no battery-saver/metered-deny machinery — never restricted. */
public actual class ConnectivityRestrictionMonitor {
  public actual fun observe(): Flow<ConnectivityRestrictions> = flowOf(ConnectivityRestrictions.EMPTY)

  public actual fun refresh(): Unit = Unit
}
