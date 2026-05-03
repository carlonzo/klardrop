package com.carlom.klardrop.common.ble

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.message.AckType
import com.carlom.klardrop.common.communication.message.ConnectionInfoMessage
import com.carlom.klardrop.common.communication.message.ConnectionKind
import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.communication.message.MessageAcknowledgment
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.utils.Coroutines
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * End-to-end test for the BLE data-plane using an in-memory chunk loopback instead of a
 * real radio. Two [FakeBlePeer] endpoints exchange chunks over a pair of [Channel]s (one
 * per direction), mirroring what `GATT TX characteristic write` + `RX notification` would
 * do on the wire.
 *
 * The goal is to prove that the production [BleFraming] + [BleReassembler] + [MessageSerializer]
 * pipeline correctly carries the same Klardrop wire format the TCP transport uses, so we can
 * ship the BLE transport with confidence that framing is right before any native BLE
 * connect/write plumbing lands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleLoopbackIntegrationTest {

  private val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
  private val coroutines: Coroutines = object : Coroutines {
    override val ioDispatcher = testDispatcher
    override val mainDispatcher = testDispatcher
    override val cpuDispatcher = testDispatcher
    override val appScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
    override fun newScope() = kotlinx.coroutines.CoroutineScope(testDispatcher)
    override fun newScope(context: kotlin.coroutines.CoroutineContext) =
      kotlinx.coroutines.CoroutineScope(context)
  }
  private val serializer = MessageSerializer(ProtoBuf, coroutines)

  /** A simulated BLE peer: sends chunks to [outbound], reassembles chunks from [inbound]. */
  private class FakeBlePeer(
    val name: String,
    val outbound: Channel<ByteArray>,
    val inbound: Channel<ByteArray>,
    val mtu: Int,
  ) {
    private val reassembler = BleReassembler()

    suspend fun send(message: Message, serializer: MessageSerializer) {
      val payload = serializer.serialize(message)
      BleFraming.chunk(payload, mtu).forEach { chunk -> outbound.send(chunk) }
    }

    /** Receives exactly [count] complete messages from the inbound channel. */
    suspend fun receive(count: Int, serializer: MessageSerializer): List<Message> {
      val collected = mutableListOf<Message>()
      while (collected.size < count) {
        val chunk = inbound.receive()
        for (framePayload in reassembler.onChunk(chunk)) {
          collected += serializer.deserialize(framePayload)
          if (collected.size == count) break
        }
      }
      return collected
    }
  }

  private fun connectPeers(mtu: Int): Pair<FakeBlePeer, FakeBlePeer> {
    val aToB = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    val bToA = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    val a = FakeBlePeer("A", outbound = aToB, inbound = bToA, mtu = mtu)
    val b = FakeBlePeer("B", outbound = bToA, inbound = aToB, mtu = mtu)
    return a to b
  }

  @Test
  fun handshakeAndConnectionInfoRoundTripAcrossMtuBoundaries() = runTest(testDispatcher) {
    // MTU 23 is the BLE minimum; forces heavy chunking of anything non-trivial.
    val (a, b) = connectPeers(mtu = 23)

    val handshake = HandshakeMessage(deviceId = "devA1234", id = 100)
    val wifi = ConnectionInfoMessage(
      kind = ConnectionKind.WIFI_WPA2,
      ssid = "HomeRouter",
      password = "p@ssw0rd-long-enough-to-force-chunking-aaaaaaaaaa",
      hidden = false,
      id = 200,
    )

    coroutineScope {
      val received = async { b.receive(count = 2, serializer) }
      launch {
        a.send(handshake, serializer)
        a.send(wifi, serializer)
      }
      val messages = received.await()
      assertEquals(2, messages.size)
      assertIs<HandshakeMessage>(messages[0])
      assertEquals(handshake, messages[0])
      assertIs<ConnectionInfoMessage>(messages[1])
      assertEquals(wifi, messages[1])
    }
  }

  @Test
  fun bidirectionalExchangeSimulatesRequestResponseWithAck() = runTest(testDispatcher) {
    val (a, b) = connectPeers(mtu = 30)

    val outgoing = TextMessage(title = "note", text = "meet me at the cafe", id = 1)
    val ack = MessageAcknowledgment(ackType = AckType.RECEIVED, id = 2)

    coroutineScope {
      // B receives the text, sends ack back; A receives the ack.
      val bFlow = async {
        val got = b.receive(count = 1, serializer).single()
        b.send(ack, serializer)
        got
      }
      val aFlow = async {
        a.send(outgoing, serializer)
        a.receive(count = 1, serializer).single()
      }

      val received = bFlow.await()
      assertIs<TextMessage>(received)
      assertEquals(outgoing, received)

      val gotAck = aFlow.await()
      assertIs<MessageAcknowledgment>(gotAck)
      assertEquals(ack, gotAck)
    }
  }

  @Test
  fun tinyMtuStillDeliversLargeMessage() = runTest(testDispatcher) {
    // MTU 6 is unrealistically small but proves the reassembler copes with payloads that
    // need dozens of chunks and where the length prefix itself spans multiple chunks.
    val (a, b) = connectPeers(mtu = 6)

    val longText = (1..500).joinToString(separator = ",") { "item-$it" }
    val message = TextMessage(title = "big", text = longText, id = 999)

    coroutineScope {
      val received = async { b.receive(count = 1, serializer).single() }
      launch { a.send(message, serializer) }
      val got = received.await()
      assertIs<TextMessage>(got)
      assertEquals(message, got)
    }
  }

  @Test
  fun manyMessagesInSequencePreserveOrderingAndContent() = runTest(testDispatcher) {
    val (a, b) = connectPeers(mtu = 40)

    val messages: List<Message> = (1..20).map { i ->
      if (i % 3 == 0) {
        ConnectionInfoMessage(
          kind = ConnectionKind.WIFI_WPA2,
          ssid = "net-$i",
          password = "pw-$i",
          id = i,
        )
      } else {
        TextMessage(title = "msg-$i", text = "payload-$i".repeat(i), id = i)
      }
    }

    coroutineScope {
      val received = async { b.receive(count = messages.size, serializer) }
      launch { messages.forEach { a.send(it, serializer) } }
      val got = received.await()
      assertEquals(messages, got)
    }
  }
}
