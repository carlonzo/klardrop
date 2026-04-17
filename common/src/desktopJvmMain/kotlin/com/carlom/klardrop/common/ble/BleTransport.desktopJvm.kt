package com.carlom.klardrop.common.ble

import com.carlom.klardrop.common.discovery.CurrentDevice
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * JVM desktop BLE transport placeholder.
 *
 * Real implementation will be per-OS:
 *   - Linux:   BlueZ via D-Bus (`org.bluez.LEAdvertisement1` + `org.bluez.GattApplication1`);
 *              `dbus-java` or `tinyb` as candidate libraries.
 *   - Windows: WinRT `GattServiceProvider` + `BluetoothLEAdvertisement*` APIs via JNA
 *              or `jextract`-generated bindings. Requires Windows 10 1803+.
 *   - macOS-JVM: no clean bridge to CoreBluetooth from a pure JVM — documented out of
 *              scope; Mac users should run the native macOS module instead.
 *
 * Stubbed for now so the desktop target compiles; `isSupported()` reports false so BLE
 * discovery is a no-op on desktop until the per-OS implementations land.
 */
actual class BleTransport {

  actual suspend fun isSupported(): Boolean {
    val os = System.getProperty("os.name")?.lowercase().orEmpty()
    log(TAG, "Desktop BLE transport not implemented for '$os' yet")
    return false
  }

  actual suspend fun startAdvertising(currentDevice: CurrentDevice) {
    // TODO: BlueZ LEAdvertisement1 on Linux; WinRT GattServiceProvider on Windows.
  }

  actual suspend fun stopAdvertising() {
    // TODO: tear down platform-specific advertiser.
  }

  actual fun scanForPeers(): Flow<BlePeerEvent> {
    // TODO: BlueZ StartDiscovery + DeviceFound signals on Linux; WinRT
    //       BluetoothLEAdvertisementWatcher on Windows.
    return emptyFlow()
  }

  actual suspend fun connectCentral(address: String, remoteShortDeviceId: String): BleSession {
    throw NotImplementedError("Desktop JVM BLE central not implemented yet")
  }

  actual fun serveGatt(): Flow<BleSession> = emptyFlow()

  private companion object {
    const val TAG = "BleTransport.desktopJvm"
  }
}
