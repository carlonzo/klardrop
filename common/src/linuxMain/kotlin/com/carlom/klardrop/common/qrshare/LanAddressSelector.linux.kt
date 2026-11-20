package com.carlom.klardrop.common.qrshare

import com.carlom.klardrop.common.utils.sharedNativeIoDispatcher
import com.carlom.klardrop.common.network.getLinuxNetworkInterfaces
import com.carlom.klardrop.common.network.isVirtualOrDroppedInterface
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

internal actual class PlatformLanAddressSelector actual constructor() : LanAddressSelector {

  actual override suspend fun selectIpv4(): String? = withContext(sharedNativeIoDispatcher) {
    selectLanAddressOf(enumerateInterfaces())
  }

  actual override suspend fun selectManualLanAddress(): ManualLanAddress? = withContext(sharedNativeIoDispatcher) {
    selectManualLanAddress(enumerateInterfaces())
  }

  // Polls getifaddrs every 5s for changes.
  // ponytail: scope is never cancelled — singleton lifecycle.
  // TODO: netlink if latency matters
  actual override fun observeChanges(): Flow<String?> = flow {
    var previous = selectLanAddressOf(enumerateInterfaces())
    emit(previous)
    while (true) {
      delay(POLL_INTERVAL)
      val current = selectLanAddressOf(enumerateInterfaces())
      if (current != previous) {
        previous = current
        emit(current)
      }
    }
  }.distinctUntilChanged().flowOn(sharedNativeIoDispatcher)

  private fun enumerateInterfaces(): List<LanInterfaceAddress> =
    getLinuxNetworkInterfaces()
      .filter { it.isUp && !it.isLoopback }
      .filterNot { isVirtualOrDroppedInterface(it.name) }
      .flatMap { it.addresses }

  private companion object {
    val POLL_INTERVAL = 5.seconds
  }
}
