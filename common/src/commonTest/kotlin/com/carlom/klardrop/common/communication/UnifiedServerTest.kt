package com.carlom.klardrop.common.communication

import FakeLocalPropertiesRepository
import TestCoroutines
import com.carlom.klardrop.common.FakeConnectionPool
import com.carlom.klardrop.common.FakeMessagesRouter
import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.FileTransfer
import com.carlom.klardrop.common.communication.di.CommunicationModule
import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.mdns.FakeVisibleDevices
import com.carlom.klardrop.common.mdns.NearbyReceiverConnectionHandlerFactory
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.MessageReceiverImpl
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.utils.UtilsModule
import com.google.location.nearby.connections.proto.ConnectionRequestFrame
import com.google.location.nearby.connections.proto.OfflineFrame
import com.google.location.nearby.connections.proto.V1Frame
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests for UnifiedServer protocol detection logic.
 *
 * These tests verify that the protocol detection correctly identifies
 * Klardrop vs Nearby Share protocols based on message structure.
 */
class UnifiedServerTest {

  private val protoBuf = ProtoBuf


  @Test
  fun testDetectKlardropProtocol() {
    // Create a Klardrop handshake message
    val handshakeMessage = HandshakeMessage("test-device-id")
    val serializedHandshake = protoBuf.encodeToByteArray(HandshakeMessage.serializer(), handshakeMessage)

    // Create the payload (message type + protobuf data)
    val messageType = MessageType.HANDSHAKE.id
    val payload = byteArrayOf(messageType) + serializedHandshake

    // Test protocol detection using production code
    val server = createTestUnifiedServer()
    val protocol = server.detectProtocol(payload)
    assertEquals(UnifiedServer.Protocol.KLARDROP, protocol)
  }

  @Test
  fun testDetectNearbyShareProtocol() {
    // Create a Nearby Share connection request
    val connectionRequest = OfflineFrame(
      version = OfflineFrame.Version.V1,
      v1 = V1Frame(
        type = V1Frame.FrameType.CONNECTION_REQUEST,
        connection_request = ConnectionRequestFrame(
          endpoint_info = "test-endpoint-info".encodeToByteArray().toByteString(),
          endpoint_name = "Test Device"
        )
      )
    )

    val serializedRequest = connectionRequest.encode()

    // The production method expects just the payload, not the complete message

    // Test protocol detection using production code
    val server = createTestUnifiedServer()
    val protocol = server.detectProtocol(serializedRequest)
    assertEquals(UnifiedServer.Protocol.NEARBY_SHARE, protocol)
  }

  @Test
  fun testDetectProtocolWithTooShortMessage() {
    assertFailsWith<IllegalArgumentException> {
      val server = createTestUnifiedServer()
      server.detectProtocol(byteArrayOf()) // Empty payload
    }
  }

  @Test
  fun testDetectProtocolWithInvalidKlardropMessage() {
    // Create a message that looks like Klardrop (starts with message type 0)
    // but has invalid protobuf payload
    val invalidPayload = byteArrayOf(0, 1, 2, 3, 4) // Invalid protobuf
    val messageType = MessageType.HANDSHAKE.id
    val payload = byteArrayOf(messageType) + invalidPayload

    // Should fail to detect any protocol
    assertFailsWith<IllegalArgumentException> {
      val server = createTestUnifiedServer()
      server.detectProtocol(payload)
    }
  }

  @Test
  fun testDetectProtocolWithInvalidNearbyShareMessage() {
    // Create a message that doesn't match Klardrop pattern
    // but also isn't valid Nearby Share
    val invalidPayload = byteArrayOf(42, 1, 2, 3, 4) // Invalid protobuf starting with non-Klardrop type

    // Should fail to detect any protocol
    assertFailsWith<IllegalArgumentException> {
      val server = createTestUnifiedServer()
      server.detectProtocol(invalidPayload)
    }
  }

  @Test
  fun testDetectNearbyShareProtocolWithWrongFrameType() {
    // Create a Nearby Share message that's not a CONNECTION_REQUEST
    val offlineFrame = OfflineFrame(
      version = OfflineFrame.Version.V1,
      v1 = V1Frame(
        type = V1Frame.FrameType.KEEP_ALIVE, // Wrong frame type
        keep_alive = com.google.location.nearby.connections.proto.KeepAliveFrame(ack = false)
      )
    )

    val serializedFrame = offlineFrame.encode()

    // Should fail to detect as Nearby Share since it's not a CONNECTION_REQUEST
    assertFailsWith<IllegalArgumentException> {
      val server = createTestUnifiedServer()
      server.detectProtocol(serializedFrame)
    }
  }

  @Test
  fun testKlardropProtocolBoundaryValues() {
    // Test all valid Klardrop message types
    for (messageTypeId in MessageType.HANDSHAKE.id..MessageType.FILE.id) {
      val handshakeMessage = HandshakeMessage("boundary-test-device")
      val serializedHandshake = protoBuf.encodeToByteArray(HandshakeMessage.serializer(), handshakeMessage)

      val payload = byteArrayOf(messageTypeId.toByte()) + serializedHandshake

      // Should detect as Klardrop for HANDSHAKE type, might fail for others due to wrong message structure
      if (messageTypeId.toByte() == MessageType.HANDSHAKE.id) {
        val server = createTestUnifiedServer()
        val protocol = server.detectProtocol(payload)
        assertEquals(UnifiedServer.Protocol.KLARDROP, protocol)
      }
    }
  }

}

internal class UnifiedServerTestFakeFileManager : FileManager {
  override fun prepareSaveFile(fileName: String, mimeType: String): FileTransfer = error("Not needed for protocol detection test")
  override fun getReadStreamFrom(file: PlatformFile): kotlinx.io.Source = error("Not needed for protocol detection test")
}

internal fun createTestUnifiedServer(
  connectionsPool: ConnectionsPool = FakeConnectionPool(),
  fileManager: FileManager = UnifiedServerTestFakeFileManager(),
  coroutines: TestCoroutines = TestCoroutines(),
  localPropertiesRepository: LocalPropertiesRepository = FakeLocalPropertiesRepository(),
  visibleDevices: VisibleDevices = FakeVisibleDevices(),
  messageReceiver: MessageReceiver = MessageReceiverImpl(coroutines, visibleDevices),
  messagesRouter: MessagesRouter = FakeMessagesRouter(),
): UnifiedServer {
  val currentDeviceProvider = CurrentDeviceProvider(localPropertiesRepository)

  return UnifiedServer(
    connectionsPool = connectionsPool,
    coroutines = coroutines,
    messagesRouter = messagesRouter,
    serializer = MessageSerializer(ProtoBuf, coroutines),
    currentDeviceProvider = currentDeviceProvider,
    nearbyReceiverConnectionHandlerFactory = NearbyReceiverConnectionHandlerFactory(
      fileManager,
      coroutines
    ),
    visibleDevices = visibleDevices,
    messageReceiver = messageReceiver,
    protoBuf = ProtoBuf
  )
}