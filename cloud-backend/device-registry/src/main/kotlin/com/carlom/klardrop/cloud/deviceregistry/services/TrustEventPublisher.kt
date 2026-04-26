package com.carlom.klardrop.cloud.deviceregistry.services

import com.carlom.klardrop.cloud.deviceregistry.config.BrokerServiceConfig
import com.carlom.klardrop.cloud.deviceregistry.config.MqttBrokerConfig
import com.carlom.klardrop.cloud.deviceregistry.models.Device
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * Publishes trust-membership changes to the user's `trust/events` topic so
 * peer devices can update their `TrustedDeviceCache` within seconds without
 * polling `GET /v1/users/{userId}/devices`.
 *
 * Topic: `klardrop/v1/users/{userId}/trust/events` — broker ACL allows only
 * this service identity to PUBLISH there; trusted devices may SUBSCRIBE.
 *
 * Why pluggable:
 *  - In CI / unit tests we wire `NoopTrustEventPublisher`.
 *  - In single-replica dev compose with no broker running yet, we wire
 *    `LoggingTrustEventPublisher` so enroll/revoke are visible in logs.
 *  - In production we wire `PahoTrustEventPublisher` with broker credentials.
 *
 * Failures are logged but never propagated — a publish glitch must not
 * roll back an enroll/revoke transaction. Clients also fall back to the
 * HTTP `GET .../devices` path, so missing one event is recoverable.
 */
interface TrustEventPublisher {
    fun publishEnrolled(userId: String, device: Device)
    fun publishRevoked(userId: String, deviceId: String)
    fun close()
}

object NoopTrustEventPublisher : TrustEventPublisher {
    override fun publishEnrolled(userId: String, device: Device) = Unit
    override fun publishRevoked(userId: String, deviceId: String) = Unit
    override fun close() = Unit
}

class LoggingTrustEventPublisher : TrustEventPublisher {
    private val logger = KotlinLogging.logger("trust-events")
    override fun publishEnrolled(userId: String, device: Device) {
        logger.info { "trust.enrolled userId=$userId deviceId=${device.deviceId} deviceName=${device.deviceName}" }
    }
    override fun publishRevoked(userId: String, deviceId: String) {
        logger.info { "trust.revoked userId=$userId deviceId=$deviceId" }
    }
    override fun close() = Unit
}

/**
 * Eclipse Paho-backed publisher. Connects lazily on first use and reconnects
 * on failure. QoS 1 — we want at-least-once delivery; the client de-dupes
 * idempotently by `(deviceId, eventType, occurredAtMs)`.
 *
 * Retain bit: **off**. Trust events are real-time signals, not retained
 * state; clients reconcile on connect by calling
 * `GET /v1/users/{userId}/devices`.
 */
class PahoTrustEventPublisher(
    private val mqttConfig: MqttBrokerConfig,
    private val service: BrokerServiceConfig,
    private val json: Json = DefaultJson
) : TrustEventPublisher {

    private val logger = KotlinLogging.logger {}

    @Volatile
    private var client: MqttAsyncClient? = null
    private val lock = Any()

    init {
        require(service.isConfigured) { "PahoTrustEventPublisher requires BrokerServiceConfig" }
    }

    override fun publishEnrolled(userId: String, device: Device) {
        publish(userId, TrustEventDto.enrolled(device))
    }

    override fun publishRevoked(userId: String, deviceId: String) {
        publish(userId, TrustEventDto.revoked(deviceId))
    }

    private fun publish(userId: String, event: TrustEventDto) {
        val topic = "${mqttConfig.userScope(userId)}/trust/events"
        val payload = json.encodeToString(event).toByteArray()
        val message = MqttMessage(payload).apply {
            qos = 1
            isRetained = false
        }
        try {
            ensureConnected().publish(topic, message)
            logger.debug { "Published trust event to $topic: $event" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to publish trust event to $topic; clients will reconcile via HTTP polling" }
            // Drop the broker connection so the next call attempts a fresh connect.
            invalidateClient()
        }
    }

    private fun ensureConnected(): MqttAsyncClient {
        client?.takeIf { it.isConnected }?.let { return it }
        synchronized(lock) {
            client?.takeIf { it.isConnected }?.let { return it }
            invalidateClient()
            val fresh = MqttAsyncClient(service.brokerUrl, service.clientId, MemoryPersistence())
            val opts = MqttConnectOptions().apply {
                isCleanSession = true
                isAutomaticReconnect = true
                connectionTimeout = CONNECT_TIMEOUT_SECONDS
                keepAliveInterval = KEEPALIVE_SECONDS
                userName = service.username
                password = service.password.toCharArray()
            }
            fresh.connect(opts).waitForCompletion(CONNECT_TIMEOUT_SECONDS * 1000L)
            client = fresh
            return fresh
        }
    }

    private fun invalidateClient() {
        synchronized(lock) {
            val current = client ?: return
            client = null
            runCatching { if (current.isConnected) current.disconnect().waitForCompletion(1000L) }
            runCatching { current.close() }
        }
    }

    override fun close() = invalidateClient()

    @Serializable
    private data class TrustEventDto(
        val event: String,                    // "ENROLLED" | "REVOKED"
        val deviceId: String,
        val deviceName: String? = null,
        val publicKeyBase64: String? = null,
        val signatureAlgorithm: String? = null,
        val occurredAtMs: Long = System.currentTimeMillis()
    ) {
        companion object {
            fun enrolled(device: Device) = TrustEventDto(
                event = "ENROLLED",
                deviceId = device.deviceId,
                deviceName = device.deviceName,
                publicKeyBase64 = device.publicKey,
                signatureAlgorithm = "ED25519"
            )

            fun revoked(deviceId: String) = TrustEventDto(event = "REVOKED", deviceId = deviceId)
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 10
        private const val KEEPALIVE_SECONDS = 30
        private val DefaultJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        @Suppress("unused")
        private val keepClientAlive = MqttClient::class
    }
}
