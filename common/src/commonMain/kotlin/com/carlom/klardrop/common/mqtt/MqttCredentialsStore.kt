package com.carlom.klardrop.common.mqtt

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Persistent home for the device's current `MqttCredentials` (broker JWT,
 * topic scope, etc.).
 *
 * The broker JWT has a short TTL (15min); the [MqttCredentialsRefresher]
 * uses this store to decide whether the cached credential is still good or
 * we need to call `device-registry` for a new one. If the app crashes and
 * restarts before the TTL elapses, we can resume MQTT immediately without
 * an extra round-trip.
 */
interface MqttCredentialsStore {
    suspend fun load(): MqttCredentials?
    suspend fun save(credentials: MqttCredentials)
    suspend fun clear()
}

class InMemoryMqttCredentialsStore : MqttCredentialsStore {
    @Volatile
    private var current: MqttCredentials? = null

    override suspend fun load(): MqttCredentials? = current
    override suspend fun save(credentials: MqttCredentials) { current = credentials }
    override suspend fun clear() { current = null }
}

/**
 * DataStore-backed implementation. Stores a single key whose value is the
 * protobuf-encoded `MqttCredentials`. Uses the existing platform-provided
 * `DataStore<Preferences>` (same one already wired for KnownDevicesRepository).
 */
class DataStoreMqttCredentialsStore(
    private val dataStore: DataStore<Preferences>
) : MqttCredentialsStore {

    @OptIn(ExperimentalSerializationApi::class)
    private val protoBuf = ProtoBuf { encodeDefaults = true }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun load(): MqttCredentials? {
        val prefs = dataStore.data.first()
        val bytes = prefs[KEY] ?: return null
        return runCatching {
            protoBuf.decodeFromByteArray(MqttCredentialsDto.serializer(), bytes).toModel()
        }.getOrNull()
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun save(credentials: MqttCredentials) {
        val bytes = protoBuf.encodeToByteArray(MqttCredentialsDto.serializer(), credentials.toDto())
        dataStore.edit { it[KEY] = bytes }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    companion object {
        private val KEY = byteArrayPreferencesKey("mqtt_credentials")
    }
}

/**
 * Wire-stable serializable mirror of [MqttCredentials]. The data class is
 * intentionally separate so we can evolve the public model without breaking
 * persisted credentials.
 */
@kotlinx.serialization.Serializable
private data class MqttCredentialsDto(
    val brokerUrl: String,
    val brokerToken: String,
    val mqttClientId: String,
    val userId: String,
    val deviceId: String,
    val topicScope: String,
    val expiresAtEpochMs: Long,
    val ttlSeconds: Long
)

private fun MqttCredentialsDto.toModel(): MqttCredentials = MqttCredentials(
    brokerUrl = brokerUrl,
    brokerToken = brokerToken,
    mqttClientId = mqttClientId,
    userId = userId,
    deviceId = deviceId,
    topicScope = topicScope,
    expiresAtEpochMs = expiresAtEpochMs,
    ttlSeconds = ttlSeconds
)

private fun MqttCredentials.toDto(): MqttCredentialsDto = MqttCredentialsDto(
    brokerUrl = brokerUrl,
    brokerToken = brokerToken,
    mqttClientId = mqttClientId,
    userId = userId,
    deviceId = deviceId,
    topicScope = topicScope,
    expiresAtEpochMs = expiresAtEpochMs,
    ttlSeconds = ttlSeconds
)
