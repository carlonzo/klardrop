package com.carlom.klardrop.common.permissions

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationNotDetermined
import platform.CoreBluetooth.CBManagerAuthorizationRestricted

/**
 * Apple permission monitor.
 *
 * - Bluetooth status comes straight from [CBCentralManager.authorization].
 * - Local Network state isn't queryable without actively starting an
 *   `NWBrowser` and observing failures, which we already do via
 *   [com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns]. For v1 we report
 *   it as [CapabilityStatus.NotApplicable] — the system will still throw the
 *   first-use prompt, and the desktopJvm impl carries the educational note
 *   for macOS users about that specific prompt.
 */
public actual class PermissionsMonitor {

  // See the Android impl: the system Bluetooth prompt only pauses the app, so
  // [refresh] pings this to force a re-read of CBCentralManager.authorization.
  private val refreshTrigger = MutableSharedFlow<Unit>(
    extraBufferCapacity = 1,
    onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
  )

  public actual fun observe(): Flow<PermissionsState> = refreshTrigger
    .map { snapshot() }
    .onStart { emit(snapshot()) }
    .distinctUntilChanged()

  public actual fun refresh(): Unit {
    refreshTrigger.tryEmit(Unit)
  }

  private fun snapshot(): PermissionsState {
    return PermissionsState(
      capabilities = listOf(
        CapabilityGrant(Capability.BLUETOOTH, bluetoothStatus()),
        CapabilityGrant(Capability.LOCAL_NETWORK, CapabilityStatus.NotApplicable),
        CapabilityGrant(Capability.NOTIFICATIONS, CapabilityStatus.NotApplicable),
        CapabilityGrant(Capability.LOCATION, CapabilityStatus.NotApplicable),
        CapabilityGrant(Capability.NEARBY_WIFI_DEVICES, CapabilityStatus.NotApplicable),
      ),
    )
  }

  private fun bluetoothStatus(): CapabilityStatus {
    return when (CBCentralManager.authorization) {
      CBManagerAuthorizationAllowedAlways -> CapabilityStatus.Granted
      CBManagerAuthorizationDenied,
      CBManagerAuthorizationRestricted -> CapabilityStatus.Denied
      CBManagerAuthorizationNotDetermined -> CapabilityStatus.Unknown
      else -> CapabilityStatus.Unknown
    }
  }
}
