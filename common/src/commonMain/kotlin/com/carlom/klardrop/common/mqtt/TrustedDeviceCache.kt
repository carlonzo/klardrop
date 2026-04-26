package com.carlom.klardrop.common.mqtt

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Local snapshot of "devices my user owns" (i.e. the trusted set for the
 * receiver-side auto-accept gate).
 *
 * Refilled from two sources:
 *  1. **HTTP** — `DeviceRegistryClient.listDevices(userId)` on app start and
 *     after long disconnects.
 *  2. **MQTT** — `MqttPayload.TrustEvent` published on
 *     `klardrop/v1/users/{userId}/trust/events` whenever a device is enrolled
 *     or revoked, so the cache reflects revocations within seconds without
 *     polling.
 *
 * The cache holds **only the public material** (deviceId, public key, name).
 * Private signing keys live in platform secure storage (Android Keystore /
 * iOS Keychain) and are never stored here.
 */
interface TrustedDeviceCache {
    /** Returns the cached entry for [deviceId], or null if not trusted. */
    fun get(deviceId: String): TrustedDevice?

    /** Atomic snapshot of all trusted devices. */
    val devices: StateFlow<Map<String, TrustedDevice>>

    /** Replace the entire cache (used after `listDevices` HTTP refresh). */
    fun replaceAll(devices: List<TrustedDevice>)

    /** Add or update one device (used on `TrustEvent.ENROLLED`). */
    fun upsert(device: TrustedDevice)

    /** Remove a device (used on `TrustEvent.REVOKED`). */
    fun remove(deviceId: String)
}

data class TrustedDevice(
    val deviceId: String,
    val deviceName: String,
    val publicKey: ByteArray,
    val signatureAlgorithm: SignatureAlgorithm = SignatureAlgorithm.ED25519,
    val enrolledAtMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustedDevice) return false
        return deviceId == other.deviceId && deviceName == other.deviceName &&
            publicKey.contentEquals(other.publicKey) &&
            signatureAlgorithm == other.signatureAlgorithm &&
            enrolledAtMs == other.enrolledAtMs
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + signatureAlgorithm.hashCode()
        result = 31 * result + enrolledAtMs.hashCode()
        return result
    }
}

/** Thread-safe in-memory implementation. Suitable for tests and as a base
 *  class for a persistence-backed implementation in androidMain/desktopJvmMain. */
open class InMemoryTrustedDeviceCache : TrustedDeviceCache {
    private val state = MutableStateFlow<Map<String, TrustedDevice>>(emptyMap())

    override val devices: StateFlow<Map<String, TrustedDevice>> = state.asStateFlow()

    override fun get(deviceId: String): TrustedDevice? = state.value[deviceId]

    override fun replaceAll(devices: List<TrustedDevice>) {
        state.value = devices.associateBy { it.deviceId }
    }

    override fun upsert(device: TrustedDevice) {
        state.update { current -> current + (device.deviceId to device) }
    }

    override fun remove(deviceId: String) {
        state.update { current -> current - deviceId }
    }
}

/** Convenience: observe a single device's trust state for receiver dispatching. */
fun TrustedDeviceCache.observe(deviceId: String): Flow<TrustedDevice?> =
    devices.map { snapshot -> snapshot[deviceId] }
