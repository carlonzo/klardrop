package com.carlom.klardrop.common.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Apple no-op implementation. iOS / macOS native have richer reachability APIs
 * (NWPathMonitor, SCNetworkReachability) that we can wire up later. For now
 * the platform's own mDNS stack (Bonjour) typically handles network transitions
 * transparently, so the cost of skipping this here is low.
 */
actual class NetworkLifecycleMonitor {

  actual fun observe(): Flow<NetworkChangeEvent> = emptyFlow()
}
