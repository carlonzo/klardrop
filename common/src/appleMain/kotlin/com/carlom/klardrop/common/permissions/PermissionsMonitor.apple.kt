package com.carlom.klardrop.common.permissions

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
actual class PermissionsMonitor {

  actual fun observe(): Flow<PermissionsState> {
    val capabilities = mutableMapOf<Capability, CapabilityStatus>()
    capabilities[Capability.BLUETOOTH] = bluetoothStatus()
    capabilities[Capability.LOCAL_NETWORK] = CapabilityStatus.NotApplicable
    capabilities[Capability.NOTIFICATIONS] = CapabilityStatus.NotApplicable
    capabilities[Capability.LOCATION] = CapabilityStatus.NotApplicable
    capabilities[Capability.NEARBY_WIFI_DEVICES] = CapabilityStatus.NotApplicable

    return flowOf(PermissionsState(capabilities = capabilities))
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
