package com.carlom.klardrop.common.mqtt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class InMemoryMqttCredentialsStoreTest {

    @Test
    fun load_after_save_returns_the_same_credentials() = runTest {
        val store = InMemoryMqttCredentialsStore()
        val creds = sample()

        store.save(creds)

        assertEquals(creds, store.load())
    }

    @Test
    fun load_returns_null_after_clear() = runTest {
        val store = InMemoryMqttCredentialsStore()
        store.save(sample())
        store.clear()
        assertNull(store.load())
    }

    private fun sample(): MqttCredentials = MqttCredentials(
        brokerUrl = "ssl://broker:8883",
        brokerToken = "jwt",
        mqttClientId = "klardrop_usr_a_dev_1",
        userId = "usr_a",
        deviceId = "dev_1",
        topicScope = "klardrop/v1/users/usr_a",
        expiresAtEpochMs = 1_700_000_000_000L,
        ttlSeconds = 900L
    )
}

class MqttCredentialsRefresherDecisionTest {
    private val clock = object : Clock {
        var ms: Long = 0
        override fun nowMs(): Long = ms
    }
    private val refresher: MqttCredentialsRefresher
        get() = MqttCredentialsRefresher(
            store = InMemoryMqttCredentialsStore(),
            // Not used in decideFor — pass a no-op DeviceRegistryClientHttp via a mock.
            registry = throwingRegistry,
            clock = clock,
            refreshLeadMs = 60_000L
        )

    @Test
    fun cached_with_room_to_spare_uses_cached() {
        clock.ms = 100_000
        val cached = sample(expiresAtEpochMs = 100_000 + 10 * 60 * 1000L)
        assertEquals(MqttCredentialsRefresher.Decision.UseCached, refresher.decideFor(cached, "u", "d"))
    }

    @Test
    fun cached_within_refresh_lead_is_refreshed() {
        clock.ms = 100_000
        // Lead = 60s; expiresAt only 30s away → must refresh.
        val cached = sample(expiresAtEpochMs = 100_000 + 30_000)
        assertEquals(MqttCredentialsRefresher.Decision.Refresh, refresher.decideFor(cached, "u", "d"))
    }

    @Test
    fun expired_cache_is_refreshed() {
        clock.ms = 200_000
        val cached = sample(expiresAtEpochMs = 100_000)
        assertEquals(MqttCredentialsRefresher.Decision.Refresh, refresher.decideFor(cached, "u", "d"))
    }

    @Test
    fun mismatched_user_or_device_is_refreshed() {
        clock.ms = 0
        val cached = sample(expiresAtEpochMs = Long.MAX_VALUE - 1)
        assertEquals(MqttCredentialsRefresher.Decision.Refresh, refresher.decideFor(cached, "other-user", "d"))
        assertEquals(MqttCredentialsRefresher.Decision.Refresh, refresher.decideFor(cached, "u", "other-device"))
    }

    @Test
    fun null_cache_yields_NoCachedNorRefresh_for_decision() {
        assertEquals(MqttCredentialsRefresher.Decision.NoCachedNorRefresh,
            refresher.decideFor(null, "u", "d"))
    }

    private fun sample(expiresAtEpochMs: Long): MqttCredentials = MqttCredentials(
        brokerUrl = "ssl://b:8883",
        brokerToken = "jwt",
        mqttClientId = "cid",
        userId = "u",
        deviceId = "d",
        topicScope = "klardrop/v1/users/u",
        expiresAtEpochMs = expiresAtEpochMs,
        ttlSeconds = 900L
    )

    private val throwingRegistry: DeviceRegistryClientHttp by lazy {
        // Constructed but never invoked by `decideFor`. Use a baseUrl that
        // would fail-fast if anyone accidentally calls into it.
        DeviceRegistryClientHttp(
            baseUrl = "https://disabled-in-test",
            brokerUrl = "ssl://disabled",
            sessionToken = { error("registry must not be called from decideFor tests") }
        )
    }
}
