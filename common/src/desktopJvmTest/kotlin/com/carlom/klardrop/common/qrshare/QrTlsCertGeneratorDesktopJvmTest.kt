package com.carlom.klardrop.common.qrshare

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.KeyStore
import java.security.cert.X509Certificate
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QrTlsCertGeneratorDesktopJvmTest {

    @Test
    fun testPkcs12LoadAndCertSanAndEku(): Unit = runBlocking {
        val ipv4 = "192.168.1.100"
        val result = QrTlsCertGenerator.generate(ipv4)

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(
            ByteArrayInputStream(result.pkcs12Der),
            QrTlsCertGenerator.PKCS12_PASSWORD.toCharArray()
        )

        val aliases = keyStore.aliases().toList()
        assertTrue(aliases.isNotEmpty(), "PKCS#12 bundle must contain at least one alias")

        val cert = keyStore.getCertificate(aliases.first()) as? X509Certificate
        assertNotNull(cert, "Certificate must be present in PKCS#12 bundle and be an X509Certificate")

        // Check SAN contains IPAddress 192.168.1.100 (GeneralName tag [7])
        val sans = cert.subjectAlternativeNames
        assertNotNull(sans, "Certificate must have Subject Alternative Names")
        assertTrue(
            sans.any { it[0] == 7 && it[1] == ipv4 },
            "SAN must contain IPAddress $ipv4, got: $sans"
        )

        // Check EKU contains id-kp-serverAuth (1.3.6.1.5.5.7.3.1)
        val eku = cert.extendedKeyUsage
        assertNotNull(eku, "Certificate must have Extended Key Usage")
        assertTrue(
            eku.contains("1.3.6.1.5.5.7.3.1"),
            "EKU must contain id-kp-serverAuth (1.3.6.1.5.5.7.3.1), got: $eku"
        )

        // Loading with incorrect password must fail integrity verification
        assertFailsWith<IOException> {
            val badKeyStore = KeyStore.getInstance("PKCS12")
            badKeyStore.load(
                ByteArrayInputStream(result.pkcs12Der),
                "incorrect-passphrase".toCharArray()
            )
        }
    }
}
