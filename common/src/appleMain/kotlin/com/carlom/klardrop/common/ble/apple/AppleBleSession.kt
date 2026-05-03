package com.carlom.klardrop.common.ble.apple

import com.carlom.klardrop.common.ble.BleSession
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * `BleSession` shared between central and peripheral roles on Apple platforms.
 * Send semantics are abstracted via [sender]; the session maintains a single
 * write-in-flight discipline so chunks are ordered, and exposes hooks for the
 * platform delegates to push inbound data and ack outbound writes.
 */
internal class AppleBleSession(
  override val deviceId: String,
  override val mtu: Int,
  private val sender: (ByteArray) -> Unit,
  private val closer: () -> Unit,
) : BleSession {

  private val incoming = Channel<ByteArray>(capacity = Channel.UNLIMITED)
  private val writeLock = Mutex()
  @Volatile private var open = true

  // For peripheral role: queue of (bytes, deferred) waiting on
  // peripheralManagerIsReadyToUpdateSubscribers.
  private val pendingSends = ArrayDeque<Pair<ByteArray, CompletableDeferred<Unit>>>()
  // For both roles: ack waiter for the in-flight write.
  private var inFlight: CompletableDeferred<Unit>? = null

  override val isOpen: Boolean get() = open

  override suspend fun sendChunk(chunk: ByteArray) {
    require(chunk.size <= mtu) { "chunk size ${chunk.size} exceeds mtu $mtu" }
    check(open) { "Apple BLE session $deviceId closed" }
    writeLock.withLock {
      val ack = CompletableDeferred<Unit>()
      inFlight = ack
      try {
        sender(chunk)
      } catch (t: Throwable) {
        // Notify queue full or write threw — defer until ready.
        inFlight = null
        pendingSends.addLast(chunk to ack)
        ack.await()
        return@withLock
      }
      ack.await()
    }
  }

  override suspend fun receiveChunk(): ByteArray? =
    incoming.receiveCatching().getOrNull()

  override fun close() {
    if (!open) return
    open = false
    runCatching { incoming.close() }
    runCatching { closer() }
  }

  internal fun pushIncoming(bytes: ByteArray) {
    if (!open || bytes.isEmpty()) return
    incoming.trySend(bytes)
  }

  internal fun completeNextWriteAck(success: Boolean) {
    val deferred = inFlight ?: return
    inFlight = null
    if (success) deferred.complete(Unit)
    else deferred.completeExceptionally(IllegalStateException("write failed"))
  }

  internal fun markRemoteClosed() {
    if (!open) return
    open = false
    runCatching { incoming.close() }
    inFlight?.completeExceptionally(IllegalStateException("session closed"))
    pendingSends.forEach { (_, d) -> d.completeExceptionally(IllegalStateException("session closed")) }
    pendingSends.clear()
  }

  internal fun retryPendingSends() {
    while (pendingSends.isNotEmpty()) {
      val (bytes, ack) = pendingSends.removeFirst()
      try {
        inFlight = ack
        sender(bytes)
      } catch (t: Throwable) {
        // Still not ready — put it back and stop.
        inFlight = null
        pendingSends.addFirst(bytes to ack)
        return
      }
    }
  }
}

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData = memScoped {
  if (isEmpty()) return NSData.create(bytes = allocArrayOf(byteArrayOf()), length = 0u)
  this@toNSData.usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = this@toNSData.size.toULong())
  }
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
  val len = length.toInt()
  if (len == 0) return ByteArray(0)
  val out = ByteArray(len)
  out.usePinned { pinned ->
    memcpy(pinned.addressOf(0), this@toByteArray.bytes, length)
  }
  return out
}
