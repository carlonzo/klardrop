package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.envelopes.Envelope
import com.carlom.klardrop.common.communication.envelopes.IntroductionEnvelope
import com.carlom.klardrop.common.communication.router.IncomingMessagesRouter
import com.carlom.klardrop.common.log
import com.carlom.klardrop.common.persistence.DeviceInfo
import com.carlom.klardrop.common.persistence.KlardropProperties
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.utils.Coroutines
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.protobuf.ProtoBuf

internal const val SERVER_PORT = 65221

class SocketServer(
  private val localPropertiesRepository: LocalPropertiesRepository,
  private val connectionsPool: ConnectionsPool,
  private val coroutines: Coroutines,
  private val flowKnownDevices: Flow<Set<DeviceInfo>>,
  private val incomingMessagesRouter: IncomingMessagesRouter,
) {

  private val serverScope = CoroutineScope(coroutines.ioDispatcher)
  private val knownDevices = flowKnownDevices.stateIn(serverScope, started = SharingStarted.Eagerly, initialValue = emptySet())
  private val properties =
    localPropertiesRepository.properties.stateIn(serverScope, started = SharingStarted.Eagerly, initialValue = KlardropProperties(""))
  private val proto = ProtoBuf

  private fun isAcceptedSender(deviceId: String, receiverAddress: String): Boolean {

    return knownDevices.value.firstOrNull { it.deviceId == deviceId } != null
  }

  fun startServer() {
    embeddedServer(CIO, port = SERVER_PORT) {

      install(WebSockets) {
        pingPeriodMillis = 10_000
        contentConverter = WebSocketEnvelopeContentConverted(proto)
      }

      routing {
        webSocket("/connect") {
          val remoteAddress = call.request.local.remoteAddress
          log("New connection from: $remoteAddress")

          onConnectionRequest(this, remoteAddress)
        }
      }

    }
  }

  private suspend fun onConnectionRequest(wsSession: DefaultWebSocketServerSession, remoteAddress: String) {
    val request = wsSession.receiveDeserialized<IntroductionEnvelope>()

    if (isAcceptedSender(request.deviceId, remoteAddress)) {
      val connection = Connection(wsSession, request.deviceId)
      val connectionMessenger = ConnectionMessenger(connection, incomingMessagesRouter)

      connectionsPool.updateConnection(request.deviceId, connectionMessenger)

      connectionMessenger.acceptIncomingMessages()
      log("Connection accepted from: $remoteAddress")

      //    send back introduction
      val intro = IntroductionEnvelope(properties.value.deviceId)
      sendEnvelope(request.deviceId, intro)
    } else {
      log("Connection rejected from: $remoteAddress")
      wsSession.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Connection rejected"))
    }
  }

  private suspend fun sendEnvelope(receiverDeviceId: String, envelope: Envelope) {
    withContext(coroutines.ioDispatcher) {
      connectionsPool.getConnection(receiverDeviceId)?.send(envelope)
    }
  }

  suspend fun closeConnection(deviceId: String) {
    withContext(coroutines.ioDispatcher) {
      connectionsPool.getConnection(deviceId)?.close()
    }
  }
}