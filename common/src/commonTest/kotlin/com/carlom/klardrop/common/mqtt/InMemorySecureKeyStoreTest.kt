package com.carlom.klardrop.common.mqtt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InMemorySecureKeyStoreTest {

    @Test
    fun load_or_generate_returns_same_pair_on_repeated_calls() {
        val store = InMemorySecureKeyStore()

        val first = store.loadOrGenerate()
        val second = store.loadOrGenerate()

        assertTrue(first.privateKeySeed.contentEquals(second.privateKeySeed))
        assertTrue(first.publicKey.contentEquals(second.publicKey))
    }

    @Test
    fun clear_then_load_produces_a_different_pair() {
        val store = InMemorySecureKeyStore()
        val first = store.loadOrGenerate()

        store.clear()
        val second = store.loadOrGenerate()

        assertEquals(32, second.privateKeySeed.size)
        assertEquals(32, second.publicKey.size)
        // Astronomical odds of collision.
        assertNotEquals(first.publicKey.toList(), second.publicKey.toList())
    }
}
