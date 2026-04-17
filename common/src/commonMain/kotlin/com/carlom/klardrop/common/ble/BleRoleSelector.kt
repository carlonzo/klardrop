package com.carlom.klardrop.common.ble

/**
 * Deterministic central/peripheral role selection for the BLE transport.
 *
 * Both Klardrop peers advertise and scan simultaneously, so each side can see the other.
 * To avoid a connect race (both sides dialing at once), the device with the
 * lexicographically smaller `shortDeviceId` acts as the BLE central (initiator). The
 * other side waits for the incoming GATT connection.
 *
 * The comparison is simple and stable — any two devices will always agree on who
 * initiates, regardless of which discovers the other first.
 */
object BleRoleSelector {

  /**
   * Returns true if the local device should initiate the BLE GATT connection to [peerShortDeviceId].
   * Returns false if the peer should be the initiator.
   *
   * @throws IllegalArgumentException if both ids are equal (duplicate device id — impossible
   * for two different devices, and a sign of a configuration bug).
   */
  fun shouldInitiate(selfShortDeviceId: String, peerShortDeviceId: String): Boolean {
    require(selfShortDeviceId != peerShortDeviceId) {
      "Both devices advertise the same shortDeviceId '$selfShortDeviceId'"
    }
    return selfShortDeviceId < peerShortDeviceId
  }
}
