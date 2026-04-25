package com.carlom.klardrop.cloud.deviceregistry.models

import kotlinx.serialization.Serializable

@Serializable
data class RotateDeviceCredentialResponse(
    val deviceId: String,
    val brokerToken: String
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
