package com.carlom.klardrop.common.trust

import com.carlom.klardrop.common.trust.secretstore.EncryptedFileSecretStore
import com.carlom.klardrop.common.trust.secretstore.SecretStore
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Pins down the contract between [DesktopTrustStorage] and the new
 * [SecretStore] abstraction. The peer trust map is intentionally exercised
 * lightly here — the existing TrustManagerTest covers cross-platform
 * pairing semantics; we just want to know the device-key path goes to the
 * SecretStore and the public-key path stays on disk.
 */
class DesktopTrustStorageTest {

    private lateinit var appDir: File
    private lateinit var secretStore: FakeSecretStore
    private lateinit var storage: DesktopTrustStorage

    @BeforeTest
    fun setUp() {
        appDir = Files.createTempDirectory("klardrop-trust-test").toFile()
        secretStore = FakeSecretStore()
        storage = DesktopTrustStorage(appDir, secretStore)
    }

    @AfterTest
    fun tearDown() {
        appDir.deleteRecursively()
    }

    @Test
    fun storeDevicePrivateKeyDelegatesToSecretStore() = runTest {
        val bytes = ByteArray(32) { it.toByte() }
        storage.storeDevicePrivateKey(bytes)

        assertEquals(1, secretStore.puts.size)
        assertEquals("device-private-key", secretStore.puts.single().first)
        assertContentEquals(bytes, secretStore.puts.single().second)
        assertContentEquals(bytes, storage.getDevicePrivateKey())
    }

    @Test
    fun deletePurgesSecretStoreAndPublicKeyFile() = runTest {
        storage.storeDevicePrivateKey(ByteArray(32) { 0x7 })
        storage.storeDevicePublicKey(ByteArray(65) { 0x9 })

        storage.deleteDevicePrivateKey()

        assertNull(storage.getDevicePrivateKey())
        assertTrue(secretStore.deletes.contains("device-private-key"))
    }

    @Test
    fun publicKeyPersistsToDiskAcrossInstances() = runTest {
        val publicKey = ByteArray(65) { (it * 3).toByte() }
        storage.storeDevicePublicKey(publicKey)
        val reopened = DesktopTrustStorage(appDir, secretStore)
        assertContentEquals(publicKey, reopened.getDevicePublicKey())
    }

    @Test
    fun hasDeviceKeyMirrorsSecretStorePresence() = runTest {
        assertFalse(storage.hasDeviceKey())
        storage.storeDevicePrivateKey(ByteArray(32))
        assertTrue(storage.hasDeviceKey())
        storage.deleteDevicePrivateKey()
        assertFalse(storage.hasDeviceKey())
    }

    /**
     * Regression for the "can't share after trusting" bug. The private key (secret store /
     * keychain) and the public key (file under appDir) have independent lifetimes. If they
     * desync — e.g. a leftover keychain entry from a previous identity plus a public-key file
     * from another — ensureDeviceKey must NOT hand back the mismatched pair, or we advertise a
     * public key at pairing that we can't sign for and every peer rejects us permanently.
     */
    @Test
    fun ensureDeviceKeyRegeneratesWhenStoredHalvesDoNotMatch() = runTest {
        val crypto = TrustCrypto()
        // Seed mismatched halves: private of one keypair in the secret store, public of another on disk.
        secretStore.put("device-private-key", crypto.generateECDSAKeyPair().privateKey.data)
        storage.storeDevicePublicKey(crypto.generateECDSAKeyPair().publicKey.data)

        val advertised = storage.ensureDeviceKey(crypto).data

        val data = "desktop identity probe".encodeToByteArray()
        val signature = assertNotNull(storage.signWithDeviceKey(data, crypto))
        assertTrue(
            crypto.verifyECDSA(advertised, data, signature),
            "Desktop must advertise the public key that matches its keychain signing key",
        )
    }

    @Test
    fun ensureDeviceKeyReusesAConsistentStoredPair() = runTest {
        val crypto = TrustCrypto()
        val first = storage.ensureDeviceKey(crypto).data
        // Second call (e.g. app restart against the same stores) must reuse the same identity.
        val second = DesktopTrustStorage(appDir, secretStore).ensureDeviceKey(crypto).data
        assertContentEquals(first, second, "A matching stored pair must be reused, not regenerated")
    }

    @Test
    fun encryptedFileSecretStoreRoundTrips() {
        val store = EncryptedFileSecretStore(appDir)
        val data = ByteArray(48) { (it + 7).toByte() }
        store.put("device-private-key", data)
        assertContentEquals(data, store.get("device-private-key"))
        store.delete("device-private-key")
        assertNull(store.get("device-private-key"))
    }

    @Test
    fun encryptedFileSecretStoreRefusesTamperedCiphertext() {
        val store = EncryptedFileSecretStore(appDir)
        val data = ByteArray(64) { it.toByte() }
        store.put("device-private-key", data)

        val blob = File(appDir, "device-private-key.aes")
        val raw = blob.readBytes()
        // Flip a byte well inside the AES-GCM ciphertext region (after the 33-byte header).
        raw[40] = (raw[40].toInt() xor 0x55).toByte()
        blob.writeBytes(raw)

        // GCM authentication tag should fail; get() returns null rather than throwing.
        assertNull(store.get("device-private-key"))
    }

    private class FakeSecretStore : SecretStore {
        private val backing = mutableMapOf<String, ByteArray>()
        val puts = mutableListOf<Pair<String, ByteArray>>()
        val deletes = mutableListOf<String>()

        override fun get(account: String): ByteArray? = backing[account]?.copyOf()

        override fun put(account: String, value: ByteArray) {
            backing[account] = value.copyOf()
            puts += account to value.copyOf()
        }

        override fun delete(account: String) {
            backing.remove(account)
            deletes += account
        }
    }
}
