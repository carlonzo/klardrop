package com.carlom.klardrop.common.ble

/**
 * Describes the contents of a BLE advertisement: what goes in the **primary**
 * 31-byte AD packet vs the **scan response** (an additional 31-byte payload
 * jmDNS-style scanners merge with the primary).
 *
 * Kept as a pure-Kotlin data class so the wire-format decision is testable in
 * `commonTest` without instantiating Android `AdvertiseData` / iOS `CBAdvert*`
 * types. The platform implementations translate this descriptor into native
 * BLE advertise data.
 *
 * The single most common BLE bug this guards against: putting the service UUID
 * only inside a Service-Data AD field (type `0x21`) while a peer's scan filter
 * looks for it in the standard Service Class UUIDs field (type `0x07`). Both
 * sides must agree on which AD type carries the UUID — this descriptor pins
 * that decision once.
 */
data class BleAdvertisePayload(
  /** AD records that go in the primary advertisement packet (≤31 bytes total). */
  val primary: AdRecords,
  /**
   * AD records that go in the scan response (≤31 bytes total). Null when the
   * platform does not allow a scan response or when nothing extra needs to be
   * advertised. iOS / macOS CoreBluetooth chooses this automatically; Android
   * must opt in by passing both buffers to `BluetoothLeAdvertiser.startAdvertising`.
   */
  val scanResponse: AdRecords?,
) {

  data class AdRecords(
    /**
     * Service Class UUIDs (AD type `0x07` for 128-bit). What `setServiceUuid`
     * scan filters match against. **Always include this for Klardrop's primary
     * AD** so peers can find us via the standard filter mechanism.
     */
    val serviceUuids: List<String> = emptyList(),
    /**
     * Service Data (AD type `0x21` for 128-bit-keyed service data). Lets peers
     * read short app-specific payloads without opening a GATT connection.
     */
    val serviceData: Map<String, ByteArray> = emptyMap(),
    /**
     * Local name (AD type `0x09`). Apple's only writable channel for app data, since
     * `CBPeripheralManager` refuses custom service-data. Android adapters ignore this:
     * `AdvertiseData.Builder` can only include the *system* Bluetooth name, never a
     * custom one.
     */
    val localName: String? = null,
  ) {
    /**
     * Approximate byte cost of these AD records in the BLE packet. Excludes the
     * outer flags AD (3 bytes) which the BLE stack adds automatically. Used for
     * unit-test budget assertions.
     */
    val approxBytes: Int
      get() {
        var total = 0
        for (uuid in serviceUuids) {
          total += 2 + 16 // 1-byte length + 1-byte type + 16-byte UUID
        }
        for ((uuid, data) in serviceData) {
          total += 2 + 16 + data.size
        }
        if (localName != null) total += 2 + localName.encodeToByteArray().size
        return total
      }
  }

  companion object {
    /**
     * Hard limit for a single legacy BLE advertisement packet (excluding the
     * 3-byte flags AD that the stack prepends).
     */
    const val LEGACY_AD_BUDGET_BYTES = 28
  }
}

/**
 * Builds the canonical Klardrop BLE advertisement — the one description of what we
 * broadcast, shared by every platform so no radio can drift from the others.
 *
 *  - **primary**: the service UUID (so scanners filtering by UUID find us at all) plus
 *    the shortDeviceId as the local name.
 *  - **scan response**: the shortDeviceId as service-data.
 *
 * The id is deliberately in *both* AD fields because neither platform family can write
 * both, and each reads what the other writes — see [BleAdvertisementCodec.decode]:
 *
 * | Field         | Android write            | Apple write | Android read | Apple read |
 * |---------------|--------------------------|-------------|--------------|------------|
 * | service UUID  | yes                      | yes         | scan filter  | scan filter|
 * | service-data  | yes (scan response)      | **no**      | yes          | yes        |
 * | local name    | **no**                   | yes         | yes          | yes        |
 *
 * Platform adapters publish the records their native API can express and ignore the
 * rest; the two "no" cells are hard API limits, not choices:
 * `AdvertiseData.Builder` has no custom-local-name setter (only
 * `setIncludeDeviceName`, which sends the *system* Bluetooth name), and
 * `CBPeripheralManager.startAdvertising` documents exactly two supported keys —
 * service UUIDs and local name — silently dropping anything else.
 *
 * @throws IllegalArgumentException if [shortDeviceId] isn't a well-formed short device id.
 */
fun klardropAdvertisePayload(shortDeviceId: String): BleAdvertisePayload {
  val serviceDataBytes = BleAdvertisementCodec.encodeShortDeviceId(shortDeviceId)
  return BleAdvertisePayload(
    primary = BleAdvertisePayload.AdRecords(
      serviceUuids = listOf(BleConstants.SERVICE_UUID),
      localName = shortDeviceId,
    ),
    scanResponse = BleAdvertisePayload.AdRecords(
      serviceData = mapOf(
        BleConstants.SERVICE_UUID to serviceDataBytes,
      ),
    ),
  )
}
