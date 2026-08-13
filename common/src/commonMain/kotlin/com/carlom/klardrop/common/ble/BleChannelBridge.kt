package com.carlom.klardrop.common.ble

import com.carlom.klardrop.common.utils.logLocal
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Adapts a chunk-oriented [BleSession] to the stream-oriented `ByteReadChannel` /
 * `ByteWriteChannel` pair that the existing Klardrop Client/Server code expects.
 *
 * The wire format is identical to the TCP transport (`[4-byte length][type][protobuf]`).
 * This bridge is stream-level only: it does not look at frame boundaries. It just
 * forwards byte order faithfully in both directions, splitting outgoing bytes into
 * MTU-sized GATT writes and appending incoming chunks to the read channel in order.
 * Ktor's existing `sendMessage` / `readMessage` extensions handle length-prefix framing
 * on top, unchanged.
 *
 * Lifecycle:
 *  - Call [start] once; it launches two pump jobs in the provided [scope].
 *  - Close either side (write channel, read channel, or the session) and the bridge
 *    will propagate closure to the other endpoints.
 *  - [close] is idempotent and cancels the pumps.
 */
class BleChannelBridge(
  private val session: BleSession,
  private val scope: CoroutineScope,
) {

  private val outgoing = ByteChannel(autoFlush = true)
  private val incoming = ByteChannel(autoFlush = true)

  /** Bytes written here by callers are chunked and pushed to [session]. */
  val writeChannel: ByteWriteChannel get() = outgoing

  /** Bytes the remote peer sent over [session] show up here in order. */
  val readChannel: ByteReadChannel get() = incoming

  private var writePump: Job? = null
  private var readPump: Job? = null

  /** Launches the write/read pumps. Safe to call once per bridge. */
  fun start(): BleChannelBridge {
    check(writePump == null && readPump == null) { "BleChannelBridge already started" }
    writePump = scope.launch { runWritePump() }
    readPump = scope.launch { runReadPump() }
    return this
  }

  private suspend fun runWritePump() {
    val mtu = session.mtu
    require(mtu > 0) { "BleSession.mtu must be > 0 (was $mtu)" }
    val buffer = ByteArray(mtu)
    try {
      while (true) {
        val read = outgoing.readAvailable(buffer, 0, mtu)
        if (read < 0) break
        if (read == 0) continue
        val chunk = if (read == mtu) buffer.copyOf() else buffer.copyOf(read)
        session.sendChunk(chunk)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      // Pump failures here mean the BLE session ended — peer closed, link lost, or
      // the caller cancelled. All routine; don't pollute Sentry.
      logLocal(TAG, "write pump for ${session.deviceId} failed: ${e.message}", e)
    } finally {
      // If the caller stopped writing, close the session and the inbound side too so that
      // readers unblock and the ConnectionMessenger notices closure.
      runCatching { session.close() }
      runCatching { incoming.close() }
    }
  }

  private suspend fun runReadPump() {
    try {
      while (true) {
        val chunk = session.receiveChunk() ?: break
        if (chunk.isEmpty()) continue
        incoming.writeByteArray(chunk)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      logLocal(TAG, "read pump for ${session.deviceId} failed: ${e.message}", e)
    } finally {
      runCatching { incoming.close() }
      // A closed session means the peer is gone — also close the outbound side so writers
      // see the failure instead of hanging.
      runCatching { outgoing.close() }
    }
  }

  /** Stop both pumps and close the session. Idempotent. */
  fun close() {
    runCatching { writePump?.cancel() }
    runCatching { readPump?.cancel() }
    runCatching { session.close() }
    runCatching { outgoing.close() }
    runCatching { incoming.close() }
  }

  private companion object {
    const val TAG = "BleChannelBridge"
  }
}
