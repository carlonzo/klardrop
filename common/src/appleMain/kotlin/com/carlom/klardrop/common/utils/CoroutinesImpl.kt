package com.carlom.klardrop.common.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

actual class CoroutinesImpl actual constructor() : Coroutines {

  // Last-resort handler for uncaught failures in the top-level coroutines of the app's
  // SupervisorJob scopes (Client, Server, DiscoveryNetwork, ConnectionsPool, …). It runs
  // on the failing coroutine's own thread, which for the ioDispatcher scopes is a
  // background worker. On Kotlin/Native an exception that escapes this handler propagates
  // to the top of that thread and terminates the whole process with abort()/SIGABRT — so
  // we must NOT rethrow. Instead we mirror Server.kt's per-connection handler and the
  // contract documented on Throwable.isExpectedNetworkNoise(): expected network churn is
  // logged locally, everything else is reported to Bugsnag, and the process keeps running.
  private val handler = CoroutineExceptionHandler { _, exception ->
    if (exception.isExpectedNetworkNoise()) {
      logLocal("CoroutinesImpl", "coroutine ended (${exception.message})", exception)
    } else {
      log("CoroutinesImpl", "uncaught coroutine exception (${exception.message})", exception)
    }
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
