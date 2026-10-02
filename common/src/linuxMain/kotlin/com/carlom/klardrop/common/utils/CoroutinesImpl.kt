package com.carlom.klardrop.common.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

// Bounded (unlike the JVM/Android default, which allows up to 64 threads): the native daemon
// is a single low-traffic process, and each Dispatchers.IO thread here has a measurable RSS
// cost (native thread stack + malloc arena). Shared as one instance across every linuxMain
// user of "background IO work" (CoroutinesImpl and the handful of actuals that predate it,
// e.g. LanAddressSelector/NetworkLifecycleMonitor/LinuxTrustStorage) so the bound is
// real: limitedParallelism() allocates a fresh bounded view per call, so handing each call
// site its own Dispatchers.IO.limitedParallelism(8) would not actually cap total thread count.
//
internal val sharedNativeIoDispatcher: CoroutineDispatcher by lazy { Dispatchers.IO.limitedParallelism(8) }

actual class CoroutinesImpl actual constructor() : Coroutines {

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
    get() = sharedNativeIoDispatcher

  // No UI main loop on headless native (daemon/CLI) — same single-threaded IO dispatcher
  // desktopJvmMain uses instead of Dispatchers.Main, which would need Compose's event loop.
  actual override val mainDispatcher: CoroutineDispatcher by lazy { Dispatchers.IO.limitedParallelism(1) }

  actual override val cpuDispatcher: CoroutineDispatcher
    get() = Dispatchers.Default
}
