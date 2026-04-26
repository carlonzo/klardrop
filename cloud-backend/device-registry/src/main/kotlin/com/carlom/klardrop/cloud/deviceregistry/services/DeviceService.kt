package com.carlom.klardrop.cloud.deviceregistry.services

import com.carlom.klardrop.cloud.deviceregistry.config.MqttBrokerConfig
import com.carlom.klardrop.cloud.deviceregistry.models.*
import com.carlom.klardrop.cloud.deviceregistry.repository.DeviceRepository
import com.carlom.klardrop.cloud.deviceregistry.security.TokenService
import java.util.UUID

class DeviceService(
    private val redisService: RedisService,
    private val tokenService: TokenService,
    private val mqttBrokerConfig: MqttBrokerConfig,
    private val identityProviderVerifier: IdentityProviderVerifier,
    private val deviceRepository: DeviceRepository,
    private val brokerSessionManager: BrokerSessionManager,
    private val approvalService: ApprovalService,
    private val transferService: TransferService,
    private val auditLogger: AuditLogger = NoopAuditLogger
) {
    // Stage 1
    fun exchangeSession(request: SessionExchangeRequest): SessionExchangeResponse {
        val identity = identityProviderVerifier.verify(request.idToken)
        val userId = identity.internalUserId()
        deviceRepository.ensureUser(userId)
        auditLogger.record(AuditEvent.SessionExchanged(userId = userId, provider = identity.provider))
        return SessionExchangeResponse(userId = userId, accessToken = tokenService.issueSessionToken(userId))
    }

    fun bootstrapUser(userId: String): BootstrapResponse {
        requireValidUserId(userId)
        deviceRepository.ensureUser(userId)
        return BootstrapResponse(userId = userId, userScope = mqttBrokerConfig.userScope(userId))
    }

    fun issuePairingCode(userId: String, request: PairingCodeRequest): PairingCodeResponse {
        requireValidUserId(userId)
        val twoFactorProvided = request.secondFactorToken.isNotBlank()
        val challengeApproved = request.approvedChallengeId
            ?.let { approvalService.consumeApprovedChallenge(userId, it) } == true
        require(twoFactorProvided || challengeApproved) {
            "Pairing requires secondFactorToken or approvedChallengeId"
        }
        val code = UUID.randomUUID().toString().substring(0, 8).uppercase()
        redisService.savePairingCode(code, userId, ttlSeconds = PAIRING_TTL_SECONDS)
        auditLogger.record(AuditEvent.PairingCodeIssued(userId = userId))
        return PairingCodeResponse(pairingCode = code, expiresInSeconds = PAIRING_TTL_SECONDS)
    }

    fun enrollDevice(userId: String, request: EnrollDeviceRequest): EnrollDeviceResponse {
        requireValidUserId(userId)
        val owner = redisService.consumePairingCode(request.pairingCode)
            ?: throw IllegalArgumentException("Invalid or expired pairing code")
        require(owner == userId) { "Pairing code does not belong to current user" }

        val deviceId = UUID.randomUUID().toString()
        val mqttClientId = "klardrop_${userId}_$deviceId"
        val device = Device(
            deviceId = deviceId,
            deviceName = request.deviceName,
            platform = request.platform,
            publicKey = request.devicePublicKey,
            mqttClientId = mqttClientId
        )

        deviceRepository.saveDevice(userId, device)
        brokerSessionManager.registerSession(deviceId, UUID.randomUUID().toString())
        val brokerToken = tokenService.issueBrokerToken(userId, deviceId)
        auditLogger.record(AuditEvent.DeviceEnrolled(userId = userId, deviceId = deviceId))

        return EnrollDeviceResponse(
            device = device,
            brokerToken = brokerToken.token,
            brokerTokenExpiresAt = brokerToken.expiresAtEpochMs,
            brokerTokenTtlSeconds = brokerToken.ttlSeconds,
            topicScope = mqttBrokerConfig.userScope(userId),
            mqttClientId = mqttClientId
        )
    }

    fun revokeDevice(userId: String, deviceId: String) {
        requireValidUserId(userId)
        if (deviceRepository.revokeDevice(userId, deviceId)) {
            brokerSessionManager.disconnectDevice(userId, deviceId)
            auditLogger.record(AuditEvent.DeviceRevoked(userId = userId, deviceId = deviceId))
        }
    }

    fun rotateDeviceCredential(userId: String, deviceId: String): RotateDeviceCredentialResponse {
        requireValidUserId(userId)
        val device = deviceRepository.getDevice(userId, deviceId)
            ?: throw IllegalArgumentException("Device not found or revoked")
        val brokerToken = tokenService.issueBrokerToken(userId, device.deviceId)
        auditLogger.record(AuditEvent.BrokerTokenRotated(userId = userId, deviceId = device.deviceId))
        return RotateDeviceCredentialResponse(
            deviceId = device.deviceId,
            brokerToken = brokerToken.token,
            brokerTokenExpiresAt = brokerToken.expiresAtEpochMs,
            brokerTokenTtlSeconds = brokerToken.ttlSeconds
        )
    }

    fun listDevices(userId: String): List<Device> {
        requireValidUserId(userId)
        return deviceRepository.listDevices(userId)
    }

    // Stage 2
    fun createApprovalChallenge(
        userId: String,
        request: CreateApprovalChallengeRequest
    ): CreateApprovalChallengeResponse {
        requireValidUserId(userId)
        val requester = deviceRepository.getDevice(userId, request.requesterDeviceId)
            ?: throw IllegalArgumentException("Requester device is not trusted")

        val challenge = approvalService.createChallenge(userId, requester.deviceId)
        return CreateApprovalChallengeResponse(
            challengeId = challenge.challengeId,
            expiresInSeconds = APPROVAL_TTL_SECONDS
        )
    }

    fun approveChallenge(userId: String, challengeId: String): ApproveChallengeResponse {
        requireValidUserId(userId)
        return ApproveChallengeResponse(
            challengeId = challengeId,
            approved = approvalService.approveChallenge(userId, challengeId)
        )
    }

    // Stage 3
    fun decideTransferRoute(request: RouteDecisionRequest): RouteDecisionResponse {
        return RouteDecisionResponse(route = transferService.decideRoute(request.receiverReachableLocally))
    }

    private fun requireValidUserId(userId: String) {
        require(userId.isNotBlank()) { "userId is required" }
    }

    companion object {
        private const val PAIRING_TTL_SECONDS = 300L
        private const val APPROVAL_TTL_SECONDS = 300L
    }
}
