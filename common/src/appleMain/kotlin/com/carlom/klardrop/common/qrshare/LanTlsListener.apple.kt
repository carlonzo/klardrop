@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class
)

package com.carlom.klardrop.common.qrshare

import com.carlom.klardrop.common.utils.log
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.CoreFoundation.*
import platform.Network.*
import platform.Security.*
import platform.darwin.dispatch_data_apply
import platform.darwin.dispatch_data_create
import platform.darwin.dispatch_queue_create
import platform.posix.memcpy
import platform.posix.sockaddr_in

actual class LanTlsListener actual constructor() {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var listener: nw_listener_t = null
    private var identity: SecIdentityRef? = null
    private var connectionsChannel = Channel<TlsConnection>(Channel.BUFFERED)
    private val activeConnections = mutableListOf<TlsConnection>()
    private val mutex = Mutex()
    private val queue = dispatch_queue_create("com.carlom.klardrop.lan_tls", null)

    actual suspend fun bind(ipv4: String, port: Int): Bound {
        close()
        mutex.withLock {
            if (connectionsChannel.isClosedForSend) {
                connectionsChannel = Channel(Channel.BUFFERED)
            }
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }

        val certResult = QrTlsCertGenerator.generate(ipv4)

        val importedIdentity = importPkcs12Identity(certResult.pkcs12Der)
            ?: throw IllegalStateException("Failed to import self-signed PKCS#12 identity into Keychain")
        identity = importedIdentity

        val secId = sec_identity_create(importedIdentity)
        val parameters = nw_parameters_create_secure_tcp(
            configure_tls = { tlsOptions ->
                val secOptions = nw_tls_copy_sec_protocol_options(tlsOptions)
                sec_protocol_options_set_local_identity(secOptions, secId)
                sec_protocol_options_set_min_tls_protocol_version(secOptions, platform.Security.tls_protocol_version_TLSv12)
            },
            configure_tcp = NW_PARAMETERS_DEFAULT_CONFIGURATION
        )

        val l = nw_listener_create_with_port(port.toString(), parameters)
            ?: throw IllegalStateException("Failed to create NWListener on port $port")
        nw_listener_set_queue(l, queue)

        val boundDeferred = CompletableDeferred<Bound>()

        nw_listener_set_state_changed_handler(l) { state, error ->
            when (state) {
                nw_listener_state_ready -> {
                    val actualPort = nw_listener_get_port(l).toInt()
                    log("LanTlsListener", "NWListener ready on 0.0.0.0:$actualPort for advertised host $ipv4")
                    boundDeferred.complete(Bound(actualPort))
                }
                nw_listener_state_failed -> {
                    log("LanTlsListener", "NWListener state failed: $error")
                    boundDeferred.completeExceptionally(IllegalStateException("NWListener failed: $error"))
                }
                nw_listener_state_cancelled -> {
                    boundDeferred.completeExceptionally(IllegalStateException("NWListener cancelled"))
                }
                else -> {}
            }
        }

        nw_listener_set_new_connection_handler(l) { connection ->
            if (connection == null) return@nw_listener_set_new_connection_handler
            handleConnection(connection)
        }

        listener = l
        nw_listener_start(l)

        return boundDeferred.await()
    }

    actual fun incoming(): Flow<TlsConnection> = connectionsChannel.receiveAsFlow()

    actual fun close() {
        val l = listener
        listener = null
        if (l != null) {
            nw_listener_cancel(l)
        }

        val connsToClose = mutableListOf<TlsConnection>()
        val idToClean = identity
        identity = null

        connsToClose.addAll(activeConnections)
        activeConnections.clear()

        for (conn in connsToClose) {
            try {
                conn.close()
            } catch (_: Exception) {
            }
        }

        connectionsChannel.close()
        scope.cancel()

        if (idToClean != null) {
            deleteKeychainIdentity(idToClean)
            CFRelease(idToClean)
        }
    }

    private fun handleConnection(connection: nw_connection_t) {
        nw_connection_set_queue(connection, queue)

        nw_connection_set_state_changed_handler(connection) { state, error ->
            when (state) {
                nw_connection_state_ready -> {
                    onConnectionReady(connection)
                }
                nw_connection_state_failed -> {
                    log("LanTlsListener", "Apple connection failed/handshake dropped: $error")
                    nw_connection_cancel(connection)
                }
                nw_connection_state_cancelled -> {
                    // Handled
                }
                else -> {}
            }
        }

        nw_connection_start(connection)
    }

    private fun onConnectionReady(connection: nw_connection_t) {
        val peerIpv4 = extractPeerIpv4(connection) ?: ""
        val inputChannel = ByteChannel(autoFlush = true)
        val outputChannel = ByteChannel(autoFlush = true)

        var connectionClosed = false
        fun closeConn() {
            if (connectionClosed) return
            connectionClosed = true
            try {
                inputChannel.close()
            } catch (_: Exception) {}
            try {
                outputChannel.close()
            } catch (_: Exception) {}
            try {
                nw_connection_cancel(connection)
            } catch (_: Exception) {}
        }

        val tlsConnection = TlsConnection(
            peerIpv4 = peerIpv4,
            input = inputChannel,
            output = outputChannel,
            close = { closeConn() }
        )

        activeConnections.add(tlsConnection)

        // Start receive loop
        scheduleReceive(connection, inputChannel) {
            closeConn()
            activeConnections.remove(tlsConnection)
        }

        // Start send loop
        scheduleSend(connection, outputChannel) {
            closeConn()
            activeConnections.remove(tlsConnection)
        }

        scope.launch {
            try {
                connectionsChannel.send(tlsConnection)
            } catch (_: Exception) {
                closeConn()
            }
        }
    }

    private fun scheduleReceive(
        connection: nw_connection_t,
        inputChannel: ByteChannel,
        onClose: () -> Unit,
    ) {
        nw_connection_receive(
            connection = connection,
            minimum_incomplete_length = 1u,
            maximum_length = 65536u
        ) { content, _, isComplete, error ->
            if (content != null) {
                dispatch_data_apply(content) { _, _, buffer, size ->
                    if (buffer != null && size > 0u) {
                        val len = size.toInt()
                        val bytes = ByteArray(len)
                        bytes.usePinned { pinned ->
                            memcpy(pinned.addressOf(0), buffer, size)
                        }
                        scope.launch {
                            try {
                                inputChannel.writeFully(bytes)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    true
                }
            }

            if (isComplete || error != null) {
                inputChannel.close()
                onClose()
            } else {
                scheduleReceive(connection, inputChannel, onClose)
            }
        }
    }

    private fun scheduleSend(
        connection: nw_connection_t,
        outputChannel: ByteChannel,
        onClose: () -> Unit,
    ) {
        scope.launch {
            val buffer = ByteArray(8192)
            try {
                while (isActive && !outputChannel.isClosedForRead) {
                    val read = outputChannel.readAvailable(buffer, 0, buffer.size)
                    if (read == -1) break
                    if (read > 0) {
                        val chunk = buffer.copyOf(read)
                        val sendDone = CompletableDeferred<Unit>()
                        chunk.usePinned { pinned ->
                            val dispatchData = dispatch_data_create(pinned.addressOf(0), read.toULong(), queue, null)
                            nw_connection_send(
                                connection = connection,
                                content = dispatchData,
                                context = NW_CONNECTION_DEFAULT_MESSAGE_CONTEXT,
                                is_complete = false
                            ) { sendError ->
                                if (sendError != null) {
                                    sendDone.completeExceptionally(IllegalStateException("Send failed: $sendError"))
                                } else {
                                    sendDone.complete(Unit)
                                }
                            }
                        }
                        sendDone.await()
                    }
                }
            } catch (_: Exception) {
            } finally {
                onClose()
            }
        }
    }

    private fun extractPeerIpv4(connection: nw_connection_t): String? {
        val endpoint = nw_connection_copy_endpoint(connection) ?: return null
        val saPtr = nw_endpoint_get_address(endpoint) ?: return null
        val family = saPtr.pointed.sa_family.toInt() and 0xFF
        if (family == 2) { // AF_INET
            val saIn = saPtr.reinterpret<sockaddr_in>()
            val bytePtr = saIn.pointed.sin_addr.ptr.reinterpret<UByteVar>()
            return "${bytePtr[0]}.${bytePtr[1]}.${bytePtr[2]}.${bytePtr[3]}"
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun importPkcs12Identity(pkcs12Bytes: ByteArray): SecIdentityRef? = memScoped {
        val cfData = pkcs12Bytes.usePinned { pinned ->
            CFDataCreate(null, pinned.addressOf(0).reinterpret(), pkcs12Bytes.size.toLong())!!
        }
        try {
            val itemsVar = alloc<CFArrayRefVar>()
            val status = SecPKCS12Import(cfData, null, itemsVar.ptr)
            if (status != errSecSuccess) {
                log("LanTlsListener", "SecPKCS12Import failed: $status")
                return null
            }
            val items = itemsVar.value ?: return null
            val count = CFArrayGetCount(items)
            if (count <= 0) return null
            val dict = CFArrayGetValueAtIndex(items, 0) as? CFDictionaryRef ?: return null
            val idRef = CFDictionaryGetValue(dict, kSecImportItemIdentity) as? SecIdentityRef ?: return null
            CFRetain(idRef)
            idRef
        } finally {
            CFRelease(cfData)
        }
    }

    private fun deleteKeychainIdentity(id: SecIdentityRef) = memScoped {
        val certRefVar = alloc<SecCertificateRefVar>()
        if (SecIdentityCopyCertificate(id, certRefVar.ptr) == errSecSuccess) {
            val cert = certRefVar.value
            if (cert != null) {
                val query = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)!!
                CFDictionarySetValue(query, kSecValueRef, cert)
                SecItemDelete(query)
                CFRelease(query)
                CFRelease(cert)
            }
        }
        val keyRefVar = alloc<SecKeyRefVar>()
        if (SecIdentityCopyPrivateKey(id, keyRefVar.ptr) == errSecSuccess) {
            val key = keyRefVar.value
            if (key != null) {
                val query = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)!!
                CFDictionarySetValue(query, kSecValueRef, key)
                SecItemDelete(query)
                CFRelease(query)
                CFRelease(key)
            }
        }
    }
}
