package com.carlom.klardrop.cloud.deviceregistry.services

import com.carlom.klardrop.cloud.deviceregistry.config.EmqxAdminConfig
import io.lettuce.core.api.sync.RedisCommands
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which devices are currently connected to the MQTT broker and is the
 * single integration point used by the device-registry to revoke access.
 *
 * Two responsibilities:
 *  1. **Force-disconnect** an active session when a device is revoked, so the
 *     30-second revocation SLA is met.
 *  2. **Block reconnect** until the device's outstanding broker JWT(s) have
 *     expired naturally — done by adding the device to a "revoked" set that the
 *     broker authn webhook (`/v1/internal/broker/auth`) consults on CONNECT.
 */
interface BrokerSessionManager {
    fun registerSession(deviceId: String, sessionId: String)
    fun disconnectDevice(userId: String, deviceId: String)
    fun isConnected(deviceId: String): Boolean
    fun isRevoked(deviceId: String): Boolean
}

/**
 * Single-process, no-broker implementation. Acceptable for unit tests and
 * single-replica dev. Will NOT meet the revocation SLA in a multi-replica
 * deployment because revocations on one pod won't propagate to another.
 */
class InMemoryBrokerSessionManager : BrokerSessionManager {
    private val sessions = ConcurrentHashMap<String, MutableSet<String>>()
    private val revoked = ConcurrentHashMap.newKeySet<String>()

    override fun registerSession(deviceId: String, sessionId: String) {
        revoked.remove(deviceId)
        sessions.computeIfAbsent(deviceId) { ConcurrentHashMap.newKeySet() }.add(sessionId)
    }

    override fun disconnectDevice(userId: String, deviceId: String) {
        sessions.remove(deviceId)
        revoked.add(deviceId)
    }

    override fun isConnected(deviceId: String): Boolean = sessions[deviceId]?.isNotEmpty() == true

    override fun isRevoked(deviceId: String): Boolean = revoked.contains(deviceId)
}

/**
 * Production implementation backed by:
 *  - **EMQX REST API** for live force-disconnect ("kick client").
 *  - **Redis** for the revoked-device set, so any device-registry replica can
 *    write the revocation and the broker authn webhook can read it.
 *
 * The revoked entry lives slightly longer than the broker JWT TTL — after the
 * JWT expires there's nothing left to reject, so the entry can age out.
 */
class EmqxBrokerSessionManager(
    private val emqxConfig: EmqxAdminConfig,
    private val redis: RedisCommands<String, String>,
    private val brokerTokenTtlSeconds: Long,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
) : BrokerSessionManager {

    private val logger = KotlinLogging.logger {}
    private val authHeader: String = "Basic " + Base64.getEncoder()
        .encodeToString("${emqxConfig.apiKey}:${emqxConfig.apiSecret}".toByteArray())

    override fun registerSession(deviceId: String, sessionId: String) {
        // EMQX is the source of truth for live sessions; nothing to record locally.
        // Clear any stale revocation if this device is being re-enrolled.
        runCatching { redis.del(revokedKey(deviceId)) }
    }

    override fun disconnectDevice(userId: String, deviceId: String) {
        // 1. Mark device as revoked so the broker authn webhook denies new CONNECTs.
        val ttl = brokerTokenTtlSeconds + REVOCATION_BUFFER_SECONDS
        runCatching { redis.setex(revokedKey(deviceId), ttl, "1") }
            .onFailure { logger.error(it) { "Failed to record revocation for $deviceId in Redis" } }

        // 2. Kick any currently connected session via EMQX REST API.
        val clientId = mqttClientId(userId, deviceId)
        val url = "${emqxConfig.apiUrl.trimEnd('/')}/api/v5/clients/$clientId"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", authHeader)
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(5))
            .DELETE()
            .build()

        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299 || response.statusCode() == 404) {
                logger.info { "EMQX kicked client $clientId (status ${response.statusCode()})" }
            } else {
                logger.warn {
                    "EMQX kick for $clientId returned ${response.statusCode()} body=${response.body().take(200)}"
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to call EMQX REST API to kick $clientId" }
        }
    }

    override fun isConnected(deviceId: String): Boolean {
        // Liveness is surfaced through MQTT presence topics on the data plane,
        // so the device-registry never needs to ask the broker directly.
        return false
    }

    override fun isRevoked(deviceId: String): Boolean = try {
        redis.get(revokedKey(deviceId)) != null
    } catch (e: Exception) {
        logger.error(e) { "Failed to check revocation for $deviceId" }
        false
    }

    private fun revokedKey(deviceId: String) = "broker:revoked:$deviceId"

    companion object {
        private const val REVOCATION_BUFFER_SECONDS = 60L

        fun mqttClientId(userId: String, deviceId: String): String = "klardrop_${userId}_$deviceId"
    }
}
