package com.carlom.klardrop.common.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

actual class CoroutinesImpl actual constructor() : Coroutines {

  // CEH is the terminal sink for uncaught coroutine failures. Do NOT rethrow:
  // on Kotlin/Native a throw from CEH aborts the process (Bugsnag SIGABRT after
  // IndexOutOfBoundsException / ArrayIndexOutOfBounds during mDNS browse). log()
  // already breadcrumb+notify Bugsnag; noise is filtered in isExpectedNetworkNoise.
  private val handler = CoroutineExceptionHandler { _, exception ->
    if (exception is kotlinx.coroutines.CancellationException) return@CoroutineExceptionHandler
    log("CoroutinesImpl", "CoroutineExceptionHandler got ${exception.message}", exception)
  }

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
    get() = Dispatchers.Main
  actual override val cpuDispatcher: CoroutineDispatcher
    get() = Dispatchers.Default
}
