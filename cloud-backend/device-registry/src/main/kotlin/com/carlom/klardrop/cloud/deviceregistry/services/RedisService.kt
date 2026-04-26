package com.carlom.klardrop.cloud.deviceregistry.services

import com.carlom.klardrop.cloud.deviceregistry.config.RedisConfig
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

class RedisService(config: RedisConfig) {
    private val logger = KotlinLogging.logger {}
    private val inMemoryFallback = ConcurrentHashMap<String, String>()

    private val client: RedisClient?
    private val connection: StatefulRedisConnection<String, String>?
    private val syncCommands: RedisCommands<String, String>?

    val isConnected: Boolean get() = syncCommands != null

    init {
        val setup = if (config.url.isBlank()) {
            logger.warn { "REDIS_URL not configured; using in-memory fallback for pairing codes." }
            null
        } else {
            try {
                val redisClient = RedisClient.create(config.url)
                val redisConnection = redisClient.connect()
                val redisCommands = redisConnection.sync()
                redisCommands.ping()
                logger.info { "Connected to Redis at ${config.url}" }
                Triple(redisClient, redisConnection, redisCommands)
            } catch (e: Exception) {
                logger.warn(e) { "Redis unavailable, using in-memory fallback for pairing codes." }
                null
            }
        }

        client = setup?.first
        connection = setup?.second
        syncCommands = setup?.third
    }

    /**
     * Returns the live Lettuce sync command interface. Used by services that
     * need direct Redis access (e.g. EmqxBrokerSessionManager's revoked-set).
     * Throws if Redis is not actually connected — those services must not run
     * with the in-memory fallback.
     */
    fun requireCommands(): RedisCommands<String, String> =
        syncCommands ?: error("Redis is required for this operation but is not configured")

    fun savePairingCode(code: String, userId: String, ttlSeconds: Long = 300) {
        val key = key(code)
        val redis = syncCommands
        if (redis != null) {
            redis.setex(key, ttlSeconds, userId)
            return
        }
        inMemoryFallback[code] = userId
    }

    fun consumePairingCode(code: String): String? {
        val key = key(code)
        val redis = syncCommands
        if (redis != null) {
            val value = redis.get(key)
            if (value != null) {
                redis.del(key)
            }
            return value
        }
        return inMemoryFallback.remove(code)
    }

    /**
     * Atomically check-and-insert a nonce. Returns true if the nonce was
     * accepted (first time seen within the window), false if it's a replay.
     * Used for replay protection on signed message envelopes.
     */
    fun consumeNonce(scope: String, nonce: String, ttlSeconds: Long): Boolean {
        require(scope.isNotBlank()) { "scope is required" }
        require(nonce.isNotBlank()) { "nonce is required" }
        val key = "nonce:$scope:$nonce"
        val redis = syncCommands
        if (redis != null) {
            val ok = redis.setnx(key, "1")
            if (ok) {
                redis.expire(key, ttlSeconds)
            }
            return ok
        }
        val previous = inMemoryFallback.putIfAbsent(key, "1")
        return previous == null
    }

    fun close() {
        runCatching { connection?.close() }
        runCatching { client?.shutdown() }
    }

    private fun key(code: String) = "pairing:$code"
}
