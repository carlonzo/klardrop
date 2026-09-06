package com.carlom.klardrop.common.qrshare

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.FileTransfer
import com.carlom.klardrop.common.communication.createTestPlatformFile
import com.carlom.klardrop.common.utils.CoroutinesImpl
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeFileManager(
  private val files: Map<PlatformFile, ByteArray> = emptyMap()
) : FileManager {
  override fun prepareSaveFile(fileName: String, mimeType: String): FileTransfer = error("unused")
  override fun getReadStreamFrom(file: PlatformFile): RawSource {
    val bytes = files[file] ?: ByteArray(0)
    val buffer = Buffer()
    buffer.write(bytes)
    return buffer
  }
  override suspend fun openFile(filePath: String): Boolean = false
  override suspend fun openUrl(url: String): Boolean = false
}

data class TestHttpResponse(
  val status: Int,
  val headers: Map<String, String>,
  val body: ByteArray,
) {
  val text: String get() = body.decodeToString()
}

class LanHttpShareServerIntegrationTest {

  private var activeServer: LanHttpShareServer? = null

  @After
  fun tearDown() {
    activeServer?.stop()
    activeServer = null
  }

  private fun executeRequest(
    port: Int,
    method: String,
    path: String,
    headers: Map<String, String> = emptyMap(),
    trustAll: Boolean = true,
  ): TestHttpResponse {
    val sslContext = if (trustAll) {
      val trustingTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
      }
      val ctx = SSLContext.getInstance("TLS")
      ctx.init(null, arrayOf(trustingTrustManager), SecureRandom())
      ctx
    } else {
      SSLContext.getDefault()
    }

    val socket = sslContext.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
    socket.soTimeout = 5000
    socket.startHandshake()

    val request = buildString {
      append("$method $path HTTP/1.1\r\n")
      append("Host: 127.0.0.1:$port\r\n")
      append("Connection: close\r\n")
      for ((k, v) in headers) {
        append("$k: $v\r\n")
      }
      append("\r\n")
    }
    socket.outputStream.write(request.encodeToByteArray())
    socket.outputStream.flush()

    val input = socket.inputStream
    val (status, respHeaders) = readStatusAndHeaders(input)

    val body = if (method == "HEAD") {
      ByteArray(0)
    } else {
      readBody(input, respHeaders)
    }

