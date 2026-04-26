package com.carlom.klardrop.cloud.deviceregistry.services

import io.lettuce.core.api.sync.RedisCommands
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which devices have been revoked and is the integration point used
 * by the device-registry to enforce the revocation SLA against the MQTT
 * broker.
 *
 * Mosquitto has no native REST API to force-disconnect a client, so the
 * production `MosquittoBrokerSessionManager` relies on:
 *
 *  1. **Redis revoked-set** — every device-registry replica writes to it on
 *     `revokeDevice`; the broker authn webhook reads it on every CONNECT,
 *     PUBLISH, and SUBSCRIBE (subject to mosquitto-go-auth's auth_cache_seconds).
 *  2. **Short auth-cache TTL** in mosquitto-go-auth (~15-20s) — bounds the
 *     time between the registry recording revocation and the broker actually
 *     denying the next operation. Together with the revoked-set this meets
 *     our ≤30s SLA without needing a kick API.
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
 * Production implementation backed by Redis.
 *
 * `disconnectDevice` writes a revoked-marker keyed by deviceId with a TTL
 * slightly longer than the broker JWT TTL — after the JWT naturally expires
 * there's nothing to deny anyway, so the entry can age out and stop bloating
 * Redis.
 *
 * On a multi-replica device-registry deployment all replicas read/write the
 * same Redis, so any replica's revocation is immediately visible to the
 * broker auth webhook regardless of which replica the broker is hitting.
 */
class MosquittoBrokerSessionManager(
    private val redis: RedisCommands<String, String>,
    private val brokerTokenTtlSeconds: Long
) : BrokerSessionManager {

    private val logger = KotlinLogging.logger {}

    override fun registerSession(deviceId: String, sessionId: String) {
        // Re-enrolling clears any stale revocation (defence in case a deviceId
        // is recycled — which we don't, but cheap to be safe).
        runCatching { redis.del(revokedKey(deviceId)) }
            .onFailure { logger.warn(it) { "Failed to clear stale revocation for $deviceId" } }
    }

    override fun disconnectDevice(userId: String, deviceId: String) {
        val ttl = brokerTokenTtlSeconds + REVOCATION_BUFFER_SECONDS
        runCatching { redis.setex(revokedKey(deviceId), ttl, "1") }
            .onFailure { logger.error(it) { "Failed to record revocation for $deviceId in Redis" } }
        // Mosquitto provides no native kick, but mosquitto-go-auth re-checks
        // ACL with a small cache TTL (auth_opt_acl_cache_seconds), so the next
        // PUBLISH or SUBSCRIBE within ~20s will hit isRevoked() and be denied,
        // dropping the session.
    }

    override fun isConnected(deviceId: String): Boolean {
        // Liveness is surfaced via MQTT presence topics on the data plane.
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
