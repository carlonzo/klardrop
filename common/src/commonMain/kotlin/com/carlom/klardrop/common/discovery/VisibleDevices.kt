package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import io.ktor.network.sockets.InetSocketAddress
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.invoke
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

interface VisibleDevices {

  val visibleDevices: StateFlow<Map<String, DiscoveryDevice>>

  suspend fun onNewDeviceVisible(deviceInfo: DeviceInfo, deviceConnection: DeviceConnection)

  fun isDeviceVisible(deviceId: String): Boolean

  fun getDevice(deviceId: String): DiscoveryDevice?

  /**
   * Best-effort lookup of a previously-seen friendly name for [deviceId], even if the
   * device is no longer in the visible map. Backed by the in-memory identity cache that
   * the BLE/mDNS discovery enriches; survives staleness eviction. Returns `null` when we
   * have never learned a real name (e.g. only a placeholder shortId snapshot, or the cache
   * is cold). Caller is expected to fall back to the deviceId or a generic label.
   */
  fun cachedNameFor(deviceId: String): String?

  /**
   * Refresh [DiscoveryDevice.lastSeenTimestamp] for [deviceId] so the periodic stale-
   * eviction loop doesn't drop a peer we know is alive via another channel (TCP
   * heartbeat, BLE GATT). Called from the ConnectionMessenger PONG path so currently-
   * connected peers never time out, regardless of whether their mDNS announcement
   * triggers a fresh NsdManager onServiceFound/onServiceUpdated event.
   *
   * No-op if [deviceId] isn't in the visible map.
   */
  fun touchLastSeen(deviceId: String)

  fun onDeviceLost(deviceId: String)
  fun onDeviceLost(deviceId: String, deviceConnectionToRemove: DeviceConnection)

  fun findDeviceByAddress(address: InetSocketAddress): DiscoveryDevice?

  /**
   * Remove a specific [KlardropConnection] endpoint (address+port) from the cached device
   * entry. Called when a dial to that endpoint is refused (ECONNREFUSED / ConnectException),
   * which means the peer restarted and its old ephemeral port is dead. Removing the stale
   * endpoint prevents repeated dials to the dead port while mDNS rediscovery delivers the
   * fresh SRV. If removing the endpoint leaves the device with no connections at all, the
   * device is removed from the visible map entirely so callers don't see a ghost entry.
   *
   * No-op when [deviceId] is unknown or the endpoint is not cached.
   */
  fun invalidateKlardropEndpoint(deviceId: String, address: String, port: Int)

}

