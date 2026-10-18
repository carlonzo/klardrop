// TODO(linux-native): network connectivity restrictions stub (unrestricted)
package com.carlom.klardrop.common.connectivity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

public actual class ConnectivityRestrictionMonitor(
  initial: ConnectivityRestrictions = ConnectivityRestrictions.EMPTY,
) {
  private val state = MutableStateFlow(initial)

  public actual fun observe(): Flow<ConnectivityRestrictions> = state.asStateFlow()

  public actual fun refresh(): Unit = Unit
}
