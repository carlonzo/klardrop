package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.trust.TrustStorage
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Every paired device, with the best identity we know for it — whether or not it is
 * currently discoverable.
 *
 * [VisibleDevices] only holds peers that are announcing right now, so a trusted device that
 * is asleep, on another network, or simply has the app closed disappears from it entirely.
 * That is the right behaviour for "Nearby", but a pairing is a durable relationship: the user
 * expects a paired device to stay in "Your devices" (flagged offline) and to be able to open
 * its message history at any time.
 *
 * Discovery is also the only place a friendly name / device type is ever learned, so this
 * class snapshots the identity of each trusted peer into [KnownDevicesRepository] while the
 * peer is visible, and replays it from disk while it is not. Devices paired before that
 * snapshot existed are backfilled the first time they are seen again; until then they fall
 * back to a short form of their device id.
 */
class TrustedDevicesDirectory(
  private val visibleDevices: VisibleDevices,
  private val knownDevicesRepository: KnownDevicesRepository,
  private val trustStorage: TrustStorage,
  /** Fires whenever the trust store is written; see [TrustManager.trustChanges]. */
  trustChanges: Flow<Unit>,
  coroutines: Coroutines,
) {

  private val scope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)

  private val refreshes = MutableStateFlow(0)

  private val _trustedDevices = MutableStateFlow<Map<String, DeviceInfo>>(emptyMap())

  // What we last handed to [knownDevicesRepository]. Every write feeds back through its flow
  // and re-enters sync(); if a store ever round-tripped an identity to something slightly
  // different we would otherwise rewrite it on every pass forever. Only touched from the
  // single sync coroutine.
  private val lastPersisted = mutableMapOf<String, DeviceInfo>()

  /**
   * Trusted devices keyed by device id, including the ones that are not currently visible.
   * Callers that need to know whether a device is reachable should consult
   * [VisibleDevices] / reachability separately — presence here only means "paired".
   */
  val trustedDevices: StateFlow<Map<String, DeviceInfo>> = _trustedDevices.asStateFlow()

  init {
    scope.launch {
      trustChanges.collect { refresh() }
    }

    scope.launch {
      combine(
        visibleDevices.visibleDevices,
        knownDevicesRepository.knownDevices.catch { error ->
          // The store holds protobuf-encoded identities; a decode/IO failure must not take
          // the directory down, it would strand the trusted list at whatever it last held.
          log(TAG, "Unable to read known devices", error)
          emit(emptyMap())
        },
        refreshes,
      ) { visible, known, _ -> visible to known }
        .collect { (visible, known) -> sync(visible, known) }
    }
  }

  /**
   * Re-read the trust store now.
   *
   * Wired to [TrustManager.trustChanges] already; exposed for callers that mutate trust
   * storage by some other route, and so tests can force a pass.
   */
  fun refresh() {
    refreshes.update { it + 1 }
  }

  private suspend fun sync(visible: Map<String, DiscoveryDevice>, known: Map<String, DeviceInfo>) {
    val trustedIds = runCatching { trustStorage.getAllTrustedDevices().keys }
      .onFailure { log(TAG, "Unable to read trusted devices", it) }
      .getOrElse { return }

    val resolved = trustedIds.associateWith { deviceId ->
      bestIdentity(deviceId, live = visible[deviceId]?.deviceInfo, stored = known[deviceId])
    }

    // Persist what discovery has taught us so the identity outlives the peer going offline
    // (this is also what picks up a rename, on the next sighting).
    resolved.forEach { (deviceId, identity) ->
      val isNew = identity != known[deviceId] && identity != lastPersisted[deviceId]
      if (visible.containsKey(deviceId) && isNew) {
        lastPersisted[deviceId] = identity
        runCatching { knownDevicesRepository.addKnownDevice(identity) }
          .onFailure { log(TAG, "Unable to persist identity for $deviceId", it) }
      }
    }

    // Drop identities we no longer hold a pairing for.
    known.keys.filterNot { it in trustedIds }.forEach { deviceId ->
      lastPersisted.remove(deviceId)
      runCatching { knownDevicesRepository.removeKnownDevice(deviceId) }
        .onFailure { log(TAG, "Unable to drop identity for $deviceId", it) }
    }

    _trustedDevices.value = resolved
  }

  /**
   * Merge the live and stored snapshots field by field, always upgrading and never
   * downgrading — BLE discovery announces a placeholder identity (name = device id, UNKNOWN
   * types) that would otherwise wipe a good stored name.
   */
  private fun bestIdentity(deviceId: String, live: DeviceInfo?, stored: DeviceInfo?): DeviceInfo {
    val name = listOfNotNull(live?.name, stored?.name, visibleDevices.cachedNameFor(deviceId))
      .firstOrNull { it.isNotBlank() && it != deviceId }
      ?: deviceId.take(SHORT_DEVICE_ID_LENGTH)

    return DeviceInfo(
      deviceId = deviceId,
      name = name,
      deviceType = listOfNotNull(live?.deviceType, stored?.deviceType)
        .firstOrNull { it != DeviceType.UNKNOWN } ?: DeviceType.UNKNOWN,
      osType = listOfNotNull(live?.osType, stored?.osType)
        .firstOrNull { it != OsType.UNKNOWN } ?: OsType.UNKNOWN,
    )
  }

  private companion object {
    const val TAG = "TrustedDevicesDirectory"

    /** Matches `CurrentDevice.shortDeviceId`, the id form already shown elsewhere. */
    const val SHORT_DEVICE_ID_LENGTH = 8
  }
}
