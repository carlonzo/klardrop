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

/**
 * Access kind for an MQTT ACL check, matching mosquitto-go-auth's `acc` field.
 *  1 = MOSQ_ACL_READ      (subscribe-time topic match)
 *  2 = MOSQ_ACL_WRITE     (publish)
 *  4 = MOSQ_ACL_SUBSCRIBE (subscribe-time topic filter check)
 */
@Serializable
enum class BrokerAclAccess(val mosquittoCode: Int) {
    READ(1), WRITE(2), SUBSCRIBE(4);

    companion object {
        fun fromMosquittoCode(code: Int): BrokerAclAccess? =
            entries.firstOrNull { it.mosquittoCode == code }
    }
}

@Serializable
data class BrokerTokenRefreshResponse(
    val deviceId: String,
    val brokerToken: String,
    val brokerTokenExpiresAt: Long,
    val brokerTokenTtlSeconds: Long
)
