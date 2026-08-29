package com.carlom.klardrop.common.ble

import com.carlom.klardrop.common.ble.linux.LinuxBlueZTransport
import com.carlom.klardrop.common.ble.mac.HelperBinaryResolver
import com.carlom.klardrop.common.ble.mac.MacBleHelperProcess
import com.carlom.klardrop.common.discovery.CurrentDevice
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/**
 * Desktop JVM BLE transport.
 *
 * macOS: backed by a Swift `klardrop-ble-helper` subprocess that wraps CoreBluetooth.
 *        See `desktop/native/macos/`.
 *
 * Linux: backed by an in-process BlueZ D-Bus transport ([LinuxBlueZTransport]) —
 *        GATT peripheral + central and advertising over org.bluez, gated by the
 *        BlueZ capability probe.
 *
 * Windows: not implemented yet; `isSupported()` returns false so BLE is a no-op on
 * that host. Future implementations will plug an OS-specific backend into the same
 * per-OS selection below.
 */
actual class BleTransport internal constructor(
  private val helper: MacBleHelperProcess?,
  private val linux: LinuxBlueZTransport? = null,
) {

  constructor() : this(selectForOs())

  private constructor(selected: Pair<MacBleHelperProcess?, LinuxBlueZTransport?>) :
    this(selected.first, selected.second)

  actual suspend fun isSupported(): Boolean {
    linux?.let { return it.isSupported() }
    val h = helper ?: return false
    return h.awaitPoweredOn()
  }

  actual suspend fun startAdvertising(currentDevice: CurrentDevice) {
    linux?.let { return it.startAdvertising(currentDevice) }
    val h = helper ?: return
    if (!h.ensureStarted()) return
    runCatching {
      // BLE advertisements are public — anyone within range with a BLE scanner can
      // read the local name. We only broadcast the 8-char shortDeviceId (which is
      // app-specific and not user-identifying). The friendly device name, OS type,
      // and device type are exchanged inside the encrypted Klardrop handshake
      // after the GATT connection opens, so non-Klardrop scanners never see them.
      h.startAdvertising(currentDevice.shortDeviceId, currentDevice.shortDeviceId)
    }.onFailure { log(TAG, "startAdvertising failed", it) }
  }

  actual suspend fun stopAdvertising() {
    linux?.let { return it.stopAdvertising() }
    helper?.stopAdvertising()
  }

  actual fun scanForPeers(): Flow<BlePeerEvent> {
    linux?.let { return it.scanForPeers() }
    val h = helper ?: return emptyFlow()
    return h.scanForPeers()
  }

  actual suspend fun connectCentral(address: String, remoteShortDeviceId: String): BleSession {
    linux?.let { return it.connectCentral(address, remoteShortDeviceId) }
    val h = helper ?: throw IllegalStateException("BLE not supported on this host")
    return h.connectCentral(address, remoteShortDeviceId)
  }

  actual fun serveGatt(): Flow<BleSession> {
    linux?.let { return it.serveGatt() }
    val h = helper ?: return emptyFlow()
    return h.serveGatt().map { it as BleSession }
  }

  private companion object {
    const val TAG = "BleTransport.desktopJvm"

    /** Per-OS selection: mac → helper subprocess, linux → in-process BlueZ, else skip. */
    private fun selectForOs(): Pair<MacBleHelperProcess?, LinuxBlueZTransport?> {
      val os = System.getProperty("os.name")?.lowercase().orEmpty()
      return when {
        os.contains("mac") || os.contains("darwin") -> MacBleHelperProcess(
          commandProvider = {
            HelperBinaryResolver.resolve()?.let { listOf(it.absolutePath) }
          },
        ) to null

        os.contains("nix") || os.contains("nux") || os.contains("aix") -> null to LinuxBlueZTransport()

        else -> {
          log(TAG, "Desktop BLE not implemented for '$os' yet")
          null to null
        }
      }
    }
  }
}