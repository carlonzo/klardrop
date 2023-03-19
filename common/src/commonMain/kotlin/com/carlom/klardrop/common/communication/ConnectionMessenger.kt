package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.envelopes.Envelope
import com.carlom.klardrop.common.communication.router.IncomingMessagesRouter
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.*
import io.ktor.server.websocket.*
import io.ktor.util.reflect.*
import io.ktor.websocket.*
import io.ktor.websocket.serialization.*

class ConnectionMessenger internal constructor(
  private val coroutineScope: Coroutines,
  val connection: Connection,
  private val incomingMessagesRouter: IncomingMessagesRouter
) {


  init {

    if (connection.session.isClosed()) {
      throw IllegalStateException("Socket with ${connection.deviceId} is closed. Reason: ${connection.session.closeReason.getCompleted()}")
    }

  }

  //  activates read from socket
  suspend fun acceptIncomingMessages() {
    log("ConnectionMessenger: Reading from socket with ${connection.deviceId}")

    val wsSession = connection.session

    while (!wsSession.incoming.isClosedForReceive) {

      val envelope = connection.session.receiveDeserialized<Envelope>()

      incomingMessagesRouter.onMessageReceived(connection.deviceId, envelope)
    }


    log("ConnectionMessenger: Stop listening for messages from ${connection.deviceId} ${connection.session.closeReason.isCompleted}")
    log("ConnectionMessenger: Close reason ${connection.session.closeReason.await()}")
  }

  suspend fun <E : Envelope> send(envelope: E) {
    connection.session.sendSerialized(envelope)
  }

}

internal suspend inline fun <reified T> DefaultWebSocketSession.receiveDeserialized(): T {

  return when (this) {
    is DefaultClientWebSocketSession -> {
      receiveDeserializedBase<T>(
        converter ?: throw WebsocketConverterNotFoundException("No converter was found for websocket"),
        call.request.headers.suitableCharset()
      ) as T
    }

    is DefaultWebSocketServerSession -> {
      receiveDeserializedBase<T>(
        converter ?: throw WebsocketConverterNotFoundException("No converter was found for websocket"),
        call.request.headers.suitableCharset()
      ) as T
    }

    else -> throw WebsocketConverterNotFoundException("Current websocket session is not supported")
  }

}

internal suspend fun <T : Envelope> DefaultWebSocketSession.sendSerialized(envelope: T) {
  val type = typeInfo<Any>()

  val frame = when (this) {
    is DefaultClientWebSocketSession -> {
      converter!!.serializeNullable(
        charset = call.request.headers.suitableCharset(),
        typeInfo = type,
        value = envelope
      )

    }

    is DefaultWebSocketServerSession -> {
      converter!!.serializeNullable(
        charset = call.request.headers.suitableCharset(),
        typeInfo = type,
        value = envelope
      )
    }

    else -> throw WebsocketConverterNotFoundException("Current websocket session is not supported")
  }

  outgoing.send(frame)
}