package com.carlom.klardrop.common.communication.message

import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
enum class ConnectionKind {
  WIFI_OPEN,
  WIFI_WEP,
  WIFI_WPA,
  WIFI_WPA2,
  WIFI_WPA3,
  WIFI_WPA_ENTERPRISE,
}

@Serializable
data class ConnectionInfoMessage(
  val kind: ConnectionKind,
  val ssid: String,
  val password: String? = null,
  val hidden: Boolean = false,
  override val id: Int = Random.nextInt(),
) : Message() {

  override val type: MessageType = MessageType.CONNECTION_INFO
  override val hasPayload: Boolean = false
}
