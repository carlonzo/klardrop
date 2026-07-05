package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.FakeMessagesRouter
import com.carlom.klardrop.common.ble.BleSession
import com.carlom.klardrop.common.utils.Coroutines
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests for the Probing watchdog (F2/F3, V4 (b)/(c)):
 * [Reachability.Probing] must not wedge forever when nothing ever calls
 * [ConnectionsPool.updateConnection] / [ConnectionsPool.markUnreachable] — it must fall back to
 * [Reachability.Unknown] after [ConnectionsPoolImpl] internal watchdog window. A probe that DOES
 * reach a terminal call (e.g. [ConnectionsPool.updateConnection]) must not be clobbered by a
 * later-firing watchdog.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsPoolProbingWatchdogTest {

  // --- Fakes -----------------------------------------------------------------

  /**
   * [Coroutines] whose every dispatcher is the test's [StandardTestDispatcher] so
   * [advanceTimeBy]/[runCurrent] deterministically drive the watchdog's [kotlinx.coroutines.delay]
   * without a real 15 s wait. Mirrors the local helper in ConnectionsPoolNetworkDebounceTest.
   */
  private fun testCoroutines(
    scope: TestScope,
    dispatcher: kotlinx.coroutines.test.TestDispatcher,
  ): Coroutines = object : Coroutines {
    override val ioDispatcher: CoroutineDispatcher = dispatcher
    override val mainDispatcher: CoroutineDispatcher = dispatcher
    override val cpuDispatcher: CoroutineDispatcher = dispatcher
    override val appScope: CoroutineScope = scope
    override fun newScope(): CoroutineScope = CoroutineScope(dispatcher)
    override fun newScope(context: CoroutineContext): CoroutineScope =
      CoroutineScope(dispatcher + context)
  }

  private class FakeBleSession(override val deviceId: String) : BleSession {
    override var isOpen: Boolean = true
      private set

    override val mtu: Int get() = 512

    override suspend fun sendChunk(chunk: ByteArray) = Unit
    override suspend fun receiveChunk(): ByteArray? = null

    override fun close() {
      isOpen = false
    }
  }

  private fun fakeMessenger(coroutines: Coroutines, deviceId: String): ConnectionMessenger {
    val session = FakeBleSession(deviceId)
    val conn = Connection.Ble(session, deviceId)
    val readCh = ByteChannel(autoFlush = true)
    val writeCh = ByteChannel(autoFlush = true)
    return ConnectionMessenger(
      coroutines = coroutines,
      connection = conn,
      messagesRouter = FakeMessagesRouter(),
      readChannel = readCh,
      writeChannel = writeCh,
      ackTimeoutMs = 500L,
    )
  }

  // --- Tests -------------------------------------------------------------------

  /**
   * (b) A probe that never reaches a terminal call (mirrors [ConnectOutcome.NotInitiated] or any
   * other forgotten path, F2/F3) must not wedge on Probing forever: after the watchdog window it
   * falls back to Unknown — NOT Unreachable, since we never established the peer is actually down.
   */
  @Test
  fun probingWithNoTerminalCall_fallsBackToUnknownAfterWatchdogWindow() = runTest(timeout = 10.seconds) {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coroutines = testCoroutines(this, dispatcher)
    val pool = ConnectionsPoolImpl(coroutines = coroutines)

    pool.markProbing("device-1")
    assertEquals(Reachability.Probing, pool.reachability.value["device-1"], "expected Probing right after markProbing")

    // Still within the watchdog window: must remain Probing.
    advanceTimeBy(10.seconds)
    runCurrent()
    assertEquals(
      Reachability.Probing,
      pool.reachability.value["device-1"],
      "must not time out before the watchdog window elapses",
    )

    // Past the watchdog window: falls back to Unknown, never Unreachable.
    advanceTimeBy(6.seconds)
    runCurrent()
    assertEquals(
      Reachability.Unknown,
      pool.reachability.value["device-1"],
      "a wedged Probing state must resolve to Unknown (not Unreachable) once the watchdog fires",
    )
  }

  /**
   * (c) A probe that DOES land a terminal call (updateConnection => Reachable) must survive past
   * the watchdog window unclobbered — the watchdog only acts if state is still Probing when it fires.
   */
  @Test
  fun markProbingFollowedByUpdateConnection_staysReachable_watchdogDoesNotClobber() = runTest(timeout = 10.seconds) {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coroutines = testCoroutines(this, dispatcher)
    val pool = ConnectionsPoolImpl(coroutines = coroutines)

    pool.markProbing("device-2")
    assertEquals(Reachability.Probing, pool.reachability.value["device-2"])

    pool.updateConnection("device-2", fakeMessenger(coroutines, "device-2"))
    assertEquals(Reachability.Reachable, pool.reachability.value["device-2"])

    // Advance well past the watchdog window — the terminal Reachable state must survive.
    advanceTimeBy(20.seconds)
    runCurrent()
    assertEquals(
      Reachability.Reachable,
      pool.reachability.value["device-2"],
      "the watchdog must not override a state that already left Probing via a terminal call",
    )
  }
}
