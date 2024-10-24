package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.persistence.KlardropProperties
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


class Server(
  private val connectionsPool: ConnectionsPool,
  private val coroutines: Coroutines,
  private val messagesRouter: MessagesRouter,
  private val serializer: MessageSerializer,
  private val currentDeviceProvider: CurrentDeviceProvider
) {

  private fun isAcceptedSender(deviceId: String, receiverAddress: String): Boolean {
    return true // always accept for now. should only accept if known? or just hold the connection if known?
  }


  @Suppress("ExtractKtorModule")
  /**
   * Starts the server and returns the configuration of the engine connector.
   *
   * @return The configuration of the engine connector.
   */
  suspend fun startServer(): EngineConnectorConfig {

    val server = embeddedServer(CIO, port = 0) {

      install(WebSockets) {

        pingPeriodMillis = 10_000
        timeoutMillis = 10_000

        extensions { install(FrameLoggerExtension) }
      }

      routing {
        webSocket("/connect") {
          val remoteAddress = call.request.local.remoteAddress
          log("Server", "New connection from: $remoteAddress")

          onConnectionRequest(this, remoteAddress)
        }
      }

    }
    server.start(wait = false)

    val config = server.engineConfig.connectors.first()

    log("Server", "Server started on ${config.host}:${config.port}")

    return config
  }
// 36645 n 36951
  private suspend fun onConnectionRequest(wsSession: DefaultWebSocketServerSession, remoteAddress: String) {
    val request = serializer.deserialize(wsSession.incoming.receive()) as HandshakeMessage

    log("Server", "Connection request from: $remoteAddress - ${request.deviceId}")

    if (isAcceptedSender(request.deviceId, remoteAddress)) {
      val connection = Connection(wsSession, request.deviceId)
      val connectionMessenger = ConnectionMessenger(coroutines, connection, messagesRouter)

      connectionsPool.updateConnection(request.deviceId, connectionMessenger)

      //    send back introduction
      val deviceId = currentDeviceProvider.get().shortDeviceId
      val intro = HandshakeMessage(deviceId)
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