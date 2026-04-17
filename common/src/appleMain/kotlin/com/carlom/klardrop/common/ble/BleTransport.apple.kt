package com.carlom.klardrop.common.ble

import com.carlom.klardrop.common.discovery.CurrentDevice
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Apple (iOS + macOS) BLE transport placeholder.
 *
 * Real implementation will use CoreBluetooth:
 *   - Advertising:      `CBPeripheralManager` + `CBMutableService` + `CBMutableCharacteristic`
 *                       advertising `BleConstants.SERVICE_UUID` with the short device id
 *                       embedded in a GATT characteristic (iOS does not expose
 *                       service-data AD in the background).
 *   - Scanning:          `CBCentralManager.scanForPeripheralsWithServices([serviceUUID])`
 *                       and connect on first sighting to read the short device id
 *                       characteristic.
 *   - Permissions:       `NSBluetoothAlwaysUsageDescription` in Info.plist + runtime
 *                       prompt on first access. Background advertising is iOS-to-iOS only
 *                       and only when the app is backgrounded with the bluetooth-peripheral
 *                       background mode enabled.
 *
 * Stubbed for now so the Apple targets compile; `isSupported()` reports false so
 * BLE discovery is a no-op until the real implementation lands.
 */
actual class BleTransport {

  actual suspend fun isSupported(): Boolean {
    log(TAG, "Apple BLE transport not implemented yet")
    return false
  }

  actual suspend fun startAdvertising(currentDevice: CurrentDevice) {
    // TODO: CBPeripheralManager advertise with serviceUUID + CBMutableService exposing
    //       a read characteristic that returns currentDevice.shortDeviceId.
  }

  actual suspend fun stopAdvertising() {
    // TODO: CBPeripheralManager.stopAdvertising()
  }

  actual fun scanForPeers(): Flow<BlePeerEvent> {
    // TODO: CBCentralManager.scanForPeripherals(withServices:) + GATT-read the short id.
    return emptyFlow()
  }

  private companion object {
    const val TAG = "BleTransport.apple"
  }
}
