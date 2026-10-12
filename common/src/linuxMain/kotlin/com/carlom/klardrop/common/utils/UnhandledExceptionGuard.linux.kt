package com.carlom.klardrop.common.utils

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook

private var hookInstalled = false

@OptIn(ExperimentalNativeApi::class)
actual fun installUnhandledExceptionGuard() {
  if (hookInstalled) return
  hookInstalled = true

  setUnhandledExceptionHook { throwable ->
    reportUncaughtException("UnhandledExceptionGuard", throwable, fatal = true)
  }
}
