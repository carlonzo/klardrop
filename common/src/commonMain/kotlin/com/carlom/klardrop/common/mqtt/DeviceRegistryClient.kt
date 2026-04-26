package com.carlom.klardrop.common.mqtt

/**
 * Thin contract for the HTTP API exposed by the cloud `device-registry`.
 *
 * Defined in commonMain as an interface so the auto-accept logic and
 * connection-bring-up flow can be tested without standing up a real HTTP
 * server. The platform implementation (ktor-client / OkHttp / NSURLSession)
 * lives in the relevant `*Main` source set and is added in a follow-up.
 *
 * All methods are suspending — callers are coroutine-driven.
 */
interface DeviceRegistryClient {
    /** Exchange a third-party OIDC ID token for a Klardrop session JWT. */
    suspend fun exchangeSession(idToken: String): SessionExchangeResult

    /** List the trusted devices currently enrolled under [userId]. */
    suspend fun listDevices(userId: String): List<TrustedDevice>

    /** Refresh the broker JWT for [deviceId]. Called shortly before expiry. */
    suspend fun refreshBrokerToken(deviceId: String): MqttCredentials
}

data class SessionExchangeResult(
    val userId: String,
    val sessionToken: String
)

/**
 * In-memory test stub that lets `MqttIncomingMessageHandler` and friends be
 * exercised without any network. Real platform implementations live next to
 * the ktor-client setup.
 */
class InMemoryDeviceRegistryClient(
    private val sessionsBySub: Map<String, SessionExchangeResult> = emptyMap(),
    private val devicesByUser: MutableMap<String, MutableList<TrustedDevice>> = mutableMapOf(),
    private val credentialsByDevice: MutableMap<String, MqttCredentials> = mutableMapOf()
) : DeviceRegistryClient {

    override suspend fun exchangeSession(idToken: String): SessionExchangeResult {
        return sessionsBySub[idToken]
            ?: error("InMemoryDeviceRegistryClient: no session configured for idToken=$idToken")
    }

    override suspend fun listDevices(userId: String): List<TrustedDevice> =
        devicesByUser[userId].orEmpty().toList()

    override suspend fun refreshBrokerToken(deviceId: String): MqttCredentials {
        return credentialsByDevice[deviceId]
            ?: error("InMemoryDeviceRegistryClient: no credentials configured for deviceId=$deviceId")
    }

    fun setDevices(userId: String, devices: List<TrustedDevice>) {
        devicesByUser[userId] = devices.toMutableList()
    }

    fun setCredentials(deviceId: String, credentials: MqttCredentials) {
        credentialsByDevice[deviceId] = credentials
    }
}
