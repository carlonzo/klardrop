package com.carlom.klardrop.common.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothSocket
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [BleSession] over an (insecure) L2CAP CoC [BluetoothSocket]. A central owns the GATT link the
 * channel rides on: [adoptGatt] hands it over so [close] tears both down. The peripheral side has none.
 */
@SuppressLint("MissingPermission")
internal class AndroidL2capBleSession(
  private val socket: BluetoothSocket,
  override val deviceId: String,
) : BleSession {

  override val transport = BleSessionTransport.L2CAP
  override val mtu = BleConstants.L2CAP_CHUNK_SIZE

  private val input = socket.inputStream
  private val output = socket.outputStream
  private val buffer = ByteArray(mtu) // single reader: the bridge read pump
  private val writeLock = Mutex()
  private val closed = AtomicBoolean(false)
  private val gattLock = Any()
  private var gatt: BluetoothGatt? = null

  override val isOpen: Boolean get() = !closed.get()

  init {
    log(TAG, "L2CAP session $deviceId open (maxTx=${socket.maxTransmitPacketSize} maxRx=${socket.maxReceivePacketSize})")
  }

  /** Take over the GATT link; closes it right away if this session is already closed. */
  fun adoptGatt(g: BluetoothGatt) {
    val reject = synchronized(gattLock) {
      if (closed.get()) true else { gatt = g; false }
    }
    if (reject) releaseGatt(g)
  }

  override suspend fun sendChunk(chunk: ByteArray) {
    require(chunk.size <= mtu) { "chunk size ${chunk.size} exceeds mtu $mtu" }
    check(isOpen) { "BLE L2CAP session closed" }
    writeLock.withLock {
      try {
        withContext(Dispatchers.IO) {
          output.write(chunk)
          output.flush()
        }
      } catch (e: IOException) {
        close()
        throw IllegalStateException("L2CAP write failed: ${e.message}", e)
      }
    }
  }

  override suspend fun receiveChunk(): ByteArray? {
    if (!isOpen) return null
    return try {
      val n = withContext(Dispatchers.IO) { input.read(buffer) }
      if (n < 0) {
        close()
        null
      } else {
        buffer.copyOf(n)
      }
    } catch (e: IOException) {
      close()
      null
    }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    runCatching { socket.close() } // unblocks a pending read/write
    val g = synchronized(gattLock) { gatt.also { gatt = null } }
    if (g != null) releaseGatt(g)
  }

  private fun releaseGatt(g: BluetoothGatt) {
    runCatching { g.disconnect() }
    runCatching { g.close() }
  }

  private companion object {
    const val TAG = "AndroidL2capBleSession"
  }
}
