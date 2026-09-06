@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

package com.carlom.klardrop.common.qrshare

import com.carlom.klardrop.common.utils.log
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

actual class LanTlsListener actual constructor() {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: SSLServerSocket? = null
    private var connectionsChannel = Channel<TlsConnection>(Channel.BUFFERED)
    private val activeConnections = mutableListOf<TlsConnection>()
    private val lock = Any()

    actual suspend fun bind(ipv4: String, port: Int): Bound = withContext(Dispatchers.IO) {
        close()
        synchronized(lock) {
            if (connectionsChannel.isClosedForSend) {
                connectionsChannel = Channel(Channel.BUFFERED)
            }
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }

        val certResult = QrTlsCertGenerator.generate(ipv4)

        val kf = KeyFactory.getInstance("EC")
        val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(certResult.privateKeyPkcs8Der))
        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(ByteArrayInputStream(certResult.certDer)) as X509Certificate

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setKeyEntry("lan-share", privKey, charArrayOf(), arrayOf(cert))

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, charArrayOf())

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, null)

        val ss = sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 50)
        val supported = ss.supportedProtocols.toSet()
        val enabled = listOf("TLSv1.3", "TLSv1.2").filter { it in supported }.toTypedArray()
        if (enabled.isNotEmpty()) {
            ss.enabledProtocols = enabled
        }
        serverSocket = ss

        val boundPort = ss.localPort
        log("LanTlsListener", "Listening on 0.0.0.0:$boundPort for advertised host $ipv4")

        startAcceptLoop(ss, scope)

        Bound(boundPort)
    }

    actual fun incoming(): Flow<TlsConnection> = connectionsChannel.receiveAsFlow()

    actual fun close() {
        synchronized(lock) {
            val ss = serverSocket
            serverSocket = null
            try {
                ss?.close()
            } catch (_: Exception) {
            }

            for (conn in activeConnections.toList()) {
                try {
                    conn.close()
                } catch (_: Exception) {
                }
            }
            activeConnections.clear()
            connectionsChannel.close()
            scope.cancel()
        }
    }

    private fun startAcceptLoop(ss: SSLServerSocket, acceptScope: CoroutineScope) {
        acceptScope.launch(Dispatchers.IO) {
            try {
                while (isActive && !ss.isClosed) {
                    val clientSocket = try {
                        ss.accept() as SSLSocket
                    } catch (_: SocketException) {
                        break
                    } catch (_: Exception) {
                        break
                    }

                    launch(Dispatchers.IO) {
                        handleClientSocket(clientSocket)
                    }
                }
            } finally {
                try {
                    ss.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun handleClientSocket(socket: SSLSocket) {
        try {
            socket.soTimeout = 3000
            socket.startHandshake()
            socket.soTimeout = 0

            val remoteAddr = socket.remoteSocketAddress as? InetSocketAddress
            val peerIpv4 = remoteAddr?.address?.hostAddress ?: ""

            val inputChannel = ByteChannel(autoFlush = true)
            val outputChannel = ByteChannel(autoFlush = true)

            var connectionClosed = false
            fun closeConn() {
                synchronized(lock) {
                    if (connectionClosed) return
                    connectionClosed = true
                    try {
                        inputChannel.close()
                    } catch (_: Exception) {}
                    try {
                        outputChannel.close()
                    } catch (_: Exception) {}
                    try {
                        socket.close()
                    } catch (_: Exception) {}
                }
            }

            val tlsConnection = TlsConnection(
                peerIpv4 = peerIpv4,
                input = inputChannel,
                output = outputChannel,
                close = { closeConn() }
            )

            synchronized(lock) {
                activeConnections.add(tlsConnection)
            }

            scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(8192)
                try {
                    val stream = socket.inputStream
                    while (isActive && !inputChannel.isClosedForWrite) {
                        val read = stream.read(buffer)
                        if (read == -1) break
                        if (read > 0) {
                            inputChannel.writeFully(buffer, 0, read)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    inputChannel.close()
                }
            }

            scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(8192)
                try {
                    val stream = socket.outputStream
                    while (isActive && !outputChannel.isClosedForRead) {
                        val read = outputChannel.readAvailable(buffer, 0, buffer.size)
                        if (read == -1) break
                        if (read > 0) {
                            stream.write(buffer, 0, read)
                            stream.flush()
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    closeConn()
                    synchronized(lock) {
                        activeConnections.remove(tlsConnection)
                    }
                }
            }

            connectionsChannel.send(tlsConnection)
        } catch (e: Exception) {
            log("LanTlsListener", "Handshake dropped: ${e.message}")
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }
}
