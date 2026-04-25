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
    private val commands: RedisCommands<String, String>?

    init {
        val setup = try {
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

        client = setup?.first
        connection = setup?.second
        commands = setup?.third
    }

    fun savePairingCode(code: String, userId: String, ttlSeconds: Long = 300) {
        val key = key(code)
        val redis = commands
        if (redis != null) {
            redis.setex(key, ttlSeconds, userId)
            return
        }
        inMemoryFallback[code] = userId
    }

    fun consumePairingCode(code: String): String? {
        val key = key(code)
        val redis = commands
        if (redis != null) {
            val value = redis.get(key)
            if (value != null) {
                redis.del(key)
            }
            return value
        }
        return inMemoryFallback.remove(code)
    }

    fun close() {
        runCatching { connection?.close() }
        runCatching { client?.shutdown() }
    }

    private fun key(code: String) = "pairing:$code"
}
