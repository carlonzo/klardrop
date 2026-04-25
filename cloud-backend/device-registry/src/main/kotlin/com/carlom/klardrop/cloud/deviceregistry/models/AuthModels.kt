package com.carlom.klardrop.cloud.deviceregistry.models

import kotlinx.serialization.Serializable

@Serializable
data class SessionExchangeRequest(
    val idToken: String
)

@Serializable
data class SessionExchangeResponse(
    val userId: String,
    val accessToken: String,
    val authProvider: String = "auth0"
)

@Serializable
data class BootstrapResponse(
    val userId: String,
    val userScope: String
)

@Serializable
data class PairingCodeRequest(
    val secondFactorToken: String = "",
    val approvedChallengeId: String? = null
)

@Serializable
data class PairingCodeResponse(
    val pairingCode: String,
    val expiresInSeconds: Long
)

@Serializable
data class EnrollDeviceRequest(
    val pairingCode: String,
    val deviceName: String,
    val platform: Platform,
    val devicePublicKey: String
)

@Serializable
data class EnrollDeviceResponse(
    val device: Device,
    val brokerToken: String,
    val topicScope: String
)

@Serializable
data class ApiError(
    val error: String
)
