package com.carlom.klardrop.cloud.deviceregistry.models

import kotlinx.serialization.Serializable

@Serializable
data class RotateDeviceCredentialResponse(
    val deviceId: String,
    val brokerToken: String,
    val brokerTokenExpiresAt: Long,
    val brokerTokenTtlSeconds: Long
)

@Serializable
data class CreateApprovalChallengeRequest(
    val requesterDeviceId: String
)

@Serializable
data class CreateApprovalChallengeResponse(
    val challengeId: String,
    val expiresInSeconds: Long
)

@Serializable
data class ApproveChallengeResponse(
    val challengeId: String,
    val approved: Boolean
)

@Serializable
data class RouteDecisionRequest(
    val receiverReachableLocally: Boolean
)

@Serializable
enum class TransferRoute {
    LOCAL,
    CLOUD
}

@Serializable
data class RouteDecisionResponse(
    val route: TransferRoute
)

/** Request body for the broker authn/ACL webhook (`/v1/internal/broker/auth`). */
@Serializable
data class BrokerAuthRequest(
    val username: String? = null,
    val password: String,
    val clientId: String? = null
)

@Serializable
data class BrokerAuthResponse(
    val result: BrokerAuthResult,
    val isSuperuser: Boolean = false,
    val userId: String? = null,
    val deviceId: String? = null,
    val publishAcl: List<String> = emptyList(),
    val subscribeAcl: List<String> = emptyList(),
    val expireAt: Long? = null,
    val reason: String? = null
)

@Serializable
enum class BrokerAuthResult {
    @kotlinx.serialization.SerialName("allow") ALLOW,
    @kotlinx.serialization.SerialName("deny")  DENY,
    @kotlinx.serialization.SerialName("ignore") IGNORE
}

@Serializable
data class BrokerTokenRefreshResponse(
    val deviceId: String,
    val brokerToken: String,
    val brokerTokenExpiresAt: Long,
    val brokerTokenTtlSeconds: Long
)
