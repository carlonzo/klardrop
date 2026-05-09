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

  fun onDeviceLost(deviceId: String)
  fun onDeviceLost(deviceId: String, deviceConnectionToRemove: DeviceConnection)

  fun findDeviceByAddress(address: InetSocketAddress): DiscoveryDevice?

}

internal class VisibleDevicesImpl(
  private val coroutines: Coroutines,
  private val clock: Clock
) : VisibleDevices {

  private companion object {
    val deviceTTLVisibility = 90.seconds
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
      }
    }
  }

  private fun cleanupStaleDevices() {
    val currentTime = clock.currentTimeMillis()

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

  override fun onDeviceLost(deviceId: String) {
    visibleDevicesFlow.update {
      it.toMutableMap().also { map -> map.remove(deviceId) }
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
        val now = clock.currentTimeMillis()
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