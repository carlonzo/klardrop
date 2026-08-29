package com.carlom.klardrop.common.ble.linux

import com.carlom.klardrop.common.ble.BleChannelBridge
import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.message.ConnectionInfoMessage
import com.carlom.klardrop.common.communication.message.ConnectionKind
import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.readMessage
import com.carlom.klardrop.common.communication.sendMessage
import com.carlom.klardrop.common.utils.Coroutines
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Wire-format compatibility suite: the exact semantics proven in commonTest's
 * `BleChannelBridgeTest` (LoopbackBleSession pair) re-run against a real
 * [LinuxBleSession] pair — the session class the BlueZ peripheral/central roles hand
 * to [BleChannelBridge] in production. Two bridges are wired back-to-back over the
 * session pair, so a `sendMessage` on one side's write channel must arrive on the other
 * side's read channel after MTU chunking — zero radio, zero D-Bus.
 *
 * The loopback wiring mirrors the fake facade's radio simulation: a notify on one
 * session is the other session's incoming GATT write, and closing one side propagates
 * via [LinuxBleSession.markRemoteClosed] — exactly what the facade's
 * unsubscribe/disconnect callbacks do in production.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinuxBleSessionBridgeCompatibilityTest {

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

  /** Two [LinuxBleSession]s wired back-to-back the way the two BlueZ roles are. */
  private fun pairedSessions(mtu: Int): Pair<LinuxBleSession, LinuxBleSession> {
    lateinit var b: LinuxBleSession
    val a = LinuxBleSession(deviceId = "peerB", mtu = mtu, notify = { b.pushIncoming(it) })
    b = LinuxBleSession(deviceId = "peerA", mtu = mtu, notify = { a.pushIncoming(it) })
    return a to b
  }

  @Test
  fun forwardsRawBytesInOrder() = runTest(testDispatcher, timeout = 5.seconds) {
    coroutineScope {
      val (a, b) = pairedSessions(mtu = 8)
      val aBridge = BleChannelBridge(a, this).start()
      val bBridge = BleChannelBridge(b, this).start()

      val payload = ByteArray(50) { (it + 1).toByte() }
      aBridge.writeChannel.sendMessage(HandshakeMessage("xxxxxxxx"), serializer)
      // Raw bytes below the sendMessage layer must forward just as faithfully:
      aBridge.writeChannel.apply { writeByteArray(payload); flush() }

      val handshake = bBridge.readChannel.readMessage(serializer) as HandshakeMessage
      assertEquals("xxxxxxxx", handshake.deviceId)

      val received = ByteArray(50)
      var offset = 0
      while (offset < received.size) {
        offset += bBridge.readChannel.readAvailable(received, offset, received.size - offset).coerceAtLeast(1)
      }
      assertEquals(payload.toList(), received.toList())

      a.close()
      b.close()
    }
  }

  @Test
  fun sendMessageRoundTripsAcrossBridge() = runTest(testDispatcher, timeout = 5.seconds) {
    coroutineScope {
      val (a, b) = pairedSessions(mtu = 23)
      val aBridge = BleChannelBridge(a, this).start()
      val bBridge = BleChannelBridge(b, this).start()

      val wifi = ConnectionInfoMessage(
        kind = ConnectionKind.WIFI_WPA2,
        ssid = "HomeRouter-TestNet",
        password = "p@ssw0rd-but-longer-than-one-chunk-should-allow",
        hidden = false,
        id = 42,
      )

      val received = async {
        withTimeout(3.seconds) { bBridge.readChannel.readMessage(serializer) }
      }

      aBridge.writeChannel.sendMessage(wifi, serializer)

      val got = received.await()
      assertIs<ConnectionInfoMessage>(got)
      assertEquals(wifi, got)

      a.close()
      b.close()
    }
  }

  @Test
  fun bidirectionalExchangeHandshakeBothWays() = runTest(testDispatcher, timeout = 5.seconds) {
    coroutineScope {
      val (a, b) = pairedSessions(mtu = 20)
      val aBridge = BleChannelBridge(a, this).start()
      val bBridge = BleChannelBridge(b, this).start()

      val aSentHandshake = HandshakeMessage("device-a1")
      val bSentHandshake = HandshakeMessage("device-b2")

      val bReceived = async { bBridge.readChannel.readMessage(serializer) }
      val aReceived = async { aBridge.readChannel.readMessage(serializer) }

      launch { aBridge.writeChannel.sendMessage(aSentHandshake, serializer) }
      launch { bBridge.writeChannel.sendMessage(bSentHandshake, serializer) }

      assertEquals(aSentHandshake, bReceived.await())
      assertEquals(bSentHandshake, aReceived.await())

      a.close()
      b.close()
    }
  }

  @Test
  fun manyMessagesInSequencePreserveOrdering() = runTest(testDispatcher, timeout = 10.seconds) {
    coroutineScope {
      val (a, b) = pairedSessions(mtu = 40)
      val aBridge = BleChannelBridge(a, this).start()
      val bBridge = BleChannelBridge(b, this).start()

      val sent = (1..15).map { i ->
        TextMessage(title = "msg-$i", text = "body-$i".repeat(i * 3), id = i)
      }

      val received = async {
        sent.map { bBridge.readChannel.readMessage(serializer) }
      }

      for (m in sent) aBridge.writeChannel.sendMessage(m, serializer)

      assertEquals(sent, received.await())

      a.close()
      b.close()
    }
  }

  @Test
  fun sessionCloseSurfacesAsClosedChannels() = runTest(testDispatcher, timeout = 5.seconds) {
    val scope = TestScope(testDispatcher)
    val (a, b) = pairedSessions(mtu = 32)
    val aBridge = BleChannelBridge(a, scope).start()
    val bBridge = BleChannelBridge(b, scope).start()

    // Exchange one message so pumps are hot.
    val hello = TextMessage(title = "hi", text = "hello", id = 1)
    val received = scope.async { bBridge.readChannel.readMessage(serializer) }
    aBridge.writeChannel.sendMessage(hello, serializer)
    testDispatcher.scheduler.advanceUntilIdle()
    assertEquals(hello, received.await())

    // Peer A drops the link. In production the facade fires the unsubscribe/disconnect
    // callback, which marks the surviving side's session closed — mirror that here.
    a.close()
    b.markRemoteClosed()
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(a.isOpen)
    assertFalse(b.isOpen)
    assertTrue(bBridge.readChannel.isClosedForRead, "B's read channel must close after the remote dropped")
  }
}
