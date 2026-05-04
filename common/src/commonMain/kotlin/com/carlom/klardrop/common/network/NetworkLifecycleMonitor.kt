package com.carlom.klardrop.common.network

import kotlinx.coroutines.flow.Flow

/**
 * Surfaces coarse network-state changes to the rest of the app — typically
 * triggered by NIC up/down, IP rotation, or sleep/wake (post-wake state usually
 * presents as a fresh round of NIC events). Consumers (mDNS, ConnectionsPool,
 * eager probers) listen and rebuild their state from scratch.
 *
 * This is intentionally coarse: a single [NetworkChangeEvent.Changed] tells
 * everyone to re-evaluate. Implementations debounce as needed to avoid
 * thrashing during transient changes.
 */
expect class NetworkLifecycleMonitor {

  fun observe(): Flow<NetworkChangeEvent>
}

sealed interface NetworkChangeEvent {
  /** The set of available network interfaces / connectivity has changed. */
  data object Changed : NetworkChangeEvent
}
