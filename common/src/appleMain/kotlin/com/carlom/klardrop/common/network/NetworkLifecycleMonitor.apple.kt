package com.carlom.klardrop.common.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Apple impl. Currently we don't observe NWPathMonitor / SCNetworkReachability — Bonjour
 * largely handles NIC transitions transparently. What we DO need on iOS is a synthetic
 * "rebuild" event when the app comes back to the foreground: iOS suspends NSNetService
 * publishes while the app is inactive (e.g. screen lock), and they don't auto-resume on
 * resume. The iosMain InternalPlatformDependencies wires UIApplicationDidBecomeActive to
 * call [trigger] so DiscoveryNetwork's existing rebuildMdnsState path re-publishes.
 */
actual class NetworkLifecycleMonitor {

  // extraBufferCapacity=8 so a quick burst of triggers doesn't drop emissions; consumers
  // (DiscoveryNetwork) only react to "something changed" anyway, so collapsing dups is fine.
  private val flow = MutableSharedFlow<NetworkChangeEvent>(extraBufferCapacity = 8)

  actual fun observe(): Flow<NetworkChangeEvent> = flow

  /**
   * Emit a synthetic [NetworkChangeEvent.Changed]. Caller can use this to force
   * downstream consumers to rebuild — currently used by the iOS app-lifecycle observer
   * to recover Bonjour publishes after foreground.
   */
  fun trigger() {
    flow.tryEmit(NetworkChangeEvent.Changed)
  }
}
