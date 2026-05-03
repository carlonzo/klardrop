package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.utils.Coroutines
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.DeviceType
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Round-trip tests for [ConnectionInfoMessage]: serialize via [MessageSerializer], then
 * deserialize and assert equality. Also covers the handler's read/write behaviour against
 * in-memory Ktor byte channels.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionInfoMessageHandlerTest {

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

  @Test
  fun serializesAndDeserializesWpa2Credentials() = runTest(testDispatcher) {
    val original = ConnectionInfoMessage(
      kind = ConnectionKind.WIFI_WPA2,
      ssid = "HomeNetwork",
      password = "secret-pass-123",
      hidden = false,
      id = 42,
    )

    val bytes = serializer.serialize(original)
    val decoded = serializer.deserialize(bytes)

    assertIs<ConnectionInfoMessage>(decoded)
    assertEquals(original, decoded)
  }

  @Test
  fun serializesOpenNetworkWithNoPassword() = runTest(testDispatcher) {
    val original = ConnectionInfoMessage(
      kind = ConnectionKind.WIFI_OPEN,
      ssid = "GuestNetwork",
      password = null,
      hidden = false,
      id = 1,
    )

    val decoded = serializer.deserialize(serializer.serialize(original))

    assertIs<ConnectionInfoMessage>(decoded)
    assertEquals(original, decoded)
    assertNull(decoded.password)
  }

  @Test
  fun serializesHiddenNetwork() = runTest(testDispatcher) {
    val original = ConnectionInfoMessage(
      kind = ConnectionKind.WIFI_WPA3,
      ssid = "Hidden-SSID",
      password = "topsecret",
      hidden = true,
      id = 99,
    )

    val decoded = serializer.deserialize(serializer.serialize(original))

    assertIs<ConnectionInfoMessage>(decoded)
    assertEquals(original, decoded)
    assertEquals(true, decoded.hidden)
  }

  @Test
  fun allConnectionKindsRoundTrip() = runTest(testDispatcher) {
    ConnectionKind.entries.forEach { kind ->
      val original = ConnectionInfoMessage(
        kind = kind,
        ssid = "ssid-for-$kind",
        password = "pw",
        id = kind.ordinal,
      )
      val decoded = serializer.deserialize(serializer.serialize(original))
      assertIs<ConnectionInfoMessage>(decoded)
      assertEquals(original, decoded, "mismatch for kind=$kind")
    }
  }

  @Test
  fun serializedPayloadBeginsWithConnectionInfoMessageTypeId() = runTest(testDispatcher) {
    val original = ConnectionInfoMessage(
      kind = ConnectionKind.WIFI_WPA2,
      ssid = "t",
      password = "p",
      id = 0,
    )
    val bytes = serializer.serialize(original)
    assertEquals(MessageType.CONNECTION_INFO.id, bytes[0])
  }

  @Test
  fun handleIncomingUpdatesReceiveFlowToCompleted() = runTest(testDispatcher) {
    val handler = ConnectionInfoMessageHandler(serializer)
    val message = ConnectionInfoMessage(
      kind = ConnectionKind.WIFI_WPA2,
      ssid = "RouterX",
      password = "abc123",
      id = 7,
    )
    val receiveFlow = MutableStateFlow(
      ReceiveMessageUpdate(
        device = DeviceInfo("peer-01", "Peer", DeviceType.MOBILE),
        status = ReceiveMessageStatus.Started,
      )
    )

    // handleIncoming doesn't read from the channel (hasPayload=false), but still requires one.
    val emptyChannel: ByteReadChannel = ByteReadChannel(ByteArray(0))
    handler.handleIncoming(message, emptyChannel, receiveFlow)

    assertIs<ReceiveMessageStatus.Completed>(receiveFlow.value.status)
    assertEquals(listOf(message), receiveFlow.value.messages)
  }

  @Test
  fun handleOutgoingWritesLengthPrefixedFrame() = runTest(testDispatcher) {
    val handler = ConnectionInfoMessageHandler(serializer)
    val message = ConnectionInfoMessage(
      kind = ConnectionKind.WIFI_WPA2,
      ssid = "CafeWiFi",
      password = "latte123",
      id = 11,
    )
    val channel = ByteChannel(autoFlush = true)
    val progress = MutableSharedFlow<com.carlom.klardrop.common.communication.MessengerSendProgress>()

    handler.handleOutgoing("peer-01", SimpleSendMessageRequest(message), channel, progress)
    channel.flushAndClose()

    val allBytes = channel.readRemaining().readByteArray()
    // First 4 bytes are the big-endian frame length; bytes 5..end are serialized message.
    val frameLength = ((allBytes[0].toInt() and 0xFF) shl 24) or
      ((allBytes[1].toInt() and 0xFF) shl 16) or
      ((allBytes[2].toInt() and 0xFF) shl 8) or
      (allBytes[3].toInt() and 0xFF)
    assertEquals(allBytes.size - 4, frameLength)

    val payload = allBytes.copyOfRange(4, allBytes.size)
    val decoded = serializer.deserialize(payload)
    assertIs<ConnectionInfoMessage>(decoded)
    assertEquals(message, decoded)
  }
}
