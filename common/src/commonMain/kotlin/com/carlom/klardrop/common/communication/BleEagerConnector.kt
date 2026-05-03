package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.ble.BleRoleSelector
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.OsType
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Eagerly opens a BLE GATT session to newly-discovered BLE-only peers so the
 * rich identity carried in [com.carlom.klardrop.common.communication.message.HandshakeMessage]
 * (friendly name, OS type, device type) lands in [VisibleDevices] without
 * waiting for the user to tap-to-send.
 *
 * BLE is just one discovery medium alongside mDNS — both run in parallel. This
 * connector only operates on BLE-only entries (no Wi-Fi reachability) and only
 * the role-selector-picked initiator dials, so each BLE peer pair opens at most
 * one GATT session. Failed attempts back off for [cooldownDuration] per peer to
 * avoid burning the radio on offline peers.
 *
 * The created [ConnectionMessenger] stays in the [ConnectionsPool] so subsequent
 * user-initiated transfers reuse it.
 */
class BleEagerConnector(
  private val coroutines: Coroutines,
  private val visibleDevices: VisibleDevices,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val client: Client,
  private val connectionsPool: ConnectionsPool,
) {

  private val scope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)
  private var job: Job? = null

  // Per-peer cooldown after a failed eager attempt so we don't burn battery
  // hammering an offline peer every staleness cycle.
  private val failureCooldownUntil = mutableMapOf<String, TimeSource.Monotonic.ValueTimeMark>()
  private val cooldownDuration = 5.minutes

  fun start() {
    if (job?.isActive == true) return
    job = scope.launch {
      val self = currentDeviceProvider.get().shortDeviceId
      visibleDevices.visibleDevices
        .collect { devices ->
          for ((deviceId, device) in devices) {
            if (deviceId == self) continue
            val isBleOnly = device.deviceConnections.all { it is DeviceConnection.BleConnection }
            val isPlaceholder = device.deviceInfo.name == device.deviceInfo.deviceId &&
              device.deviceInfo.osType == OsType.UNKNOWN
            if (!isBleOnly || !isPlaceholder) continue
            if (connectionsPool.isAvailable(deviceId)) {
              failureCooldownUntil.remove(deviceId)
              continue
            }
            if (!BleRoleSelector.shouldInitiate(self, deviceId)) continue
            val cooldown = failureCooldownUntil[deviceId]
            if (cooldown != null && cooldown.elapsedNow() < cooldownDuration) continue

            log(TAG, "Eager BLE handshake to $deviceId (role: initiator)")
            // Set the cooldown unconditionally before attempting. If the attempt
            // succeeds, `connectionsPool.isAvailable` upstream will skip future
            // eager attempts anyway. If it fails, the cooldown prevents us from
            // hammering the radio every staleness cycle.
            failureCooldownUntil[deviceId] = TimeSource.Monotonic.markNow()
            scope.launch {
              runCatching { client.connectTo(deviceId) }
                .onFailure { log(TAG, "Eager BLE connect to $deviceId failed: ${it.message}") }
            }
          }
        }
    }
  }

  fun stop() {
    job?.cancel()
    job = null
  }

  private companion object {
    const val TAG = "BleEagerConnector"
  }
}
