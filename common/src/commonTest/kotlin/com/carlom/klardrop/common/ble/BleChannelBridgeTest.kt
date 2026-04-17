package com.carlom.klardrop.common.ble

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.message.ConnectionInfoMessage
import com.carlom.klardrop.common.communication.message.ConnectionKind
import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.readMessage
import com.carlom.klardrop.common.communication.sendMessage
import com.carlom.klardrop.common.utils.Coroutines
import io.ktor.utils.io.close
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end tests for [BleChannelBridge]. Two bridges are wired back-to-back via a
 * [LoopbackBleSession] pair, proving that writes on one side's `ByteWriteChannel` end up
 * readable on the other side's `ByteReadChannel` after going through MTU chunking —
 * and that the existing `sendMessage` / `readMessage` extensions work unchanged over
 * the BLE bridge.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleChannelBridgeTest {

  private val testDispatcher: TestDispatcher = StandardTestDispatcher()
  private val coroutines: Coroutines = object : Coroutines {
    override val ioDispatcher = testDispatcher
    override val mainDispatcher = testDispatcher
    override val cpuDispatcher = testDispatcher
    override val appScope = CoroutineScope(testDispatcher)
    override fun newScope() = CoroutineScope(testDispatcher)
    override fun newScope(context: kotlin.coroutines.CoroutineContext) =
      CoroutineScope(context)
  }
  private val serializer = MessageSerializer(ProtoBuf, coroutines)

  /**
   * In-memory bidirectional BLE session pair. Chunks written on [a] appear on [b]
   * (in order) and vice versa. Designed for testing — no radio involved.
   */
  private class LoopbackBleSession(
    override val deviceId: String,
    override val mtu: Int,
    private val outbound: Channel<ByteArray>,
    private val inbound: Channel<ByteArray>,
  ) : BleSession {
    private var open = true
    override val isOpen: Boolean get() = open

    override suspend fun sendChunk(chunk: ByteArray) {
      require(chunk.size <= mtu) { "chunk size ${chunk.size} exceeds mtu $mtu" }
      check(open) { "session closed" }
      outbound.send(chunk)
    }

    override suspend fun receiveChunk(): ByteArray? {
      if (!open) return null
      return runCatching { inbound.receive() }.getOrNull()
    }

    override fun close() {
      if (!open) return
      open = false
      outbound.close()
      inbound.close()
    }
  }

  private data class PairedBridges(
    val aBridge: BleChannelBridge,
    val bBridge: BleChannelBridge,
    val aSession: LoopbackBleSession,
    val bSession: LoopbackBleSession,
    val pumpJob: Job,
  )

  /**
   * Build two [BleChannelBridge]s connected back-to-back. Each end gets its own [BleSession]
   * whose outbound channel feeds the other end's inbound channel.
   */
  private fun pairedBridges(mtu: Int, scope: CoroutineScope): PairedBridges {
    val aToB = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    val bToA = Channel<ByteArray>(capacity = Channel.UNLIMITED)

    val aSession = LoopbackBleSession(deviceId = "peerB", mtu = mtu, outbound = aToB, inbound = bToA)
    val bSession = LoopbackBleSession(deviceId = "peerA", mtu = mtu, outbound = bToA, inbound = aToB)

    val aBridge = BleChannelBridge(aSession, scope).start()
    val bBridge = BleChannelBridge(bSession, scope).start()

    return PairedBridges(aBridge, bBridge, aSession, bSession, pumpJob = SupervisorJob())
  }

  @Test
  fun forwardsRawBytesInOrder() = runTest(testDispatcher, timeout = 5.seconds) {
    coroutineScope {
      val pair = pairedBridges(mtu = 8, scope = this)

      val payload = ByteArray(50) { (it + 1).toByte() }
      pair.aBridge.writeChannel.sendMessage(HandshakeMessage("xxxxxxxx"), serializer)
      // Writing an additional ad-hoc payload to verify raw-byte forwarding works below
      // the sendMessage layer as well:
      pair.aBridge.writeChannel.apply { writeByteArray(payload); flush() }

      val handshake = pair.bBridge.readChannel.readMessage(serializer) as HandshakeMessage
      assertEquals("xxxxxxxx", handshake.deviceId)

      val received = ByteArray(50)
      var offset = 0
      while (offset < received.size) {
        offset += pair.bBridge.readChannel.readAvailable(received, offset, received.size - offset).coerceAtLeast(1)
      }
      assertEquals(payload.toList(), received.toList())

      pair.aSession.close()
      pair.bSession.close()
    }
  }

  @Test
  fun sendMessageRoundTripsAcrossBridge() = runTest(testDispatcher, timeout = 5.seconds) {
    coroutineScope {
      val pair = pairedBridges(mtu = 23, scope = this)

      val wifi = ConnectionInfoMessage(
        kind = ConnectionKind.WIFI_WPA2,
        ssid = "HomeRouter-TestNet",
        password = "p@ssw0rd-but-longer-than-one-chunk-should-allow",
        hidden = false,
        id = 42,
      )

      val received = async {
        withTimeout(3.seconds) { pair.bBridge.readChannel.readMessage(serializer) }
      }

      pair.aBridge.writeChannel.sendMessage(wifi, serializer)

      val got = received.await()
      assertIs<ConnectionInfoMessage>(got)
      assertEquals(wifi, got)

      pair.aSession.close()
      pair.bSession.close()
    }
  }

  @Test
  fun bidirectionalExchangeHandshakeBothWays() = runTest(testDispatcher, timeout = 5.seconds) {
    coroutineScope {
      val pair = pairedBridges(mtu = 20, scope = this)

      val aSentHandshake = HandshakeMessage("device-a1")
      val bSentHandshake = HandshakeMessage("device-b2")

      val bReceived = async { pair.bBridge.readChannel.readMessage(serializer) }
      val aReceived = async { pair.aBridge.readChannel.readMessage(serializer) }

      launch {
        pair.aBridge.writeChannel.sendMessage(aSentHandshake, serializer)
      }
      launch {
        pair.bBridge.writeChannel.sendMessage(bSentHandshake, serializer)
      }

      assertEquals(aSentHandshake, bReceived.await())
      assertEquals(bSentHandshake, aReceived.await())

      pair.aSession.close()
      pair.bSession.close()
    }
  }

  @Test
  fun manyMessagesInSequencePreserveOrdering() = runTest(testDispatcher, timeout = 10.seconds) {
    coroutineScope {
      val pair = pairedBridges(mtu = 40, scope = this)

      val sent = (1..15).map { i ->
        TextMessage(title = "msg-$i", text = "body-$i".repeat(i * 3), id = i)
      }

      val received = async {
        sent.map { pair.bBridge.readChannel.readMessage(serializer) }
      }

      for (m in sent) pair.aBridge.writeChannel.sendMessage(m, serializer)

      val got = received.await()
      assertEquals(sent, got)

      pair.aSession.close()
      pair.bSession.close()
    }
  }

  @Test
  fun sessionCloseSurfacesAsClosedChannels() = runTest(testDispatcher, timeout = 5.seconds) {
    val scope = TestScope(testDispatcher)
    val pair = pairedBridges(mtu = 32, scope = scope)

    // Exchange one message so pumps are hot.
    val hello = TextMessage(title = "hi", text = "hello", id = 1)
    val received = scope.async { pair.bBridge.readChannel.readMessage(serializer) }
    pair.aBridge.writeChannel.sendMessage(hello, serializer)
    testDispatcher.scheduler.advanceUntilIdle()
    assertEquals(hello, received.await())

    // Peer A drops the session — peer B should see its read channel drain and close.
    pair.aSession.close()
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(pair.aSession.isOpen)
    // Read channel on B eventually closes as its read pump ends.
    assertEquals(true, pair.bBridge.readChannel.isClosedForRead || !pair.bSession.isOpen)
  }
}
