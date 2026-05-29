package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * First message exchanged on every Klardrop connection (TCP and BLE). Carries the
 * peer's stable [deviceId] plus optional rich identity ([deviceName], [osType],
 * [deviceType]) so receivers can enrich the discovered-device entry without that
 * info ever appearing in public BLE advertisements.
 *
 * The new fields default to empty/UNKNOWN, which keeps wire compatibility with
 * older peers that only emit [deviceId].
 *
 * [supportsEncryption] advertises whether this peer speaks the UKEY2-encrypted
 * Klardrop transport. It is appended LAST so the positional protobuf field numbers
 * of the existing fields are unchanged — older peers simply omit it and it decodes
 * to `false`, which the TCP path treats as "does not support encryption". The
 * field is set to `true` only on the TCP handshake (Client/Server); the BLE path
 * leaves it `false` because BLE transfers stay cleartext for now.
 */
@Serializable
data class HandshakeMessage(
  val deviceId: String,
  val deviceName: String = "",
  val osType: OsType = OsType.UNKNOWN,
  val deviceType: DeviceType = DeviceType.UNKNOWN,
  override val id: Int = Random.nextInt(),
  val supportsEncryption: Boolean = false,
) : Message() {
  override val type: MessageType = MessageType.HANDSHAKE
  override val hasPayload: Boolean = false
}

