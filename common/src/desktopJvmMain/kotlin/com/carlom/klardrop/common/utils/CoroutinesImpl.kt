package com.carlom.klardrop.common.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

actual class CoroutinesImpl actual constructor() : Coroutines {

  // Last-resort handler for uncaught failures in the top-level coroutines of the app's
  // SupervisorJob scopes. It runs on the failing coroutine's own thread. Rethrowing here
  // pushes the exception to the thread's uncaught handler for no benefit (and aborts the
  // process outright on the Apple targets that share this logic) — so we don't. Expected
  // network churn is logged locally, everything else is reported to Bugsnag, and the
  // process keeps running, matching Server.kt and Throwable.isExpectedNetworkNoise().
  private val handler = nonFatalCoroutineExceptionHandler("CoroutinesImpl")

  private val scope by lazy { CoroutineScope(SupervisorJob() + mainDispatcher + handler) }

  actual override fun newScope(): CoroutineScope {
    return CoroutineScope(mainDispatcher + handler)
  }

  actual override fun newScope(context: CoroutineContext): CoroutineScope {

    val newContext = if (context[CoroutineExceptionHandler.Key] == null) {
      context + handler
    } else {
      context
    }

    return CoroutineScope(newContext)
  }

  actual override val appScope: CoroutineScope
    get() = scope
  actual override val ioDispatcher: CoroutineDispatcher
    get() = Dispatchers.IO
  actual override val mainDispatcher: CoroutineDispatcher
    get() = Dispatchers.IO.limitedParallelism(1)
  actual override val cpuDispatcher: CoroutineDispatcher
    get() = Dispatchers.Default
}