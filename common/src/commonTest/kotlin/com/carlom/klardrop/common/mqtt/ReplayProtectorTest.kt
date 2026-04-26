package com.carlom.klardrop.common.mqtt

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplayProtectorTest {

    @Test
    fun first_use_returns_true_replay_returns_false() {
        val clock = StaticClock(1_000)
        val rp = ReplayProtector(ttlMs = 60_000, clock = clock)
        val nonce = byteArrayOf(1, 2, 3)

        assertTrue(rp.consume("alice", nonce))
        assertFalse(rp.consume("alice", nonce))
    }

    @Test
    fun same_nonce_under_different_sender_is_independent() {
        val rp = ReplayProtector(ttlMs = 60_000, clock = StaticClock(1_000))
        val nonce = byteArrayOf(7, 7, 7)

        assertTrue(rp.consume("alice", nonce))
        // Different sender, same nonce — must NOT be considered a replay.
        assertTrue(rp.consume("bob", nonce))
    }

    @Test
    fun expired_entries_are_evicted_and_can_be_re_used() {
        val clock = AdvancingClock(0)
        val rp = ReplayProtector(ttlMs = 1_000, clock = clock)
        val nonce = byteArrayOf(1, 1, 1)

        assertTrue(rp.consume("alice", nonce))
        clock.value = 5_000           // > ttl + the 1s eviction cooldown
        assertTrue(rp.consume("alice", nonce))
    }

    @Test
    fun cap_evicts_oldest_half_when_full() {
        val rp = ReplayProtector(ttlMs = 60_000, maxEntries = 4, clock = StaticClock(1_000))
        repeat(4) { i -> assertTrue(rp.consume("alice", byteArrayOf(i.toByte()))) }
        // Fifth insert triggers compaction; after that, the oldest 2 should be free.
        assertTrue(rp.consume("alice", byteArrayOf(99)))
        assertTrue(rp.consume("alice", byteArrayOf(0)))
    }

    private class StaticClock(private val ms: Long) : Clock {
        override fun nowMs(): Long = ms
    }

    private class AdvancingClock(var value: Long) : Clock {
        override fun nowMs(): Long = value
    }
}
