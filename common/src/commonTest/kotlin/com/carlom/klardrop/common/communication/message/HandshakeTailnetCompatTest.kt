package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals

class HandshakeTailnetCompatTest {

  /** HandshakeMessage as shipped before [HandshakeMessage.tailnetAddresses] existed. */
  @Serializable
  private data class OldHandshake(
    val deviceId: String,
    val deviceName: String = "",
    val osType: OsType = OsType.UNKNOWN,
    val deviceType: DeviceType = DeviceType.UNKNOWN,
    val id: Int = 0,
    val supportsEncryption: Boolean = false,
    val listenPort: Int = 0,
    val claimsTrust: Boolean = false,
    // Body properties of the real class; serialized after the constructor ones.
    val type: MessageType = MessageType.HANDSHAKE,
    val hasPayload: Boolean = false,
  )

  @Test
  fun olderPeersStillDecodeTheNewHandshake() {
    val bytes = ProtoBuf.encodeToByteArray(
      HandshakeMessage(deviceId = "abcd1234", supportsEncryption = true, listenPort = 47470, tailnetAddresses = listOf("100.64.0.2")),
    )
    val old = ProtoBuf.decodeFromByteArray<OldHandshake>(bytes)
    assertEquals("abcd1234", old.deviceId)
    assertEquals(47470, old.listenPort)
    assertEquals(MessageType.HANDSHAKE, old.type)
  }

  @Test
  fun handshakeFromOlderPeersHasNoTailnetAddresses() {
    val bytes = ProtoBuf.encodeToByteArray(OldHandshake(deviceId = "abcd1234", supportsEncryption = true))
    assertEquals(emptyList(), ProtoBuf.decodeFromByteArray<HandshakeMessage>(bytes).tailnetAddresses)
  }

  @Test
  fun tailnetAddressesRoundTrip() {
    val sent = HandshakeMessage(deviceId = "abcd1234", tailnetAddresses = listOf("100.64.0.2", "100.100.3.4"))
    assertEquals(sent, ProtoBuf.decodeFromByteArray<HandshakeMessage>(ProtoBuf.encodeToByteArray(sent)))
  }
}
