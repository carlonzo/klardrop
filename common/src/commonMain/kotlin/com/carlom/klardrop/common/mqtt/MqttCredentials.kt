package com.carlom.klardrop.common.mqtt

/**
 * Everything the platform MQTT client needs in order to connect to the broker
 * **as a specific device**, plus the routing prefix the device may use.
 *
 *   brokerToken      = MQTT password (broker JWT, audience=klardrop-mqtt-broker)
 *   mqttClientId     = MQTT clientId, must match what the device-registry told
 *                      EMQX to expect (`klardrop_<userId>_<deviceId>`)
 *   topicScope       = e.g. "klardrop/v1/users/usr_abc..."; all valid pub/sub
 *                      paths live under this prefix
 *   expiresAtEpochMs = wall-clock when the broker JWT expires; client should
 *                      refresh at expiresAtEpochMs - REFRESH_LEAD_MS
 */
data class MqttCredentials(
    val brokerUrl: String,
    val brokerToken: String,
    val mqttClientId: String,
    val userId: String,
    val deviceId: String,
    val topicScope: String,
    val expiresAtEpochMs: Long,
    val ttlSeconds: Long
) {
    companion object {
        const val REFRESH_LEAD_MS: Long = 60_000L
    }
}

/**
 * Topic helpers that mirror the ACL emitted by the device-registry at
 * `BrokerAuthService` (see `cloud-backend/.../services/BrokerAuthService.kt`).
 *
 * Single source of truth on the client — every publish goes through one of
 * these so a typo can't accidentally leak outside the user scope.
 */
class MqttTopics(private val topicScope: String) {
    fun presence(deviceId: String) = "$topicScope/presence/$deviceId"
    fun presenceAll() = "$topicScope/presence/+"
    fun transferRequest(transferId: String) = "$topicScope/transfer/$transferId/request"
    fun transferResponse(transferId: String) = "$topicScope/transfer/$transferId/response"
    fun transferChunk(transferId: String, chunkIndex: Int) =
        "$topicScope/transfer/$transferId/chunks/$chunkIndex"
    fun transferControl(transferId: String) = "$topicScope/transfer/$transferId/control"
    fun transferComplete(transferId: String) = "$topicScope/transfer/$transferId/complete"
    fun transferAll() = "$topicScope/transfer/#"
    fun trustEvents() = "$topicScope/trust/events"
    fun trustAck(deviceId: String) = "$topicScope/trust/ack/$deviceId"
}

/**
 * Lifecycle states surfaced by `MqttConnectionManager`. Compose UI observes
 * this to render a "cloud reachable" indicator and to gate transfer routing.
 */
sealed class MqttConnectionState {
    data object Disconnected : MqttConnectionState()
    data object Connecting : MqttConnectionState()
    data class Connected(val sinceMs: Long) : MqttConnectionState()
    data class Reconnecting(val attempt: Int, val nextRetryMs: Long) : MqttConnectionState()
    data class Failed(val reason: String, val transient: Boolean) : MqttConnectionState()
}
