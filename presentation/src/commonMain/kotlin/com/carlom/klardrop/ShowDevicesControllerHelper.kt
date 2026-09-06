package com.carlom.klardrop

import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.Reachability
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class ShowDevicesControllerHelper(
  private val coroutineScope: CoroutineScope,
  private val visibleDevices: StateFlow<Map<String, DiscoveryDevice>>,
  private val messageRepository: MessageRepository,
  private val trustedDevices: StateFlow<Map<String, DeviceInfo>>,
  private val reachabilitySource: StateFlow<Map<String, Reachability>>,
) {

  private val _devicesFlow = MutableStateFlow<Map<String, DeviceUi>>(mapOf())
  val devicesFlow: Flow<Collection<DeviceUi>> = _devicesFlow.map { it.values }

  init {
    coroutineScope.launch {
      combine(
        visibleDevices.onEach { log("VisibleDevices", "emitting: $it") },
        messageRepository.getAllDevicesWithUnreadCounts(),
        reachabilitySource,
        trustedDevices,
      ) { devices, unreadCounts, reachabilityMap, trusted ->

        val visibleRows = devices.values
          .filter { device ->
            val isTrusted = trusted.containsKey(device.deviceInfo.deviceId)
            if (isTrusted) return@filter true
            if (!device.hasKlardropConnection() && !device.hasNearbyConnection()) return@filter false
            val info = device.deviceInfo
            val isRawPlaceholder = info.name.isBlank() ||
              (info.name == info.deviceId && info.deviceType == DeviceType.UNKNOWN)
            !isRawPlaceholder
          }
          .map { device ->
            val deviceInfo = device.deviceInfo
            val unreadCount = unreadCounts[deviceInfo.deviceId] ?: 0L

            val isTrusted = trusted.containsKey(deviceInfo.deviceId)
            val trustStatus = if (isTrusted) TrustStatus.Trusted else TrustStatus.Untrusted

            DeviceUi(
              deviceId = deviceInfo.deviceId,
              deviceName = deviceInfo.name,
              deviceType = deviceInfo.deviceType,
              connectionTypes = device.deviceConnections.map { it.deviceConnectionType }.distinct(),
              hasUnreadMessages = unreadCount > 0,
              trustStatus = trustStatus,
              reachability = reachabilityMap[deviceInfo.deviceId] ?: Reachability.Unknown,
            )
          }

        // A pairing outlives discovery: a trusted device that isn't announcing right now
        // still belongs in "Your devices" — flagged offline — so the user can reach its
        // message history instead of watching the row vanish.
        val offlineRows = trusted
          .filterKeys { deviceId -> !devices.containsKey(deviceId) }
          .map { (deviceId, deviceInfo) ->
            DeviceUi(
              deviceId = deviceId,
              deviceName = deviceInfo.name,
              deviceType = deviceInfo.deviceType,
              connectionTypes = emptyList(),
              hasUnreadMessages = (unreadCounts[deviceId] ?: 0L) > 0,
              trustStatus = TrustStatus.Trusted,
              // Not visible doesn't strictly mean unreachable — a live connection can
              // outlive the mDNS announcement — so keep a Reachable verdict when we have
              // one, and otherwise say Offline rather than Unknown, which renders no badge.
              reachability = reachabilityMap[deviceId]?.takeIf { it == Reachability.Reachable }
                ?: Reachability.Unreachable,
            )
          }

        visibleRows + offlineRows
      }.collect { deviceList ->
        _devicesFlow.emit(deviceList.associateBy { device -> device.deviceId }.toMutableMap())
      }
    }
  }

  suspend fun collectProgress(flow: Flow<MessengerSendProgress>, deviceId: String) {
    flow.collect { progress ->
      _devicesFlow.update { devices ->

        val device = devices[deviceId] ?: return@collect

        val newDevices = devices.toMutableMap()

        val activityState = when (progress) {
          MessengerSendProgress.Completed -> ActivityState.SentCompleted()
          is MessengerSendProgress.Error -> ActivityState.SentCompleted(error = true)
          is MessengerSendProgress.InProgress -> ActivityState.Sending(progress.percentage)
          // The discovery row renders a "Sending…" label and ignores the number, so the
          // pre-transfer phases (dialing, waiting on the recipient's accept) map to the same
          // state as Pending — the row is honestly "busy with this device" throughout.
          MessengerSendProgress.Pending,
          MessengerSendProgress.AwaitingRecipient -> ActivityState.Sending(0)
        }

        newDevices[deviceId] = device.copy(
          activityState = activityState
        )
        newDevices
      }
    }

    // send idle state after 2 seconds
    coroutineScope.launch {
      delay(2.seconds)

      _devicesFlow.update { devices ->
        val device = devices[deviceId] ?: return@launch

        val newDevices = devices.toMutableMap()

        newDevices[deviceId] = device.copy(
          activityState = ActivityState.Idle
        )

        newDevices
      }
    }
  }

}