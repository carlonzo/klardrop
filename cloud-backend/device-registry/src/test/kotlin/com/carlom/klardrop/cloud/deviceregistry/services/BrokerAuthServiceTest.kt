package com.carlom.klardrop.cloud.deviceregistry.services

import com.carlom.klardrop.cloud.deviceregistry.config.BrokerJwtConfig
import com.carlom.klardrop.cloud.deviceregistry.config.JwtConfig
import com.carlom.klardrop.cloud.deviceregistry.config.MqttBrokerConfig
import com.carlom.klardrop.cloud.deviceregistry.config.RedisConfig
import com.carlom.klardrop.cloud.deviceregistry.models.*
import com.carlom.klardrop.cloud.deviceregistry.repository.InMemoryDeviceRepository
import com.carlom.klardrop.cloud.deviceregistry.security.TokenService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrokerAuthServiceTest {

    private fun fixture(): Fixture {
        val tokenService = TokenService(
            sessionConfig = JwtConfig("session-secret", "issuer", "audience", ttlSeconds = 3600),
            brokerConfig = BrokerJwtConfig("broker-secret", "issuer", "klardrop-mqtt-broker", ttlSeconds = 900)
        )
        val mqtt = MqttBrokerConfig("ssl://broker", "klardrop/v1")
        val repo = InMemoryDeviceRepository()
        val sessions = InMemoryBrokerSessionManager()
        val deviceService = DeviceService(
            redisService = RedisService(RedisConfig("")),
            tokenService = tokenService,
            mqttBrokerConfig = mqtt,
            identityProviderVerifier = StubIdentityProviderVerifier(),
            deviceRepository = repo,
            brokerSessionManager = sessions,
            approvalService = ApprovalService(),
            transferService = TransferService()
        )
        val brokerAuth = BrokerAuthService(
            tokenService = tokenService,
            mqttConfig = mqtt,
            deviceRepository = repo,
            brokerSessionManager = sessions
        )
        return Fixture(deviceService, brokerAuth, sessions, tokenService, mqtt)
    }

    @Test
    fun `enrolled device receives broker token that the auth webhook accepts`() {
        val f = fixture()
        val (user, device) = enrollNew(f, "sub:webhook-user")

        val response = f.brokerAuth.authenticate(
            BrokerAuthRequest(password = device.brokerToken, clientId = device.mqttClientId)
        )

        assertEquals(BrokerAuthResult.ALLOW, response.result)
        assertEquals(user, response.userId)
        assertEquals(device.device.deviceId, response.deviceId)
        // ACL must scope to the user's subtree only.
        val scope = "klardrop/v1/users/$user"
        assertTrue(
            response.publishAcl.all { it.startsWith(scope) },
            "publish ACL leaked outside user scope: $response"
        )
        assertTrue(
            response.subscribeAcl.all { it.startsWith(scope) },
            "subscribe ACL leaked outside user scope: $response"
        )
    }

    @Test
    fun `random or empty token is denied`() {
        val f = fixture()

        assertEquals(BrokerAuthResult.DENY, f.brokerAuth.authenticate(BrokerAuthRequest(password = "")).result)
        assertEquals(
            BrokerAuthResult.DENY,
            f.brokerAuth.authenticate(BrokerAuthRequest(password = "not-a-jwt")).result
        )
    }

    @Test
    fun `session token cannot be used as broker token`() {
        val f = fixture()
        val sessionToken = f.tokenService.issueSessionToken("usr_some-user")

        val response = f.brokerAuth.authenticate(BrokerAuthRequest(password = sessionToken))

        assertEquals(BrokerAuthResult.DENY, response.result, "session JWT must not authenticate against broker (different signing key + audience)")
    }

    @Test
    fun `mismatched clientId is denied`() {
        val f = fixture()
        val (_, device) = enrollNew(f, "sub:cid-user")

        val response = f.brokerAuth.authenticate(
            BrokerAuthRequest(password = device.brokerToken, clientId = "klardrop_attacker_${device.device.deviceId}")
        )

        assertEquals(BrokerAuthResult.DENY, response.result)
    }

    @Test
    fun `revoked device is denied even when its token is still valid`() {
        val f = fixture()
        val (user, device) = enrollNew(f, "sub:revoked-user")

        f.deviceService.revokeDevice(user, device.device.deviceId)

        val response = f.brokerAuth.authenticate(
            BrokerAuthRequest(password = device.brokerToken, clientId = device.mqttClientId)
        )

        assertEquals(BrokerAuthResult.DENY, response.result)
        assertNotNull(response.reason)
        assertNull(response.userId)
    }

    private fun enrollNew(f: Fixture, sub: String): Pair<String, EnrollDeviceResponse> {
        val user = f.deviceService.exchangeSession(SessionExchangeRequest(sub)).userId
        val pairing = f.deviceService.issuePairingCode(user, PairingCodeRequest(secondFactorToken = "totp:1"))
        val enrolled = f.deviceService.enrollDevice(
            user,
            EnrollDeviceRequest(pairing.pairingCode, "TestDevice", Platform.LINUX, "pk")
        )
        return user to enrolled
    }

    private data class Fixture(
        val deviceService: DeviceService,
        val brokerAuth: BrokerAuthService,
        val sessions: InMemoryBrokerSessionManager,
        val tokenService: TokenService,
        val mqtt: MqttBrokerConfig
    )
}
