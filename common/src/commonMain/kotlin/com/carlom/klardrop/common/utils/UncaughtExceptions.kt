package com.carlom.klardrop.common.utils

import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Terminal reporting for a coroutine failure that nothing else caught.
 *
 * Expected network churn (peer vanished, socket reset, scope cancelled — see
 * [Throwable.isExpectedNetworkNoise]) is logged locally; everything else is reported to Sentry.
 * Never throws: this runs on paths where an escaping exception would kill the process, so the
 * whole body is wrapped defensively.
 */
internal fun reportUncaughtException(tag: String, throwable: Throwable) {
  runCatching {
    if (throwable.isExpectedNetworkNoise()) {
      logLocal(tag, "coroutine ended (${throwable.message})", throwable)
    } else {
      log(tag, "uncaught coroutine exception (${throwable.message})", throwable)
    }
  }
}

/**
 * Last-resort [CoroutineExceptionHandler] for the top-level coroutines of a long-lived
 * `SupervisorJob` scope.
 *
 * Every scope the app keeps alive for the process lifetime MUST carry one. Without a handler the
 * failure of a single top-level coroutine reaches kotlinx.coroutines' final resort, which on
 * Kotlin/Native is `processUnhandledException` → `abort()` — i.e. the whole macOS/iOS app dies
 * (EXC_CRASH / SIGABRT) because one background job threw. The JVM merely prints, which is why
 * these only ever surface on the Apple targets.
 *
 * The handler runs on the failing coroutine's own thread and deliberately does NOT rethrow — see
 * [reportUncaughtException] for the reporting contract.
 */
fun nonFatalCoroutineExceptionHandler(tag: String): CoroutineExceptionHandler =
  CoroutineExceptionHandler { _, throwable -> reportUncaughtException(tag, throwable) }
