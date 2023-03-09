package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.envelopes.Envelope
import com.carlom.klardrop.common.communication.router.IncomingMessagesRouter
import io.ktor.serialization.*
import io.ktor.server.websocket.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.core.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// makes handshaking
// receives messages
class ConnectionMessenger(
  val connection: Connection,
  private val incomingMessagesRouter: IncomingMessagesRouter
) : Closeable {

  private val reader: ReceiveChannel<Frame>

  init {

    if (!connection.session.isActive) {
      throw IllegalStateException("Socket is closed")
    }

    reader = connection.session.incoming
  }

  //  activates read from socket
  fun acceptIncomingMessages() {
    connection.session.launch {
      while (isActive) {
        val envelope = connection.session.receiveDeserialized<Envelope>()

        incomingMessagesRouter.onMessageReceived(connection.deviceId, envelope)
      }
    }
  }

  suspend fun <E : Envelope> send(envelope: E) {
    val session = connection.session

    session.converter!!.serialize(session.call.request.headers.suitableCharset(), typeInfo<Any>(), envelope)
  }

  override fun close() {
    reader.cancel()
  }

}