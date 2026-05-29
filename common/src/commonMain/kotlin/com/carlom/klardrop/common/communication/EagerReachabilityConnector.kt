package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.network.NetworkLifecycleMonitor
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Proactively dials newly-discovered TCP-reachable peers so "visible" implies
 * "reachable" in the UI. Without this, a device shows up as soon as its mDNS
 * announcement is heard but the user only learns of an unreachable address
 * when they tap to send.
 *
 * Mirrors [BleEagerConnector] for the Klardrop/TCP transport: it watches
 * [VisibleDevices.visibleDevices], skips peers already in the [ConnectionsPool],
 * and delegates to [Client.connectTo] which iterates every known endpoint and
 * updates the pool on success.
 *
 * On a [com.carlom.klardrop.common.network.NetworkChangeEvent] (sleep/wake,
 * NIC change), [ConnectionsPool] flushes itself; re-emission of the
 * visibleDevices flow then triggers fresh probes here. Per-peer cooldowns
 * back off failed probes so we don't hammer the radio on a peer that's
 * announcing but unreachable.
 */
class EagerReachabilityConnector(
  private val coroutines: Coroutines,
  private val visibleDevices: VisibleDevices,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val client: Client,
  private val connectionsPool: ConnectionsPool,
  private val networkLifecycleMonitor: NetworkLifecycleMonitor,
) {

  private val scope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)
  private var watchJob: Job? = null
  private var lifecycleJob: Job? = null

  // Per-peer cooldown after a failed probe. Re-probing on every visibleDevices update for a
  // peer that's announcing-but-unreachable would waste the radio, so we back off briefly. The
  // cooldown is intentionally short (vs. minutes) so a peer that comes back is re-dialed fast —
  // important because when the peer is behind a default-deny-inbound firewall, OUR outbound dial
  // is the only way a connection can ever form, and a pending send is waiting on exactly that.
  // The cooldown is also cleared the instant a device (re)appears in discovery (see [start]).
  private val failureCooldownUntil = mutableMapOf<String, TimeSource.Monotonic.ValueTimeMark>()
  private val cooldownDuration = 5.seconds

  fun start() {
    if (watchJob?.isActive == true) return

    watchJob = scope.launch {
      val self = currentDeviceProvider.get().shortDeviceId
      var previouslyVisible = emptySet<String>()
      visibleDevices.visibleDevices.collect { devices ->
        // A device that just (re)appeared in discovery is a strong "it's back" signal: clear any
        // failure cooldown so we re-dial it immediately instead of waiting the cooldown out.
        val currentlyVisible = devices.keys.toSet()
        (currentlyVisible - previouslyVisible).forEach { failureCooldownUntil.remove(it) }
        previouslyVisible = currentlyVisible

        for ((deviceId, device) in devices) {
          if (deviceId == self) continue
          if (!device.hasKlardropConnection()) continue
          if (shouldSkip(deviceId)) continue
          if (connectionsPool.isAvailable(deviceId)) {
            failureCooldownUntil.remove(deviceId)
            continue
          }
          probe(deviceId, device)
        }
      }
    }

    lifecycleJob = scope.launch {
      networkLifecycleMonitor.observe().collect {
        log(TAG, "Network change observed; clearing reachability cooldowns")
        failureCooldownUntil.clear()
      }
    }
  }

  fun stop() {
    watchJob?.cancel()
    watchJob = null
    lifecycleJob?.cancel()
    lifecycleJob = null
  }

  private fun shouldSkip(deviceId: String): Boolean {
    val cooldown = failureCooldownUntil[deviceId] ?: return false
    return cooldown.elapsedNow() < cooldownDuration
  }

  private fun probe(deviceId: String, device: DiscoveryDevice) {
    log(TAG, "Probing reachability for $deviceId (${device.deviceConnections.size} endpoint(s))")
    failureCooldownUntil[deviceId] = TimeSource.Monotonic.markNow()
    connectionsPool.markProbing(deviceId)
    scope.launch {
      runCatching { client.connectTo(deviceId) }
        .onSuccess {
          if (connectionsPool.isAvailable(deviceId)) {
            failureCooldownUntil.remove(deviceId)
            log(TAG, "Probe succeeded for $deviceId")
            // updateConnection() inside Client already marked Reachable.
          } else {
            log(TAG, "Probe completed without establishing connection for $deviceId")
            connectionsPool.markUnreachable(deviceId)
          }
        }
        .onFailure {
          log(TAG, "Probe failed for $deviceId: ${it.message}")
          connectionsPool.markUnreachable(deviceId)
        }
    }
  }

  private companion object {
    const val TAG = "EagerReachabilityConnector"
  }
}
