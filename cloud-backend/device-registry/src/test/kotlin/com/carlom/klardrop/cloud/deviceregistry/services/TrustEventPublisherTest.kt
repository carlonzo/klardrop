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
import kotlin.test.assertTrue

class TrustEventPublisherTest {

    @Test
    fun enroll_publishes_an_enrolled_event_with_device_metadata() {
        val publisher = RecordingTrustEventPublisher()
        val service = serviceWith(publisher)

        val user = service.exchangeSession(SessionExchangeRequest("sub:enroll-user")).userId
        val pairing = service.issuePairingCode(user, PairingCodeRequest(secondFactorToken = "t"))
        val enrolled = service.enrollDevice(
            user,
            EnrollDeviceRequest(pairing.pairingCode, "iPhone", Platform.IOS, "pubkey-1")
        )

        assertEquals(1, publisher.events.size)
        val event = publisher.events.single()
        assertTrue(event is RecordingTrustEventPublisher.Event.Enrolled)
        assertEquals(user, event.userId)
        assertEquals(enrolled.device.deviceId, event.device.deviceId)
        assertEquals("iPhone", event.device.deviceName)
    }

    @Test
    fun revoke_publishes_a_revoked_event() {
        val publisher = RecordingTrustEventPublisher()
        val service = serviceWith(publisher)

        val user = service.exchangeSession(SessionExchangeRequest("sub:revoke-user")).userId
        val pairing = service.issuePairingCode(user, PairingCodeRequest(secondFactorToken = "t"))
        val enrolled = service.enrollDevice(
            user,
            EnrollDeviceRequest(pairing.pairingCode, "iPhone", Platform.IOS, "pk")
        )
        publisher.events.clear()

        service.revokeDevice(user, enrolled.device.deviceId)

        assertEquals(1, publisher.events.size)
        val event = publisher.events.single()
        assertTrue(event is RecordingTrustEventPublisher.Event.Revoked)
        assertEquals(user, event.userId)
        assertEquals(enrolled.device.deviceId, event.deviceId)
    }

    @Test
    fun revoke_does_not_publish_when_device_is_unknown() {
        val publisher = RecordingTrustEventPublisher()
        val service = serviceWith(publisher)

        val user = service.exchangeSession(SessionExchangeRequest("sub:no-device")).userId
        service.revokeDevice(user, "non-existent")

        assertEquals(0, publisher.events.size)
    }

    private fun serviceWith(publisher: TrustEventPublisher): DeviceService {
        val tokens = TokenService(
            sessionConfig = JwtConfig("s", "i", "a", ttlSeconds = 3600),
            brokerConfig = BrokerJwtConfig("b", "i", "br", ttlSeconds = 900)
        )
        return DeviceService(
            redisService = RedisService(RedisConfig("")),
            tokenService = tokens,
            mqttBrokerConfig = MqttBrokerConfig("ssl://broker", "klardrop/v1"),
            identityProviderVerifier = StubIdentityProviderVerifier(),
            deviceRepository = InMemoryDeviceRepository(),
            brokerSessionManager = InMemoryBrokerSessionManager(),
            approvalService = ApprovalService(),
            transferService = TransferService(),
            trustEventPublisher = publisher
        )
    }

    private class RecordingTrustEventPublisher : TrustEventPublisher {
        val events = mutableListOf<Event>()

        override fun publishEnrolled(userId: String, device: Device) {
            events += Event.Enrolled(userId, device)
        }

        override fun publishRevoked(userId: String, deviceId: String) {
            events += Event.Revoked(userId, deviceId)
        }

        override fun close() = Unit

        sealed class Event {
            data class Enrolled(val userId: String, val device: Device) : Event()
            data class Revoked(val userId: String, val deviceId: String) : Event()
        }
    }
}
