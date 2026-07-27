package com.carlom.klardrop.common.ble

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
 * Linux/Windows: not implemented yet; `isSupported()` returns false so BLE is a
 * no-op on those hosts. Future implementations will plug an OS-specific helper
 * binary into the same [MacBleHelperProcess]-style IPC.
 */
actual class BleTransport internal constructor(
  private val helper: MacBleHelperProcess?,
) {

  constructor() : this(defaultHelper())


  actual suspend fun isSupported(): Boolean {
    val h = helper ?: return false
    return h.awaitPoweredOn()
  }

  actual suspend fun startAdvertising(currentDevice: CurrentDevice) {
    val h = helper ?: return
    if (!h.ensureStarted()) return
    runCatching {
      // What goes on the air is decided by the shared payload builder; the helper only
      // makes the CoreBluetooth calls. CBPeripheralManager can express the service UUID
      // (a constant the helper already mirrors) and the local name, so that is all we
      // hand it — the payload's service-data record is Android's to carry.
      //
      // BLE advertisements are public: anyone in range with a scanner can read the local
      // name. It is only the 8-char shortDeviceId, which is app-specific and not
      // user-identifying. The friendly device name, OS type and device type are exchanged
      // inside the encrypted Klardrop handshake after the GATT connection opens, so
      // non-Klardrop scanners never see them.
      val payload = klardropAdvertisePayload(currentDevice.shortDeviceId)
      h.startAdvertising(currentDevice.shortDeviceId, payload.primary.localName)
    }.onFailure { log(TAG, "startAdvertising failed", it) }
  }

  actual suspend fun stopAdvertising() {
    helper?.stopAdvertising()
  }

  actual fun scanForPeers(): Flow<BlePeerEvent> {
    val h = helper ?: return emptyFlow()
    return h.scanForPeers()
  }

  actual suspend fun connectCentral(address: String, remoteShortDeviceId: String): BleSession {
    val h = helper ?: throw IllegalStateException("BLE not supported on this host")
    return h.connectCentral(address, remoteShortDeviceId)
  }

  actual fun serveGatt(): Flow<BleSession> {
    val h = helper ?: return emptyFlow()
    return h.serveGatt().map { it as BleSession }
  }

  private companion object {
    const val TAG = "BleTransport.desktopJvm"

    private fun defaultHelper(): MacBleHelperProcess? {
      val os = System.getProperty("os.name")?.lowercase().orEmpty()
      val isMac = os.contains("mac") || os.contains("darwin")
      if (!isMac) {
        log(TAG, "Desktop BLE not implemented for '$os' yet")
        return null
      }
      return MacBleHelperProcess(
        commandProvider = {
          HelperBinaryResolver.resolve()?.let { listOf(it.absolutePath) }
        },
      )
    }
  }
}
