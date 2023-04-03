package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.persistence.KlardropProperties
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

internal const val SERVER_PORT = 65221

class Server(
  localPropertiesRepository: LocalPropertiesRepository,
  private val connectionsPool: ConnectionsPool,
  private val coroutines: Coroutines,
  knownDevicesRepository: KnownDevicesRepository,
  private val messagesRouter: MessagesRouter,
  private val serializer: MessageSerializer,
) {

  private val serverScope = CoroutineScope(coroutines.ioDispatcher)
  private val knownDevices =
    knownDevicesRepository.knownDevices.stateIn(serverScope, started = SharingStarted.Eagerly, initialValue = emptyMap())
  private val properties =
    localPropertiesRepository.properties.stateIn(serverScope, started = SharingStarted.Eagerly, initialValue = KlardropProperties(""))

  private fun isAcceptedSender(deviceId: String, receiverAddress: String): Boolean {
    return knownDevices.value.containsKey(deviceId)
  }

  @Suppress("ExtractKtorModule")
  fun startServer(): ApplicationEngine {
    return embeddedServer(CIO, port = SERVER_PORT) {

      install(WebSockets) {
        pingPeriodMillis = 10_000
      }

      routing {
        webSocket("/connect") {
          val remoteAddress = call.request.local.remoteAddress
          log("Server", "New connection from: $remoteAddress")

          onConnectionRequest(this, remoteAddress)
        }
      }

    }.start(wait = false)
  }

  private suspend fun onConnectionRequest(wsSession: DefaultWebSocketServerSession, remoteAddress: String) {
    val request = serializer.deserialize(wsSession.incoming.receive()) as HandshakeMessage

    log("Server", "Connection request from: $remoteAddress - ${request.deviceId}")

    if (isAcceptedSender(request.deviceId, remoteAddress)) {
      val connection = Connection(wsSession, request.deviceId)
      val connectionMessenger = ConnectionMessenger(coroutines, connection, messagesRouter)

      connectionsPool.updateConnection(request.deviceId, connectionMessenger)

      //    send back introduction
      val intro = HandshakeMessage(deviceId = properties.value.deviceId)
      log("Server", "Sending greetings back to ${request.deviceId} on $remoteAddress")
      wsSession.send(serializer.serialize(intro))

      log("Server", "Connection accepted from: $remoteAddress")

      connectionMessenger.acceptIncomingMessages()
    } else {
      log("Server", "Connection rejected from: $remoteAddress")
      wsSession.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Connection rejected"))
    }
  }

}