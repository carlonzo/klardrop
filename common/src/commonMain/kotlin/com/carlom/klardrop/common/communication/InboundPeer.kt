package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.trust.dropSupersededTrust
import com.carlom.klardrop.common.utils.log
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket

/**
 * Re-seed [VisibleDevices] from an inbound handshake. Outbound probes can invalidate every
 * advertised endpoint (multi-homed peer, one black-holed address) and drop the row; the
 * connection that then arrives inbound would otherwise be invisible in the UI.
 */
internal suspend fun rememberInboundPeer(
  visibleDevices: VisibleDevices,
  handshake: HandshakeMessage,
  socket: Socket,
  trustManager: TrustManager? = null,
) {
  val host = peerHost(socket) ?: return
  val port = handshake.listenPort
  if (port <= 0) {
    log("Server", "Inbound ${handshake.deviceId} from $host has no listenPort; not adding a dialable endpoint")
    return
  }
  val info = DeviceInfo(
    deviceId = handshake.deviceId,
    name = handshake.deviceName.ifBlank { handshake.deviceId },
    deviceType = handshake.deviceType,
    osType = handshake.osType,
  )
  visibleDevices.onNewDeviceVisible(info, DeviceConnection.KlardropConnection(host, port))
  log("Server", "Recorded inbound peer ${handshake.deviceId} @ $host:$port")
  if (trustManager != null) {
    dropSupersededTrust(handshake.deviceId, host, visibleDevices, trustManager)
  }
}

internal fun peerHost(socket: Socket): String? {
  val address = socket.remoteAddress as? InetSocketAddress ?: return null
  val host = address.hostname.trim().removePrefix("/")
  if (host.isBlank()) return null
  return host.substringBefore('%')
}
