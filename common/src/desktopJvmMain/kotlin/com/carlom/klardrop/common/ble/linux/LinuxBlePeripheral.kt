package com.carlom.klardrop.common.ble.linux

import com.carlom.klardrop.common.ble.BleSession
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * GATT server (peripheral role) over BlueZ: exports the Klardrop service via
 * [BlueZFacade.exportApplication] and emits a [LinuxBleSession] every time a remote
 * central subscribes to the RX characteristic. Cancelling the flow unregisters the
 * application and closes all live sessions.
 */
class LinuxBlePeripheral(private val facade: BlueZFacade) {

  fun serveGatt(): Flow<BleSession> = callbackFlow {
    val sessions = ConcurrentHashMap<String, LinuxBleSession>()

    facade.onCharacteristicWrite { centralId, value ->
      sessions[centralId]?.pushIncoming(value)
    }
    facade.onCentralSubscription { centralId, subscribed ->
      if (subscribed) {
        // Re-subscribe replaces any stale session for the same central.
        sessions.remove(centralId)?.markRemoteClosed()
        val session = LinuxBleSession(
          deviceId = centralId,
          mtu = facade.mtu,
          notify = { facade.notifyValue(centralId, it) },
        )
        sessions[centralId] = session
        trySend(session)
      } else {
        sessions.remove(centralId)?.markRemoteClosed()
      }
    }

    facade.exportApplication()

    try {
      awaitClose()
    } finally {
      withContext(NonCancellable) {
        facade.onCharacteristicWrite(null)
        facade.onCentralSubscription(null)
        sessions.values.forEach { it.markRemoteClosed() }
        facade.unregisterApplication()
      }
    }
  }
}