class VisibleDevicesImpl(
  private val coroutines: Coroutines,
  private val clock: Clock,
  /**
   * Epoch-millis time source. Defaults to [clock]; overridable in tests to drive the
   * grace-window and TTL logic deterministically without real-time waits.
   */
  private val nowMs: () -> Long = { clock.currentTimeMillis() },
  /**
   * Returns the local device's short id. Announcements carrying this id are the local
   * machine seeing its own advertisement (multi-NIC bindings, protocol twin, stale
   * publications) and must never enter the visible list. This is the single choke
   * point every transport routes through, so self-visibility is structurally
   * impossible regardless of per-transport filtering upstream. Null disables the
   * filter (tests, headless wiring).
   */
  private val selfDeviceId: suspend () -> String? = { null },
) : VisibleDevices {

  private companion object {
    // mDNS NsdManager (Android) only fires onServiceFound / onServiceLost — it does NOT
    // periodically re-emit onServiceUpdated for stable services that simply keep
    // refreshing their TXT/SRV records. So our internal TTL has no fresh-event source
    // for a peer that's announcing steadily; if we set it too short we evict perfectly-
    // alive devices. 5 minutes gives the system mDNS its own TTL window to expire stale
    // records (NsdManager fires onServiceLost when the underlying record actually
    // expires, typically 75 min) while still removing genuinely-departed peers within a
    // reasonable window. ConnectionMessenger.touchLastSeen() additionally refreshes the
    // timestamp from the TCP heartbeat path so an actively-connected peer never expires
    // here even if its mDNS announcement is missed by our browser.
    val deviceTTLVisibility = 5.minutes

    // Grace window for bare onDeviceLost(deviceId) (no address) removals.
    //
    // On Android, NsdManager.onServiceLost delivers an UNRESOLVED ServiceInfo (no IP
    // addresses), so DiscoveryNetwork falls through to the bare onDeviceLost(deviceId)
    // path which previously deleted the entire device entry immediately. This is wrong
    // when the device is actively connected: the TCP heartbeat refreshes lastSeenTimestamp
    // via touchLastSeen(), so a fresh timestamp is evidence the peer is alive.
    //
    // If the device's lastSeenTimestamp is within this window we skip the removal and
    // defer to the TTL sweep, which will remove it after deviceTTLVisibility if no
    // further liveness signal arrives.
    //
    // INVARIANT: this window MUST be comfortably larger than the TCP heartbeat interval
    // (HeartbeatConfig.interval, currently 15 s). The heartbeat refreshes lastSeenTimestamp
    // via touchLastSeen() on every inbound frame, so an actively-connected peer's age stays
    // below one interval; keeping the window > interval guarantees such a peer is always
    // shielded from a spurious bare onDeviceLost. 30 s gives ~2× headroom over the 15 s
    // interval (tolerating a missed beat) while staying far below the 5-minute TTL, so a
    // genuinely-departed peer whose heartbeats have stopped is still evicted promptly.
    // If the heartbeat interval is ever raised, raise this window to stay > interval.
    val lostEventGraceWindow = 30.seconds
  }

  private val visibleDevicesFlow = MutableStateFlow(emptyMap<String, DiscoveryDevice>())

  // Identity cache that survives staleness eviction. Once the BLE handshake
  // enriches a device with friendly name + types, those facts are remembered;
  // when the same deviceId is re-discovered later we re-apply them immediately
  // instead of regressing to "<shortId>, UNKNOWN, UNKNOWN".
  private val identityCache = mutableMapOf<String, DeviceInfo>()

  override val visibleDevices: StateFlow<Map<String, DiscoveryDevice>> = visibleDevicesFlow.asStateFlow()

  private val cleanupScope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)

  init {
    // Start periodic cleanup of stale devices
    cleanupScope.launch {
      while (isActive) {
        delay(30.seconds)
        cleanupStaleDevices()
        // Snapshot the current visibility map every cleanup tick. Discovery flake
        // bugs are notoriously hard to debug from event logs alone (devices appear
        // and disappear, and you can't tell from a single line whether the *current*
        // state is empty or just one removal in a longer sequence). This periodic
        // dump gives us a known-state checkpoint when the user reports "I see
        // nothing" — we can scroll back to the last dump and see what the app
        // actually thought it had.
        val snapshot = visibleDevicesFlow.value
        if (snapshot.isEmpty()) {
          log("VisibleDevices", "snapshot: <empty>")
        } else {
          val rows = snapshot.values.joinToString(separator = "\n  ") { d ->
            val transports = d.deviceConnections.map { it.deviceConnectionType }.distinct()
            val ageSec = (nowMs() - d.lastSeenTimestamp) / 1000
            "${d.deviceInfo.name} id=${d.deviceInfo.deviceId} transports=$transports age=${ageSec}s"
          }
          log("VisibleDevices", "snapshot (${snapshot.size}):\n  $rows")
        }
      }
    }
  }

  private fun cleanupStaleDevices() {
    val currentTime = nowMs()

    val staleDevices = visibleDevicesFlow.value.filter { (deviceId, device) ->
      val isStale = (currentTime - device.lastSeenTimestamp) > deviceTTLVisibility.inWholeMilliseconds
      if (isStale) {
        log(
          "VisibleDevices",
          "Removing stale device: ${device.deviceInfo.name} (id: $deviceId), last seen ${(currentTime - device.lastSeenTimestamp) / 1000}s ago"
        )
      }
      isStale
    }

    if (staleDevices.isNotEmpty()) {
      visibleDevicesFlow.update { currentMap ->
        currentMap.toMutableMap().apply {
          staleDevices.keys.forEach { remove(it) }
        }
      }
    }
  }

  override suspend fun onNewDeviceVisible(deviceInfo: DeviceInfo, deviceConnection: DeviceConnection) {

    // Choke-point self filter: the local device's own announcement (re-broadcast
    // across our multiple mDNS bindings, or echoed by any transport) must never
    // become a visible "peer".
    if (deviceInfo.deviceId == selfDeviceId()) {
      log("VisibleDevices", "Ignoring self announcement (id=${deviceInfo.deviceId}, ${deviceConnection.deviceConnectionType})")
      return
    }

    val isNew = addDevice(deviceInfo, deviceConnection)

    if (isNew)
      log("VisibleDevices", "new device: $deviceInfo isNew: $isNew connections: ${deviceConnection.deviceConnectionType}")
  }

  override fun isDeviceVisible(deviceId: String): Boolean {
    return visibleDevicesFlow.value.containsKey(deviceId)
  }

  override fun getDevice(deviceId: String): DiscoveryDevice? {
    return visibleDevicesFlow.value[deviceId]
  }

  override fun cachedNameFor(deviceId: String): String? {
    val live = visibleDevicesFlow.value[deviceId]?.deviceInfo
    val cached = identityCache[deviceId]
    val candidates = listOfNotNull(live, cached)
    return candidates
      .firstOrNull { it.name.isNotBlank() && it.name != it.deviceId }
      ?.name
  }

  override fun touchLastSeen(deviceId: String) {
    visibleDevicesFlow.update { current ->
      val device = current[deviceId] ?: return@update current
      current.toMutableMap().apply {
        put(deviceId, device.copy(lastSeenTimestamp = nowMs()))
      }
    }
  }

  override fun onDeviceLost(deviceId: String) {
    visibleDevicesFlow.update { current ->
      val device = current[deviceId] ?: return@update current
      val ageMs = nowMs() - device.lastSeenTimestamp
      if (ageMs < lostEventGraceWindow.inWholeMilliseconds) {
        // The device's lastSeenTimestamp is fresh — it is likely alive via a TCP
        // heartbeat or another transport. Skip this removal and let the TTL sweep
        // evict it if liveness signals stop arriving.
        log(
          "VisibleDevices",
          "Ignoring bare onDeviceLost for ${device.deviceInfo.name} (id: $deviceId): lastSeen ${ageMs}ms ago (within grace window)"
        )
        return@update current
      }
      current.toMutableMap().also { map -> map.remove(deviceId) }
    }
  }

  override fun onDeviceLost(deviceId: String, deviceConnectionToRemove: DeviceConnection) {

    if (deviceConnectionToRemove is DeviceConnection.NearbyConnection && deviceConnectionToRemove.port == 0 && deviceConnectionToRemove.address.isEmpty()) {
      onDeviceLost(deviceId)
      return
    }

    visibleDevicesFlow.update { currentMap ->
      currentMap.toMutableMap().also { map ->

        val device = map[deviceId] ?: return@also

        val newConnections = device.deviceConnections.filterNot { it == deviceConnectionToRemove }

        if (newConnections.isEmpty()) {
          map.remove(deviceId)
        } else {
          map[deviceId] = device.copy(deviceConnections = newConnections)
        }

      }
    }
  }

  /**
   * Pick the richer of two `DeviceInfo` snapshots for the same device. Treats a
   * name equal to the deviceId as a placeholder (BLE discovery sets it that way).
   */
  private fun mergeDeviceInfo(existing: DeviceInfo, incoming: DeviceInfo): DeviceInfo {
    val name = pickRicherName(existing, incoming)
    val deviceType = if (incoming.deviceType != DeviceType.UNKNOWN) incoming.deviceType else existing.deviceType
    val osType = if (incoming.osType != OsType.UNKNOWN) incoming.osType else existing.osType
    return existing.copy(name = name, deviceType = deviceType, osType = osType)
  }

  private fun pickRicherName(a: DeviceInfo, b: DeviceInfo): String {
    val aIsPlaceholder = a.name.isBlank() || a.name == a.deviceId
    val bIsPlaceholder = b.name.isBlank() || b.name == b.deviceId
    return when {
      !bIsPlaceholder -> b.name
      !aIsPlaceholder -> a.name
      else -> b.name.ifBlank { a.name }
    }
  }

  override fun findDeviceByAddress(address: InetSocketAddress): DiscoveryDevice? {
    val hostname = address.hostname

    return visibleDevicesFlow.value.values.firstOrNull { device -> device.deviceConnections.any { it.address == hostname } }
  }

  override fun invalidateKlardropEndpoint(deviceId: String, address: String, port: Int) {
    val staleConnection = DeviceConnection.KlardropConnection(address = address, port = port)
    log("VisibleDevices", "Invalidating stale Klardrop endpoint for $deviceId @ $address:$port")
    onDeviceLost(deviceId, staleConnection)
  }

  /**
   * @return true if the device was never seen before
   */
  private suspend fun addDevice(deviceInfo: DeviceInfo, deviceConnection: DeviceConnection): Boolean {
    return coroutines.ioDispatcher {
      val existing = visibleDevicesFlow.value[deviceInfo.deviceId]
      val containsAlready = existing != null

      if (existing != null) {
        // Skip the early return when the incoming DeviceInfo carries a richer
        // identity than what's stored — we still need to fall through to the
        // merge step below so the friendly name / type can land on the entry.
        val existingIsRicher =
          (existing.deviceInfo.name.isNotBlank() && existing.deviceInfo.name != existing.deviceInfo.deviceId) &&
            existing.deviceInfo.deviceType != DeviceType.UNKNOWN &&
            existing.deviceInfo.osType != OsType.UNKNOWN
        val incomingIsPlaceholder = deviceInfo.name == deviceInfo.deviceId &&
          deviceInfo.deviceType == DeviceType.UNKNOWN && deviceInfo.osType == OsType.UNKNOWN
        if (existing.deviceConnections.contains(deviceConnection) && (existingIsRicher || incomingIsPlaceholder)) {
          return@ioDispatcher false
        }

      }

      visibleDevicesFlow.update {
        val now = nowMs()
        // Seed from the existing entry if present, otherwise from the identity
        // cache so a re-discovery after eviction picks up the previously-learned
        // friendly identity.
        val seedInfo = it[deviceInfo.deviceId]?.deviceInfo
          ?: identityCache[deviceInfo.deviceId]
          ?: deviceInfo
        val storedDiscoveryDevice = it[deviceInfo.deviceId]
          ?: DiscoveryDevice(seedInfo, lastSeenTimestamp = now)

        val newConnections = storedDiscoveryDevice.deviceConnections
          // removes connections same connection type and address. Probably new connection with new port that did not expire yet from mdns
          .filterNot { it.deviceConnectionType == deviceConnection.deviceConnectionType && it.address == deviceConnection.address }
          .toMutableList().also { it.add(deviceConnection) }

        // Merge identity fields: prefer the richer one from either side. The BLE
        // discovery layer surfaces a placeholder DeviceInfo with name=shortId and
        // UNKNOWN type/os; the BLE handshake later supplies the real values. mDNS
        // already arrives rich. Always upgrade, never downgrade.
        val mergedInfo = mergeDeviceInfo(seedInfo, deviceInfo)
        // Remember the enriched identity for future eviction-recovery cycles.
        if (mergedInfo.name != mergedInfo.deviceId || mergedInfo.deviceType != DeviceType.UNKNOWN || mergedInfo.osType != OsType.UNKNOWN) {
          identityCache[deviceInfo.deviceId] = mergedInfo
        }

        it.toMutableMap().apply {

          put(
            deviceInfo.deviceId, storedDiscoveryDevice.copy(
              deviceInfo = mergedInfo,
              deviceConnections = newConnections,
              lastSeenTimestamp = now
            )
          )
        }

      }

      !containsAlready
    }

  }

}