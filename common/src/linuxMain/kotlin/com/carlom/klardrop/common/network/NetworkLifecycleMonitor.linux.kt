package com.carlom.klardrop.common.network

import com.carlom.klardrop.common.utils.sharedNativeIoDispatcher
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlin.time.Duration.Companion.seconds

/**
 * Linux monitor that polls [getLinuxNetworkInterfaces] for changes.
 *
 * ponytail: scope is never cancelled — the desktop instance is a process-lifetime singleton.
 * TODO: netlink if latency matters
 */
actual class NetworkLifecycleMonitor(
  /**
   * Test seam: fixed event flow instead of live NIC polling. Production call
   * sites use the default (null -> poll getifaddrs every 5 s).
   */
  private val events: Flow<NetworkChangeEvent>? = null,
) {

  // One shared poll loop no matter how many consumers call observe()
  private val pollScope = CoroutineScope(SupervisorJob() + sharedNativeIoDispatcher)
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
  }.flowOn(sharedNativeIoDispatcher)

  private fun snapshot(): Set<InterfaceFingerprint> =
    getLinuxNetworkInterfaces()
      .filter { !it.isLoopback }
      .filterNot { isVirtualOrDroppedInterface(it.name) }
      .map { InterfaceFingerprint(it.name, it.isUp, it.ipv4Addresses.sorted()) }
      .toSet()

  private data class InterfaceFingerprint(
    val name: String,
    val isUp: Boolean,
    val ipv4: List<String>,
  )

  private companion object {
    val POLL_INTERVAL = 5.seconds
  }
}
