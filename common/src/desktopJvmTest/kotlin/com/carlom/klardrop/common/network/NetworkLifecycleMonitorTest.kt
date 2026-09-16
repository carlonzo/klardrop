package com.carlom.klardrop.common.network

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.test.Test
import kotlin.test.assertSame

class NetworkLifecycleMonitorTest {

  // Three production consumers (ConnectionsPool, EagerReachabilityConnector,
  // DiscoveryNetwork) each collect observe(). A shared instance means a single
  // NIC poll loop per boot instead of one loop per collector.
  @Test
  fun observeReturnsSharedFlowAcrossCollectors() {
    val monitor = NetworkLifecycleMonitor()
    assertSame(monitor.observe(), monitor.observe())
  }

  @Test
  fun eventsSeamStillPassesThrough() {
    val events = MutableSharedFlow<NetworkChangeEvent>()
    val monitor = NetworkLifecycleMonitor(events)
    assertSame(events, monitor.observe())
  }
}
