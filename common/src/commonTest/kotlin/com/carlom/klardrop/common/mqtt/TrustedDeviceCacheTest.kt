package com.carlom.klardrop.common.mqtt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TrustedDeviceCacheTest {

    @Test
    fun upsert_and_get_round_trip() {
        val cache = InMemoryTrustedDeviceCache()
        val device = TrustedDevice("a", "Alice", byteArrayOf(1), enrolledAtMs = 100)

        cache.upsert(device)

        assertEquals(device, cache.get("a"))
    }

    @Test
    fun replaceAll_drops_devices_not_in_new_list() {
        val cache = InMemoryTrustedDeviceCache()
        cache.upsert(TrustedDevice("a", "Alice", byteArrayOf(1), enrolledAtMs = 100))
        cache.upsert(TrustedDevice("b", "Bob", byteArrayOf(2), enrolledAtMs = 200))

        cache.replaceAll(listOf(TrustedDevice("c", "Carol", byteArrayOf(3), enrolledAtMs = 300)))

        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertNotNull(cache.get("c"))
    }

    @Test
    fun remove_revokes_device() {
        val cache = InMemoryTrustedDeviceCache()
        cache.upsert(TrustedDevice("a", "Alice", byteArrayOf(1), enrolledAtMs = 100))

        cache.remove("a")

        assertNull(cache.get("a"))
    }

    @Test
    fun devices_state_flow_emits_current_snapshot() {
        val cache = InMemoryTrustedDeviceCache()
        cache.upsert(TrustedDevice("a", "Alice", byteArrayOf(1), enrolledAtMs = 100))

        val snapshot = cache.devices.value
        assertEquals(1, snapshot.size)
        assertEquals("Alice", snapshot["a"]?.deviceName)
    }
}
