package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log

/**
 * Was: eagerly GATT-connect BLE-only placeholders so HandshakeMessage identity
 * lands without a user tap. Disabled — Linux (and many phones) host one LE
 * link; a second connect races pair/send and drops handshake notifies.
 *
 * Constructor args stay so Dagger wiring is unchanged. Identity still arrives
 * on the first real connect.
 */
class BleEagerConnector(
  @Suppress("unused") private val coroutines: Coroutines,
  @Suppress("unused") private val visibleDevices: VisibleDevices,
  @Suppress("unused") private val currentDeviceProvider: CurrentDeviceProvider,
  @Suppress("unused") private val client: Client,
  @Suppress("unused") private val connectionsPool: ConnectionsPool,
) {

  fun start() {
    log(TAG, "Eager BLE handshake disabled (single LE slot)")
  }

  fun stop() = Unit

  private companion object {
    const val TAG = "BleEagerConnector"
  }
}
