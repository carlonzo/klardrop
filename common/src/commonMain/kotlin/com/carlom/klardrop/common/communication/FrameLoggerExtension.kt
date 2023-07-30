package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.utils.log
import io.ktor.util.*
import io.ktor.websocket.*

class FrameLoggerExtension : WebSocketExtension<Unit> {

  override val factory: WebSocketExtensionFactory<Unit, out WebSocketExtension<Unit>>
    get() = FrameLoggerExtension
  override val protocols: List<WebSocketExtensionHeader>
    get() = emptyList()

  override fun clientNegotiation(negotiatedProtocols: List<WebSocketExtensionHeader>): Boolean {
    log("FrameLoggerExtension", "clientNegotiation")
    return true
  }

  override fun processIncomingFrame(frame: Frame): Frame {
    log("FrameLoggerExtension", "incoming frame: $frame")
    return frame
  }

  override fun processOutgoingFrame(frame: Frame): Frame {
    log("FrameLoggerExtension", "outgoing frame: $frame")
    return frame
  }

  override fun serverNegotiation(requestedProtocols: List<WebSocketExtensionHeader>): List<WebSocketExtensionHeader> {
    log("FrameLoggerExtension", "serverNegotiation")
    return emptyList()
  }

  companion object : WebSocketExtensionFactory<Unit, WebSocketExtension<Unit>> {
    override val key: AttributeKey<WebSocketExtension<Unit>>
      get() = AttributeKey("frame-logger")
    override val rsv1: Boolean
      get() = false
    override val rsv2: Boolean
      get() = false
    override val rsv3: Boolean
      get() = false

    override fun install(config: Unit.() -> Unit): WebSocketExtension<Unit> {
      return FrameLoggerExtension()
    }

  }
}