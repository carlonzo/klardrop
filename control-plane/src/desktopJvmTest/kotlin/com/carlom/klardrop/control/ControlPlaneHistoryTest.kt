package com.carlom.klardrop.control

import com.carlom.klardrop.DeviceUi
import com.carlom.klardrop.DiscoveryController
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.persistence.FileTransferStatus
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.MessageType
import com.carlom.klardrop.common.persistence.SendStatus
import com.carlom.klardrop.common.utils.DeviceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue


/**
 * How long a test waits for the event-driven collectors to republish before it
 * calls the result a failure. Generous on purpose: these loops are waiting on
 * a coroutine that has to re-query the database and bump a `StateFlow`, and a
 * shared CI runner (or a full `--rerun-tasks` compile competing for cores) can
 * push that past a second. A budget that is too tight turns a slow machine
 * into a red build that says nothing about the code.
 */
private const val POLL_ATTEMPTS = 250
private const val POLL_INTERVAL_MS = 20L
class ControlPlaneHistoryTest {

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

    // Isolate trust storage per test (same override the CLI's --data-dir uses) so this suite
    // never reads/writes the developer's real ~/.klardrop trust store.
    dataDir = File.createTempFile("klardrop-debugcontrol-test", "").apply { delete(); mkdirs() }
    System.setProperty("klardrop.data.dir", dataDir.absolutePath)

