package com.carlom.klardrop.common.communication.message

import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
public enum class ConnectionKind {
  WIFI_OPEN,
  WIFI_WEP,
  WIFI_WPA,
  WIFI_WPA2,
  WIFI_WPA3,
  WIFI_WPA_ENTERPRISE,
}

@Serializable
public data class ConnectionInfoMessage(
  public val kind: ConnectionKind,
  public val ssid: String,
  public val password: String? = null,
  public val hidden: Boolean = false,
  override val id: Int = Random.nextInt(),
) : Message() {

  override val type: MessageType = MessageType.CONNECTION_INFO
  override val hasPayload: Boolean = false
}
