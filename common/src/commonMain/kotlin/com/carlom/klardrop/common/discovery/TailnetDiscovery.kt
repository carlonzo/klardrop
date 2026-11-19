package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.communication.ConnectionsPool
import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.persistence.KnownEndpoint
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
 * Also re-probes manually added LAN addresses.
 *
 * Candidates are the tailnet addresses remembered from earlier authenticated handshakes
 * ([KnownEndpointsRepository]) plus, where the platform can ask Tailscale, every online peer of
 * the same user, along with any manually added LAN endpoints.
 * Without that list (mobile), only paired devices' remembered tailnet addresses are used.
 * Manually added LAN addresses are always probed without a tailnet or trust gate.
 * Each candidate is probed on its remembered port and on [defaultPort]. A peer that answers
 * is added to [VisibleDevices] with its endpoint, and the eager reachability connector
 * makes the real, encrypted connection from there.
 *
 * Tailnet candidate probing runs only while this device has a tailnet address itself. Peers already connected,
 * or heard on the LAN within the last [INTERVAL], are skipped: the LAN path is faster and mDNS keeps them
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
  /** Returns the current device's own LAN IPv4 addresses (for subnet-matching LAN probes). */
  private val ownLanAddresses: suspend () -> List<String> = { emptyList() },
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
      val known = knownEndpoints.endpoints().values
      // Enumerating interfaces is only worth it when there are saved LAN addresses to match.
      val ownLan = if (known.any { it.lanAddresses.isNotEmpty() }) ownLanAddresses() else emptyList()
      val manualOwners = mutableMapOf<String, String>() // saved LAN address -> device id
      val targets = candidates(known, ownAddresses, ownLan, manualOwners)
      if (targets.isEmpty()) return
      log(TAG, "Probing ${targets.size} address(es)")
      val selfId = currentDeviceProvider.get().shortDeviceId
      coroutineScope {
        targets.map { (address, ports) -> async { probeAddress(address, ports, selfId, manualOwners[address]) } }.awaitAll()
      }
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Exception) {
      log(TAG, "Sweep failed", e)
    } finally {
      probing.unlock()
    }
  }

  /** Address -> ports to try, in order. */
  private suspend fun candidates(
    known: Collection<KnownEndpoint>,
    ownAddresses: Set<String>,
    ownLanIps: List<String>,
    manualOwners: MutableMap<String, String>,
  ): Map<String, List<Int>> {
    // Manual LAN targets: only probe a saved LAN address if it shares the first two octets
    // with one of this device's current Wi-Fi/Ethernet IPv4s (privacy fix: no cross-network probing).
    val manualTargets = linkedMapOf<String, List<Int>>()
    if (ownLanIps.isNotEmpty()) {
      for (endpoint in known) {
        if (endpoint.lanAddresses.isEmpty()) continue
        if (alreadyReachable(endpoint.deviceId)) continue
        val ports = listOf(endpoint.port, defaultPort).distinct()
        for (address in endpoint.lanAddresses) {
          if (ownLanIps.any { sharesTwoOctets(address, it) }) {
            manualTargets[address] = ports
            manualOwners[address] = endpoint.deviceId
          }
        }
      }
    }

    // Tailnet targets: gated by having an own tailnet address and isTrusted filter
    val tailnetTargets = linkedMapOf<String, List<Int>>()
    if (ownAddresses.isNotEmpty()) {
      val online = tailnetPeers()?.toSet()
      for (endpoint in known) {
        if (alreadyReachable(endpoint.deviceId)) continue
        // Without Tailscale's own list (mobile), only re-dial paired devices: the eager connector
        // also handshakes with strangers on a shared Wi-Fi, whose tailnet IPs mean nothing here.
        if (online == null && !isTrusted(endpoint.deviceId)) continue
        for (address in endpoint.tailnetAddresses) {
          // When Tailscale can tell us who is online, don't wait on connect timeouts for the rest.
          if (online != null && address !in online) continue
          tailnetTargets[address] = listOf(endpoint.port, defaultPort).distinct()
        }
      }
      if (online != null) {
        val knownAddresses = known.flatMap { it.tailnetAddresses }.toSet()
        for (address in online - knownAddresses) tailnetTargets[address] = listOf(defaultPort)
      }
    }

    val merged = linkedMapOf<String, List<Int>>()
    merged.putAll(manualTargets)
    for ((address, ports) in tailnetTargets) {
      merged[address] = (merged[address].orEmpty() + ports).distinct()
    }
    return merged - ownAddresses
  }

  suspend fun addManually(
    addresses: List<String>,
    port: Int,
    expectedDeviceId: String?,
  ): ManualAddResult {
    val selfId = currentDeviceProvider.get().shortDeviceId
    val ports = listOf(port, defaultPort).distinct()
    // Build the ordered list of (address, port) candidates
    val candidates = addresses.flatMap { address -> ports.map { p -> address to p } }

    // Probe all candidates in parallel, but decide in candidate order: the first usable answer wins
    // as soon as every earlier candidate has finished, and the rest are cancelled.
    return coroutineScope {
      val probes = candidates.map { (address, p) -> async { probe(address, p) } }
      var hadMismatchedDeviceId = false
      var sawSelf = false
      try {
        for ((idx, deferred) in probes.withIndex()) {
          val peer = deferred.await() ?: continue
          val (address, p) = candidates[idx]
          // Our own answer may be one of several candidates (short code + several own LAN addresses): keep scanning.
          if (peer.deviceId == selfId) {
            if (expectedDeviceId == null || expectedDeviceId == selfId) sawSelf = true else hadMismatchedDeviceId = true
            continue
          }
          if (expectedDeviceId != null && peer.deviceId != expectedDeviceId) {
            hadMismatchedDeviceId = true
            continue
          }

          val listenPort = peer.listenPort.takeIf { it > 0 } ?: p
          val deviceName = peer.deviceName.ifBlank { peer.deviceId }
          knownEndpoints.recordManual(peer.deviceId, listenPort, address)

          if (alreadyReachable(peer.deviceId)) {
            return@coroutineScope ManualAddResult.AlreadyVisible(deviceName)
          }

          log(TAG, "Found ${peer.deviceId} via manual LAN add at $address:$listenPort")
          registerVisibleDevice(peer, address, listenPort)
          return@coroutineScope ManualAddResult.Added(deviceName)
        }
      } finally {
        probes.forEach { it.cancel() }
      }
      if (hadMismatchedDeviceId) ManualAddResult.WrongDevice
      else if (sawSelf) ManualAddResult.ThisDevice
      else ManualAddResult.Unreachable("${addresses.firstOrNull().orEmpty()}:$port")
    }
  }

  /** [manualOwner]: id of the device a saved manual LAN [address] belongs to; a matching answer refreshes its expiry. */
  private suspend fun probeAddress(address: String, ports: List<Int>, selfId: String, manualOwner: String? = null) {
    for (port in ports) {
      val peer = probe(address, port) ?: continue
      if (manualOwner != null && peer.deviceId == manualOwner) {
        knownEndpoints.recordManual(peer.deviceId, peer.listenPort.takeIf { it > 0 } ?: port, address)
      }
      if (peer.deviceId == selfId || alreadyReachable(peer.deviceId)) return
      val listenPort = peer.listenPort.takeIf { it > 0 } ?: port
      log(TAG, "Found ${peer.deviceId} at $address:$listenPort")
      registerVisibleDevice(peer, address, listenPort)
      return
    }
  }

  private suspend fun registerVisibleDevice(peer: HandshakeMessage, address: String, listenPort: Int) {
    visibleDevices.onNewDeviceVisible(
      DeviceInfo(
        deviceId = peer.deviceId,
        name = peer.deviceName.ifBlank { peer.deviceId },
        deviceType = peer.deviceType,
        osType = peer.osType,
      ),
      DeviceConnection.KlardropConnection(address, listenPort),
    )
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
  val cleanHost = remoteHost.substringBefore('%')
  val addresses = (handshake.tailnetAddresses + cleanHost).tailnetOnly()
  runCatching {
    record(handshake.deviceId, port, addresses)
  }
    .onFailure { log("TailnetDiscovery", "Failed to remember endpoint of ${handshake.deviceId}", it) }
}

/** True when both IPv4 addresses share their first two octets (same /16: a cheap "same network" test). */
internal fun sharesTwoOctets(a: String, b: String): Boolean {
  val x = a.split('.')
  val y = b.split('.')
  return x.size == 4 && y.size == 4 && x[0] == y[0] && x[1] == y[1]
}
