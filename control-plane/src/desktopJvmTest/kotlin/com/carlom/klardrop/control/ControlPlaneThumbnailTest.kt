package com.carlom.klardrop.control

import com.carlom.klardrop.DiscoveryController
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.common.persistence.FileTransferStatus
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers GET /thumbnail's validation paths only (unknown id, missing id, non-video message) —
 * none of these require ffmpeg to be installed on the test machine. The ffmpeg extraction path
 * itself is exercised manually via scripts/klardrop-ctl (see .grok/skills/klardrop-*-control).
 */
class ControlPlaneThumbnailTest {

  private val token = "test-auth-token"
  private var server: LoopbackHttpServer? = null
  private var port: Int = 0
  private lateinit var app: Klardrop
  private lateinit var controller: DiscoveryController
  private lateinit var repo: MessageRepository
  private lateinit var dataDir: File

  @BeforeTest
  fun setUp() = runBlocking {
    val http = LoopbackHttpServer(
      host = "127.0.0.1",
      port = 0,
      authToken = token,
      dispatcher = Dispatchers.IO,
      handle = ControlPlane::dispatchForTesting,
    )
    server = http
    http.start()
    port = http.boundPort

    dataDir = File.createTempFile("klardrop-debugcontrol-thumb-test", "").apply { delete(); mkdirs() }
    System.setProperty("klardrop.data.dir", dataDir.absolutePath)

    val appInfo = ApplicationInfo(
      enableKlardropServer = false,
      enableNearbyServer = false,
      disablePersistence = true,
      disableSystemClipboard = true,
    )
    app = Klardrop(
      applicationInfo = appInfo,
      internalPlatformDependency = InternalPlatformDependencies(appInfo),
    )
    io.github.vinceglb.filekit.FileKit.init("klardrop")
    app.init()
    controller = DiscoveryController(app.commonComponent)
    ControlPlane.bind(controller, app)
    repo = app.commonComponent.messageRepository()
  }

  @AfterTest
  fun tearDown() {
    server?.stop()
    server = null
    ControlPlane.stop()
    System.clearProperty("klardrop.data.dir")
    dataDir.deleteRecursively()
  }

  private fun get(path: String): Pair<Int, String> {
    val conn = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.setRequestProperty("Authorization", "Bearer $token")
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val text = stream?.bufferedReader()?.readText().orEmpty()
    return code to text
  }

  @Test
  fun thumbnailMissingIdReturns400() = runBlocking {
    val (code, _) = get("/thumbnail")
    assertEquals(400, code)
  }

  @Test
  fun thumbnailUnknownIdReturns404() = runBlocking {
    val (code, _) = get("/thumbnail?id=999999")
    assertEquals(404, code)
  }

  @Test
  fun thumbnailNonVideoMessageReturns400() = runBlocking {
    val transferId = repo.insertFileTransfer(
      fileName = "photo.png",
      filePath = "/tmp/does-not-matter.png",
      totalSize = 100L,
      status = FileTransferStatus.COMPLETED,
      mimeType = "image/png",
    )
    val messageId = repo.insertMessage(
      remoteDeviceId = "dev-thumb",
      content = "photo.png",
      isSender = true,
      messageType = MessageType.FILE,
      fileTransferId = transferId,
      mimeType = "image/png",
    )
    val (code, resp) = get("/thumbnail?id=$messageId")
    assertEquals(400, code)
    assertTrue(resp.contains("no video file"), resp)
  }

  @Test
  fun thumbnailVideoFileMissingOnDiskReturns404() = runBlocking {
    val transferId = repo.insertFileTransfer(
      fileName = "clip.mp4",
      filePath = "/no/such/path/clip.mp4",
      totalSize = 100L,
      status = FileTransferStatus.COMPLETED,
      mimeType = "video/mp4",
    )
    val messageId = repo.insertMessage(
      remoteDeviceId = "dev-thumb",
      content = "clip.mp4",
      isSender = true,
      messageType = MessageType.FILE,
      fileTransferId = transferId,
      mimeType = "video/mp4",
    )
    val (code, _) = get("/thumbnail?id=$messageId")
    assertEquals(404, code)
  }
}
