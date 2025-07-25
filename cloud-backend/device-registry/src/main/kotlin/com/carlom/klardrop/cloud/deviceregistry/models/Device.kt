package com.carlom.klardrop.cloud.deviceregistry.models

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
data class Device(
    val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val deviceName: String,
    val platform: Platform,
    val publicKey: String,
    val mqttClientId: String,
    @Serializable(with = InstantSerializer::class)
    val lastSeen: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant = Instant.now()
)

@Serializable
enum class Platform {
    ANDROID,
    IOS,
    MACOS,
    WINDOWS,
    LINUX,
    WEB
}

@Serializable
data class DeviceCapability(
    val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val capability: String,
    val value: String
)

@Serializable
data class DeviceRegistrationRequest(
    val deviceId: String,
    val deviceName: String,
    val platform: Platform,
    val publicKey: String,
    val capabilities: Map<String, String> = emptyMap()
)

@Serializable
data class DeviceRegistrationResponse(
    val device: Device,
    val mqttConfig: MqttConfig,
    val token: String
)

@Serializable
data class MqttConfig(
    val brokerUrl: String,
    val clientId: String,
    val username: String,
    val password: String,
    val topicPrefix: String
)

@Serializable
data class DeviceHeartbeat(
    val deviceId: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class DeviceSearchRequest(
    val query: String? = null,
    val platform: Platform? = null,
    val capabilities: Map<String, String> = emptyMap(),
    val onlineOnly: Boolean = false,
    val limit: Int = 20,
    val offset: Int = 0
)

@Serializable
data class DeviceSearchResponse(
    val devices: List<Device>,
    val total: Int,
    val hasMore: Boolean
)