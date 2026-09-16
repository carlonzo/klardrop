package com.carlom.klardrop.common.network

import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.time.Duration.Companion.seconds

/**
 * JVM monitor that polls [NetworkInterface] for changes. Java has no portable
 * push-notification for NIC events, so we snapshot the set of (interface name,
 * isUp, IPv4 addresses) every [POLL_INTERVAL] and emit when it differs.
 *
 * Sleep/wake on macOS/Linux/Windows reliably tears down and re-creates NICs, so
 * polling catches both: sleep → NICs go down or addresses disappear; wake →
 * they come back up, often with the same address but with the underlying
 * sockets already broken. Either transition surfaces as a [NetworkChangeEvent.Changed].
 */
actual class NetworkLifecycleMonitor(
  /**
   * Test seam: fixed event flow instead of live NIC polling. Production call
   * sites use the default (null -> poll [NetworkInterface] every 5 s).
   */
  private val events: Flow<NetworkChangeEvent>? = null,
) {

  // One shared poll loop no matter how many consumers call observe() (ConnectionsPool,
  // EagerReachabilityConnector, DiscoveryNetwork each collect). Previously every observe()
  // built a fresh cold flow, so a single boot ran three identical 5s NIC poll loops.
  // ponytail: scope is never cancelled — the desktop instance is a process-lifetime singleton.
  private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val sharedPoll: SharedFlow<NetworkChangeEvent> by lazy {
    pollUpstream().shareIn(pollScope, SharingStarted.Lazily)
  }

  actual fun observe(): Flow<NetworkChangeEvent> = events ?: sharedPoll

  private fun pollUpstream(): Flow<NetworkChangeEvent> = flow {
    var previous = snapshot()
    log("NetworkLifecycleMonitor", "starting NIC polling; initial snapshot=${previous.size}")
    while (true) {
      delay(POLL_INTERVAL)
      val current = snapshot()
      if (current != previous) {
        log(
          "NetworkLifecycleMonitor",
          "network change detected: prev=${previous.size} curr=${current.size}"
        )
        previous = current
        emit(NetworkChangeEvent.Changed)
      }
    }
  }.flowOn(Dispatchers.IO)

  private fun snapshot(): Set<InterfaceFingerprint> {
    return runCatching {
      val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptySet()
      buildSet {
        for (iface in ifaces) {
          if (iface.isLoopback) continue
          val isUp = runCatching { iface.isUp }.getOrDefault(false)
          val addrs = iface.inetAddresses.toList()
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
            .toSortedSet()
          add(InterfaceFingerprint(iface.name, isUp, addrs.toList()))
        }
      }
    }.getOrElse {
      log("NetworkLifecycleMonitor", "snapshot failed: ${it.message}")
      emptySet()
    }
  }

  private data class InterfaceFingerprint(
    val name: String,
    val isUp: Boolean,
    val ipv4: List<String>,
  )

  private companion object {
    val POLL_INTERVAL = 5.seconds
  }
}
