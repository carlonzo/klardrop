package com.carlom.klardrop.common.ble

import com.carlom.klardrop.common.discovery.CurrentDevice
import kotlinx.coroutines.flow.Flow

/**
 * Platform-agnostic BLE transport abstraction. Each actual implementation owns the
 * native Bluetooth stack (BluetoothAdapter on Android, CoreBluetooth on Apple, BlueZ/
 * WinRT on JVM desktop) and exposes a uniform API to the rest of the app.
 *
 * Responsibilities:
 *  1. Advertise this device as a Klardrop BLE peer (peripheral role).
 *  2. Scan for other Klardrop peers (central role) — emits [BlePeerEvent] on discovery.
 *
 * Higher-level framing (length-prefixed messages) is handled by [BleFraming] using
 * the raw write/notify primitives exposed by the platform implementation via
 * [BleSession]. Session-level I/O is intentionally not modeled here in the MVP; the
 * discovery + advertise pair above is sufficient to surface a peer in
 * `VisibleDevices`, and the Klardrop Client/Server can connect to the BLE address
 * through a dedicated BLE Client impl in a follow-up.
 */
expect class BleTransport {

  /** Whether the platform + runtime actually supports BLE (permissions granted, adapter on). */
  suspend fun isSupported(): Boolean

  /**
   * Start advertising [currentDevice] as a Klardrop BLE peer. Safe to call repeatedly —
   * implementations should replace any previous advertisement with the new device info.
   */
  suspend fun startAdvertising(currentDevice: CurrentDevice)

  /** Stop advertising. No-op if not currently advertising. */
  suspend fun stopAdvertising()

  /**
   * Start scanning for Klardrop peers. The returned flow emits peer found/lost events.
   * Cancelling the flow stops the scan.
   */
  fun scanForPeers(): Flow<BlePeerEvent>

  /**
   * Open a GATT central connection to the peer at [address], negotiate MTU, discover the
   * Klardrop service and return a fully-initialised [BleSession] ready for I/O. The caller
   * is responsible for performing the app-level handshake on top of this.
   *
   * @throws IllegalStateException if BLE is not supported or the connection fails.
   */
  suspend fun connectCentral(address: String, remoteShortDeviceId: String): BleSession

  /**
   * Start a GATT server hosting the Klardrop service. The returned flow emits a
   * [BleSession] every time a remote central finishes MTU negotiation and subscribes to
   * our RX characteristic. Collecting stops the server.
   */
  fun serveGatt(): Flow<BleSession>
}

/** Events emitted while scanning for BLE peers. */
sealed interface BlePeerEvent {

  /**
   * A peer advertising the Klardrop service UUID was discovered.
   *
   * @param address Platform-specific peripheral identifier (BluetoothDevice MAC on Android,
   *                CBPeripheral.identifier UUID on Apple).
   * @param shortDeviceId The 8-char short device id advertised by the peer, used to match
   *                      the same device across different transports (e.g. mDNS + BLE).
   * @param localName Human-readable name from the advertisement, if present.
   * @param rssi Signal strength in dBm (negative values, closer to zero = stronger).
   */
  data class Found(
    val address: String,
    val shortDeviceId: String,
    val localName: String?,
    val rssi: Int,
  ) : BlePeerEvent

  /** A previously-seen peer is no longer visible. */
  data class Lost(val address: String) : BlePeerEvent
}
