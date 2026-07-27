package com.carlom.klardrop.common.ble

/**
 * The Klardrop identity carried by a BLE advertisement, once decoded.
 *
 * @param shortDeviceId The peer's 8-char [com.carlom.klardrop.common.discovery.CurrentDevice.shortDeviceId].
 *        This is the key every other layer (VisibleDevices, ConnectionsPool, TrustManager)
 *        uses to merge the peer across transports, so it must be exactly the value the
 *        peer publishes over mDNS too — never a synthesised substitute.
 * @param friendlyName Human-readable name from the advertisement, or null when the
 *        advertisement carries no name beyond the id.
 */
data class BleAdvertisement(
  val shortDeviceId: String,
  val friendlyName: String?,
)

/**
 * Single source of truth for what Klardrop puts **into** a BLE advertisement and how it
 * reads one back **out**. Every platform transport funnels its native scan callback
 * through [decode] and builds its advertisement from [klardropAdvertisePayload], so the
 * three radios can't drift apart in what they consider a valid peer.
 *
 * Why this needs to be shared: the encode and decode sides are a wire contract between
 * *different devices*, not an implementation detail of one app. When Android decided a
 * peer's id lived in service-data, Apple read the local name, and the macOS helper
 * required 8 hex chars, a Mac and an Android phone could both be advertising correctly
 * and still never see each other. Keeping both directions in one commonMain file means a
 * single `commonTest` covers the contract for all of them.
 *
 * Platform code is expected to do nothing but call its own system APIs: pull the raw AD
 * fields out of the native scan result, hand them here, and publish whatever
 * [klardropAdvertisePayload] describes that its API is able to express.
 */
object BleAdvertisementCodec {

  /**
   * Length of the advertised short device id.
   *
   * Fixed at 8 by two independent constraints that happen to agree:
   *  - [com.carlom.klardrop.common.discovery.CurrentDevice.shortDeviceId] is `deviceId.take(8)`.
   *  - The BLE advertisement budget. Apple can only express the id via the local name,
   *    and flags (3) + 128-bit service UUID (2 + 16) + local name (2 + n) fills the
   *    31-byte legacy advertisement exactly at n = 8. One char more and CoreBluetooth
   *    moves the service UUID into Apple's proprietary overflow area, where no Android
   *    scanner filtering on that UUID can see it.
   */
  const val SHORT_DEVICE_ID_LENGTH = 8

  /**
   * Characters a short device id may contain: exactly the alphabet
   * `CurrentDeviceProvider.cleanDeviceId` emits (a lowercased UUID stripped of anything
   * that isn't a letter or digit).
   */
  private fun Char.isShortDeviceIdChar(): Boolean = this in 'a'..'z' || this in '0'..'9'

  /**
   * Whether [value] has the shape of a Klardrop short device id.
   *
   * Advertisements are public and the local-name AD field is whatever the peer's
   * Bluetooth stack decided to put there, so an id is only trusted when it matches the
   * shape we know we generate. Without this check a nearby device's Bluetooth name
   * ("Galaxy A32") gets adopted as a peer identity, producing a ghost entry that never
   * merges with that device's real mDNS record and never resolves to anything.
   */
  fun isKlardropShortDeviceId(value: String?): Boolean {
    if (value == null || value.length != SHORT_DEVICE_ID_LENGTH) return false
    return value.all { it.isShortDeviceIdChar() }
  }

  /** Encode [shortDeviceId] for the service-data AD field. */
  fun encodeShortDeviceId(shortDeviceId: String): ByteArray {
    require(isKlardropShortDeviceId(shortDeviceId)) {
      "'$shortDeviceId' is not a valid Klardrop short device id " +
        "($SHORT_DEVICE_ID_LENGTH chars of [a-z0-9])"
    }
    return shortDeviceId.encodeToByteArray()
  }

  /**
   * Decode a scan result into a Klardrop peer identity, or null when the advertisement
   * isn't one of ours.
   *
   * Both AD fields are accepted because the two platform families can only express the
   * id in different places, and each reads what the other can write:
   *  - [serviceData] — the bytes stored under [BleConstants.SERVICE_UUID] in the
   *    service-data AD field. What Android peers advertise; Apple can read it but the
   *    CoreBluetooth peripheral API gives apps no way to write it.
   *  - [localName] — the local-name AD field. What Apple peers advertise; Android reads
   *    it via `ScanRecord.getDeviceName()` but its `AdvertiseData` builder has no
   *    setter for a custom one.
   *
   * Service-data wins when both are present: it is the channel only a Klardrop peer can
   * have populated under our service UUID.
   *
   * [BleAdvertisement.friendlyName] deliberately comes from the advertisement alone, and
   * is null when the local name is just the id echoed back. Callers must NOT substitute
   * the peer's GAP device name (`CBPeripheral.name` / `BluetoothDevice.name`) here: a
   * BLE-only peer whose `DeviceInfo.name` differs from its id stops looking like a
   * placeholder to [com.carlom.klardrop.common.communication.BleEagerConnector], which
   * then never opens the GATT session that carries the peer's real name, OS and device
   * type. A visible hash is a worse label but a recoverable state; a plausible-looking
   * wrong name is a permanent one.
   */
  fun decode(serviceData: ByteArray?, localName: String?): BleAdvertisement? {
    val fromServiceData = serviceData
      ?.takeIf { it.isNotEmpty() }
      ?.decodeToString()
      ?.takeIf { isKlardropShortDeviceId(it) }

    val shortDeviceId = fromServiceData
      ?: localName?.takeIf { isKlardropShortDeviceId(it) }
      ?: return null

    val friendlyName = localName?.takeIf { it.isNotBlank() && it != shortDeviceId }
    return BleAdvertisement(shortDeviceId = shortDeviceId, friendlyName = friendlyName)
  }
}
