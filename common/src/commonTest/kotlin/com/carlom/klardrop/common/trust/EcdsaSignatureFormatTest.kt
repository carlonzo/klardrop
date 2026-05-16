package com.carlom.klardrop.common.trust

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks in the DER ↔ RAW conversion used by the Android Keystore and Apple
 * Keychain signing paths. Wire format must remain 64-byte RAW (r‖s); any
 * regression here breaks signature verification on every peer.
 */
class EcdsaSignatureFormatTest {

    @Test
    fun rawRoundTripsThroughDerWithFullSizeComponents() {
        val raw = ByteArray(64) { (it + 1).toByte() }
        val der = EcdsaSignatureFormat.rawToDer(raw)
        val recovered = EcdsaSignatureFormat.derToRaw(der)
        assertContentEquals(raw, recovered)
    }

    @Test
    fun derWithLeadingZeroForHighBitParsesBackToRaw() {
        // r and s both have the high bit set in their first byte: DER encoding
        // must prepend 0x00 to keep the INTEGER positive. derToRaw must strip it.
        val r = ByteArray(32) { 0xff.toByte() }
        val s = ByteArray(32) { 0x80.toByte() }
        val raw = r + s

        val der = EcdsaSignatureFormat.rawToDer(raw)
        // 0x30 LEN 0x02 0x21 0x00 r... 0x02 0x21 0x00 s...
        assertEquals(0x30.toByte(), der[0])
        assertEquals(0x02.toByte(), der[2])
        assertEquals(0x21.toByte(), der[3]) // 33 bytes (32 + leading 0x00)
        assertEquals(0x00.toByte(), der[4])

        assertContentEquals(raw, EcdsaSignatureFormat.derToRaw(der))
    }

    @Test
    fun derWithComponentSmallerThan32BytesPadsLeftToRaw() {
        // r is only 30 bytes long; raw form must left-pad to 32.
        val rValue = ByteArray(30) { 0x33.toByte() }
        val s = ByteArray(32) { 0x44.toByte() } // high bit clear, no DER padding

        // Build DER manually: 30 LEN 02 1E rValue 02 20 s
        val der = byteArrayOf(0x30, (4 + rValue.size + s.size).toByte()) +
            byteArrayOf(0x02, rValue.size.toByte()) + rValue +
            byteArrayOf(0x02, s.size.toByte()) + s

        val raw = EcdsaSignatureFormat.derToRaw(der)
        assertEquals(64, raw.size)
        // First 2 bytes of r component must be zero padding.
        assertEquals(0x00, raw[0])
        assertEquals(0x00, raw[1])
        for (i in 0 until 30) assertEquals(rValue[i], raw[2 + i])
        for (i in 0 until 32) assertEquals(s[i], raw[32 + i])
    }

    @Test
    fun signatureFromTrustCryptoConvertsBetweenFormatsLossless() = runTest {
        val crypto = TrustCrypto()
        val keyPair = crypto.generateECDSAKeyPair()
        val data = "ecdsa format round trip".encodeToByteArray()

        val rawSig = crypto.signWithECDSA(keyPair.privateKey, data)
        assertEquals(64, rawSig.size)

        val der = EcdsaSignatureFormat.rawToDer(rawSig)
        val backToRaw = EcdsaSignatureFormat.derToRaw(der)
        assertContentEquals(rawSig, backToRaw)
        assertTrue(crypto.verifyECDSA(keyPair.publicKey.data, data, backToRaw))
    }
}
