package com.carlom.klardrop.common.qrshare

import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LanTlsListenerTest {

    @Test
    fun testBindReturnsNonZeroPortAndParsesCert(): Unit = runBlocking {
        val listener = LanTlsListener()
        try {
            val bound = listener.bind("10.0.0.1", 0)
            assertTrue(bound.port > 0, "Bound port must be non-zero")

            var presentedCert: X509Certificate? = null
            val capturingTrustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    if (chain != null && chain.isNotEmpty()) {
                        presentedCert = chain[0]
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(capturingTrustManager), SecureRandom())
            val clientSocket = sslContext.socketFactory.createSocket("127.0.0.1", bound.port) as SSLSocket
            clientSocket.startHandshake()
            clientSocket.close()

            val cert = presentedCert ?: error("Server must present a certificate during TLS handshake")

            // Check SAN contains IPAddress 10.0.0.1 (GeneralName tag [7])
            val sans = cert.subjectAlternativeNames
            assertNotNull(sans, "Certificate must have Subject Alternative Names")
            assertTrue(
                sans.any { it[0] == 7 && it[1] == "10.0.0.1" },
                "SAN must contain IPAddress 10.0.0.1, got: $sans"
            )

            // Check EKU contains id-kp-serverAuth (1.3.6.1.5.5.7.3.1)
            val eku = cert.extendedKeyUsage
            assertNotNull(eku, "Certificate must have Extended Key Usage")
            assertTrue(
                eku.contains("1.3.6.1.5.5.7.3.1"),
                "EKU must contain id-kp-serverAuth (1.3.6.1.5.5.7.3.1), got: $eku"
            )

            // Check KeyUsage digitalSignature (bit 0)
            val keyUsage = cert.keyUsage
            assertNotNull(keyUsage, "Certificate must have KeyUsage")
            assertTrue(keyUsage[0], "KeyUsage must include digitalSignature")
        } finally {
            listener.close()
        }
    }

    @Test
    fun testDefaultTrustHandshakeFails(): Unit = runBlocking {
        val listener = LanTlsListener()
        try {
            val bound = listener.bind("10.0.0.1", 0)
            assertTrue(bound.port > 0)

            val defaultSslContext = SSLContext.getDefault()
            val clientSocket = defaultSslContext.socketFactory.createSocket("127.0.0.1", bound.port) as SSLSocket

            assertFails {
                clientSocket.startHandshake()
            }
            try {
                clientSocket.close()
            } catch (_: Exception) {}
        } finally {
            listener.close()
        }
    }

    @Test
    fun testTrustedClientHandshakeAndByteExchangeAndPeerIpv4(): Unit = runBlocking {
        val listener = LanTlsListener()
        try {
            val bound = listener.bind("10.0.0.1", 0)

            val capturedCert = CompletableDeferred<X509Certificate>()
            val trustingTrustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    val presented = chain?.firstOrNull() ?: throw CertificateException("Empty cert chain")
                    capturedCert.complete(presented)
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val trustedCtx = SSLContext.getInstance("TLS")
            trustedCtx.init(null, arrayOf(trustingTrustManager), SecureRandom())

            val connDeferred = CompletableDeferred<TlsConnection>()
            val collectJob = launch {
                listener.incoming().collect { conn ->
                    connDeferred.complete(conn)
                }
            }

            val clientSocket = trustedCtx.socketFactory.createSocket("127.0.0.1", bound.port) as SSLSocket
            clientSocket.startHandshake()

            val serverConn = withTimeout(5000) { connDeferred.await() }
            val serverCert = withTimeout(5000) { capturedCert.await() }
            assertNotNull(serverCert)

            // Verify peer IPv4 is 127.0.0.1
            assertEquals("127.0.0.1", serverConn.peerIpv4)

            // Client sends "PING\n"
            clientSocket.outputStream.write("PING\n".encodeToByteArray())
            clientSocket.outputStream.flush()

            // Server receives "PING"
            @Suppress("DEPRECATION")
            val line = serverConn.input.readUTF8Line()
            assertEquals("PING", line)

            // Server writes "PONG\n"
            serverConn.output.writeStringUtf8("PONG\n")
            serverConn.output.flush()

            // Client reads "PONG"
            val reader = BufferedReader(InputStreamReader(clientSocket.inputStream))
            val response = reader.readLine()
            assertEquals("PONG", response)

            clientSocket.close()
            serverConn.close()
            collectJob.cancel()
        } finally {
            listener.close()
        }
    }

    @Test
    fun testClosePreventsNewConnect(): Unit = runBlocking {
        val listener = LanTlsListener()
        val bound = listener.bind("10.0.0.1", 0)
        assertTrue(bound.port > 0)

        // Close the listener
        listener.close()

        // Connecting to the closed port must fail
        assertFails {
            val socket = Socket("127.0.0.1", bound.port)
            socket.close()
        }
    }
}
