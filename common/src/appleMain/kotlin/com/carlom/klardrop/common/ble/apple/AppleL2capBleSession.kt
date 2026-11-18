package com.carlom.klardrop.common.ble.apple

import com.carlom.klardrop.common.ble.BleConstants
import com.carlom.klardrop.common.ble.BleSession
import com.carlom.klardrop.common.ble.BleSessionTransport
import com.carlom.klardrop.common.utils.log
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.CoreBluetooth.CBL2CAPChannel
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.NSStream
import platform.Foundation.NSStreamDelegateProtocol
import platform.Foundation.NSStreamEvent
import platform.Foundation.NSStreamEventEndEncountered
import platform.Foundation.NSStreamEventErrorOccurred
import platform.Foundation.NSStreamEventHasBytesAvailable
import platform.Foundation.NSStreamEventHasSpaceAvailable
import platform.Foundation.NSStreamStatusAtEnd
import platform.Foundation.NSStreamStatusClosed
import platform.Foundation.NSStreamStatusError
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * [BleSession] over a CoreBluetooth L2CAP CoC channel. All NSStream access happens on the main
 * thread / run loop (NSStream is not thread-safe); the delegate only signals, never reads.
 * [channel] is held strongly for the session lifetime: CoreBluetooth closes it on dealloc.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AppleL2capBleSession(
  private val channel: CBL2CAPChannel,
  override val deviceId: String,
  private val onClose: () -> Unit,
) : BleSession {

  override val transport: BleSessionTransport get() = BleSessionTransport.L2CAP
  override val mtu: Int get() = BleConstants.L2CAP_CHUNK_SIZE

  @Volatile private var open = true
  private val closeRequested = AtomicInt(0)
  override val isOpen: Boolean get() = open

  private val readable = Channel<Unit>(Channel.CONFLATED)
  private val writable = Channel<Unit>(Channel.CONFLATED)
  private val writeLock = Mutex()
  private val readBuf = ByteArray(BleConstants.L2CAP_CHUNK_SIZE)

  // NSStream.delegate is weak: keep a strong reference.
  private val delegate = object : NSObject(), NSStreamDelegateProtocol {
    override fun stream(aStream: NSStream, handleEvent: NSStreamEvent) {
      when {
        handleEvent and NSStreamEventHasBytesAvailable != 0uL -> readable.trySend(Unit)
        handleEvent and NSStreamEventHasSpaceAvailable != 0uL -> writable.trySend(Unit)
        handleEvent and (NSStreamEventErrorOccurred or NSStreamEventEndEncountered) != 0uL -> markClosed()
      }
    }
  }

  /** Schedules and opens both streams on the main run loop. */
  suspend fun open() {
    withContext(Dispatchers.Main) {
      for (s in listOf<NSStream>(channel.inputStream!!, channel.outputStream!!)) {
        s.delegate = delegate
        s.scheduleInRunLoop(NSRunLoop.mainRunLoop, forMode = NSDefaultRunLoopMode)
        s.open()
      }
    }
    log(TAG, "L2CAP session $deviceId open psm=${channel.PSM}")
  }

  override suspend fun receiveChunk(): ByteArray? = withContext(Dispatchers.Main) {
    val input = channel.inputStream!!
    while (true) {
      if (!open) return@withContext null
      if (input.hasBytesAvailable) {
        val n = readBuf.usePinned { input.read(it.addressOf(0).reinterpret(), readBuf.size.toULong()) }
        if (n > 0) return@withContext readBuf.copyOf(n.toInt())
        markClosed() // 0 = EOF, <0 = error
        return@withContext null
      }
      val st = input.streamStatus
      if (st == NSStreamStatusAtEnd || st == NSStreamStatusError || st == NSStreamStatusClosed) {
        markClosed()
        return@withContext null
      }
      if (readable.receiveCatching().isClosed) return@withContext null
    }
    @Suppress("UNREACHABLE_CODE")
    null
  }

  override suspend fun sendChunk(chunk: ByteArray) {
    require(chunk.size <= mtu) { "chunk size ${chunk.size} exceeds mtu $mtu" }
    check(open) { "L2CAP session $deviceId closed" }
    writeLock.withLock {
      withContext(Dispatchers.Main) {
        val out = channel.outputStream!!
        var offset = 0
        while (offset < chunk.size) {
          check(open) { "L2CAP session $deviceId closed" }
          if (out.hasSpaceAvailable) {
            val n = chunk.usePinned { out.write(it.addressOf(offset).reinterpret(), (chunk.size - offset).toULong()) }
            if (n <= 0) {
              markClosed()
              throw IllegalStateException("L2CAP write failed: ${out.streamError?.localizedDescription}")
            }
            offset += n.toInt()
          } else if (writable.receiveCatching().isClosed) {
            throw IllegalStateException("L2CAP session $deviceId closed")
          }
        }
      }
    }
  }

  /** Marks the session dead and wakes any waiter; teardown happens in [close]. */
  private fun markClosed() {
    open = false
    readable.close()
    writable.close()
  }

  override fun close() {
    if (!closeRequested.compareAndSet(0, 1)) return
    markClosed()
    dispatch_async(dispatch_get_main_queue()) {
      for (s in listOf<NSStream?>(channel.inputStream, channel.outputStream)) {
        s ?: continue
        s.delegate = null
        s.removeFromRunLoop(NSRunLoop.mainRunLoop, forMode = NSDefaultRunLoopMode)
        s.close()
      }
      onClose()
    }
  }

  private companion object {
    const val TAG = "AppleL2capBleSession"
  }
}
