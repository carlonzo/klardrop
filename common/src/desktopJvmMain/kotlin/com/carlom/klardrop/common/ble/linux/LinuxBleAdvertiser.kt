package com.carlom.klardrop.common.ble.linux

import com.carlom.klardrop.common.discovery.CurrentDevice

/**
 * BLE advertising over BlueZ: delegates the D-Bus work to [BlueZFacade.startAdvertising]/
 * [stopAdvertising] and keeps the start/stop state idempotent — a double start never
 * re-registers, a stop without a start is a no-op, and a start/stop/start cycle
 * re-registers cleanly (the facade owns the exported object's lifecycle).
 */
class LinuxBleAdvertiser(private val facade: BlueZFacade) {

  @Volatile private var advertising = false

  suspend fun startAdvertising(currentDevice: CurrentDevice) {
    if (advertising) return
    facade.startAdvertising(currentDevice)
    advertising = true
  }

  suspend fun stopAdvertising() {
    if (!advertising) return
    facade.stopAdvertising()
    advertising = false
  }
}
