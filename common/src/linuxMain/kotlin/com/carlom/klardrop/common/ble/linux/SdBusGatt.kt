@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common.ble.linux

import com.carlom.klardrop.common.ble.BleConstants
import com.carlom.klardrop.common.utils.log

/**
 * GATT server over BlueZ, sd-bus edition — spike stub. The full port serves the Klardrop
 * primary service ([BleConstants.SERVICE_UUID] with TX-write / RX-notify characteristics
 * per [BleConstants.TX_CHARACTERISTIC_UUID] / [BleConstants.RX_CHARACTERISTIC_UUID]) as
 * local objects via `sd_bus_add_object_vtable` under [APP_PATH], then registers them
 * with `GattManager1.RegisterApplication` on the probed adapter path. Central-role I/O
 * (AcquireWrite/AcquireNotify fd passing, `sd_bus_message_read_basic` on `'h'`) follows
 * the same pattern.
 *
 * Until that lands every entry point here reports not-ready so the transport stays
 * provably inert: BLE is disabled by default and LAN-only native v1 is unaffected.
 */
object SdBusGatt {

  /** Object root mirroring the dbus-java peripheral's exported application path layout. */
  const val APP_PATH = "/com/carlom/klardrop/ble"

  const val SERVICE_PATH = "$APP_PATH/service0"
  const val TX_CHARACTERISTIC_PATH = "$SERVICE_PATH/tx"
  const val RX_CHARACTERISTIC_PATH = "$SERVICE_PATH/rx"

  val serviceUuid: String = BleConstants.SERVICE_UUID
  val txCharacteristicUuid: String = BleConstants.TX_CHARACTERISTIC_UUID
  val rxCharacteristicUuid: String = BleConstants.RX_CHARACTERISTIC_UUID

  /** Always false in the spike: there is no served application to export yet. */
  fun isSupported(): Boolean = false

  suspend fun exportApplication() {
    log(TAG, "spike: GattManager1.RegisterApplication not served yet")
  }

  suspend fun unregisterApplication() = Unit

  suspend fun notifyValue(centralId: String, value: ByteArray) {
    log(TAG, "spike: notify to $centralId (${value.size} bytes) dropped, no GATT server")
  }

  private const val TAG = "SdBusGatt"
}
