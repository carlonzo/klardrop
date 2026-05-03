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
 */
@Serializable
data class HandshakeMessage(
  val deviceId: String,
  val deviceName: String = "",
  val osType: OsType = OsType.UNKNOWN,
  val deviceType: DeviceType = DeviceType.UNKNOWN,
  override val id: Int = Random.nextInt(),
) : Message() {
  override val type: MessageType = MessageType.HANDSHAKE
  override val hasPayload: Boolean = false
}

