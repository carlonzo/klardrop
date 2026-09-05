package com.carlom.klardrop.common.ble.linux

import com.carlom.klardrop.common.ble.BleSession
import kotlin.concurrent.Volatile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * `BleSession` over BlueZ D-Bus characteristics, shared by the peripheral role
 * ([LinuxBlePeripheral]) and the central role. Send semantics mirror the Apple
 * session: a single write-in-flight under a mutex so chunks sent in order arrive
 * in order — the write "ack" is the notify/write D-Bus call returning.
 *
 * ponytail: no pendingSends queue — D-Bus notify has no queue-full callback (unlike
 * CoreBluetooth's peripheralManagerIsReady), so a queue would have nothing to drain
 * it; a failed notify means the link is broken and the error propagates to the caller.
 */
internal class LinuxBleSession(
  override val deviceId: String,
  override val mtu: Int,
  private val notify: suspend (ByteArray) -> Unit,
  private val onClose: () -> Unit = {},
) : BleSession {

  private val incoming = Channel<ByteArray>(capacity = Channel.UNLIMITED)
  private val writeLock = Mutex()
  @Volatile private var open = true

  override val isOpen: Boolean get() = open

  override suspend fun sendChunk(chunk: ByteArray) {
    require(chunk.size <= mtu) { "chunk size ${chunk.size} exceeds mtu $mtu" }
    check(open) { "Linux BLE session $deviceId closed" }
    writeLock.withLock { notify(chunk) }
  }

  override suspend fun receiveChunk(): ByteArray? =
    incoming.receiveCatching().getOrNull()

  override fun close() {
    if (!open) return
    open = false
    runCatching { onClose() }
    runCatching { incoming.close() }
  }

  /** Pushes a chunk written by the remote peer onto the receive channel. */
  internal fun pushIncoming(bytes: ByteArray) {
    if (!open || bytes.isEmpty()) return
    incoming.trySend(bytes)
  }

  /** Marks the session dead after the remote unsubscribed/disconnected. Idempotent. */
  internal fun markRemoteClosed() = close()
}