    socket.close()
    return TestHttpResponse(status, respHeaders, body)
  }

  private fun readLine(input: InputStream): String? {
    val bytes = ByteArrayOutputStream()
    while (true) {
      val b = input.read()
      if (b == -1) {
        return if (bytes.size() > 0) bytes.toByteArray().decodeToString() else null
      }
      if (b == '\n'.code) {
        val str = bytes.toByteArray().decodeToString()
        return if (str.endsWith("\r")) str.dropLast(1) else str
      }
      bytes.write(b)
    }
  }

  private fun readStatusAndHeaders(input: InputStream): Pair<Int, Map<String, String>> {
    val statusLine = readLine(input) ?: error("Empty HTTP response")
    val parts = statusLine.split(" ")
    if (parts.size < 2) error("Malformed status line: $statusLine")
    val status = parts[1].toInt()

    val headers = mutableMapOf<String, String>()
    while (true) {
      val line = readLine(input) ?: break
      if (line.isEmpty()) break
      val idx = line.indexOf(':')
      if (idx > 0) {
        val key = line.substring(0, idx).trim().lowercase()
        val value = line.substring(idx + 1).trim()
        headers[key] = value
      }
    }
    return Pair(status, headers)
  }

  private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
    val isChunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
    if (isChunked) {
      val out = ByteArrayOutputStream()
      while (true) {
        val line = readLine(input) ?: break
        if (line.isEmpty()) continue
        val length = line.trim().toInt(16)
        if (length == 0) {
          readLine(input) // consume trailing empty line
          break
        }
        val chunk = ByteArray(length)
        var read = 0
        while (read < length) {
          val n = input.read(chunk, read, length - read)
          if (n == -1) break
          read += n
        }
        out.write(chunk, 0, read)
        readLine(input) // consume CRLF after chunk
      }
      return out.toByteArray()
    }

    val contentLength = headers["content-length"]?.toIntOrNull()
    if (contentLength != null) {
      val body = ByteArray(contentLength)
      var read = 0
      while (read < contentLength) {
        val n = input.read(body, read, contentLength - read)
        if (n == -1) break
        read += n
      }
      return body
    }

    return input.readBytes()
  }

  @Test
  fun testTextPayloadLandingPageContainsEscapedText(): Unit = runBlocking(Dispatchers.IO) {
    val coroutines = CoroutinesImpl()
    val fileManager = FakeFileManager()
    val tls = LanTlsListener()
    val server = LanHttpShareServer(coroutines, fileManager, tls)
    activeServer = server

    val payload = QrSharePayload.Text("Hello <World> & \"Friends\"!\nLine 2")
    val waitingToken = "T_TEXT"
    val bound = server.start(payload, waitingToken, "127.0.0.1", 0)

    val resp = executeRequest(bound.port, "GET", "/s/$waitingToken")
    assertEquals(200, resp.status)
    assertEquals("text/html; charset=utf-8", resp.headers["content-type"])
    assertEquals("no-store", resp.headers["cache-control"])
    assertEquals("nosniff", resp.headers["x-content-type-options"])
    assertEquals("close", resp.headers["connection"])

    assertTrue(resp.text.contains("Hello &lt;World&gt; &amp; &quot;Friends&quot;!\nLine 2"))
    assertTrue(resp.text.contains("<textarea id=\"shared-text\" readonly>"))
    assertTrue(resp.text.contains("id=\"copy-btn\""))
  }

  @Test
  fun testFilesLandingAndFileDownload(): Unit = runBlocking(Dispatchers.IO) {
    val file0Bytes = "First file test content 12345".encodeToByteArray()
    val platformFile0 = createTestPlatformFile("first.txt", file0Bytes)

    val file1Bytes = "Second file bytes here".encodeToByteArray()
    val platformFile1 = createTestPlatformFile("second.jpg", file1Bytes)

    val fileManager = FakeFileManager(
      mapOf(
        platformFile0 to file0Bytes,
        platformFile1 to file1Bytes,
      )
    )
    val coroutines = CoroutinesImpl()
    val tls = LanTlsListener()
    val server = LanHttpShareServer(coroutines, fileManager, tls)
    activeServer = server

    val sharedFiles = listOf(
      SharedFile(platformFile0, "first.txt", "text/plain", file0Bytes.size.toLong()),
      SharedFile(platformFile1, "second.jpg", "image/jpeg", file1Bytes.size.toLong()),
    )
    val payload = QrSharePayload.Files(sharedFiles)
    val waitingToken = "T_FILES"
    val bound = server.start(payload, waitingToken, "127.0.0.1", 0)

    // GET landing
    val landingResp = executeRequest(bound.port, "GET", "/s/$waitingToken")
    assertEquals(200, landingResp.status)
    assertEquals("text/html; charset=utf-8", landingResp.headers["content-type"])
    assertTrue(landingResp.text.contains("first.txt"))
    assertTrue(landingResp.text.contains("second.jpg"))
    assertTrue(landingResp.text.contains("/s/$waitingToken/file/0"))
    assertTrue(landingResp.text.contains("/s/$waitingToken/file/1"))
    assertTrue(landingResp.text.contains("<title>Klardrop</title>"))
    assertTrue(landingResp.text.contains("class=\"wordmark\">Klardrop</div>"))
    assertTrue(landingResp.text.contains("<svg"))
    assertTrue(landingResp.text.contains(LANDING_BG))
    assertTrue(landingResp.text.contains(LANDING_TEXT))
    assertTrue(landingResp.text.contains(LANDING_ACCENT))
    assertTrue(landingResp.text.contains("color-scheme: dark") || landingResp.text.contains("content=\"dark\""))
    assertFalse(landingResp.text.contains("light dark"))
    assertFalse(
      landingResp.text.contains("download>"),
      "bare download attribute sends Chrome Android through a TLS path that never GETs the file",
    )
    val file0Href = Regex("""href="(/s/$waitingToken/file/0)"""").find(landingResp.text)?.groupValues?.get(1)
    assertEquals("/s/$waitingToken/file/0", file0Href)

    // GET file 0
    val file0Resp = executeRequest(bound.port, "GET", "/s/$waitingToken/file/0")
    assertEquals(200, file0Resp.status)
    assertEquals("text/plain", file0Resp.headers["content-type"])
    assertEquals(file0Bytes.size.toString(), file0Resp.headers["content-length"])
    assertEquals("none", file0Resp.headers["accept-ranges"])
    assertEquals("no-store", file0Resp.headers["cache-control"])
    assertEquals("nosniff", file0Resp.headers["x-content-type-options"])
    assertNotNull(file0Resp.headers["content-disposition"])
    assertTrue(file0Resp.headers["content-disposition"]!!.contains("filename=\"first.txt\""))
    assertTrue(file0Resp.headers["content-disposition"]!!.contains("filename*=UTF-8''first.txt"))
    assertEquals(file0Bytes.decodeToString(), file0Resp.body.decodeToString())

    // GET file 1
    val file1Resp = executeRequest(bound.port, "GET", "/s/$waitingToken/file/1")
    assertEquals(200, file1Resp.status)
    assertEquals("image/jpeg", file1Resp.headers["content-type"])
    assertEquals(file1Bytes.size.toString(), file1Resp.headers["content-length"])
    assertEquals(file1Bytes.decodeToString(), file1Resp.body.decodeToString())
  }

  @Test
  fun testSecondGetOfSameTokenFromSameIpStill200(): Unit = runBlocking(Dispatchers.IO) {
    val coroutines = CoroutinesImpl()
    val fileManager = FakeFileManager()
    val tls = LanTlsListener()
    val server = LanHttpShareServer(coroutines, fileManager, tls)
    activeServer = server

    val payload = QrSharePayload.Text("Test Reload")
    val waitingToken = "T_RELOAD"
    val bound = server.start(payload, waitingToken, "127.0.0.1", 0)

    val firstResp = executeRequest(bound.port, "GET", "/s/$waitingToken")
    assertEquals(200, firstResp.status)

    // Second GET of same token from 127.0.0.1 still 200 (reload)
    val secondResp = executeRequest(bound.port, "GET", "/s/$waitingToken")
    assertEquals(200, secondResp.status)
    assertTrue(secondResp.text.contains("Test Reload"))
  }

  @Test
  fun testGetUnknownTokenReturns404(): Unit = runBlocking(Dispatchers.IO) {
    val coroutines = CoroutinesImpl()
    val fileManager = FakeFileManager()
    val tls = LanTlsListener()
    val server = LanHttpShareServer(coroutines, fileManager, tls)
    activeServer = server

    val payload = QrSharePayload.Text("Secret")
    val waitingToken = "T_REAL"
    val bound = server.start(payload, waitingToken, "127.0.0.1", 0)

    val resp = executeRequest(bound.port, "GET", "/s/notatoken")
    assertEquals(404, resp.status)
    assertEquals("Not found.\n", resp.text)
    assertEquals("text/plain; charset=utf-8", resp.headers["content-type"])
  }

  @Test
  fun testTokenRotatedEventAfterFirstGet(): Unit = runBlocking(Dispatchers.IO) {
    val coroutines = CoroutinesImpl()
    val fileManager = FakeFileManager()
    val tls = LanTlsListener()
    val server = LanHttpShareServer(coroutines, fileManager, tls)
    activeServer = server

    val rotatedEvents = mutableListOf<LanHttpShareServer.DownloadEvent.TokenRotated>()
    val rotatedDeferred = CompletableDeferred<LanHttpShareServer.DownloadEvent.TokenRotated>()
    val collectJob = launch {
      server.events.filterIsInstance<LanHttpShareServer.DownloadEvent.TokenRotated>().collect {
        rotatedEvents.add(it)
        rotatedDeferred.complete(it)
      }
    }

    val payload = QrSharePayload.Text("Rotate Test")
    val waitingToken = "T_ROTATE"
    val bound = server.start(payload, waitingToken, "127.0.0.1", 0)

    // First GET triggers TokenRotated
    val resp1 = executeRequest(bound.port, "GET", "/s/$waitingToken")
    assertEquals(200, resp1.status)

    val event = withTimeout(3000) {
      rotatedDeferred.await()
    }
    assertTrue(event.url.startsWith("https://127.0.0.1:${bound.port}/s/"))
    assertFalse(event.url.contains("T_ROTATE"), "New URL must not contain the old waiting token")

    // Second GET (reload) must NOT emit another TokenRotated
    val resp2 = executeRequest(bound.port, "GET", "/s/$waitingToken")
    assertEquals(200, resp2.status)
    assertEquals(1, rotatedEvents.size, "Reload should not emit TokenRotated")

    collectJob.cancel()
  }

  @Test
  fun testHeadRequestClaimsWaitingToken(): Unit = runBlocking(Dispatchers.IO) {
    val coroutines = CoroutinesImpl()
    val fileManager = FakeFileManager()
    val tls = LanTlsListener()
    val server = LanHttpShareServer(coroutines, fileManager, tls)
    activeServer = server

    val payload = QrSharePayload.Text("Head Claim Test")
    val waitingToken = "T_HEAD"
    val bound = server.start(payload, waitingToken, "127.0.0.1", 0)

    val rotatedEvent = CompletableDeferred<LanHttpShareServer.DownloadEvent.TokenRotated>()
    val landingHitEvent = CompletableDeferred<LanHttpShareServer.DownloadEvent.LandingHit>()
    val collectJob = launch {
      server.events.collect {
        if (it is LanHttpShareServer.DownloadEvent.TokenRotated) rotatedEvent.complete(it)
        if (it is LanHttpShareServer.DownloadEvent.LandingHit) landingHitEvent.complete(it)
      }
    }

    // Camera prefetch HEAD
    val headResp = executeRequest(bound.port, "HEAD", "/s/$waitingToken")
    assertEquals(200, headResp.status)
    assertEquals(0, headResp.body.size, "HEAD must have empty body")
    assertNotNull(headResp.headers["content-length"])

    val rotated = withTimeout(3000) { rotatedEvent.await() }
    assertFalse(rotated.url.contains("T_HEAD"))
    val landingHit = withTimeout(3000) { landingHitEvent.await() }
    assertEquals("/s/$waitingToken", landingHit.path)

    // Token is now claimed by 127.0.0.1. Waiting token is rotated.
    // GET from same IP should succeed (reload)
    val getResp = executeRequest(bound.port, "GET", "/s/$waitingToken")
    assertEquals(200, getResp.status)
    assertTrue(getResp.text.contains("Head Claim Test"))

    collectJob.cancel()
  }

  @Test
  fun testUntrustedTlsClientCannotGet(): Unit = runBlocking(Dispatchers.IO) {
    val coroutines = CoroutinesImpl()
    val fileManager = FakeFileManager()
    val tls = LanTlsListener()
    val server = LanHttpShareServer(coroutines, fileManager, tls)
    activeServer = server

    val payload = QrSharePayload.Text("TLS Test")
    val waitingToken = "T_TLS"
    val bound = server.start(payload, waitingToken, "127.0.0.1", 0)

    // Default SSLContext does not trust the self-signed certificate
    assertFails {
      executeRequest(bound.port, "GET", "/s/$waitingToken", trustAll = false)
    }
  }

  @Test
  fun testChunkedDownloadWhenFileSizeZero(): Unit = runBlocking(Dispatchers.IO) {
    val fileBytes = "Data with unknown file size (chunked streaming)".encodeToByteArray()
    val platformFile = createTestPlatformFile("stream.bin", fileBytes)

    val fileManager = FakeFileManager(mapOf(platformFile to fileBytes))
    val coroutines = CoroutinesImpl()
    val tls = LanTlsListener()
    val server = LanHttpShareServer(coroutines, fileManager, tls)
    activeServer = server

    // fileSize = 0 indicates unknown size -> chunked
    val sharedFiles = listOf(
      SharedFile(platformFile, "stream.bin", "application/octet-stream", fileSize = 0L)
    )
    val payload = QrSharePayload.Files(sharedFiles)
    val waitingToken = "T_CHUNKED"
    val bound = server.start(payload, waitingToken, "127.0.0.1", 0)

    val resp = executeRequest(bound.port, "GET", "/s/$waitingToken/file/0")
    assertEquals(200, resp.status)
    assertEquals("chunked", resp.headers["transfer-encoding"])
    assertNull(resp.headers["content-length"], "Chunked response must not include Content-Length")
    assertEquals(fileBytes.decodeToString(), resp.body.decodeToString())
  }

  @Test
  fun testRangeHeaderIsIgnoredAndFullBodyReturned(): Unit = runBlocking(Dispatchers.IO) {
    val fileBytes = "Full 100-byte content should be returned even when Range header is sent by the client".encodeToByteArray()
    val platformFile = createTestPlatformFile("full.txt", fileBytes)

    val fileManager = FakeFileManager(mapOf(platformFile to fileBytes))
    val coroutines = CoroutinesImpl()
    val tls = LanTlsListener()
    val server = LanHttpShareServer(coroutines, fileManager, tls)
    activeServer = server

    val sharedFiles = listOf(
      SharedFile(platformFile, "full.txt", "text/plain", fileSize = fileBytes.size.toLong())
    )
    val payload = QrSharePayload.Files(sharedFiles)
    val waitingToken = "T_RANGE"
    val bound = server.start(payload, waitingToken, "127.0.0.1", 0)

    val resp = executeRequest(
      bound.port,
      "GET",
      "/s/$waitingToken/file/0",
      headers = mapOf("Range" to "bytes=0-10")
    )
    assertEquals(200, resp.status, "Range must be ignored and status 200 returned")
    assertEquals("none", resp.headers["accept-ranges"])
    assertEquals(fileBytes.decodeToString(), resp.body.decodeToString())
  }

  @Test
  fun testDownloadEventsEmitted(): Unit = runBlocking(Dispatchers.IO) {
    val fileBytes = "Streaming progress event test bytes".encodeToByteArray()
    val platformFile = createTestPlatformFile("events.txt", fileBytes)

    val fileManager = FakeFileManager(mapOf(platformFile to fileBytes))
    val coroutines = CoroutinesImpl()
    val tls = LanTlsListener()
    val server = LanHttpShareServer(coroutines, fileManager, tls)
    activeServer = server

    val sharedFiles = listOf(
      SharedFile(platformFile, "events.txt", "text/plain", fileSize = fileBytes.size.toLong())
    )
    val payload = QrSharePayload.Files(sharedFiles)
    val waitingToken = "T_EV"
    val bound = server.start(payload, waitingToken, "127.0.0.1", 0)

    val events = mutableListOf<LanHttpShareServer.DownloadEvent>()
    val endedDeferred = CompletableDeferred<LanHttpShareServer.DownloadEvent.Ended>()
    val collectJob = launch {
      server.events.collect {
        events.add(it)
        if (it is LanHttpShareServer.DownloadEvent.Ended) {
          endedDeferred.complete(it)
        }
      }
    }

    val resp = executeRequest(bound.port, "GET", "/s/$waitingToken/file/0")
    assertEquals(200, resp.status)

    // Wait for ended event
    val ended = withTimeout(3000) {
      endedDeferred.await()
    }

    assertTrue(events.any { it is LanHttpShareServer.DownloadEvent.TokenRotated })
    assertTrue(events.any { it is LanHttpShareServer.DownloadEvent.Started })
    assertTrue(events.any { it is LanHttpShareServer.DownloadEvent.Progress })
    assertTrue(ended.success)

    collectJob.cancel()
  }

  @Test
  fun testLandingLinkFollowedDownloadsJpegBytes(): Unit = runBlocking(Dispatchers.IO) {
    val jpegBytes = ByteArray(512 * 1024) { i ->
      when (i) {
        0 -> 0xFF.toByte()
        1 -> 0xD8.toByte()
        2 -> 0xFF.toByte()
        else -> (i % 251).toByte()
      }
    }
    val platformFile = createTestPlatformFile("photo.jpg", jpegBytes)
    val fileManager = FakeFileManager(mapOf(platformFile to jpegBytes))
    val coroutines = CoroutinesImpl()
    val tls = LanTlsListener()
    val server = LanHttpShareServer(coroutines, fileManager, tls)
    activeServer = server

    val payload = QrSharePayload.Files(
      listOf(SharedFile(platformFile, "photo.jpg", "image/jpeg", jpegBytes.size.toLong())),
    )
    val waitingToken = "T_JPEG"
    val bound = server.start(payload, waitingToken, "127.0.0.1", 0)

    val started = CompletableDeferred<LanHttpShareServer.DownloadEvent.Started>()
    val ended = CompletableDeferred<LanHttpShareServer.DownloadEvent.Ended>()
    val collectJob = launch {
      server.events.collect {
        if (it is LanHttpShareServer.DownloadEvent.Started) started.complete(it)
        if (it is LanHttpShareServer.DownloadEvent.Ended) ended.complete(it)
      }
    }

    val landing = executeRequest(bound.port, "GET", "/s/$waitingToken")
    assertEquals(200, landing.status)
    val href = Regex("""href="(/s/[^"]+/file/0)"""").find(landing.text)?.groupValues?.get(1)
    assertNotNull(href)

    val fileResp = executeRequest(bound.port, "GET", href)
    assertEquals(200, fileResp.status)
    assertEquals("image/jpeg", fileResp.headers["content-type"])
    assertEquals(jpegBytes.size.toString(), fileResp.headers["content-length"])
    assertTrue(fileResp.headers["content-disposition"]!!.contains("filename=\"photo.jpg\""))
    assertEquals(jpegBytes.size, fileResp.body.size)
    assertEquals(0xFF.toByte(), fileResp.body[0])
    assertEquals(0xD8.toByte(), fileResp.body[1])
    assertEquals(0xFF.toByte(), fileResp.body[2])
    assertTrue(fileResp.body.contentEquals(jpegBytes))

    val startedEvent = withTimeout(3000) { started.await() }
    val endedEvent = withTimeout(3000) { ended.await() }
    assertEquals("photo.jpg", startedEvent.fileName)
    assertEquals(jpegBytes.size.toLong(), startedEvent.totalBytes)
    assertTrue(endedEvent.success)

    collectJob.cancel()
  }
}
