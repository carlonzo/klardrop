package com.carlom.klardrop.common.mqtt

/**
 * Decides whether the locally-cached `MqttCredentials` are still good or
 * a refresh is required. Pure logic, no I/O — the caller (typically the
 * platform connection manager) wires the actual HTTP call.
 *
 * Two reasons to refresh:
 *   1. **Expired or about-to-expire** — current time within
 *      `REFRESH_LEAD_MS` (60s) of `expiresAtEpochMs`.
 *   2. **User mismatch** — the cached credentials are for a different
 *      user account (login/logout drift).
 */
class MqttCredentialsRefresher(
    private val store: MqttCredentialsStore,
    private val registry: DeviceRegistryClientHttp,
    private val clock: Clock,
    private val refreshLeadMs: Long = MqttCredentials.REFRESH_LEAD_MS
) {
    sealed class Decision {
        data object UseCached : Decision()
        data object Refresh : Decision()
        data object NoCachedNorRefresh : Decision()
    }

    /**
     * Returns the credentials to use right now, refreshing them if needed.
     * Returns null only if no cached credentials exist AND the caller didn't
     * supply enough info to mint new ones (no userId/deviceId).
     */
    suspend fun loadOrRefresh(userId: String, deviceId: String): MqttCredentials {
        val cached = store.load()
        if (cached != null && isStillValid(cached) && cached.userId == userId && cached.deviceId == deviceId) {
            return cached
        }
        val fresh = registry.refreshCredentials(userId = userId, deviceId = deviceId)
        store.save(fresh)
        return fresh
    }

    /** Pure-logic helper for tests / decision logging. */
    fun decideFor(cached: MqttCredentials?, expectedUserId: String, expectedDeviceId: String): Decision {
        if (cached == null) return Decision.NoCachedNorRefresh
        if (cached.userId != expectedUserId || cached.deviceId != expectedDeviceId) return Decision.Refresh
        return if (isStillValid(cached)) Decision.UseCached else Decision.Refresh
    }

    private fun isStillValid(cached: MqttCredentials): Boolean {
        val now = clock.nowMs()
        return now < cached.expiresAtEpochMs - refreshLeadMs
    }
}