    val appInfo = ApplicationInfo(
      enableKlardropServer = false,
      enableNearbyServer = false,
      disablePersistence = true,
      // POST /clipboard exercises the real ClipboardManager -> never touch the developer's
      // actual desktop clipboard from a test.
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

  private fun post(path: String, body: String = ""): Pair<Int, String> {
    val conn = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Authorization", "Bearer $token")
    if (body.isNotEmpty()) {
      conn.doOutput = true
      conn.setRequestProperty("Content-Type", "application/json")
      conn.outputStream.use { it.write(body.encodeToByteArray()) }
    }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val text = stream?.bufferedReader()?.readText().orEmpty()
    return code to text
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
  fun historyPagingWithCursorAndNextBefore() = runBlocking {
    val deviceId = "dev-hist"
    val ids = (1..5).map {
      repo.insertMessage(
        remoteDeviceId = deviceId,
        content = "msg$it",
        isSender = true,
        messageType = MessageType.TEXT,
      )
    }

    val (code, resp) = get("/history?device=$deviceId&limit=2")
    assertEquals(200, code)
    val json = Json.parseToJsonElement(resp).jsonObject
    val messages = json["messages"]!!.jsonArray
    assertEquals(2, messages.size)
    assertEquals(ids[4], messages[0].jsonObject["id"]!!.jsonPrimitive.long)
    assertEquals(ids[3], messages[1].jsonObject["id"]!!.jsonPrimitive.long)
    val nextBefore = json["nextBefore"]!!.jsonPrimitive.longOrNull
    assertEquals(ids[3], nextBefore)

    val (code2, resp2) = get("/history?device=$deviceId&limit=2&before=$nextBefore")
    assertEquals(200, code2)
    val json2 = Json.parseToJsonElement(resp2).jsonObject
    val messages2 = json2["messages"]!!.jsonArray
    assertEquals(2, messages2.size)
    assertEquals(ids[2], messages2[0].jsonObject["id"]!!.jsonPrimitive.long)
    assertEquals(ids[1], messages2[1].jsonObject["id"]!!.jsonPrimitive.long)

    // Last page: 1 remaining message -> not full -> nextBefore null
    val nextBefore2 = json2["nextBefore"]!!.jsonPrimitive.longOrNull
    val (code3, resp3) = get("/history?device=$deviceId&limit=2&before=$nextBefore2")
    val json3 = Json.parseToJsonElement(resp3).jsonObject
    assertEquals(1, json3["messages"]!!.jsonArray.size)
    assertNull(json3["nextBefore"]!!.jsonPrimitive.longOrNull)
  }

  @Test
  fun historyLimitClampsAndRejectsNonNumeric() = runBlocking {
    val deviceId = "dev-clamp"
    repo.insertMessage(deviceId, "hi", isSender = true, messageType = MessageType.TEXT)

    val (badCode, _) = get("/history?device=$deviceId&limit=abc")
    assertEquals(400, badCode)

    // limit=0 clamps to 1
    val (code, resp) = get("/history?device=$deviceId&limit=0")
    assertEquals(200, code)
    assertEquals(1, Json.parseToJsonElement(resp).jsonObject["messages"]!!.jsonArray.size)
  }

  @Test
  fun historyUnknownBeforeReturns400() = runBlocking {
    val (code, _) = get("/history?device=dev-x&before=999999")
    assertEquals(400, code)
  }

  @Test
  fun historyBeforeOfAnotherDeviceReturns400() = runBlocking {
    val otherId = repo.insertMessage("dev-other", "hi", isSender = true, messageType = MessageType.TEXT)
    val (code, _) = get("/history?device=dev-mine&before=$otherId")
    assertEquals(400, code)
  }

  @Test
  fun historyGetDoesNotMarkRead() = runBlocking {
    val deviceId = "dev-unread"
    repo.insertMessage(deviceId, "hi", isSender = false, messageType = MessageType.TEXT)
    get("/history?device=$deviceId")
    assertEquals(1L, repo.getUnreadCountForDevice(deviceId))
  }

  @Test
  fun historyReadMarksRead() = runBlocking {
    val deviceId = "dev-read"
    repo.insertMessage(deviceId, "hi", isSender = false, messageType = MessageType.TEXT)
    val (code, resp) = post("/history/read", """{"deviceId":"$deviceId"}""")
    assertEquals(200, code)
    assertTrue(resp.contains("\"action\":\"history-read\""), resp)
    // markDeviceRead's DB write is fire-and-forget on the controller scope.
    var unread = 1L
    for (i in 0 until POLL_ATTEMPTS) {
      unread = repo.getUnreadCountForDevice(deviceId)
      if (unread == 0L) break
      delay(POLL_INTERVAL_MS)
    }
    assertEquals(0L, unread)
  }

  private fun registerDevice(deviceId: String) {
    controller.screenStateFlow.update {
      it.copy(
        devices = it.devices + DeviceUi(
          deviceId = deviceId,
          deviceName = deviceId,
          deviceType = DeviceType.DESKTOP,
          connectionTypes = emptyList(),
        )
      )
    }
  }

  private fun unreadCountFor(deviceId: String, stateJson: String): Long? =
    Json.parseToJsonElement(stateJson).jsonObject["devices"]!!.jsonArray
      .map { it.jsonObject }
      .firstOrNull { it["deviceId"]!!.jsonPrimitive.content == deviceId }
      ?.get("unreadCount")?.jsonPrimitive?.long

  @Test
  fun unreadCountAppearsInState() = runBlocking {
    val deviceId = "dev-unread-count"
    registerDevice(deviceId)
    repo.insertMessage(deviceId, "one", isSender = false, messageType = MessageType.TEXT)
    repo.insertMessage(deviceId, "two", isSender = false, messageType = MessageType.TEXT)

    var count: Long? = null
    for (i in 0 until POLL_ATTEMPTS) {
      val (code, resp) = get("/state")
      assertEquals(200, code)
      count = unreadCountFor(deviceId, resp)
      if (count == 2L) break
      delay(POLL_INTERVAL_MS)
    }
    assertEquals(2L, count)
  }

  @Test
  fun historyReadDropsUnreadCountToZeroInState() = runBlocking {
    val deviceId = "dev-unread-clear"
    registerDevice(deviceId)
    repo.insertMessage(deviceId, "one", isSender = false, messageType = MessageType.TEXT)

    // Wait for the unread count to show up first, so the drop below isn't a false positive.
    for (i in 0 until POLL_ATTEMPTS) {
      val (_, resp) = get("/state")
      if (unreadCountFor(deviceId, resp) == 1L) break
      delay(POLL_INTERVAL_MS)
    }

    val (code, _) = post("/history/read", """{"deviceId":"$deviceId"}""")
    assertEquals(200, code)
    // /history/read awaits the DB write, but the event-driven unreadCount collector still needs
    // a beat to re-query and republish.
    var count: Long? = 1L
    for (i in 0 until POLL_ATTEMPTS) {
      val (_, resp) = get("/state")
      count = unreadCountFor(deviceId, resp)
      if (count == 0L) break
      delay(POLL_INTERVAL_MS)
    }
    assertEquals(0L, count)
    assertEquals(0L, repo.getUnreadCountForDevice(deviceId))
  }

  @Test
  fun activeChatAcceptsDeviceIdOrNull() = runBlocking {
    val (c1, r1) = post("/active-chat", """{"deviceId":"dev-1"}""")
    assertEquals(200, c1)
    assertTrue(r1.contains("\"action\":\"active-chat\""), r1)

    val (c2, _) = post("/active-chat", """{"deviceId":null}""")
    assertEquals(200, c2)

    val (c3, _) = post("/active-chat")
    assertEquals(200, c3)
  }

  @Test
  fun retryMissingTransferReturns404() = runBlocking {
    val (code, _) = post("/retry", """{"fileTransferId":999999}""")
    assertEquals(404, code)
  }

  @Test
  fun retryMissingFileTransferIdReturns400() = runBlocking {
    val (code, _) = post("/retry", "{}")
    assertEquals(400, code)
  }

  @Test
  fun retryIncomingTransferReturns409() = runBlocking {
    val transferId = repo.insertFileTransfer("f.txt", "/tmp/f.txt", 10, FileTransferStatus.FAILED)
    repo.insertMessage(
      remoteDeviceId = "dev-incoming",
      content = "f.txt",
      isSender = false,
      messageType = MessageType.FILE,
      fileTransferId = transferId,
    )
    val (code, _) = post("/retry", """{"fileTransferId":$transferId}""")
    assertEquals(409, code)
  }

  @Test
  fun retryNotFailedReturns409() = runBlocking {
    val transferId = repo.insertFileTransfer("f.txt", "/tmp/f.txt", 10, FileTransferStatus.COMPLETED)
    repo.insertMessage(
      remoteDeviceId = "dev-ok",
      content = "f.txt",
      isSender = true,
      messageType = MessageType.FILE,
      fileTransferId = transferId,
      sendStatus = SendStatus.SENT,
    )
    val (code, _) = post("/retry", """{"fileTransferId":$transferId}""")
    assertEquals(409, code)
  }

  @Test
  fun retryMissingFileReturns409() = runBlocking {
    val transferId = repo.insertFileTransfer("f.txt", "/no/such/path/f.txt", 10, FileTransferStatus.FAILED)
    repo.insertMessage(
      remoteDeviceId = "dev-missing",
      content = "f.txt",
      isSender = true,
      messageType = MessageType.FILE,
      fileTransferId = transferId,
      sendStatus = SendStatus.FAILED,
    )
    val (code, resp) = post("/retry", """{"fileTransferId":$transferId}""")
    assertEquals(409, code)
    assertTrue(resp.contains("no longer available"), resp)
  }

  @Test
  fun retryHappyPathReachesSendFiles() = runBlocking {
    val tmp = File.createTempFile("klardrop-retry", ".txt").apply { writeText("hello") }
    val transferId = repo.insertFileTransfer(tmp.name, tmp.absolutePath, tmp.length(), FileTransferStatus.FAILED)
    repo.insertMessage(
      remoteDeviceId = "dev-retry",
      content = tmp.name,
      isSender = true,
      messageType = MessageType.FILE,
      fileTransferId = transferId,
      sendStatus = SendStatus.FAILED,
    )
    val (code, resp) = post("/retry", """{"fileTransferId":$transferId}""")
    assertEquals(200, code)
    assertTrue(resp.contains("\"action\":\"retry\""), resp)
    assertTrue(resp.contains("\"deviceId\":\"dev-retry\""), resp)
  }

  @Test
  fun clipboardRejectsEmptyText() = runBlocking {
    val (code, _) = post("/clipboard", """{"text":""}""")
    assertEquals(400, code)
  }

  @Test
  fun clipboardRejectsOversizedText() = runBlocking {
    // Dispatch directly: LoopbackHttpServer's own socket-read buffer caps at 1_000_000 bytes,
    // below the 1 MiB clipboard cap, so a real >1 MiB POST never reaches the handler intact.
    val big = "a".repeat(1_048_577)
    val response = ControlPlane.dispatchForTesting(
      HttpRequest(method = "POST", path = "/clipboard", query = "", body = """{"text":"$big"}""")
    )
    assertEquals(413, response.status)
    assertTrue(response.body.contains("too large"), response.body)
  }

  @Test
  fun clipboardWritesAcceptedText() = runBlocking {
    val (code, resp) = post("/clipboard", """{"text":"hello from debug control"}""")
    assertEquals(200, code)
    assertTrue(resp.contains("\"action\":\"clipboard\""), resp)
  }

  @Test
  fun transferPhaseTransitionsPendingAwaitingProgress() = runBlocking {
    val flow = MutableSharedFlow<MessengerSendProgress>(replay = 1, extraBufferCapacity = 8)
    ControlPlane.trackOutgoingSend(
      id = "tx-phase",
      deviceId = "dev-phase",
      fileName = "f.bin",
      totalSize = 100L,
      flow = flow,
      scope = app.commonComponent.coroutines().appScope,
    )

    val (c1, r1) = get("/state")
    assertEquals(200, c1)
    assertTrue(r1.contains("\"phase\":\"pending\""), r1)

    flow.emit(MessengerSendProgress.AwaitingRecipient)
    delay(50)
    val (c2, r2) = get("/state")
    assertEquals(200, c2)
    assertTrue(r2.contains("\"phase\":\"awaiting\""), r2)

    flow.emit(MessengerSendProgress.InProgress(percentage = 10, bytesTransferred = 10L, totalBytes = 100L))
    delay(50)
    val (c3, r3) = get("/state")
    assertEquals(200, c3)
    assertTrue(r3.contains("\"phase\":\"progress\""), r3)
  }
}

