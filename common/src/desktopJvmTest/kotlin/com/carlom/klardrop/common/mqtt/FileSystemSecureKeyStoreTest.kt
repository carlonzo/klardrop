package com.carlom.klardrop.common.mqtt

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FileSystemSecureKeyStoreTest {

    @Test
    fun first_call_generates_persists_subsequent_calls_load() {
        val dir = Files.createTempDirectory("klardrop-keystore-test").toFile().apply { deleteOnExit() }

        val store1 = FileSystemSecureKeyStore(dir)
        val pair1 = store1.loadOrGenerate()

        // New instance, same directory → must read what's on disk.
        val store2 = FileSystemSecureKeyStore(dir)
        val pair2 = store2.loadOrGenerate()

        assertTrue(pair1.privateKeySeed.contentEquals(pair2.privateKeySeed))
        assertTrue(pair1.publicKey.contentEquals(pair2.publicKey))
    }

    @Test
    fun clear_then_load_replaces_the_keypair() {
        val dir = Files.createTempDirectory("klardrop-keystore-test").toFile().apply { deleteOnExit() }
        val store = FileSystemSecureKeyStore(dir)
        val first = store.loadOrGenerate()

        store.clear()
        val keyFile = dir.resolve("ed25519.key")
        assertFalse(keyFile.exists(), "key file should be deleted after clear()")

        val second = store.loadOrGenerate()
        assertNotEquals(first.publicKey.toList(), second.publicKey.toList())
    }

    @Test
    fun corrupted_file_is_treated_as_missing_and_regenerated() {
        val dir = Files.createTempDirectory("klardrop-keystore-test").toFile().apply { deleteOnExit() }
        // Write garbage that's the wrong length.
        dir.resolve("ed25519.key").writeBytes(byteArrayOf(1, 2, 3))

        val pair = FileSystemSecureKeyStore(dir).loadOrGenerate()

        assertEquals(32, pair.privateKeySeed.size)
        assertEquals(32, pair.publicKey.size)
        // File should have been overwritten with the new 64-byte payload.
        assertEquals(64, dir.resolve("ed25519.key").length())
    }
}
