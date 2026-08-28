package com.carlom.klardrop.common.ble.linux

import com.carlom.klardrop.common.ble.BleConstants
import com.carlom.klardrop.common.ble.BlePeerEvent

/**
 * Seam for every BlueZ interaction the Linux BLE transport needs. The real
 * implementation talks to org.bluez over the D-Bus system bus ([BlueZConnection]);
 * every later BLE todo fakes this interface in tests.
 *
 * Peripheral-role operations have no-op defaults so fakes only override what they
 * exercise (the real implementation is [BlueZPeripheralFacade]); central-role
 * operations likewise (the real implementation is [BlueZCentralFacade]).
 */
interface BlueZFacade {

  /** Probes the system bus for adapters usable for both GATT client and peripheral roles. */
  suspend fun probeCapability(): BlueZCapability

  /**
   * Negotiated ATT MTU for GATT sessions. BlueZ only exposes `GattCharacteristic1.MTU`
   * for fd-acquired characteristics, so plain write/notify keeps the conservative
   * ATT default (correct, just not maximal throughput).
   */
  val mtu: Int get() = BleConstants.DEFAULT_MTU

  /**
   * Exports the Klardrop GATT application (service + TX write + RX notify characteristics)
   * and registers it with `GattManager1.RegisterApplication`. Throws when BlueZ cannot
   * host the application (e.g. adapter powered off).
   */
  suspend fun exportApplication() = Unit

  /** Unregisters the application exported by [exportApplication]. No-op when none. */
  suspend fun unregisterApplication() = Unit

  /**
   * Pushes [value] to [centralId] as a notification on the RX characteristic
   * (PropertiesChanged after the central's StartNotify). Throws when the write fails.
   */
  suspend fun notifyValue(centralId: String, value: ByteArray) = Unit

  /** Registers the callback invoked when a remote central writes to the TX characteristic. */
  fun onCharacteristicWrite(listener: ((centralId: String, value: ByteArray) -> Unit)?) = Unit

  /** Registers the callback invoked when a remote central subscribes/unsubscribes on RX. */
  fun onCentralSubscription(listener: ((centralId: String, subscribed: Boolean) -> Unit)?) = Unit

  // ── Central role ──────────────────────────────────────────────────────────

  /** Central role: `Adapter1.SetDiscoveryFilter` on the Klardrop service UUID + `StartDiscovery`. */
  suspend fun startScan() = Unit

  /** Central role: `StopDiscovery`. No-op when not scanning. */
  suspend fun stopScan() = Unit

  /** Registers the callback invoked when a Klardrop peer appears (valid ServiceData only). */
  fun onPeerFound(listener: ((event: BlePeerEvent.Found) -> Unit)?) = Unit

  /** Registers the callback invoked when a previously-found peer disappears. */
  fun onPeerLost(listener: ((address: String) -> Unit)?) = Unit

  /**
   * Central role: `Device1.Connect` → resolve TX/RX characteristics → read MTU →
   * `StartNotify` on RX. Inbound RX notifications and remote disconnects are delivered
   * via [onNotify]/[onDisconnected]; writes go through the returned [BlueZPeerLink].
   *
   * @throws IllegalStateException when the device is unknown or the GATT profile is wrong.
   */
  suspend fun connect(
    address: String,
    onNotify: (ByteArray) -> Unit,
    onDisconnected: () -> Unit,
  ): BlueZPeerLink = throw UnsupportedOperationException("central role not implemented by this facade")
}

/**
 * Live GATT link to a remote peer in the central role, returned by [BlueZFacade.connect].
 * Lambda-based like [LinuxBleSession]'s notify hook so fakes construct it inline.
 */
class BlueZPeerLink(
  /** Negotiated ATT MTU (`GattCharacteristic1.MTU` when BlueZ exposes it, else DEFAULT_MTU). */
  val mtu: Int,
  /** Write-with-response to the peer's TX characteristic. Throws when the write fails. */
  val writeTx: suspend (ByteArray) -> Unit,
)

/** Result of probing BlueZ for BLE capability. */
data class BlueZCapability(
  /** True when at least one adapter exposes both GattManager1 and LEAdvertisingManager1. */
  val supported: Boolean,
  /** Object paths of the fully capable adapters (e.g. /org/bluez/hci0). */
  val adapterPaths: List<String>,
)
