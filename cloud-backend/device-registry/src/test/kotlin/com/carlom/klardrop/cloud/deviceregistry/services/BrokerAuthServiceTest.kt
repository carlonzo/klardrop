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
import kotlin.test.assertIs
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

    // ─── authenticateUser (CONNECT) ──────────────────────────────────────────

    @Test
    fun enrolled_device_is_authenticated() {
        val f = fixture()
        val (user, device) = enrollNew(f, "sub:webhook-user")

        val decision = f.brokerAuth.authenticateUser(
            password = device.brokerToken,
            clientId = device.mqttClientId
        )

        val allow = assertIs<BrokerAuthDecision.Allow>(decision)
        assertEquals(user, allow.userId)
        assertEquals(device.device.deviceId, allow.deviceId)
    }

    @Test
    fun random_or_empty_token_is_denied() {
        val f = fixture()
        assertIs<BrokerAuthDecision.Deny>(f.brokerAuth.authenticateUser(password = "", clientId = null))
        assertIs<BrokerAuthDecision.Deny>(f.brokerAuth.authenticateUser(password = "not-a-jwt", clientId = null))
    }

    @Test
    fun session_token_cannot_be_used_as_broker_token() {
        val f = fixture()
        val sessionToken = f.tokenService.issueSessionToken("usr_some-user")

        val decision = f.brokerAuth.authenticateUser(password = sessionToken, clientId = null)

        assertIs<BrokerAuthDecision.Deny>(decision)
    }

    @Test
    fun mismatched_clientId_is_denied() {
        val f = fixture()
        val (_, device) = enrollNew(f, "sub:cid-user")

        val decision = f.brokerAuth.authenticateUser(
            password = device.brokerToken,
            clientId = "klardrop_attacker_${device.device.deviceId}"
        )
        assertIs<BrokerAuthDecision.Deny>(decision)
    }

    @Test
    fun revoked_device_is_denied_even_with_valid_token() {
        val f = fixture()
        val (user, device) = enrollNew(f, "sub:revoked-user")
        f.deviceService.revokeDevice(user, device.device.deviceId)

        val decision = f.brokerAuth.authenticateUser(
            password = device.brokerToken,
            clientId = device.mqttClientId
        )
        val deny = assertIs<BrokerAuthDecision.Deny>(decision)
        assertTrue("revoked" in deny.reason || "not found" in deny.reason)
    }

    // ─── checkAcl (PUBLISH/SUBSCRIBE) ────────────────────────────────────────

    @Test
    fun publish_to_own_presence_is_allowed() {
        val f = fixture()
        val (user, device) = enrollNew(f, "sub:pub-presence")
        val topic = "klardrop/v1/users/$user/presence/${device.device.deviceId}"

        assertIs<BrokerAclDecision.Allow>(
            f.brokerAuth.checkAcl(device.mqttClientId, topic, BrokerAclAccess.WRITE)
        )
    }

    @Test
    fun publish_to_someone_elses_presence_is_denied() {
        val f = fixture()
        val (user, device) = enrollNew(f, "sub:pub-other")
        val foreignTopic = "klardrop/v1/users/$user/presence/some-other-device"

        assertIs<BrokerAclDecision.Deny>(
            f.brokerAuth.checkAcl(device.mqttClientId, foreignTopic, BrokerAclAccess.WRITE)
        )
    }

    @Test
    fun publish_to_transfer_chunk_topic_is_allowed() {
        val f = fixture()
        val (user, device) = enrollNew(f, "sub:pub-chunk")
        val topic = "klardrop/v1/users/$user/transfer/tid-1/chunks/0"

        assertIs<BrokerAclDecision.Allow>(
            f.brokerAuth.checkAcl(device.mqttClientId, topic, BrokerAclAccess.WRITE)
        )
    }

    @Test
    fun subscribe_to_own_user_scope_is_allowed() {
        val f = fixture()
        val (user, device) = enrollNew(f, "sub:sub-scope")
        val topic = "klardrop/v1/users/$user/transfer/tid-1/request"

        assertIs<BrokerAclDecision.Allow>(
            f.brokerAuth.checkAcl(device.mqttClientId, topic, BrokerAclAccess.SUBSCRIBE)
        )
    }

    @Test
    fun publish_outside_user_scope_is_denied() {
        val f = fixture()
        val (_, device) = enrollNew(f, "sub:cross-user")
        // Topic targets a different userId.
        val foreign = "klardrop/v1/users/usr_other_user/transfer/tid-1/request"

        assertIs<BrokerAclDecision.Deny>(
            f.brokerAuth.checkAcl(device.mqttClientId, foreign, BrokerAclAccess.WRITE)
        )
    }

    @Test
    fun acl_check_denies_revoked_device_even_within_user_scope() {
        val f = fixture()
        val (user, device) = enrollNew(f, "sub:acl-revoked")
        val topic = "klardrop/v1/users/$user/presence/${device.device.deviceId}"
        f.deviceService.revokeDevice(user, device.device.deviceId)

        assertIs<BrokerAclDecision.Deny>(
            f.brokerAuth.checkAcl(device.mqttClientId, topic, BrokerAclAccess.WRITE)
        )
    }

    @Test
    fun malformed_clientId_is_rejected_by_acl() {
        val f = fixture()
        assertIs<BrokerAclDecision.Deny>(
            f.brokerAuth.checkAcl("not-a-klardrop-cid", "klardrop/v1/users/x/transfer/y/request", BrokerAclAccess.WRITE)
        )
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
