// TODO(linux-native): foreground state stub for headless daemon
package com.carlom.klardrop.common.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual class ForegroundState {
  actual val isForeground: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
}
