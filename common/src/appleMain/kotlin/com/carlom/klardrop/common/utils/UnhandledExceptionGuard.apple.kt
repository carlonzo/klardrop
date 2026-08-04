package com.carlom.klardrop.common.utils

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook

// Set once from Klardrop.init(). A benign race would only re-install the same hook, so no lock.
private var hookInstalled = false

@OptIn(ExperimentalNativeApi::class)
actual fun installUnhandledExceptionGuard() {
  if (hookInstalled) return
  hookInstalled = true

  // The hook replaces the runtime's default "print and abort()" for exceptions that reach
  // processUnhandledException — which is exactly where kotlinx.coroutines sends a top-level
  // coroutine failure that no CoroutineExceptionHandler claimed. Returning normally from here
  // lets the process live; throwing out of the hook terminates it, so reportUncaughtException
  // swallows everything.
  setUnhandledExceptionHook { throwable ->
    reportUncaughtException("UnhandledExceptionGuard", throwable)
  }
}
