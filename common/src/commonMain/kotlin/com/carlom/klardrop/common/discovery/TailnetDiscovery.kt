package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.communication.ConnectionsPool
import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.persistence.KnownEndpointsRepository
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Duration.Companion.minutes

/**
 * Finds Klardrop peers over Tailscale, where mDNS can't reach: the tailnet carries no multicast.
 *
 * Candidates are the tailnet addresses remembered from earlier authenticated handshakes
 * ([KnownEndpointsRepository]) plus, where the platform can ask Tailscale, every online peer of
 * the same user. Without that list (mobile), only paired devices' remembered addresses are used. Each is probed on its remembered port and on [defaultPort]. A peer that answers
 * is added to [VisibleDevices] with its tailnet endpoint, and the eager reachability connector
 * makes the real, encrypted connection from there.
 *
 * Runs only while this device has a tailnet address itself. Peers already connected, or heard on
 * the LAN within the last [INTERVAL], are skipped: the LAN path is faster and mDNS keeps them
 * fresh. A LAN entry older than that is likely a cache left behind after leaving the network.
 */
internal class TailnetDiscovery(
  private val coroutines: Coroutines,
  private val visibleDevices: VisibleDevices,
  private val connectionsPool: ConnectionsPool,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val knownEndpoints: KnownEndpointsRepository,
  private val defaultPort: Int,
  private val probe: suspend (address: String, port: Int) -> HandshakeMessage?,
  private val isTrusted: suspend (deviceId: String) -> Boolean,
  private val nowMs: () -> Long,
  private val foreground: StateFlow<Boolean>? = null,
  private val localTailnetAddresses: () -> List<String> = ::localTailnetIpv4s,
  private val tailnetPeers: suspend () -> List<String>? = ::tailscalePeerIpv4s,
) {

  private val scope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)
  private var loopJob: Job? = null
  private var foregroundJob: Job? = null
  private val probing = Mutex()

  /** Probes now, then every minute ([lowPower]: every 5 minutes) and whenever the app comes to the foreground. */
  fun start(lowPower: Boolean) {
    stop()
    val interval = if (lowPower) BACKGROUND_INTERVAL else INTERVAL
    loopJob = scope.launch {
      while (isActive) {
        probeOnce()
        delay(interval)
      }
    }
    foregroundJob = foreground?.let { state ->
      scope.launch {
        state.drop(1).filter { it }.collect { probeOnce() }
      }
    }
  }

  fun stop() {
    loopJob?.cancel()
    foregroundJob?.cancel()
    loopJob = null
    foregroundJob = null
  }

  /** One sweep over every candidate. A sweep already running makes this a no-op. */
  internal suspend fun probeOnce() {
    if (!probing.tryLock()) return
    try {
      val ownAddresses = localTailnetAddresses().toSet()
      if (ownAddresses.isEmpty()) return
      val targets = candidates(ownAddresses)
      if (targets.isEmpty()) return
      log(TAG, "Probing ${targets.size} tailnet address(es)")
      val selfId = currentDeviceProvider.get().shortDeviceId
      coroutineScope {
        targets.map { (address, ports) -> async { probeAddress(address, ports, selfId) } }.awaitAll()
      }
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Exception) {
      log(TAG, "Tailnet sweep failed", e)
    } finally {
      probing.unlock()
    }
  }

  /** Address -> ports to try, in order. */
  private suspend fun candidates(ownAddresses: Set<String>): Map<String, List<Int>> {
    val online = tailnetPeers()?.toSet()
    val known = knownEndpoints.endpoints().values
    val targets = linkedMapOf<String, List<Int>>()
    for (endpoint in known) {
      if (alreadyReachable(endpoint.deviceId)) continue
      // Without Tailscale's own list (mobile), only re-dial paired devices: the eager connector
      // also handshakes with strangers on a shared Wi-Fi, whose tailnet IPs mean nothing here.
      if (online == null && !isTrusted(endpoint.deviceId)) continue
      for (address in endpoint.tailnetAddresses) {
        // When Tailscale can tell us who is online, don't wait on connect timeouts for the rest.
        if (online != null && address !in online) continue
        targets[address] = listOf(endpoint.port, defaultPort).distinct()
      }
    }
    if (online != null) {
      val knownAddresses = known.flatMap { it.tailnetAddresses }.toSet()
      for (address in online - knownAddresses) targets[address] = listOf(defaultPort)
    }
    return targets - ownAddresses
  }

  private suspend fun probeAddress(address: String, ports: List<Int>, selfId: String) {
    for (port in ports) {
      val peer = probe(address, port) ?: continue
      if (peer.deviceId == selfId || alreadyReachable(peer.deviceId)) return
      val listenPort = peer.listenPort.takeIf { it > 0 } ?: port
      log(TAG, "Found ${peer.deviceId} over the tailnet at $address:$listenPort")
      visibleDevices.onNewDeviceVisible(
        DeviceInfo(
          deviceId = peer.deviceId,
          name = peer.deviceName.ifBlank { peer.deviceId },
          deviceType = peer.deviceType,
          osType = peer.osType,
        ),
        DeviceConnection.KlardropConnection(address, listenPort),
      )
      return
    }
  }

  private suspend fun alreadyReachable(deviceId: String): Boolean {
    if (connectionsPool.isAvailable(deviceId)) return true
    val device = visibleDevices.getDevice(deviceId) ?: return false
    if (nowMs() - device.lastSeenTimestamp > INTERVAL.inWholeMilliseconds) return false
    return device.deviceConnections.any {
      it.deviceConnectionType != DeviceConnection.DeviceConnectionType.BLE && !it.address.isCgnatIpv4()
    }
  }

  companion object {
    private const val TAG = "TailnetDiscovery"
    val INTERVAL = 1.minutes
    val BACKGROUND_INTERVAL = 5.minutes
  }
}

/** Remembers where an authenticated peer listens. Never throws: losing this only costs tailnet discovery. */
internal suspend fun KnownEndpointsRepository.recordHandshake(
  handshake: HandshakeMessage,
  remoteHost: String,
  dialedPort: Int = 0,
) {
  val port = handshake.listenPort.takeIf { it > 0 } ?: dialedPort
  val addresses = (handshake.tailnetAddresses + remoteHost.substringBefore('%')).tailnetOnly()
  runCatching { record(handshake.deviceId, port, addresses) }
    .onFailure { log("TailnetDiscovery", "Failed to remember endpoint of ${handshake.deviceId}", it) }
}
