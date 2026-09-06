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
import com.carlom.klardrop.common.communication.router.IncomingAuthorizer
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.mdns.FakeVisibleDevices
import com.carlom.klardrop.common.mdns.NearbyReceiverConnectionHandler
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.MessageReceiverImpl
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.google.location.nearby.connections.proto.ConnectionRequestFrame
import com.google.location.nearby.connections.proto.OfflineFrame
import com.google.location.nearby.connections.proto.V1Frame
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Tests for Server protocol detection logic.
 *
 * These tests verify that the protocol detection correctly identifies
 * Klardrop vs Nearby Share protocols based on message structure.
 */
class ServerTest {

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
    val server = createTestServer()
    val protocol = server.detectProtocol(payload)
    assertEquals(Server.Protocol.KLARDROP, protocol)
  }

  @Test
  fun testDetectKlardropProtocolWithEncryptionFlag() {
    // Wire-compat regression: a handshake carrying the new supportsEncryption field must still
    // be classified as KLARDROP (the field is appended last, so existing field numbers are
    // unchanged and detection only inspects the first cleartext handshake frame).
    val handshakeMessage = HandshakeMessage("test-device-id", supportsEncryption = true)
    val serializedHandshake = protoBuf.encodeToByteArray(HandshakeMessage.serializer(), handshakeMessage)
    val payload = byteArrayOf(MessageType.HANDSHAKE.id) + serializedHandshake

    val server = createTestServer()
    assertEquals(Server.Protocol.KLARDROP, server.detectProtocol(payload))
  }

  @Test
  fun testDetectKlardropProtocolWithListenPortAndClaimsTrust() {
    val handshakeMessage = HandshakeMessage(
      deviceId = "test-device-id",
      supportsEncryption = true,
      listenPort = 35199,
      claimsTrust = true,
    )
    val serializedHandshake = protoBuf.encodeToByteArray(HandshakeMessage.serializer(), handshakeMessage)
    val payload = byteArrayOf(MessageType.HANDSHAKE.id) + serializedHandshake

    val server = createTestServer()
    assertEquals(Server.Protocol.KLARDROP, server.detectProtocol(payload))
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
    val server = createTestServer()
    val protocol = server.detectProtocol(serializedRequest)
    assertEquals(Server.Protocol.NEARBY_SHARE, protocol)
  }

  @Test
  fun testDetectProtocolWithTooShortMessage() {
    assertFailsWith<IllegalArgumentException> {
      val server = createTestServer()
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
      val server = createTestServer()
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
      val server = createTestServer()
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
      val server = createTestServer()
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
        val server = createTestServer()
        val protocol = server.detectProtocol(payload)
        assertEquals(Server.Protocol.KLARDROP, protocol)
      }
    }
  }

  @Test
  fun testDetectNearbyShareProtocolWithRealDeviceCollisionFirstByte() {
    // Collision regression: real Nearby Share peers open the wire with a length-delimited
    // `version` message block (field 1, wire-type 2 → first byte 0x0A), which equals
    // MessageType.TRUST_PAIRING_REQUEST's id. Detection must fall through the failed
    // HandshakeMessage parse and classify the frame as NEARBY_SHARE.
    val frame = realDeviceNearbyConnectionRequest(
      // Version{ v1: V1{ min_version=1, max_version=1 } } — the shape real peers send.
      versionBlock = byteArrayOf(0x0A, 0x06, 0x0A, 0x04, 0x08, 0x01, 0x10, 0x01),
    )

    // Precondition: the crafted frame IS a valid Nearby CONNECTION_REQUEST OfflineFrame.
    assertEquals(V1Frame.FrameType.CONNECTION_REQUEST, OfflineFrame.ADAPTER.decode(frame).v1?.type)

    val server = createTestServer()
    assertEquals(Server.Protocol.NEARBY_SHARE, server.detectProtocol(frame))
  }

  @Test
  fun testValidNearbyFrameNeverDetectedAsKlardrop() {
    // Negative/false-positive guard: even when the bytes after a 0x0A first byte accidentally
    // parse as a HandshakeMessage, a valid Nearby CONNECTION_REQUEST frame must never be
    // classified KLARDROP. The version block below is sized so the Handshake parse walks the
    // whole frame cleanly (deviceId="AAAAAAAAA", deviceName=<v1 frame bytes>) and returns
    // successfully — without the 0x0A Nearby-first guard this frame IS misdetected as KLARDROP.
    val frame = realDeviceNearbyConnectionRequest(
      // 0x0A 0x0A: field-1 tag + length 10; content starts 0x09 so the Handshake parse reads a
      // 9-byte deviceId and lands exactly on the 0x12 deviceName tag of the v1 frame.
      versionBlock = byteArrayOf(0x0A, 0x0A, 0x09) + "AAAAAAAAA".encodeToByteArray(),
    )

    // Precondition: the crafted frame IS a valid Nearby CONNECTION_REQUEST OfflineFrame.
    assertEquals(V1Frame.FrameType.CONNECTION_REQUEST, OfflineFrame.ADAPTER.decode(frame).v1?.type)

    val server = createTestServer()
    assertNotEquals(Server.Protocol.KLARDROP, server.detectProtocol(frame))
    assertEquals(Server.Protocol.NEARBY_SHARE, server.detectProtocol(frame))
  }

  @Test
  fun testDetectProtocolWithGarbageInKlardropRangeThrowsNewMessage() {
    // Garbage whose first byte sits in the Klardrop MessageType range must be rejected by BOTH
    // parsers and surface the new "Unrecognized protocol" message naming the first byte.
    val server = createTestServer()

    val collisionByteGarbage = byteArrayOf(0x0A, 0x01, 0x02, 0x03) // 0x0A = TRUST_PAIRING_REQUEST id
    val exception = assertFailsWith<IllegalArgumentException> {
      server.detectProtocol(collisionByteGarbage)
    }
    assertEquals("Unrecognized protocol: first byte 0x0A", exception.message)

    val otherRangeByteGarbage = byteArrayOf(0x0E, 0x01, 0x02, 0x03) // 0x0E = TRUST_REVOCATION id
    val otherException = assertFailsWith<IllegalArgumentException> {
      server.detectProtocol(otherRangeByteGarbage)
    }
    assertEquals("Unrecognized protocol: first byte 0x0E", otherException.message)
  }

  /**
   * Builds a first message exactly as real Nearby Share peers send it: the OfflineFrame
   * `version` field travels as a length-delimited message block (field 1, wire-type 2 → first
   * byte 0x0A), unlike this repo's enum-typed proto whose encoding starts 0x08. Wire's parser
   * skips the unknown-shaped field 1 and still decodes the v1 frame, so the result is a valid
   * CONNECTION_REQUEST OfflineFrame for detection purposes.
   */
  private fun realDeviceNearbyConnectionRequest(versionBlock: ByteArray): ByteArray {
    val v1Frame = V1Frame(
      type = V1Frame.FrameType.CONNECTION_REQUEST,
      connection_request = ConnectionRequestFrame(
        endpoint_info = "test-endpoint-info".encodeToByteArray().toByteString(),
        endpoint_name = "Test Device"
      )
    ).encode()
    return versionBlock + byteArrayOf(0x12, v1Frame.size.toByte()) + v1Frame
  }

}

