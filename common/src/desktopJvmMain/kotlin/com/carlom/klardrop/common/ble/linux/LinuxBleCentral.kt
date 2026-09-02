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
    val pending = PendingSession()
    val link = try {
      withTimeout(CONNECT_TIMEOUT_MS) {
        facade.connect(
          address,
          onNotify = pending::pushIncoming,
          onDisconnected = pending::markRemoteClosed,
        )
      }
    } catch (e: TimeoutCancellationException) {
      throw IllegalStateException("BLE connect to $address timed out after ${CONNECT_TIMEOUT_MS / 1000}s", e)
    }
    return LinuxBleSession(
      deviceId = remoteShortDeviceId,
      mtu = link.mtu,
      notify = { value -> link.writeTx(value) },
    ).also(pending::attach)
  }

  private companion object {
    const val CONNECT_TIMEOUT_MS = 10_000L
  }
}

/**
 * Bridges the gap between wiring the facade's callbacks and having a session to hand
 * them to: [BlueZFacade.connect] subscribes to RX notifications and to the peer's
 * disconnect signal before it returns the link the session is built from, so both can
 * fire on a D-Bus signal thread while [attach] has not run yet. Buffering here (rather
 * than reading a plain captured `var`, which is neither published safely to that thread
 * nor able to remember an early disconnect) means no chunk is dropped and a peer that
 * drops immediately still closes the session.
 */
private class PendingSession {

  private val lock = Any()
  private var session: LinuxBleSession? = null
  private val buffered = mutableListOf<ByteArray>()
  private var remoteClosed = false

  fun pushIncoming(value: ByteArray): Unit = synchronized(lock) {
    val live = session
    if (live == null) buffered.add(value) else live.pushIncoming(value)
  }

  fun markRemoteClosed(): Unit = synchronized(lock) {
    val live = session
    if (live == null) remoteClosed = true else live.markRemoteClosed()
  }

  /** Publishes [newSession] under the lock, then replays what arrived before it existed. */
  fun attach(newSession: LinuxBleSession) = synchronized(lock) {
    session = newSession
    buffered.forEach(newSession::pushIncoming)
    buffered.clear()
    if (remoteClosed) newSession.markRemoteClosed()
  }
}
