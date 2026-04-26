package com.carlom.klardrop.cloud.deviceregistry.services

import com.carlom.klardrop.cloud.deviceregistry.config.MqttBrokerConfig
import com.carlom.klardrop.cloud.deviceregistry.models.BrokerAuthRequest
import com.carlom.klardrop.cloud.deviceregistry.models.BrokerAuthResponse
import com.carlom.klardrop.cloud.deviceregistry.models.BrokerAuthResult
import com.carlom.klardrop.cloud.deviceregistry.repository.DeviceRepository
import com.carlom.klardrop.cloud.deviceregistry.security.TokenService

/**
 * Backs the `/v1/internal/broker/auth` HTTP webhook called by the MQTT broker
 * (EMQX HTTP authn / authz) on each CONNECT.
 *
 * Decision flow:
 *  1. Verify the broker JWT presented as the MQTT password.
 *  2. Reject if the device has been revoked (entry in Redis revoked-set).
 *  3. Reject if the clientId doesn't match the JWT's bound (userId, deviceId).
 *  4. Reject if the device record is gone from the registry (defence in depth
 *     in case revoke ran but the broker cached the auth too long).
 *  5. Otherwise allow with an ACL list scoped to the user's topic subtree.
 *
 * The broker is expected to enforce the returned ACL — EMQX will cache the
 * decision for the JWT lifetime so the registry isn't called per publish.
 */
class BrokerAuthService(
    private val tokenService: TokenService,
    private val mqttConfig: MqttBrokerConfig,
    private val deviceRepository: DeviceRepository,
    private val brokerSessionManager: BrokerSessionManager,
    private val auditLogger: AuditLogger = NoopAuditLogger
) {
    fun authenticate(request: BrokerAuthRequest): BrokerAuthResponse {
        val principal = tokenService.verifyBrokerToken(request.password)
            ?: return deny("invalid broker token", userId = null, deviceId = null)

        if (brokerSessionManager.isRevoked(principal.deviceId)) {
            return deny("device revoked", principal.userId, principal.deviceId)
        }

        val expectedClientId = EmqxBrokerSessionManager.mqttClientId(principal.userId, principal.deviceId)
        val presentedClientId = request.clientId
        if (!presentedClientId.isNullOrBlank() && presentedClientId != expectedClientId) {
            return deny(
                "clientId mismatch (expected=$expectedClientId, got=$presentedClientId)",
                principal.userId, principal.deviceId
            )
        }

        // Defence in depth — token can outlive the device record by up to TTL.
        val device = deviceRepository.getDevice(principal.userId, principal.deviceId)
            ?: return deny("device not found", principal.userId, principal.deviceId)

        brokerSessionManager.registerSession(device.deviceId, principal.tokenId)
        auditLogger.record(AuditEvent.BrokerAuthAllowed(principal.userId, principal.deviceId))

        val scope = mqttConfig.userScope(principal.userId)
        return BrokerAuthResponse(
            result = BrokerAuthResult.ALLOW,
            isSuperuser = false,
            userId = principal.userId,
            deviceId = principal.deviceId,
            // What the device may PUBLISH to (its own outgoing slots only):
            publishAcl = listOf(
                "$scope/presence/${principal.deviceId}",
                "$scope/transfer/+/request",
                "$scope/transfer/+/response",
                "$scope/transfer/+/chunks/+",
                "$scope/transfer/+/control",
                "$scope/transfer/+/complete",
                "$scope/trust/ack/${principal.deviceId}"
            ),
            // What the device may SUBSCRIBE to (everything under its own user scope):
            subscribeAcl = listOf(
                "$scope/presence/+",
                "$scope/transfer/#",
                "$scope/trust/events"
            ),
            expireAt = principal.expiresAtEpochMs
        )
    }

    private fun deny(reason: String, userId: String?, deviceId: String?): BrokerAuthResponse {
        auditLogger.record(AuditEvent.BrokerAuthDenied(userId, deviceId, reason))
        return BrokerAuthResponse(result = BrokerAuthResult.DENY, reason = reason)
    }
}
