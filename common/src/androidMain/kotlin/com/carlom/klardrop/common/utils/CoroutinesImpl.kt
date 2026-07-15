package com.carlom.klardrop.common.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

actual class CoroutinesImpl actual constructor() : Coroutines {

  // CEH is the terminal sink for uncaught coroutine failures. Do NOT rethrow —
  // rethrowing can take down the process / main scope. log() already reports
  // to Bugsnag; isExpectedNetworkNoise filters lifecycle noise.
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