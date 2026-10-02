@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common.ble

import com.carlom.klardrop.common.ble.linux.SdBusAdvertiser
import com.carlom.klardrop.common.ble.linux.SdBusConnection
import com.carlom.klardrop.common.discovery.CurrentDevice
import kotlinx.cinterop.toKString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import platform.posix.getenv

/**
 * Linux-native BLE transport over sd-bus (BlueZ org.bluez). Native counterpart of the
 * JVM `LinuxBlueZTransport`, currently a spike: adapter enumeration ([SdBusConnection])
 * and the advertise payload stub ([SdBusAdvertiser]) compile and run; full GATT serving
 * and central I/O ([SdBusGatt]) follow.
 *
 * Disabled by default: [isSupported] is false unless `KLARDROP_LINUX_BLE=1` is set AND
 * a capable BlueZ adapter is present, so LAN-only native v1 stays the default path.
 */
actual class BleTransport {

  private val advertiser = SdBusAdvertiser()

  actual suspend fun isSupported(): Boolean {
    if (!bleEnabled()) return false
    return SdBusConnection.probeCapability().supported
  }

  actual suspend fun startAdvertising(currentDevice: CurrentDevice, lowPower: Boolean) {
    if (!bleEnabled()) return
    advertiser.startAdvertising(currentDevice)
  }

  actual suspend fun stopAdvertising() {
    advertiser.stopAdvertising()
  }

  actual fun scanForPeers(lowPower: Boolean): Flow<BlePeerEvent> = emptyFlow()

  actual suspend fun connectCentral(address: String, remoteShortDeviceId: String): BleSession {
    throw UnsupportedOperationException("BLE central not supported on Linux native yet")
  }

  actual fun serveGatt(): Flow<BleSession> = emptyFlow()

  private fun bleEnabled(): Boolean = getenv(OPT_IN_ENV)?.toKString() == "1"

  private companion object {
    const val OPT_IN_ENV = "KLARDROP_LINUX_BLE"
  }
}