internal class ServerTestFakeFileManager : FileManager {
  override fun prepareSaveFile(fileName: String, mimeType: String): FileTransfer = error("Not needed for protocol detection test")
  override fun getReadStreamFrom(file: PlatformFile): kotlinx.io.Source = error("Not needed for protocol detection test")
  override suspend fun openFile(filePath: String): Boolean = error("Not needed for protocol detection test")
  override suspend fun openUrl(url: String): Boolean = error("Not needed for protocol detection test")
}

internal fun createTestServer(
  connectionsPool: ConnectionsPool = FakeConnectionPool(),
  fileManager: FileManager = ServerTestFakeFileManager(),
  coroutines: TestCoroutines = TestCoroutines(),
  localPropertiesRepository: LocalPropertiesRepository = FakeLocalPropertiesRepository(),
  visibleDevices: VisibleDevices = FakeVisibleDevices(),
  messageReceiver: MessageReceiver = MessageReceiverImpl(coroutines, visibleDevices),
  messagesRouter: MessagesRouter = FakeMessagesRouter(),
  preferredPort: Int = 0,
): Server {
  val currentDeviceProvider = CurrentDeviceProvider(localPropertiesRepository)

  val trustManager = com.carlom.klardrop.common.trust.TrustManager(
    crypto = com.carlom.klardrop.common.trust.TrustCrypto(),
    storage = object : com.carlom.klardrop.common.trust.TrustStorage {
      override suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray) {}
      override suspend fun storeECDSAKey(deviceId: String, ecdsaPublicKey: ByteArray) {}
      override suspend fun getTrustedDeviceKey(deviceId: String): ByteArray? = null
      override suspend fun getECDSAKey(deviceId: String): ByteArray? = null
      override suspend fun getAllTrustedDevices(): Map<String, ByteArray> = emptyMap()
      override suspend fun removeTrustedDevice(deviceId: String) {}
      override suspend fun clearAllTrustedDevices() {}
      override suspend fun storeDevicePrivateKey(privateKey: ByteArray) {}
      override suspend fun getDevicePrivateKey(): ByteArray? = null
      override suspend fun storeDevicePublicKey(publicKey: ByteArray) {}
      override suspend fun getDevicePublicKey(): ByteArray? = null
      override suspend fun deleteDevicePrivateKey() {}
    },
    clock = com.carlom.klardrop.common.utils.Clock(),
    currentDeviceProvider = currentDeviceProvider,
  )

  // Always-accept authorizer — these tests exercise protocol detection only,
  // they never reach the receive pipeline that invokes it.
  val authorizer = object : IncomingAuthorizer(trustManager) {
    override suspend fun authorize(
      fromDeviceId: String,
      kind: TransferKind,
      headers: List<com.carlom.klardrop.common.communication.message.Message>,
      receiveFlow: kotlinx.coroutines.flow.MutableStateFlow<com.carlom.klardrop.common.receiver.ReceiveMessageUpdate>,
      notifyAwaitingUser: suspend () -> Unit,
    ): Boolean = true
  }

  return Server(
    connectionsPool = connectionsPool,
    coroutines = coroutines,
    messagesRouter = messagesRouter,
    serializer = MessageSerializer(ProtoBuf, coroutines),
    currentDeviceProvider = currentDeviceProvider,
    createNearbyReceiver = {
      NearbyReceiverConnectionHandler(
        fileManager,
        coroutines,
        authorizer,
        messageRepository = object : com.carlom.klardrop.common.persistence.MessageRepository {
          override suspend fun insertMessage(remoteDeviceId: String, content: String, isSender: Boolean, messageType: com.carlom.klardrop.common.persistence.MessageType, fileTransferId: Long?, isRead: Boolean, mimeType: String, messageId: Long?, sendStatus: com.carlom.klardrop.common.persistence.SendStatus): Long = 0L
          override suspend fun insertFileTransfer(fileName: String, filePath: String, totalSize: Long, status: com.carlom.klardrop.common.persistence.FileTransferStatus, mimeType: String): Long = 0L
          override suspend fun updateFileTransferStatus(id: Long, status: com.carlom.klardrop.common.persistence.FileTransferStatus) {}
          override suspend fun updateFileTransferFilePath(id: Long, filePath: String) {}
          override suspend fun markStaleInProgressAsFailed() {}
          override suspend fun markMessagesAsRead(remoteDeviceId: String) {}
          override suspend fun getUnreadCountForDevice(remoteDeviceId: String): Long = 0L
          override fun getAllDevicesWithUnreadCounts(): kotlinx.coroutines.flow.Flow<Map<String, Long>> = kotlinx.coroutines.flow.flowOf(emptyMap())
          override fun getMessagesForDevice(remoteDeviceId: String, limit: Long): kotlinx.coroutines.flow.Flow<List<com.carlom.klardrop.common.persistence.ChatMessage>> = kotlinx.coroutines.flow.flowOf(emptyList())
          override fun getFileTransferById(id: Long): kotlinx.coroutines.flow.Flow<com.carlom.klardrop.common.database.File_transfers?> = kotlinx.coroutines.flow.flowOf(null)
        },
      )
    },
    visibleDevices = visibleDevices,
    messageReceiver = messageReceiver,
    protoBuf = ProtoBuf,
    trustManager = trustManager,
    preferredPort = preferredPort,
  )
}