@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common.ble.linux

import com.carlom.klardrop.common.ble.BleConstants
import com.carlom.klardrop.common.discovery.CurrentDevice
import com.carlom.klardrop.common.utils.log
import kotlin.concurrent.Volatile

/**
 * BLE advertising over BlueZ, sd-bus edition. Mirrors the JVM [LinuxBleAdvertiser]:
 * same idempotent start/stop state machine (double start never re-registers, stop
 * without start is a no-op), and [advertisementSpec] carries the same LEAdvertisement1
 * payload the dbus-java `ExportedAdvertisement` puts on the air — service UUID in
 * ServiceUUIDs, the 8-char [CurrentDevice.shortDeviceId] as service data, nothing else
 * identifying.
 *
 * Spike boundary: this builds the payload descriptor but does NOT serve it yet.
 * Registering needs a local `LEAdvertisement1` object on the bus
 * (`sd_bus_add_object_vtable` + `LEAdvertisingManager1.RegisterAdvertisement` against
 * the probed adapter path) plus a message-process loop — that is the advertise
 * follow-up. Until then [startAdvertising] records intent and logs.
 */
class SdBusAdvertiser {

  @Volatile private var advertising = false

  suspend fun startAdvertising(currentDevice: CurrentDevice) {
    if (advertising) return
    val spec = advertisementSpec(currentDevice.shortDeviceId)
    // Spike: no object server yet, so nothing is registered with BlueZ here.
    log(TAG, "spike: advertisement not served yet (serviceData=${spec.serviceData.size} bytes)")
    advertising = true
  }

  suspend fun stopAdvertising() {
    if (!advertising) return
    advertising = false
  }

  /**
   * Pure descriptor of the LEAdvertisement1 BlueZ will read via Properties.GetAll
   * during RegisterAdvertisement. Keys mirror `ExportedAdvertisement.properties()`.
   */
  fun advertisementSpec(shortDeviceId: String): AdvertisementSpec = AdvertisementSpec(
    type = "peripheral",
    serviceUuids = listOf(BleConstants.SERVICE_UUID),
    serviceData = mapOf(BleConstants.SERVICE_UUID to shortDeviceId.encodeToByteArray()),
    localName = shortDeviceId,
    includes = listOf("tx-power"),
  )

  data class AdvertisementSpec(
    val type: String,
    val serviceUuids: List<String>,
    val serviceData: Map<String, ByteArray>,
    val localName: String,
    val includes: List<String>,
  )

  private companion object {
    const val TAG = "SdBusAdvertiser"
  }
}
