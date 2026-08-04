package com.carlom.klardrop.common.utils

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the contract that keeps a single background failure from killing the app.
 *
 * A top-level coroutine that throws in a scope with no [CoroutineExceptionHandler] reaches
 * kotlinx.coroutines' final resort, which on Kotlin/Native is `processUnhandledException` →
 * `abort()`: the observed macOS SIGABRT. So the handler must (a) exist on every long-lived scope
 * and (b) never rethrow.
 */
class UncaughtExceptionsTest {

  @Test
  fun handler_swallowsFailure_andScopeKeepsWorking() = runTest {
    val scope = CoroutineScope(
      SupervisorJob() + StandardTestDispatcher(testScheduler) +
        nonFatalCoroutineExceptionHandler("UncaughtExceptionsTest"),
    )

    // On Kotlin/Native an unhandled throw here is what used to take the process down.
    scope.launch { throw IllegalStateException("boom") }.join()

    var siblingRan = false
    scope.launch { siblingRan = true }.join()
    assertTrue(siblingRan, "a sibling job must still run after an unhandled failure")
  }

  @Test
  fun newScopeWithContext_carriesTheHandler() {
    // The scopes that outlive a screen (Client, Server, ConnectionsPool, MessagesRouter's
    // authorization scope, ConnectionMessenger's heartbeat) are all built this way.
    val scope = CoroutinesImpl().newScope(SupervisorJob() + Dispatchers.IO)

    assertNotNull(
      scope.coroutineContext[CoroutineExceptionHandler],
      "newScope(context) must attach the last-resort handler",
    )
  }
}
