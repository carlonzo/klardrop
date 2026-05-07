package com.carlom.klardrop.common.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop JVM has no system notification surface wired up, so foreground
 * tracking would only be useful for gating notifications that never fire.
 * Hard-code [isForeground] = true to make the gate a no-op until we add a
 * real notifier.
 */
actual class ForegroundState {
  actual val isForeground: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
}
