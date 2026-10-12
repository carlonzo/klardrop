// TODO(linux-native): permissions monitor stub (Linux has no app-level local network permissions)
package com.carlom.klardrop.common.permissions

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

actual class PermissionsMonitor {
  actual fun observe(): Flow<PermissionsState> = flowOf(PermissionsState.EMPTY)
  actual fun refresh() = Unit
}
