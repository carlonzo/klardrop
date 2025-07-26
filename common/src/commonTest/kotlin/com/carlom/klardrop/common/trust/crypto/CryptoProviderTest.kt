package com.carlom.klardrop.common.trust.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CryptoProviderTest {
    
    private val cryptoProvider = CryptoProviderImpl()
    
    @Test
    fun testGenerateECDSAKeypair() = runTest {
        val keypair = cryptoProvider.generateECDSAKeypair()
        
        assertNotNull(keypair)
        assertTrue(keypair.publicKey.isNotEmpty())
        assertTrue(keypair.privateKey.isNotEmpty())
        
        // Keys should be different
        assertFalse(keypair.publicKey.contentEquals(keypair.privateKey))
        
        // Generate another keypair - should be different
        val keypair2 = cryptoProvider.generateECDSAKeypair()
        assertFalse(keypair.publicKey.contentEquals(keypair2.publicKey))
        assertFalse(keypair.privateKey.contentEquals(keypair2.privateKey))
    }
    
    @Test
    fun testGenerateECDHKeypair() = runTest {
        val keypair = cryptoProvider.generateECDHKeypair()
        
        assertNotNull(keypair)
        assertTrue(keypair.publicKey.isNotEmpty())
        assertTrue(keypair.privateKey.isNotEmpty())
        
        // Keys should be different
        assertFalse(keypair.publicKey.contentEquals(keypair.privateKey))
        
        // Generate another keypair - should be different
        val keypair2 = cryptoProvider.generateECDHKeypair()
        assertFalse(keypair.publicKey.contentEquals(keypair2.publicKey))
        assertFalse(keypair.privateKey.contentEquals(keypair2.privateKey))
    }
    
    @Test
    fun testGenerateAESKey() = runTest {
        val key = cryptoProvider.generateAESKey()
        
        assertNotNull(key)
        assertEquals(32, key.size) // 256-bit key
        
        // Generate another key - should be different
        val key2 = cryptoProvider.generateAESKey()
        assertFalse(key.contentEquals(key2))
    }
    
    @Test
    fun testECDSASignAndVerify() = runTest {
        val keypair = cryptoProvider.generateECDSAKeypair()
        val data = "Test message to sign".encodeToByteArray()
        
        // Sign the data
        val signature = cryptoProvider.signECDSA(data, keypair.privateKey)
        assertNotNull(signature)
        assertTrue(signature.isNotEmpty())
        
        // Verify with correct public key
        val isValid = cryptoProvider.verifyECDSA(data, signature, keypair.publicKey)
        assertTrue(isValid)
        
        // Verify with wrong data should fail
        val wrongData = "Wrong message".encodeToByteArray()
        val isInvalid = cryptoProvider.verifyECDSA(wrongData, signature, keypair.publicKey)
        assertFalse(isInvalid)
        
        // Verify with wrong public key should fail
        val wrongKeypair = cryptoProvider.generateECDSAKeypair()
        val isInvalidKey = cryptoProvider.verifyECDSA(data, signature, wrongKeypair.publicKey)
        assertFalse(isInvalidKey)
        
        // Verify with corrupted signature should fail
        val corruptedSignature = signature.copyOf()
        corruptedSignature[0] = (corruptedSignature[0] + 1).toByte()
        val isCorrupted = cryptoProvider.verifyECDSA(data, corruptedSignature, keypair.publicKey)
        assertFalse(isCorrupted)
    }
    
    @Test
    fun testECDSASignEmptyData() = runTest {
        val keypair = cryptoProvider.generateECDSAKeypair()
        val emptyData = ByteArray(0)
        
        // Should be able to sign empty data
        val signature = cryptoProvider.signECDSA(emptyData, keypair.privateKey)
        assertNotNull(signature)
        
        // And verify it
        val isValid = cryptoProvider.verifyECDSA(emptyData, signature, keypair.publicKey)
        assertTrue(isValid)
    }
    
    @Test
    fun testECDHKeyExchange() = runTest {
        // Generate two keypairs for two parties
        val alice = cryptoProvider.generateECDHKeypair()
        val bob = cryptoProvider.generateECDHKeypair()
        
        // Compute shared secrets
        val aliceSecret = cryptoProvider.computeECDHSecret(alice.privateKey, bob.publicKey)
        val bobSecret = cryptoProvider.computeECDHSecret(bob.privateKey, alice.publicKey)
        
        // Both parties should derive the same shared secret
        assertNotNull(aliceSecret)
        assertNotNull(bobSecret)
        assertTrue(aliceSecret.contentEquals(bobSecret))
        assertTrue(aliceSecret.isNotEmpty())
        
        // Using wrong keys should produce different secrets
        val eve = cryptoProvider.generateECDHKeypair()
        val eveSecret = cryptoProvider.computeECDHSecret(eve.privateKey, alice.publicKey)
        assertFalse(eveSecret.contentEquals(aliceSecret))
    }
    
    @Test
    fun testAESGCMEncryptDecrypt() = runTest {
        val key = cryptoProvider.generateAESKey()
        val plaintext = "Secret message to encrypt".encodeToByteArray()
        
        // Encrypt
        val encrypted = cryptoProvider.encryptAESGCM(plaintext, key)
        assertNotNull(encrypted)
        assertTrue(encrypted.ciphertext.isNotEmpty())
        assertEquals(12, encrypted.nonce.size) // 96-bit nonce
        assertTrue(encrypted.tag.isNotEmpty())
        
        // Ciphertext should be different from plaintext
        assertFalse(encrypted.ciphertext.contentEquals(plaintext))
        
        // Decrypt
        val decrypted = cryptoProvider.decryptAESGCM(encrypted, key)
        assertTrue(decrypted.contentEquals(plaintext))
    }
    
    @Test
    fun testAESGCMEncryptDecryptEmptyData() = runTest {
        val key = cryptoProvider.generateAESKey()
        val emptyData = ByteArray(0)
        
        // Should be able to encrypt empty data
        val encrypted = cryptoProvider.encryptAESGCM(emptyData, key)
        assertNotNull(encrypted)
        
        // And decrypt it
        val decrypted = cryptoProvider.decryptAESGCM(encrypted, key)
        assertTrue(decrypted.contentEquals(emptyData))
        assertEquals(0, decrypted.size)
    }
    
    @Test
    fun testAESGCMDecryptWithWrongKey() = runTest {
        val key = cryptoProvider.generateAESKey()
        val wrongKey = cryptoProvider.generateAESKey()
        val plaintext = "Secret message".encodeToByteArray()
        
        val encrypted = cryptoProvider.encryptAESGCM(plaintext, key)
        
        // Decryption with wrong key should fail
        assertFailsWith<Exception> {
            cryptoProvider.decryptAESGCM(encrypted, wrongKey)
        }
    }
    
    @Test
    fun testAESGCMDecryptWithCorruptedTag() = runTest {
        val key = cryptoProvider.generateAESKey()
        val plaintext = "Secret message".encodeToByteArray()
        
        val encrypted = cryptoProvider.encryptAESGCM(plaintext, key)
        
        // Corrupt the authentication tag
        val corruptedTag = encrypted.tag.copyOf()
        corruptedTag[0] = (corruptedTag[0] + 1).toByte()
        val corruptedPayload = encrypted.copy(tag = corruptedTag)
        
        // Decryption should fail
        assertFailsWith<Exception> {
            cryptoProvider.decryptAESGCM(corruptedPayload, key)
        }
    }
    
    @Test
    fun testAESGCMDecryptWithCorruptedCiphertext() = runTest {
        val key = cryptoProvider.generateAESKey()
        val plaintext = "Secret message".encodeToByteArray()
        
        val encrypted = cryptoProvider.encryptAESGCM(plaintext, key)
        
        // Corrupt the ciphertext
        val corruptedCiphertext = encrypted.ciphertext.copyOf()
        if (corruptedCiphertext.isNotEmpty()) {
            corruptedCiphertext[0] = (corruptedCiphertext[0] + 1).toByte()
        }
        val corruptedPayload = encrypted.copy(ciphertext = corruptedCiphertext)
        
        // Decryption should fail
        assertFailsWith<Exception> {
            cryptoProvider.decryptAESGCM(corruptedPayload, key)
        }
    }
    
    @Test
    fun testAESGCMNoncesAreUnique() = runTest {
        val key = cryptoProvider.generateAESKey()
        val plaintext = "Test message".encodeToByteArray()
        
        // Encrypt same message multiple times
        val encrypted1 = cryptoProvider.encryptAESGCM(plaintext, key)
        val encrypted2 = cryptoProvider.encryptAESGCM(plaintext, key)
        val encrypted3 = cryptoProvider.encryptAESGCM(plaintext, key)
        
        // Nonces should all be different
        assertFalse(encrypted1.nonce.contentEquals(encrypted2.nonce))
        assertFalse(encrypted1.nonce.contentEquals(encrypted3.nonce))
        assertFalse(encrypted2.nonce.contentEquals(encrypted3.nonce))
        
        // Ciphertexts should also be different (due to different nonces)
        assertFalse(encrypted1.ciphertext.contentEquals(encrypted2.ciphertext))
        assertFalse(encrypted1.ciphertext.contentEquals(encrypted3.ciphertext))
        assertFalse(encrypted2.ciphertext.contentEquals(encrypted3.ciphertext))
    }
    
    @Test
    fun testDeriveKey() = runTest {
        val secret = "shared secret".encodeToByteArray()
        val salt = "salt value".encodeToByteArray()
        val info = "application info".encodeToByteArray()
        
        // Derive key
        val derivedKey = cryptoProvider.deriveKey(secret, salt, info)
        assertNotNull(derivedKey)
        assertEquals(32, derivedKey.size) // 256-bit key
        
        // Same inputs should produce same output
        val derivedKey2 = cryptoProvider.deriveKey(secret, salt, info)
        assertTrue(derivedKey.contentEquals(derivedKey2))
        
        // Different inputs should produce different outputs
        val differentSalt = "different salt".encodeToByteArray()
        val derivedKey3 = cryptoProvider.deriveKey(secret, differentSalt, info)
        assertFalse(derivedKey.contentEquals(derivedKey3))
        
        val differentInfo = "different info".encodeToByteArray()
        val derivedKey4 = cryptoProvider.deriveKey(secret, salt, differentInfo)
        assertFalse(derivedKey.contentEquals(derivedKey4))
    }
    
    @Test
    fun testDeriveKeyWithEmptyInputs() = runTest {
        val secret = "secret".encodeToByteArray()
        val emptySalt = ByteArray(0)
        val emptyInfo = ByteArray(0)
        
        // Should work with empty salt and info
        val derivedKey = cryptoProvider.deriveKey(secret, emptySalt, emptyInfo)
        assertNotNull(derivedKey)
        assertEquals(32, derivedKey.size)
    }
    
    @Test
    fun testGenerateNonce() {
        val nonce1 = cryptoProvider.generateNonce()
        val nonce2 = cryptoProvider.generateNonce()
        
        assertEquals(12, nonce1.size) // 96-bit nonce for GCM
        assertEquals(12, nonce2.size)
        assertFalse(nonce1.contentEquals(nonce2)) // Should be random
    }
    
    @Test
    fun testGenerateRandomBytes() {
        // Test various lengths
        val lengths = listOf(0, 1, 16, 32, 64, 128)
        
        for (length in lengths) {
            val bytes = cryptoProvider.generateRandomBytes(length)
            assertEquals(length, bytes.size)
            
            // For non-empty arrays, check randomness
            if (length > 0) {
                val bytes2 = cryptoProvider.generateRandomBytes(length)
                assertFalse(bytes.contentEquals(bytes2))
            }
        }
    }
    
    @Test
    fun testHash() {
        val data1 = "Hello, World!".encodeToByteArray()
        val data2 = "Hello, World!".encodeToByteArray()
        val data3 = "Different data".encodeToByteArray()
        
        val hash1 = cryptoProvider.hash(data1)
        val hash2 = cryptoProvider.hash(data2)
        val hash3 = cryptoProvider.hash(data3)
        
        // SHA-256 produces 32-byte hashes
        assertEquals(32, hash1.size)
        assertEquals(32, hash2.size)
        assertEquals(32, hash3.size)
        
        // Same input should produce same hash
        assertTrue(hash1.contentEquals(hash2))
        
        // Different input should produce different hash
        assertFalse(hash1.contentEquals(hash3))
    }
    
    @Test
    fun testHashEmptyData() {
        val emptyData = ByteArray(0)
        val hash = cryptoProvider.hash(emptyData)
        
        assertNotNull(hash)
        assertEquals(32, hash.size)
        
        // SHA-256 of empty string has a known value
        val expectedHash = byteArrayOf(
            -29, -80, -60, 66, -104, -4, 28, 20, -102, -5, -12, -56, -103, 111, -71, 36,
            39, -82, 65, -28, 100, -101, -109, 76, -92, -107, -103, 27, 120, 82, -72, 85
        )
        assertTrue(hash.contentEquals(expectedHash))
    }
    
    @Test
    fun testIntegrationECDHWithAES() = runTest {
        // Simulate secure communication between Alice and Bob
        val alice = cryptoProvider.generateECDHKeypair()
        val bob = cryptoProvider.generateECDHKeypair()
        
        // Exchange public keys and compute shared secrets
        val aliceSharedSecret = cryptoProvider.computeECDHSecret(alice.privateKey, bob.publicKey)
        val bobSharedSecret = cryptoProvider.computeECDHSecret(bob.privateKey, alice.publicKey)
        
        // Derive encryption keys from shared secret
        val salt = "klardrop-trust-v1".encodeToByteArray()
        val info = "encryption-key".encodeToByteArray()
        val aliceKey = cryptoProvider.deriveKey(aliceSharedSecret, salt, info)
        val bobKey = cryptoProvider.deriveKey(bobSharedSecret, salt, info)
        
        // Alice encrypts a message
        val message = "Secret message from Alice to Bob".encodeToByteArray()
        val encrypted = cryptoProvider.encryptAESGCM(message, aliceKey)
        
        // Bob decrypts the message
        val decrypted = cryptoProvider.decryptAESGCM(encrypted, bobKey)
        
        assertTrue(decrypted.contentEquals(message))
    }
    
    @Test
    fun testIntegrationSignatureWithHash() = runTest {
        // Test signing a hash of data (common pattern)
        val keypair = cryptoProvider.generateECDSAKeypair()
        val data = "Important document to sign".encodeToByteArray()
        
        // Hash the data first
        val dataHash = cryptoProvider.hash(data)
        
        // Sign the hash
        val signature = cryptoProvider.signECDSA(dataHash, keypair.privateKey)
        
        // Verify by hashing the original data and checking signature
        val verifyHash = cryptoProvider.hash(data)
        val isValid = cryptoProvider.verifyECDSA(verifyHash, signature, keypair.publicKey)
        
        assertTrue(isValid)
        
        // Tampering with data should fail verification
        val tamperedData = "Tampered document to sign".encodeToByteArray()
        val tamperedHash = cryptoProvider.hash(tamperedData)
        val isTampered = cryptoProvider.verifyECDSA(tamperedHash, signature, keypair.publicKey)
        
        assertFalse(isTampered)
    }
}