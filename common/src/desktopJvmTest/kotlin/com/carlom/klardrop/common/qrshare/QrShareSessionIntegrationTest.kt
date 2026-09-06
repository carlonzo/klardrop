package com.carlom.klardrop.common.qrshare

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.FileTransfer
import com.carlom.klardrop.common.communication.TransferAnchor
import com.carlom.klardrop.common.utils.CoroutinesImpl
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import org.junit.After
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

private class IntegrationRecordingTransferAnchor : TransferAnchor {
  data class BeginCall(val transferId: String, val label: String, val direction: TransferAnchor.Direction)
  val beginCalls = mutableListOf<BeginCall>()
  val endCalls = mutableListOf<String>()
  val activeTransfers = mutableSetOf<String>()

  override fun begin(transferId: String, label: String, direction: TransferAnchor.Direction) {
    beginCalls.add(BeginCall(transferId, label, direction))
    activeTransfers.add(transferId)
  }

  override fun progress(transferId: String, percentage: Int) {}

  override fun end(transferId: String) {
    endCalls.add(transferId)
    activeTransfers.remove(transferId)
  }
}

private class IntegrationFakeLanAddressSelector(
  private val ip: String = "127.0.0.1",
) : LanAddressSelector {
  val ipFlow = MutableSharedFlow<String?>(replay = 1)

  init {
    ipFlow.tryEmit(ip)
  }

  override suspend fun selectIpv4(): String = ip
  override fun observeChanges(): Flow<String?> = ipFlow
}

private class IntegrationFakeFileManager : FileManager {
  override fun prepareSaveFile(fileName: String, mimeType: String): FileTransfer = error("unused")
  override fun getReadStreamFrom(file: PlatformFile): RawSource = Buffer()
  override suspend fun openFile(filePath: String): Boolean = false
  override suspend fun openUrl(url: String): Boolean = false
}

class QrShareSessionIntegrationTest {

  private var activeSession: QrShareSession? = null

  @After
  fun tearDown() {
    activeSession?.cancel()
    activeSession = null
  }

  private fun executeGet(port: Int, path: String): String {
    val trustingTrustManager = object : X509TrustManager {
      override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
      override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
      override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, arrayOf(trustingTrustManager), SecureRandom())

    val socket = sslContext.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
    socket.soTimeout = 5000
    socket.startHandshake()

    val request = "GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n"
    socket.outputStream.write(request.encodeToByteArray())
    socket.outputStream.flush()

    val stream: InputStream = socket.inputStream
    val out = ByteArrayOutputStream()
    val buf = ByteArray(1024)
    var n: Int
    while (stream.read(buf).also { n = it } != -1) {
      out.write(buf, 0, n)
    }
    socket.close()
    return out.toByteArray().decodeToString()
  }

  @Test
  fun happyPath_realServerWithTls_servesLandingAndManagesAnchors(): Unit = runBlocking {
    val coroutines = CoroutinesImpl()
    val fileManager = IntegrationFakeFileManager()
    val tls = LanTlsListener()
    val server = LanHttpShareServer(coroutines, fileManager, tls, Clock.System)
    val anchor = IntegrationRecordingTransferAnchor()
    val selector = IntegrationFakeLanAddressSelector("127.0.0.1")

    val session = QrShareSession(
      coroutines = coroutines,
      fileManager = fileManager,
      transferAnchor = anchor,
      lanAddressSelector = selector,
      clock = Clock.System,
      server = server,
    )
    activeSession = session

    val payload = QrSharePayload.Text("Hello Real TLS QR Share")
    val state = session.start(payload)

    assertIs<QrShareState.QrVisible>(state)
    assertEquals("127.0.0.1", state.ipv4)
    assertEquals("Hello Real TLS QR Share", state.payloadSummary)

    // Verify wait anchor begun
    assertEquals(1, anchor.beginCalls.size)
    val waitCall = anchor.beginCalls.first()
    assertTrue(waitCall.transferId.endsWith(":wait"))
    assertEquals("Waiting for someone to scan", waitCall.label)

    // Extract path from url
    val initialUrl = state.url
    val path = initialUrl.substringAfter("127.0.0.1:${state.port}")

    // Listen for TokenRotated event
    val rotatedDeferred = kotlinx.coroutines.CompletableDeferred<String>()
    val collectJob = launch {
      session.state.filterIsInstance<QrShareState.QrVisible>().collect { visible ->
        if (visible.url != initialUrl) {
          rotatedDeferred.complete(visible.url)
        }
      }
    }

    // Perform real HTTPS request to the server
    val response = executeGet(state.port, path)
    assertTrue(response.startsWith("HTTP/1.1 200 OK"))
    assertTrue(response.contains("Hello Real TLS QR Share"))

    // Verify token was rotated and QR URL changed
    val rotatedUrl = withTimeout(5000) { rotatedDeferred.await() }
    assertNotEquals(initialUrl, rotatedUrl)
    collectJob.cancel()

    // Dismiss QR sheet after claim
    session.dismissQrSheet()

    // Wait anchor ended, grace anchor active
    assertTrue(anchor.endCalls.any { it.endsWith(":wait") })
    assertFalse(anchor.activeTransfers.any { it.endsWith(":wait") })
    assertTrue(anchor.activeTransfers.any { it.endsWith(":grace") })

    // Cancel cleans up everything
    session.cancel()
    assertEquals(QrShareState.Idle, session.state.value)
    assertTrue(anchor.activeTransfers.isEmpty())
  }
}
