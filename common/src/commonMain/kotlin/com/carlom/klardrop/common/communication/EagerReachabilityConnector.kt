package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.network.NetworkLifecycleMonitor
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

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
 *
 * A 30s ticker additionally re-evaluates the CURRENT visible device set on its
 * own, so a peer whose probe failed (server temporarily down, firewall drop)
 * is re-dialed periodically instead of staying Offline until some unrelated
 * discovery churn happens to re-emit the device list.
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
  private var reprobeJob: Job? = null
  private var lifecycleJob: Job? = null

  // Per-peer cooldown after a failed probe. Re-probing on every visibleDevices update for a
  // peer that's announcing-but-unreachable would waste the radio, so we back off briefly. The
  // cooldown is intentionally short (vs. minutes) so a peer that comes back is re-dialed fast —
  // important because when the peer is behind a default-deny-inbound firewall, OUR outbound dial
  // is the only way a connection can ever form, and a pending send is waiting on exactly that.
  // The cooldown is also cleared the instant a device (re)appears in discovery (see [start]).
  //
  // Implemented as one delayed-clear job per peer — plain `delay`, the same virtual-time-friendly
  // timer pattern as ConnectionsPool's debounce and Probing watchdog — rather than a TimeSource
  // mark: "in cooldown" simply means a clear job is still pending, expiry is exact, and tests can
  // drive it on the virtual clock.
  //
  // Held in a StateFlow rather than a plain map: the visibleDevices collector, the re-probe
  // ticker, the expiry jobs and the probe callbacks all touch it from the multi-threaded IO
  // dispatcher, where a plain map can drop entries or throw ConcurrentModificationException
  // out of the network-change collector — killing it for the rest of the process.
  private val failureCooldownJobs = MutableStateFlow<Map<String, Job>>(emptyMap())
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
        (currentlyVisible - previouslyVisible).forEach { clearCooldown(it) }
        previouslyVisible = currentlyVisible

        for ((deviceId, device) in devices) {
          probeIfEligible(deviceId, device, self)
        }
      }
    }

    // Periodic re-probe: re-evaluate the CURRENT device set (not waiting for a new emission)
    // with the exact same per-device decision as the collect path above, cooldown map included.
    reprobeJob = scope.launch {
      val self = currentDeviceProvider.get().shortDeviceId
      while (isActive) {
        delay(REPROBE_INTERVAL)
        for ((deviceId, device) in visibleDevices.visibleDevices.value) {
          probeIfEligible(deviceId, device, self)
        }
      }
    }

    lifecycleJob = scope.launch {
      networkLifecycleMonitor.observe().collect {
        log(TAG, "Network change observed; clearing reachability cooldowns")
        failureCooldownJobs.value.keys.toList().forEach { clearCooldown(it) }
      }
    }
  }

  fun stop() {
    watchJob?.cancel()
    watchJob = null
    reprobeJob?.cancel()
    reprobeJob = null
    lifecycleJob?.cancel()
    lifecycleJob = null
  }

  /**
   * The single per-device probe decision, shared by the visibleDevices collect path and the
   * periodic re-probe ticker: skip self, non-Klardrop peers, peers in cooldown, and peers with
   * a live pooled connection. Also skips a peer whose probe is still in flight
   * ([Reachability.Probing]) so the two paths never double-dial. Every other peer without a
   * live connection — [Reachability.Unknown], [Reachability.Unreachable], or a stale
   * [Reachability.Reachable] left behind when the heartbeat closed a dead socket (the pool's
   * reachability map is only corrected by closeConnection / network flush) — is a probe
   * candidate.
   */
  private suspend fun probeIfEligible(deviceId: String, device: DiscoveryDevice, selfId: String) {
    if (deviceId == selfId) return
    // Nearby Share advertises the same unified TCP listener; skipping those peers
    // left them permanently Unknown/disconnected whenever Klardrop mDNS was off.
    if (!device.hasKlardropConnection() && !device.hasNearbyConnection()) return
    if (shouldSkip(deviceId)) return
    if (connectionsPool.isAvailable(deviceId)) {
      clearCooldown(deviceId)
      return
    }
    if (connectionsPool.reachability.value[deviceId] == Reachability.Probing) return
    probe(deviceId, device)
  }

  private fun shouldSkip(deviceId: String): Boolean = failureCooldownJobs.value.containsKey(deviceId)

  private fun clearCooldown(deviceId: String) {
    var removed: Job? = null
    failureCooldownJobs.update { cooldowns ->
      removed = cooldowns[deviceId]
      if (removed == null) cooldowns else cooldowns - deviceId
    }
    removed?.cancel()
  }

  private fun armCooldown(deviceId: String) {
    val expiry = scope.launch {
      delay(cooldownDuration)
      failureCooldownJobs.update { it - deviceId }
    }
    var replaced: Job? = null
    failureCooldownJobs.update { cooldowns ->
      replaced = cooldowns[deviceId]
      cooldowns + (deviceId to expiry)
    }
    replaced?.cancel()
  }

  private fun probe(deviceId: String, device: DiscoveryDevice) {
    log(TAG, "Probing reachability for $deviceId (${device.deviceConnections.size} endpoint(s))")
    armCooldown(deviceId)
    connectionsPool.markProbing(deviceId)
    scope.launch {
      if (connectionsPool.isAvailable(deviceId)) {
        clearCooldown(deviceId)
        return@launch
      }
      runCatching { client.connectTo(deviceId) }
        .onSuccess { outcome ->
          when (outcome) {
            ConnectOutcome.Connected -> {
              clearCooldown(deviceId)
              log(TAG, "Probe $deviceId: Connected (${device.deviceConnections.size} endpoint(s) raced)")
              // updateConnection() inside Client already marked Reachable.
            }
            ConnectOutcome.NotInitiated -> {
              // We deliberately did not initiate (e.g. BLE non-initiator role, or already
              // connected). The peer may dial us — leave reachability as Probing so the UI
              // does not show Unreachable for an inbound-only peer. Nothing else will ever
              // move this off Probing, so ConnectionsPool's own watchdog (armed by
              // markProbing) falls it back to Unknown if no terminal call lands in time.
              log(TAG, "Probe inconclusive for $deviceId (not initiator); leaving as Probing")
            }
            ConnectOutcome.Failed -> {
              // Client collapses the per-endpoint causes (refused / timeout / handshake
              // mismatch / encryption refusal — each logged there) into this one outcome.
              log(TAG, "Probe $deviceId: Error (all ${device.deviceConnections.size} endpoint(s) exhausted)")
              if (!connectionsPool.isAvailable(deviceId)) {
                connectionsPool.markUnreachable(deviceId)
              } else {
                log(TAG, "Probe $deviceId: outcome Failed but connection is available in pool; keeping Reachable")
                clearCooldown(deviceId)
              }
            }
          }
        }
        .onFailure { cause ->
          val (outcome, detail) = classifyProbeFailure(cause)
          log(TAG, "Probe $deviceId: $outcome ($detail)")
          if (!connectionsPool.isAvailable(deviceId)) {
            connectionsPool.markUnreachable(deviceId)
          } else {
            log(TAG, "Probe $deviceId: failure ($outcome) but connection is available in pool; keeping Reachable")
            clearCooldown(deviceId)
          }
        }
    }
  }

  /**
   * Maps a dial failure that escaped [Client.connectTo] to a breadcrumb outcome label,
   * reusing Client.kt's failure classification (isConnectionRefused /
   * TimeoutCancellationException). "Failed-mismatch" / "Failed-encryption" surface the two
   * establishConnection error() messages (device-id mismatch, encryption refusal) when they
   * propagate; anything else is "Error".
   */
  private fun classifyProbeFailure(cause: Throwable): Pair<String, String> = when {
    cause.isConnectionRefused() -> "Failed-refused" to (cause.message ?: "connection refused")
    cause is TimeoutCancellationException -> "Failed-timeout" to (cause.message ?: "connect/handshake timeout")
    cause.message?.contains("mismatch", ignoreCase = true) == true ->
      "Failed-mismatch" to cause.message.orEmpty()
    cause.message?.contains("encrypted transport", ignoreCase = true) == true ->
      "Failed-encryption" to cause.message.orEmpty()
    else -> "Error" to (cause.message ?: cause::class.simpleName ?: "unknown error")
  }

  private companion object {
    const val TAG = "EagerReachabilityConnector"

    /** How often the re-probe ticker re-evaluates the current visible device set. */
    val REPROBE_INTERVAL = 30.seconds
  }
}
