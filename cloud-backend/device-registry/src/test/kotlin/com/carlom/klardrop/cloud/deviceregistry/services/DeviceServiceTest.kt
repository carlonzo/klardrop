package com.carlom.klardrop.cloud.deviceregistry.services

import com.carlom.klardrop.cloud.deviceregistry.config.JwtConfig
import com.carlom.klardrop.cloud.deviceregistry.config.MqttBrokerConfig
import com.carlom.klardrop.cloud.deviceregistry.config.RedisConfig
import com.carlom.klardrop.cloud.deviceregistry.models.*
import com.carlom.klardrop.cloud.deviceregistry.repository.InMemoryDeviceRepository
import com.carlom.klardrop.cloud.deviceregistry.security.TokenService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeviceServiceTest {

    private fun service(): DeviceService {
        val redisService = RedisService(RedisConfig("redis://localhost:6379"))
        val tokenService = TokenService(JwtConfig("test-secret", "issuer", "audience"))
        return DeviceService(
            redisService = redisService,
            tokenService = tokenService,
            mqttBrokerConfig = MqttBrokerConfig("ssl://broker", "klardrop/v1"),
            identityProviderVerifier = StubIdentityProviderVerifier(),
            deviceRepository = InMemoryDeviceRepository(),
            brokerSessionManager = InMemoryBrokerSessionManager(),
            approvalService = ApprovalService(),
            transferService = TransferService()
        )
    }

    @Test
    fun `stage1 session exchange and enrollment works`() {
        val service = service()

        val first = service.exchangeSession(SessionExchangeRequest("sub:user-123"))
        val second = service.exchangeSession(SessionExchangeRequest("sub:user-123"))

        assertEquals(first.userId, second.userId)
        assertTrue(first.accessToken.isNotBlank())

        val pairing = service.issuePairingCode(first.userId, PairingCodeRequest(secondFactorToken = "totp:123456"))
        val enrolled = service.enrollDevice(
            first.userId,
            EnrollDeviceRequest(pairing.pairingCode, "MacBook", Platform.MACOS, "pubkey")
        )

        assertTrue(enrolled.brokerToken.isNotBlank())
        assertEquals(1, service.listDevices(first.userId).size)
    }

    @Test
    fun `stage1 rotate requires existing trusted device`() {
        val service = service()
        val user = service.exchangeSession(SessionExchangeRequest("sub:rotate-user")).userId

        assertFailsWith<IllegalArgumentException> {
            service.rotateDeviceCredential(user, "unknown-device")
        }
    }

    @Test
    fun `stage1 pairing requires either second factor or approved challenge`() {
        val service = service()
        val user = service.exchangeSession(SessionExchangeRequest("sub:pair-user")).userId

        assertFailsWith<IllegalArgumentException> {
            service.issuePairingCode(user, PairingCodeRequest(secondFactorToken = ""))
        }
    }

    @Test
    fun `stage2 approval challenge can replace second factor`() {
        val service = service()
        val user = service.exchangeSession(SessionExchangeRequest("sub:stage2-user")).userId

        val pairing = service.issuePairingCode(user, PairingCodeRequest(secondFactorToken = "totp:123456"))
        val trusted = service.enrollDevice(
            user,
            EnrollDeviceRequest(pairing.pairingCode, "iPhone", Platform.IOS, "pubkey-1")
        ).device

        val challenge = service.createApprovalChallenge(user, CreateApprovalChallengeRequest(trusted.deviceId))
        val approved = service.approveChallenge(user, challenge.challengeId)
        assertTrue(approved.approved)

        val pairingViaApproval = service.issuePairingCode(
            user,
            PairingCodeRequest(secondFactorToken = "", approvedChallengeId = challenge.challengeId)
        )

        val secondDevice = service.enrollDevice(
            user,
            EnrollDeviceRequest(pairingViaApproval.pairingCode, "iPad", Platform.IOS, "pubkey-2")
        )

        assertTrue(secondDevice.device.deviceId.isNotBlank())
        assertEquals(2, service.listDevices(user).size)
    }

    @Test
    fun `stage2 approved challenge is single-use`() {
        val service = service()
        val user = service.exchangeSession(SessionExchangeRequest("sub:single-use-user")).userId

        val pairing = service.issuePairingCode(user, PairingCodeRequest(secondFactorToken = "totp:123456"))
        val trusted = service.enrollDevice(
            user,
            EnrollDeviceRequest(pairing.pairingCode, "iPhone", Platform.IOS, "pubkey-1")
        ).device

        val challenge = service.createApprovalChallenge(user, CreateApprovalChallengeRequest(trusted.deviceId))
        assertTrue(service.approveChallenge(user, challenge.challengeId).approved)

        service.issuePairingCode(
            user,
            PairingCodeRequest(secondFactorToken = "", approvedChallengeId = challenge.challengeId)
        )

        assertFailsWith<IllegalArgumentException> {
            service.issuePairingCode(
                user,
                PairingCodeRequest(secondFactorToken = "", approvedChallengeId = challenge.challengeId)
            )
        }
    }

    @Test
    fun `stage3 route decision chooses local first`() {
        val service = service()

        assertEquals(TransferRoute.LOCAL, service.decideTransferRoute(RouteDecisionRequest(true)).route)
        assertEquals(TransferRoute.CLOUD, service.decideTransferRoute(RouteDecisionRequest(false)).route)
    }
}
