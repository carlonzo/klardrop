package com.carlom.klardrop.common.ble.mac

import com.carlom.klardrop.common.ble.BleSession
import kotlinx.coroutines.channels.Channel

/**
 * `BleSession` backed by the macOS helper process. Inbound chunks are pushed via
 * [pushChunk]; sends are forwarded to the helper and only return after the helper
 * acknowledges the GATT write.
 *
 * Created by [MacBleHelperProcess] for both central- and peripheral-role sessions —
 * the helper picks the role; the JVM side just deals in chunks.
 */
internal class MacBleHelperSession(
  internal val sessionId: String,
  override val deviceId: String,
  override val mtu: Int,
  private val helper: MacBleHelperProcess,
) : BleSession {

  private val incoming = Channel<ByteArray>(capacity = Channel.UNLIMITED)
  @Volatile private var open = true

  override val isOpen: Boolean get() = open

  override suspend fun sendChunk(chunk: ByteArray) {
    require(chunk.size <= mtu) { "chunk size ${chunk.size} exceeds mtu $mtu" }
    check(open) { "BLE helper session $sessionId closed" }
    helper.sendChunk(sessionId, chunk)
  }

  override suspend fun receiveChunk(): ByteArray? =
    incoming.receiveCatching().getOrNull()

  override fun close() {
    if (!open) return
    open = false
    runCatching { incoming.close() }
    // Best-effort tell the helper to drop the GATT link. The helper still emits a
    // session_closed event which is a no-op since we're already closed.
    helper.scheduleCloseSession(sessionId)
  }

  /** Called by [MacBleHelperProcess] when the helper emits a chunk for this session. */
  internal fun pushChunk(bytes: ByteArray) {
    if (!open) return
    incoming.trySend(bytes)
  }

  /** Called by [MacBleHelperProcess] when the helper reports the session ended. */
  internal fun markRemoteClosed() {
    if (!open) return
    open = false
    runCatching { incoming.close() }
  }
}
