package com.carlom.klardrop.control

import com.carlom.klardrop.DiscoveryController
import com.carlom.klardrop.OutgoingTransfer
import com.carlom.klardrop.TRANSFER_KIND_FILE
import com.carlom.klardrop.TRANSFER_KIND_TEXT
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.common.communication.MessengerSendProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * `POST /share` and `GET /transfers`: the correlated-submission contract. Every assertion here is
 * about what a client can observe — a status code and a response body — because that is the whole
 * surface a CLI has to work with.
 */
class ControlPlaneShareTest {

  private lateinit var app: Klardrop
  private lateinit var controller: DiscoveryController
  private lateinit var dataDir: File
  private lateinit var payload: File
  private var scope = CoroutineScope(Dispatchers.Unconfined)

  @BeforeTest
  fun setUp(): Unit = runBlocking {
    dataDir = File.createTempFile("klardrop-share-test", "").apply { delete(); mkdirs() }
    System.setProperty("klardrop.data.dir", dataDir.absolutePath)
    val appInfo = ApplicationInfo(
      enableKlardropServer = false,
      enableNearbyServer = false,
      disablePersistence = true,
      // The clipboard share path reads the real clipboard unless this is set.
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
    ControlPlane.resetShareRegistryForTesting()
    scope = CoroutineScope(Dispatchers.Unconfined)
    payload = File(dataDir, "share-payload.txt").apply { writeText("hello share") }
  }

  @AfterTest
  fun tearDown(): Unit = runBlocking {
    ControlPlane.stop()
    ControlPlane.resetShareRegistryForTesting()
    System.clearProperty("klardrop.data.dir")
    dataDir.deleteRecursively()
  }

  private suspend fun post(path: String, requestBody: String): HttpResponse =
    ControlPlane.dispatchForTesting(HttpRequest("POST", path, "", requestBody))

  private suspend fun get(path: String): HttpResponse =
    ControlPlane.dispatchForTesting(
      HttpRequest("GET", path.substringBefore('?'), path.substringAfter('?', ""), ""),
    )

  /**
   * Creates a FIFO, returning false when the platform has no `mkfifo` (the caller then skips).
   * A FIFO stands in for "a path that blocks when opened", which is what makes the ordering
   * assertion below observable at runtime instead of by code reading.
   */
  private fun mkfifo(path: String): Boolean = runCatching {
    ProcessBuilder("mkfifo", path).redirectErrorStream(true).start().waitFor() == 0
  }.getOrDefault(false)

  private fun body(response: HttpResponse): JsonObject =
    Json.parseToJsonElement(response.body).jsonObject

  private fun error(response: HttpResponse): String =
    body(response)["error"]!!.jsonPrimitive.contentOrNull.orEmpty()

  /** Polls `GET /transfers?id=` until every item reports [expected]; fails the test otherwise. */
  private suspend fun awaitItemStatus(requestId: String, expected: String): JsonObject {
    var last: JsonObject? = null
    repeat(100) {
      val response = get("/transfers?id=$requestId")
      assertEquals(200, response.status, response.body)
      val request = body(response)["request"]!!.jsonObject
      last = request
      val statuses = request["items"]!!.jsonArray.map {
        it.jsonObject["status"]!!.jsonPrimitive.content
      }
      if (statuses.all { it == expected }) return request
      delay(20)
    }
    throw AssertionError("items never reached '$expected', last body: $last")
  }

  @Test
  fun shareRefusesMixedPayloadKinds() = runBlocking {
    val textAndClipboard = post("/share", """{"deviceId":"dev-a","text":"hi","clipboard":true}""")
    assertEquals(400, textAndClipboard.status)
    assertTrue(error(textAndClipboard).contains("exactly one"), textAndClipboard.body)

    val filesAndText = post(
      "/share",
      """{"deviceId":"dev-a","paths":["${payload.absolutePath}"],"text":"hi"}""",
    )
    assertEquals(400, filesAndText.status)
    assertTrue(error(filesAndText).contains("exactly one"), filesAndText.body)
  }

  @Test
  fun shareRefusesAMissingPayloadKind() = runBlocking {
    val response = post("/share", """{"deviceId":"dev-a"}""")
    assertEquals(400, response.status)
    assertTrue(error(response).contains("exactly one"), response.body)
  }

  @Test
  fun shareRequiresADeviceId() = runBlocking {
    val response = post("/share", """{"text":"hi"}""")
    assertEquals(400, response.status)
    assertTrue(error(response).contains("deviceId"), response.body)
  }

  @Test
  fun shareRefusesAnEmptyClipboard() = runBlocking {
    val response = post("/share", """{"deviceId":"dev-a","clipboard":true}""")
    assertEquals(400, response.status)
    assertEquals("clipboard is empty", error(response))
  }

  @Test
  fun shareRefusesAnEmptyPathsArray() = runBlocking {
    val response = post("/share", """{"deviceId":"dev-a","paths":[]}""")
    assertEquals(400, response.status)
    assertTrue(error(response).contains("paths"), response.body)
  }

  @Test
  fun shareAnswersWithTheDocumentedShape() = runBlocking {
    val response = post("/share", """{"deviceId":"dev-shape","text":"hello"}""")
    assertEquals(200, response.status)
    val json = body(response)
    assertEquals(
      listOf("ok", "action", "requestId", "deviceId", "kind", "status", "items"),
      json.keys.toList(),
    )
    assertEquals("share", json["action"]!!.jsonPrimitive.content)
    assertEquals("dev-shape", json["deviceId"]!!.jsonPrimitive.content)
    assertEquals("text", json["kind"]!!.jsonPrimitive.content)
    assertEquals("queued", json["status"]!!.jsonPrimitive.content)
    val requestId = json["requestId"]!!.jsonPrimitive.content
    assertTrue(requestId.startsWith("req-"), requestId)
    assertTrue(requestId.removePrefix("req-").all { it in "0123456789abcdef" }, requestId)

    val item = json["items"]!!.jsonArray.single().jsonObject
    assertEquals(
      listOf("transferId", "path", "fileName", "totalSize", "transferredSize", "status", "error"),
      item.keys.toList(),
    )
    assertTrue(item["transferId"]!!.jsonPrimitive.content.isNotEmpty())
    assertEquals(JsonNull, item["path"])
    assertEquals(JsonNull, item["fileName"])
    assertEquals(0L, item["totalSize"]!!.jsonPrimitive.long)
    assertEquals(0L, item["transferredSize"]!!.jsonPrimitive.long)
    assertEquals("queued", item["status"]!!.jsonPrimitive.content)
    assertEquals(JsonNull, item["error"])
  }

  @Test
  fun transfersLooksUpASubmittedRequestById() = runBlocking {
    val submitted = post("/share", """{"deviceId":"dev-lookup","paths":["${payload.absolutePath}"]}""")
    assertEquals(200, submitted.status)
    assertEquals("files", body(submitted)["kind"]!!.jsonPrimitive.content)
    val requestId = body(submitted)["requestId"]!!.jsonPrimitive.content

    val looked = get("/transfers?id=$requestId")
    assertEquals(200, looked.status)
    val request = body(looked)["request"]!!.jsonObject
    assertEquals(requestId, request["requestId"]!!.jsonPrimitive.content)
    assertEquals(
      listOf("requestId", "deviceId", "kind", "status", "createdAt", "updatedAt", "items"),
      request.keys.toList(),
    )
    assertEquals("dev-lookup", request["deviceId"]!!.jsonPrimitive.content)
    val item = request["items"]!!.jsonArray.single().jsonObject
    assertEquals(payload.absolutePath, item["path"]!!.jsonPrimitive.content)
    assertEquals("share-payload.txt", item["fileName"]!!.jsonPrimitive.content)
    assertEquals(payload.length(), item["totalSize"]!!.jsonPrimitive.long)

    val listed = get("/transfers")
    assertEquals(200, listed.status)
    val ids = body(listed)["requests"]!!.jsonArray.map {
      it.jsonObject["requestId"]!!.jsonPrimitive.content
    }
    assertTrue(requestId in ids, listed.body)
  }

  @Test
  fun transfersRejectsAnUnknownRequestId() = runBlocking {
    val response = get("/transfers?id=req-0000000000000000")
    assertEquals(404, response.status)
    assertEquals("unknown request id", error(response))
    assertNull(body(response)["request"])
  }

  @Test
  fun anOutgoingFileTransferWalksTheItemThroughItsOutcomes() = runBlocking {
    val record = ShareRegistry.register(
      deviceId = "dev-progress",
      kind = ShareKind.FILES,
      items = listOf(ShareItem("tx-1", payload.absolutePath, "share-payload.txt", 16L)),
    )
    ShareRegistry.bind("tx-1", record.requestId)

    // replay = 1: the tracking collector attaches asynchronously, and a value emitted before it
    // subscribes would otherwise be dropped by the replay-less progress flow.
    val sendFlow = MutableSharedFlow<MessengerSendProgress>(replay = 1, extraBufferCapacity = 16)
    ControlPlane.trackOutgoingSend(
      id = "tx-1",
      deviceId = "dev-progress",
      fileName = "share-payload.txt",
      totalSize = 16L,
      flow = sendFlow,
      scope = scope,
      kind = TRANSFER_KIND_FILE,
    )

    // An in-flight file transfer is still what /state's transfers[] reports.
    val inFlight = body(get("/state"))["transfers"]!!.jsonArray
    assertEquals(1, inFlight.size, inFlight.toString())
    assertEquals("tx-1", inFlight[0].jsonObject["id"]!!.jsonPrimitive.content)

    sendFlow.emit(MessengerSendProgress.AwaitingRecipient)
    val awaiting = awaitItemStatus(record.requestId, "awaiting")
    // A non-terminal item is still queued at the request level.
    assertEquals("queued", awaiting["status"]!!.jsonPrimitive.content)

    sendFlow.emit(
      MessengerSendProgress.InProgress(percentage = 50, bytesTransferred = 8L, totalBytes = 16L),
    )
    val transferring = awaitItemStatus(record.requestId, "transferring")
    val transferred = transferring["items"]!!.jsonArray[0].jsonObject
    assertEquals(8L, transferred["transferredSize"]!!.jsonPrimitive.long)

    sendFlow.emit(MessengerSendProgress.Completed)
    val completed = awaitItemStatus(record.requestId, "completed")
    assertEquals("completed", completed["status"]!!.jsonPrimitive.content)

    // A finished transfer leaves /state's transfers[] as it found it.
    assertTrue(body(get("/state"))["transfers"]!!.jsonArray.isEmpty())
  }

  @Test
  fun aDeclineAndAFailureAreDifferentOutcomes() = runBlocking {
    val declined = ShareRegistry.register(
      "dev-outcomes",
      ShareKind.FILES,
      listOf(ShareItem("tx-declined", payload.absolutePath, "share-payload.txt", 16L)),
    )
    ShareRegistry.bind("tx-declined", declined.requestId)
    val failed = ShareRegistry.register(
      "dev-outcomes",
      ShareKind.FILES,
      listOf(ShareItem("tx-failed", payload.absolutePath, "share-payload.txt", 16L)),
    )
    ShareRegistry.bind("tx-failed", failed.requestId)

    val declinedFlow = MutableSharedFlow<MessengerSendProgress>(replay = 1, extraBufferCapacity = 16)
    val failedFlow = MutableSharedFlow<MessengerSendProgress>(replay = 1, extraBufferCapacity = 16)
    ControlPlane.trackOutgoingSend("tx-declined", "dev-outcomes", "share-payload.txt", 16L, declinedFlow, scope)
    ControlPlane.trackOutgoingSend("tx-failed", "dev-outcomes", "share-payload.txt", 16L, failedFlow, scope)

    declinedFlow.emit(
      MessengerSendProgress.Error("Recipient declined the transfer", reason = "declined"),
    )
    failedFlow.emit(MessengerSendProgress.Error("connection reset", reason = null))

    val declinedRequest = awaitItemStatus(declined.requestId, "declined")
    assertEquals("declined", declinedRequest["status"]!!.jsonPrimitive.content)
    val declinedItem = declinedRequest["items"]!!.jsonArray[0].jsonObject
    assertTrue(
      declinedItem["error"]!!.jsonPrimitive.content.startsWith("Recipient declined the transfer"),
      declinedItem.toString(),
    )

    val failedRequest = awaitItemStatus(failed.requestId, "failed")
    assertEquals("failed", failedRequest["status"]!!.jsonPrimitive.content)
    val failedItem = failedRequest["items"]!!.jsonArray[0].jsonObject
    assertEquals("connection reset", failedItem["error"]!!.jsonPrimitive.content)
  }

  @Test
  fun aRequestWithOneUnsendablePathReportsThatItemAsFailed() = runBlocking {
    val missing = File(dataDir, "does-not-exist.bin").absolutePath
    val response = post(
      "/share",
      """{"deviceId":"dev-partial","paths":["${payload.absolutePath}","$missing"]}""",
    )
    assertEquals(200, response.status)
    val json = body(response)
    // One item failed locally, so the request as a whole must not read as queued.
    assertEquals("failed", json["status"]!!.jsonPrimitive.content)
    val items = json["items"]!!.jsonArray
    assertEquals(2, items.size)

    val sendable = items[0].jsonObject
    assertEquals("queued", sendable["status"]!!.jsonPrimitive.content)
    assertTrue(sendable["transferId"]!!.jsonPrimitive.content.isNotEmpty())
    assertNull(sendable["error"]!!.jsonPrimitive.contentOrNull)

    val unsendable = items[1].jsonObject
    assertEquals("failed", unsendable["status"]!!.jsonPrimitive.content)
    assertNull(unsendable["transferId"]!!.jsonPrimitive.contentOrNull)
    assertTrue(unsendable["error"]!!.jsonPrimitive.content.contains(missing), unsendable.toString())
  }

  @Test
  fun anEvictedRequestIsUnknownRatherThanSynthesized() = runBlocking {
    val first = ShareRegistry.register(
      "dev-eviction",
      ShareKind.FILES,
      listOf(ShareItem("tx-first", payload.absolutePath, "share-payload.txt", 1L)),
    )
    repeat(ShareRegistry.MAX_REQUESTS) {
      ShareRegistry.register(
        "dev-eviction",
        ShareKind.FILES,
        listOf(ShareItem("tx-$it", payload.absolutePath, "share-payload.txt", 1L)),
      )
    }

    val evicted = get("/transfers?id=${first.requestId}")
    assertEquals(404, evicted.status)
    assertEquals("unknown request id", error(evicted))

    val retained = ShareRegistry.list()
    assertEquals(ShareRegistry.MAX_REQUESTS, retained.size)
    assertEquals(200, get("/transfers?id=${retained.first().requestId}").status)
  }

  @Test
  fun aRequestOlderThanTheRetentionWindowIsUnknown() = runBlocking {
    var now = 1_000_000L
    ShareRegistry.setNowProviderForTesting { now }
    try {
      val record = ShareRegistry.register(
        "dev-ttl",
        ShareKind.FILES,
        listOf(ShareItem("tx-ttl", payload.absolutePath, "share-payload.txt", 1L)),
      )
      assertEquals(200, get("/transfers?id=${record.requestId}").status)

      now += ShareRegistry.TTL_MILLIS + 1L
      val expired = get("/transfers?id=${record.requestId}")
      assertEquals(404, expired.status)
      assertEquals("unknown request id", error(expired))
    } finally {
      ShareRegistry.setNowProviderForTesting(null)
    }
  }

  @Test
  fun aTextTransferNeverReachesStateTransfers() = runBlocking {
    val record = ShareRegistry.register(
      "dev-text",
      ShareKind.TEXT,
      listOf(ShareItem("tx-text", null, null, 0L)),
    )
    ShareRegistry.bind("tx-text", record.requestId)

    val outgoing = MutableSharedFlow<OutgoingTransfer>(extraBufferCapacity = 8)
    ControlPlane.bindOutgoingTransfers(outgoing)
    while (outgoing.subscriptionCount.value == 0) {
      delay(20)
    }
    val before = body(get("/state"))["transfers"]!!.jsonArray

    val textFlow = MutableSharedFlow<MessengerSendProgress>(replay = 1, extraBufferCapacity = 16)
    outgoing.emit(
      OutgoingTransfer(
        id = "tx-text",
        deviceId = "dev-text",
        fileName = "hello",
        totalSize = 0L,
        flow = textFlow,
        kind = TRANSFER_KIND_TEXT,
      ),
    )
    textFlow.emit(MessengerSendProgress.InProgress(percentage = 10, bytesTransferred = 1L, totalBytes = 1L))

    // The registry proves the collector really ran, so an empty /state is a real observation.
    awaitItemStatus(record.requestId, "transferring")
    assertEquals(before, body(get("/state"))["transfers"]!!.jsonArray)

    textFlow.emit(MessengerSendProgress.Completed)
    val completed = awaitItemStatus(record.requestId, "completed")
    assertEquals("completed", completed["status"]!!.jsonPrimitive.content)
    assertEquals(before, body(get("/state"))["transfers"]!!.jsonArray)
  }

  @Test
  fun theShareRoutesAnswerNotReadyUntilAnEngineIsBound() = runBlocking {
    // A host that publishes its control file before the engine is attached must say so, rather
    // than answering an empty success (which reads as "nothing in flight") or a 500.
    ControlPlane.stop()
    val share = post("/share", """{"deviceId":"dev-x","text":"hello"}""")
    assertEquals(503, share.status, share.body)
    assertTrue(error(share).contains("not ready"), share.body)

    val transfers = get("/transfers")
    assertEquals(503, transfers.status, transfers.body)
    assertTrue(error(transfers).contains("not ready"), transfers.body)
  }

  @Test
  fun anOversizedPathListIsRefusedBeforeAnythingIsTouchedOnDisk() = runBlocking {
    // The registry caps the number of REQUESTS, not the items inside one, so the cap has to be
    // enforced here or a single call defeats the whole retention policy.
    //
    // The 65 paths are FIFOs on purpose. Opening one blocks until a writer appears, so this test
    // fails by *hanging* — not by a subtly different assertion — if the cap ever moves below
    // `prepareOutgoingFiles`. A file-based version of this test would pass either way.
    val fifoDir = File(dataDir, "fifos").apply { mkdirs() }
    val fifos = (0 until 65).map { File(fifoDir, "pipe-$it").absolutePath }
    val created = fifos.all { mkfifo(it) }
    assumeTrue("mkfifo is unavailable on this platform", created)

    val paths = fifos.joinToString(",") { "\"$it\"" }
    val response = post("/share", """{"deviceId":"dev-many","paths":[$paths]}""")
    assertEquals(400, response.status, response.body)
    assertTrue(error(response).contains("at most 64"), response.body)

    val listing = get("/transfers")
    assertEquals(200, listing.status, listing.body)
    assertEquals(0, body(listing)["requests"]!!.jsonArray.size, "nothing may have been submitted")
  }

  @Test
  fun aBlankTextPayloadIsRefusedRatherThanSentAsAnEmptyMessage() = runBlocking {
    val response = post("/share", """{"deviceId":"dev-x","text":"   "}""")
    assertEquals(400, response.status, response.body)
    assertEquals("text is empty", error(response))
  }

  @Test
  fun anEvictedRequestIsAbsentFromTheListingNotJustFromALookup() = runBlocking {
    val first = ShareRegistry.register(
      "dev-eviction",
      ShareKind.FILES,
      listOf(ShareItem("tx-first", payload.absolutePath, "share-payload.txt", 1L)),
    )
    repeat(ShareRegistry.MAX_REQUESTS) {
      ShareRegistry.register(
        "dev-eviction",
        ShareKind.FILES,
        listOf(ShareItem("tx-$it", payload.absolutePath, "share-payload.txt", 1L)),
      )
    }

    assertEquals(404, get("/transfers?id=${first.requestId}").status)
    val listed = body(get("/transfers"))["requests"]!!.jsonArray.map { it.jsonObject["requestId"]!!.jsonPrimitive.content }
    assertFalse(
      listed.contains(first.requestId),
      "an evicted request must also be gone from the listing, not merely 404 on lookup",
    )
    assertEquals(ShareRegistry.MAX_REQUESTS, listed.size)
  }

  @Test
  fun aStoppedPlaneForgetsEveryRequestItWasTracking() = runBlocking {
    val submitted = post("/share", """{"deviceId":"dev-x","text":"hello"}""")
    assertEquals(200, submitted.status, submitted.body)
    val requestId = body(submitted)["requestId"]!!.jsonPrimitive.content
    assertNotNull(ShareRegistry.get(requestId), "the request is tracked before the stop")

    // An in-process stop/start is a fresh daemon for every client that can see the control file,
    // so nothing about the old request may still be readable. Only `stop()` can satisfy this.
    ControlPlane.stop()
    assertNull(ShareRegistry.get(requestId))
  }
}
