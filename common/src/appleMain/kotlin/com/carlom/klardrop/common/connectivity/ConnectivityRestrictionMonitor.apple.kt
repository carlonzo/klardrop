package com.carlom.klardrop.common.connectivity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Apple platforms have no battery-saver/metered-deny machinery — never restricted. */
actual class ConnectivityRestrictionMonitor {
  actual fun observe(): Flow<ConnectivityRestrictions> = flowOf(ConnectivityRestrictions.EMPTY)

  actual fun refresh() = Unit
}
