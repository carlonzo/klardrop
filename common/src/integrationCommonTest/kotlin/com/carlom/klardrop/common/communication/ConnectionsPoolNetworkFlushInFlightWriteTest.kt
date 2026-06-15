package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.FakeMessagesRouter
import com.carlom.klardrop.common.ble.BleSession
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.SimpleSendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.network.NetworkChangeEvent
import com.carlom.klardrop.common.utils.Coroutines
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
 * Repro for the reliability issue where the network-flush path bypasses the per-connection
 * [writeLock], so a spurious [NetworkChangeEvent.Changed] during a live transfer aborts the
 * in-flight send.
 *
 * [ConnectionsPoolImpl.subscribeToNetworkEvents]'s debounced job calls
 * [ConnectionsPoolImpl.closeAllConnections], which unconditionally invokes
 * [ConnectionMessenger.close] on every pooled connection. The heartbeat path
 * ([ConnectionMessenger.heartbeatLoop]) already guards against this — it probes the writeLock
 * with a bounded tryLock and SKIPS a connection whose writer is mid-flight — but the
 * network-flush path does not. A single Android onCapabilitiesChanged / onLinkPropertiesChanged
 * burst (signal-strength / DNS noise, not a real connectivity change) therefore tears down the
 * socket out from under an active multi-MB transfer.
 *
 * This test holds the messenger's writeLock the exact way a real chunked file writer does
 * (the [blockingRouter] grabs the same [Mutex] the messenger threads into
 * [com.carlom.klardrop.common.communication.router.MessagesRouter.onSendingMessage] and holds it),
 * then fires a network event and lets the debounce window elapse. On the CURRENT code the flush
 * closes the connection mid-write (test FAILS). After the fix, an idle connection is still flushed
 * but a connection with an in-flight write is skipped, so the live transfer survives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsPoolNetworkFlushInFlightWriteTest {

  /**
   * A [BleSession] whose [isOpen] flag is flipped by [close]. [Connection.Ble] derives
   * [Connection.isClosed] from `!session.isOpen`, so this lets the test observe whether the
   * network-flush path closed the connection — without standing up a real TCP socket.
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

  /**
   * All dispatchers (including [ioDispatcher], on which the pool's network-flush scope runs)
   * point at the single virtual [dispatcher] so the 500ms debounce timer and the held-writeLock
   * send are both driven deterministically by the test scheduler.
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

  @Test
  fun spuriousNetworkEventDoesNotAbortInFlightTransfer() = runTest(timeout = 20.seconds) {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coroutines = testCoroutines(this, dispatcher)
    val networkEvents = MutableSharedFlow<NetworkChangeEvent>(extraBufferCapacity = 8)

    val pool = ConnectionsPoolImpl(
      coroutines = coroutines,
      networkEvents = networkEvents,
    )

    // Signals coordinating the simulated in-flight writer (mirrors the chunked file writer:
    // it grabs the messenger's writeLock and holds it for the duration of the transfer).
    val writeLockHeld = CompletableDeferred<Unit>()
    val releaseWriter = CompletableDeferred<Unit>()

    val blockingRouter = object : FakeMessagesRouter() {
      override suspend fun <S : SendMessageRequest> onSendingMessage(
        toDeviceId: String,
        sendMessageRequest: S,
        writeChannel: ByteWriteChannel,
        readChannel: ByteReadChannel,
        progress: MutableSharedFlow<MessengerSendProgress>,
        awaitReadyAck: suspend () -> Unit,
        writeLock: Mutex,
        cipher: FrameCipher,
      ) {
        // Acquire the SAME writeLock the messenger owns — exactly what the production file
        // writer does for each framed chunk — and hold it for the whole "transfer".
        writeLock.lock()
        writeLockHeld.complete(Unit)
        releaseWriter.await()
        writeLock.unlock()
      }
    }

    val session = FakeBleSession("device-inflight")
    val messenger = ConnectionMessenger(
      coroutines = coroutines,
      connection = Connection.Ble(session, "device-inflight"),
      messagesRouter = blockingRouter,
      readChannel = ByteChannel(autoFlush = true),
      writeChannel = ByteChannel(autoFlush = true),
      ackTimeoutMs = 60_000L,
    )

    pool.updateConnection("device-inflight", messenger)
    assertTrue(
      pool.reachability.value["device-inflight"] == Reachability.Reachable,
      "reachability should be Reachable before any events",
    )

    // Let the pool's network-event collector subscribe to the SharedFlow (replay=0).
    runCurrent()

    // Kick off the send so the writer grabs and HOLDS the messenger's writeLock — the
    // connection now has an active in-flight write.
    val sendJob = launch {
      runCatching {
        messenger.send(
          SimpleSendMessageRequest(TextMessage(text = "in-flight payload")),
          MutableSharedFlow(extraBufferCapacity = 10),
        )
      }
    }
    runCurrent()
    assertTrue(writeLockHeld.isCompleted, "writer must be holding the writeLock before the network event fires")

    // A spurious network change arrives mid-transfer (e.g. Android capabilities/DNS noise).
    networkEvents.emit(NetworkChangeEvent.Changed)
    // Let the debounce window fully elapse so the flush fires.
    advanceTimeBy(700.milliseconds)
    runCurrent()

    // The in-flight transfer must NOT have been aborted: the connection with a held writeLock
    // must be SKIPPED by the network-flush path (mirroring the heartbeat guard). On current
    // code closeAllConnections() unconditionally calls messenger.close() -> session.close(),
    // so this assertion FAILS, proving the bug.
    assertFalse(
      session.isOpen.not(),
      "Spurious network event must NOT close a connection with an in-flight write (writeLock held); " +
        "the live transfer was aborted",
    )
    assertFalse(
      messenger.isClosed(),
      "Messenger for an in-flight transfer must survive a spurious network-flush",
    )

    // Now release the writer (transfer "finishes"); a subsequent network change with the lock
    // free is allowed to flush. This also lets the background send coroutine complete cleanly.
    releaseWriter.complete(Unit)
    runCurrent()
    sendJob.cancel()
  }
}
