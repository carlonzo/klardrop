package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.ble.BleChannelBridge
import com.carlom.klardrop.common.ble.BleSession
import com.carlom.klardrop.common.ble.BleTransport
import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Hosts the Klardrop BLE GATT service and turns each accepted [BleSession] into a
 * fully-initialised [ConnectionMessenger] in the [ConnectionsPool], mirroring what
 * [Server.handleKlardropConnection] does for TCP sockets.
 *
 * Call [start] once when the app comes up; cancel [stop] (or let the app shut down) to
 * tear down the GATT server.
 */
class BleServerListener(
  private val coroutines: com.carlom.klardrop.common.utils.Coroutines,
  private val bleTransport: BleTransport,
  private val serializer: MessageSerializer,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val messagesRouter: MessagesRouter,
  private val connectionsPool: ConnectionsPool,
  private val visibleDevices: VisibleDevices,
  private val ackTimeoutConfig: AckTimeoutConfig = AckTimeoutConfig.DEFAULT,
  private val heartbeatConfig: HeartbeatConfig = HeartbeatConfig.DEFAULT,
) {

  private val scope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)
  private var serveJob: Job? = null

  fun start() {
    if (serveJob?.isActive == true) return
    serveJob = scope.launch {
      if (!bleTransport.isSupported()) {
        log(TAG, "BLE not supported; GATT server not starting")
        return@launch
      }
      log(TAG, "Starting Klardrop GATT server")
      bleTransport.serveGatt()
        .onEach { session -> scope.launch { acceptSession(session) } }
        .collect {}
    }
  }

  fun stop() {
    serveJob?.cancel()
    serveJob = null
  }

  private suspend fun acceptSession(session: BleSession) {
    val bridge = BleChannelBridge(session, scope).start()
    try {
      // Central speaks first with its HandshakeMessage.
      val clientHandshake = bridge.readChannel.readMessage(serializer) as HandshakeMessage
      log(TAG, "BLE central ${session.deviceId} announced as ${clientHandshake.deviceId} (${clientHandshake.deviceName})")

      // Enrich the discovered-device entry with the friendly identity carried in
      // the handshake. BLE advertisements only ship the shortDeviceId — the rich
      // info arrives only after a Klardrop-to-Klardrop GATT session is open.
      if (clientHandshake.deviceName.isNotEmpty()) {
        runCatching {
          visibleDevices.onNewDeviceVisible(
            DeviceInfo(
              deviceId = clientHandshake.deviceId,
              name = clientHandshake.deviceName,
              deviceType = clientHandshake.deviceType,
              osType = clientHandshake.osType,
            ),
            DeviceConnection.BleConnection(address = session.deviceId),
          )
        }
      }

      val connection = Connection.Ble(session, clientHandshake.deviceId)
      val connectionMessenger = ConnectionMessenger(
        coroutines = coroutines,
        connection = connection,
        messagesRouter = messagesRouter,
        readChannel = bridge.readChannel,
        writeChannel = bridge.writeChannel,
        ackTimeoutConfig = ackTimeoutConfig,
        heartbeatConfig = heartbeatConfig,
        messageSerializer = serializer,
      )
      connectionsPool.updateConnection(clientHandshake.deviceId, connectionMessenger)

      // Reply with our handshake so the central side can unblock its read.
      val self = currentDeviceProvider.get()
      bridge.writeChannel.sendMessage(
        HandshakeMessage(
          deviceId = self.shortDeviceId,
          deviceName = self.deviceName,
          osType = self.osType,
          deviceType = self.deviceType,
        ),
        serializer,
      )

      scope.launch { connectionMessenger.acceptIncomingMessages() }
    } catch (t: Throwable) {
      log(TAG, "Handshake failed with BLE central ${session.deviceId}: ${t.message}", t)
      bridge.close()
    }
  }

  private companion object {
    const val TAG = "BleServerListener"
  }
}
