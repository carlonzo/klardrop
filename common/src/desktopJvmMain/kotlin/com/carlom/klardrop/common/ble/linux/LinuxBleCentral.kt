package com.carlom.klardrop.common.ble.linux

import com.carlom.klardrop.common.ble.BlePeerEvent
import com.carlom.klardrop.common.ble.BleSession
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * GATT client (central role) over BlueZ: scans with a service-UUID discovery filter,
 * emits [BlePeerEvent]s as Klardrop Device1 objects appear/disappear, and opens
 * [LinuxBleSession]s to peers (`Device1.Connect` → characteristic resolve → MTU →
 * `StartNotify` on RX). Behavioral mirror of the Apple transport: connect resolves
 * from the scan cache and refuses never-seen addresses.
 *
 * ponytail: notifications that arrive before the session exists are dropped by the
 * `session?` guard — impossible in the Klardrop protocol, where the central speaks
 * first (the peer only notifies in response to the handshake).
 */
class LinuxBleCentral(private val facade: BlueZFacade) {

  /** Addresses reported Found by the facade; connectCentral refuses anything else. */
  private val seenAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()

  fun scanForPeers(): Flow<BlePeerEvent> = callbackFlow {
    facade.onPeerFound { event ->
      seenAddresses.add(event.address)
      trySend(event)
    }
    facade.onPeerLost { address -> trySend(BlePeerEvent.Lost(address)) }
    facade.startScan()
    try {
      awaitClose()
    } finally {
      withContext(NonCancellable) {
        facade.onPeerFound(null)
        facade.onPeerLost(null)
        facade.stopScan()
      }
    }
  }

  /**
   * Opens a GATT connection to the peer at [address] (a MAC address reported Found
   * during a scan) and returns a session ready for I/O.
   *
   * @throws IllegalStateException when the address was never scanned or the connect
   *   does not complete within [CONNECT_TIMEOUT_MS].
   */
  suspend fun connectCentral(address: String, remoteShortDeviceId: String): BleSession {
    check(address in seenAddresses) { "Peer $address not in scan cache; scan first" }
    var session: LinuxBleSession? = null
    val link = try {
      withTimeout(CONNECT_TIMEOUT_MS) {
        facade.connect(
          address,
          onNotify = { value -> session?.pushIncoming(value) },
          onDisconnected = { session?.markRemoteClosed() },
        )
      }
    } catch (e: TimeoutCancellationException) {
      throw IllegalStateException("BLE connect to $address timed out after ${CONNECT_TIMEOUT_MS / 1000}s", e)
    }
    return LinuxBleSession(
      deviceId = remoteShortDeviceId,
      mtu = link.mtu,
      notify = { value -> link.writeTx(value) },
    ).also { session = it }
  }

  private companion object {
    const val CONNECT_TIMEOUT_MS = 10_000L
  }
}
