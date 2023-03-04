package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.envelopes.IntroductionEnvelope
import com.carlom.klardrop.common.utils.Coroutines
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.nio.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Sink
import okio.Source
import okio.buffer

// makes handshaking
// receives messages
class ConnectionMessenger(
  private val socket: Socket,
  private val coroutines: Coroutines,
) : Closeable {

  private val reader: ByteReadChannel
  private val writer: ByteWriteChannel
  private val connectionScope = CoroutineScope(coroutines.ioDispatcher)

  init {

    if (socket.isClosed) {
      throw IllegalStateException("Socket is closed")
    }

    reader = socket.openReadChannel()
    writer = socket.openWriteChannel(autoFlush = true)
  }

//  activates read from socket
  fun acceptIncomingMessages() {

    connectionScope.launch {


      while (isActive && !reader.isClosedForRead) {
        reader.awaitContent()


        // read type
        val type = reader.readInt()

        // read payload
        val buffer = okio.Buffer()
        reader.readFully(buffer)

        val payload = buffer.readByteArray()
      }
    }

  }



  // connection request is made
  // communicate device id
  suspend fun makeHandshake(intro: IntroductionEnvelope) {
    sendStaticEnvelope(intro)
  }

  suspend fun sendStaticEnvelope(intro: IntroductionEnvelope) {
    withContext(coroutines.ioDispatcher) {

      writer.writePacket {
        writeInt(intro.type.type)

        val payload = intro.payload
        writeByteBufferDirect(payload.size) { it.put(payload) }
      }

    }

  }

  override fun close() {
    reader.cancel()
    writer.close()
    connectionScope.cancel()
  }

}

const val OKIO_RECOMMENDED_BUFFER_SIZE = 8192
suspend fun ByteReadChannel.readFully(sink: Sink) {
  val channel = this
  sink.buffer().use { sink ->
    while (!channel.isClosedForRead) {
      // TODO: Allocating a new packet on every copy isn't great. Find a faster way to move bytes.
      val packet = channel.readRemaining(OKIO_RECOMMENDED_BUFFER_SIZE.toLong())
      while (!packet.isEmpty) {
        sink.write(packet.readBytes())
      }
    }
  }
}

@Suppress("NAME_SHADOWING")
suspend fun ByteWriteChannel.writeAll(source: Source) {
  val channel = this
  var bytesRead: Int
  val buffer = ByteArray(OKIO_RECOMMENDED_BUFFER_SIZE)

  source.buffer().use { source ->
    while (source.read(buffer).also { bytesRead = it } != -1 && !channel.isClosedForWrite) {
      channel.writeFully(buffer, offset = 0, length = bytesRead)
      channel.flush()
    }
  }
}