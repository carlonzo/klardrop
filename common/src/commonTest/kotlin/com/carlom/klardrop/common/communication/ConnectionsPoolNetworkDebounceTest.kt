package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.FakeMessagesRouter
import com.carlom.klardrop.common.ble.BleSession
import com.carlom.klardrop.common.network.NetworkChangeEvent
import com.carlom.klardrop.common.utils.Coroutines
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Regression test for Reliability #2.1:
 * Every [NetworkChangeEvent.Changed] emission was closing ALL live pooled connections,
 * even spurious ones (Android fires onCapabilitiesChanged / onLinkPropertiesChanged
 * for signal-strength or DNS updates that do not affect real connectivity).
 *
 * After the debounce fix, a rapid burst of [NetworkChangeEvent.Changed] events that
 * arrive within the debounce window must NOT close a live connection.  A single
 * event that arrives AFTER the debounce window elapses MUST flush the pool (real
 * network-change path is preserved).
 *
 * Observable chosen: [ConnectionsPool.reachability] transitions from [Reachability.Reachable]
 * to [Reachability.Unknown] when and only when [ConnectionsPoolImpl.closeAllConnections] fires.
 * We detect a spurious flush by watching whether the reachability map is reset before the
 * debounce window elapses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsPoolNetworkDebounceTest {

    // --- Fakes ---------------------------------------------------------------

    /**
     * A [BleSession] whose [isOpen] flag can be flipped by the test. Since [Connection.Ble]
     * delegates [Connection.isClosed] to `!session.isOpen`, this lets us drive the messenger's
     * liveness from outside. Implementing [BleSession] (an interface) from a test source set is
     * allowed even though [Connection] (a sealed class) is not directly extendable from here.
     */
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

    /**
     * Build a [ConnectionMessenger] whose underlying connection is a [Connection.Ble] wrapping
     * a [FakeBleSession]. Using BLE transport avoids the need for a real TCP socket pair.
     * The read/write channels are open [ByteChannel] instances so [isClosed] returns false
     * (both channels stay open and the BLE session starts open) until the messenger is closed.
     *
     * Returns the [ConnectionMessenger] and the [FakeBleSession] so the test can inspect closure.
     */
    private fun fakeMessenger(
        coroutines: Coroutines,
        deviceId: String,
    ): Pair<ConnectionMessenger, FakeBleSession> {
        val session = FakeBleSession(deviceId)
        val conn = Connection.Ble(session, deviceId)
        val readCh = ByteChannel(autoFlush = true)
        val writeCh = ByteChannel(autoFlush = true)
        val messenger = ConnectionMessenger(
            coroutines = coroutines,
            connection = conn,
            messagesRouter = FakeMessagesRouter(),
            readChannel = readCh,
            writeChannel = writeCh,
            ackTimeoutMs = 500L,
        )
        return messenger to session
    }

    // --- Tests ---------------------------------------------------------------

    /**
     * Spurious burst: two [NetworkChangeEvent.Changed] events emitted within the
     * debounce window must NOT flush a live connection.
     *
     * Before the fix this test FAILS because the very first event immediately
     * calls [ConnectionsPoolImpl.closeAllConnections], resetting reachability to Unknown.
     */
    @Test
    fun spuriousEventBurstDoesNotCloseActiveConnections() = runTest(timeout = 10.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coroutines = testCoroutines(this, dispatcher)
        val networkEvents = MutableSharedFlow<NetworkChangeEvent>(extraBufferCapacity = 8)

        val pool = ConnectionsPoolImpl(
            coroutines = coroutines,
            networkEvents = networkEvents,
        )

        val (messenger, session) = fakeMessenger(coroutines, "device-1")
        pool.updateConnection("device-1", messenger)
        assertTrue(
            pool.reachability.value["device-1"] == Reachability.Reachable,
            "reachability should be Reachable before any events",
        )

        // Give the pool's flow collector coroutine a chance to start and subscribe to
        // the SharedFlow BEFORE we emit any events, so events aren't lost (replay=0).
        runCurrent()

        // Emit first Changed event; then give the collector a turn so it processes the
        // event and arms the 500 ms debounce timer.
        networkEvents.emit(NetworkChangeEvent.Changed)
        advanceTimeBy(50.milliseconds) // 50ms elapsed; debounce timer started but not fired
        // Emit second event; collector cancels old debounce and resets the 500 ms timer.
        networkEvents.emit(NetworkChangeEvent.Changed)
        // Advance 200 ms after the second event — 200ms < 500ms window, so no flush yet.
        advanceTimeBy(200.milliseconds)

        assertFalse(
            pool.reachability.value["device-1"] == Reachability.Unknown,
            "reachability must NOT be reset by a burst within the debounce window; " +
                    "got reachability=${pool.reachability.value["device-1"]}",
        )
        assertFalse(
            session.isOpen.not(),
            "BLE session must NOT be closed within the debounce window",
        )
    }

    /**
     * Real network change: a single [NetworkChangeEvent.Changed] followed by the
     * full debounce delay MUST flush the pool — preserving the existing flush guarantee.
     */
    @Test
    fun realNetworkChangeEventuallyFlushesPool() = runTest(timeout = 10.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coroutines = testCoroutines(this, dispatcher)
        val networkEvents = MutableSharedFlow<NetworkChangeEvent>(extraBufferCapacity = 8)

        val pool = ConnectionsPoolImpl(
            coroutines = coroutines,
            networkEvents = networkEvents,
        )

        val (messenger, session) = fakeMessenger(coroutines, "device-2")
        pool.updateConnection("device-2", messenger)
        assertTrue(
            pool.reachability.value["device-2"] == Reachability.Reachable,
            "reachability should be Reachable before any events",
        )

        // Give the pool's flow collector coroutine a chance to start and subscribe to
        // the SharedFlow BEFORE we emit. Without this, the emit happens before the
        // collector subscribes and the event is lost (replay=0).
        runCurrent()

        // Emit one event; the collector is now subscribed and will receive it.
        // Then advance past the 500 ms debounce window.
        networkEvents.emit(NetworkChangeEvent.Changed)
        advanceTimeBy(600.milliseconds)

        assertTrue(
            pool.reachability.value["device-2"] == Reachability.Unknown,
            "reachability MUST be Unknown after single event + full debounce window elapses; " +
                    "got reachability=${pool.reachability.value["device-2"]}",
        )
        assertTrue(
            session.isOpen.not(),
            "BLE session MUST be closed after the debounce window elapses",
        )
    }
}
