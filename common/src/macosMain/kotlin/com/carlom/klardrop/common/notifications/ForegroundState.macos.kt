package com.carlom.klardrop.common.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public actual class ForegroundState {
  public actual val isForeground: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
}
