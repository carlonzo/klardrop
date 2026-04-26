package com.carlom.klardrop.cloud.deviceregistry.services

import com.carlom.klardrop.cloud.deviceregistry.config.RedisConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedisServiceNonceTest {
    @Test
    fun `consumeNonce accepts first occurrence and rejects replay`() {
        val redis = RedisService(RedisConfig(""))
        assertTrue(redis.consumeNonce("transfer", "abc-123", ttlSeconds = 60))
        assertFalse(redis.consumeNonce("transfer", "abc-123", ttlSeconds = 60))
    }

    @Test
    fun `nonce scope isolates collisions`() {
        val redis = RedisService(RedisConfig(""))
        assertTrue(redis.consumeNonce("transfer", "shared", ttlSeconds = 60))
        // Same nonce string under a different scope must not collide.
        assertTrue(redis.consumeNonce("control", "shared", ttlSeconds = 60))
    }
}
