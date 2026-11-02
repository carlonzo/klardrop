package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
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
internal data class HandshakeMessage(
  internal val deviceId: String,
  internal val deviceName: String = "",
  internal val osType: OsType = OsType.UNKNOWN,
  internal val deviceType: DeviceType = DeviceType.UNKNOWN,
  override val id: Int = Random.nextInt(),
  internal val supportsEncryption: Boolean = false,
  /**
   * The sender's TCP listen port so an inbound connection can re-seed VisibleDevices with a
   * dialable endpoint. 0 = unknown (older peers, BLE). Appended after [supportsEncryption]
   * so existing field numbers stay stable.
   */
  internal val listenPort: Int = 0,
  /**
   * True when the sender currently holds a trust entry for the recipient. Combined with the
   * recipient's local store this is how a reconnecting peer learns it has been unpaired
   * without waiting for the next TrustedMessage.
   */
  internal val claimsTrust: Boolean = false,
  /**
   * The sender's Tailscale (100.64.0.0/10) IPv4 addresses, so a peer met on the LAN can later
   * be dialed over the tailnet. Empty when the sender isn't on a tailnet or is an older build.
   *
   * Field numbers from here on are pinned: [type] and [hasPayload] below are serialized too, after
   * the constructor properties, so an auto-numbered new field would shift them and older peers
   * would fail to decode the handshake.
   */
  @ProtoNumber(11)
  internal val tailnetAddresses: List<String> = emptyList(),
) : Message() {
  @ProtoNumber(9)
  override val type: MessageType = MessageType.HANDSHAKE
  @ProtoNumber(10)
  override val hasPayload: Boolean = false
}

