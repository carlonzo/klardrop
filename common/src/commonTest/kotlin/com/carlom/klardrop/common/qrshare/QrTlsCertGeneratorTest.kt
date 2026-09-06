package com.carlom.klardrop.common.qrshare

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QrTlsCertGeneratorTest {

    @Test
    fun testGenerateCertAndPkcs12(): Unit = runBlocking {
        val result = QrTlsCertGenerator.generate("192.168.1.100")

        // DER sequence begins with tag 0x30
        assertEquals(0x30.toByte(), result.certDer[0])
        assertEquals(0x30.toByte(), result.privateKeyPkcs8Der[0])
        assertEquals(0x30.toByte(), result.pkcs12Der[0])

        // P-256 uncompressed public key begins with 0x04 and is 65 bytes
        assertEquals(65, result.publicKeyRaw.size)
        assertEquals(0x04.toByte(), result.publicKeyRaw[0])

        assertTrue(result.certDer.size > 200, "Certificate DER must be reasonably sized")
        assertTrue(result.pkcs12Der.size > 300, "PKCS#12 DER must be reasonably sized")
    }

    @Test
    fun testInvalidIpv4Rejection(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            QrTlsCertGenerator.generate("invalid")
        }
        assertFailsWith<IllegalArgumentException> {
            QrTlsCertGenerator.generate("256.1.2.3")
        }
        assertFailsWith<IllegalArgumentException> {
            QrTlsCertGenerator.generate("1.2.3")
        }
        assertFailsWith<IllegalArgumentException> {
            QrTlsCertGenerator.generate("1.2.3.4.5")
        }
    }
}
