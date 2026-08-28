package com.carlom.klardrop.common.ble.linux

/**
 * Seam for every BlueZ interaction the Linux BLE transport needs. The real
 * implementation talks to org.bluez over the D-Bus system bus ([BlueZConnection]);
 * every later BLE todo fakes this interface in tests.
 */
interface BlueZFacade {

  /** Probes the system bus for adapters usable for both GATT client and peripheral roles. */
  suspend fun probeCapability(): BlueZCapability
}

/** Result of probing BlueZ for BLE capability. */
data class BlueZCapability(
  /** True when at least one adapter exposes both GattManager1 and LEAdvertisingManager1. */
  val supported: Boolean,
  /** Object paths of the fully capable adapters (e.g. /org/bluez/hci0). */
  val adapterPaths: List<String>,
)
