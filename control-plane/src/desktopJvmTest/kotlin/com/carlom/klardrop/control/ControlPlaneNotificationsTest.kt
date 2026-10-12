package com.carlom.klardrop.control

import com.carlom.klardrop.UiNotification
import com.carlom.klardrop.DiscoveryController
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.DeviceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControlPlaneNotificationsTest {

  private val token = "test-auth-token"
  private var server: LoopbackHttpServer? = null
  private var port: Int = 0
  private lateinit var app: Klardrop
  private lateinit var controller: DiscoveryController
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

    dataDir = File.createTempFile("klardrop-debugcontrol-notif-test", "").apply { delete(); mkdirs() }
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
  fun notificationDismissUnknownIdReturns404() = runBlocking {
    val (code, _) = post("/notification/dismiss", """{"id":999999}""")
    assertEquals(404, code)
  }

  @Test
  fun notificationDismissMissingIdReturns400() = runBlocking {
    val (code, _) = post("/notification/dismiss", "{}")
    assertEquals(400, code)
  }

  @Test
  fun notificationPairUnknownIdReturns404() = runBlocking {
    val (code, _) = post("/notification/pair", """{"id":999999}""")
    assertEquals(404, code)
  }

  @Test
  fun notificationPairMissingIdReturns400() = runBlocking {
    val (code, _) = post("/notification/pair", "{}")
    assertEquals(400, code)
  }

  @Test
  fun peerRevokedTrustNotificationSurfacesDeviceInfoAndDismisses() = runBlocking {
    controller.screenStateFlow.update {
      it.copy(notifications = listOf(UiNotification.PeerRevokedTrust(7, "dev-x", "Phone")))
    }

    val (code, resp) = get("/state")
    assertEquals(200, code)
    val notifications = Json.parseToJsonElement(resp).jsonObject["notifications"]!!.jsonArray
    val n = notifications.first { it.jsonObject["id"]!!.jsonPrimitive.content == "7" }.jsonObject
    assertEquals("dev-x", n["deviceId"]!!.jsonPrimitive.content)
    assertEquals("Phone", n["deviceName"]!!.jsonPrimitive.content)

    val (dismissCode, _) = post("/notification/dismiss", """{"id":7}""")
    assertEquals(200, dismissCode)

    val (_, resp2) = get("/state")
    val notifications2 = Json.parseToJsonElement(resp2).jsonObject["notifications"]!!.jsonArray
    assertFalse(notifications2.any { it.jsonObject["id"]!!.jsonPrimitive.content == "7" })
  }

  @Test
  fun incomingDismissUnknownReceiveIdReturns404() = runBlocking {
    val (code, _) = post("/incoming/dismiss", """{"receiveId":999999}""")
    assertEquals(404, code)
  }

  @Test
  fun incomingOpenUnknownReceiveIdReturns404() = runBlocking {
    val (code, _) = post("/incoming/open", """{"receiveId":999999}""")
    assertEquals(404, code)
  }

  @Test
  fun updateStateContainsCheckerFields() = runBlocking {
    val (code, resp) = get("/state")
    assertEquals(200, code)
    val update = Json.parseToJsonElement(resp).jsonObject["update"]!!.jsonObject
    assertTrue(update.containsKey("currentVersion"))
    assertTrue(update.containsKey("channel"))
    assertTrue(update.containsKey("supported"))
  }

  @Test
  fun incomingCardWithTextAndFileSurfacesNewFieldsAndDismisses() = runBlocking {
    val receiveId = 42
    val device = DeviceInfo(deviceId = "dev-incoming", name = "Their Phone", deviceType = DeviceType.DESKTOP)
    val fileMessage = FileMessage(fileName = "photo.png", fileSize = 12345L, mimeType = "image/png")
    val textMessage = TextMessage(text = "hello there")
    controller.screenStateFlow.update {
      it.copy(
        receivingMessages = it.receivingMessages + (receiveId to ReceiveMessageUpdate(
          device = device,
          messages = listOf(textMessage, fileMessage),
          status = ReceiveMessageStatus.Completed,
        ))
      )
    }

    val (code, resp) = get("/state")
    assertEquals(200, code)
    val incoming = Json.parseToJsonElement(resp).jsonObject["incoming"]!!.jsonArray
      .first { it.jsonObject["receiveId"]!!.jsonPrimitive.content == receiveId.toString() }.jsonObject
    assertEquals(1, incoming["fileCount"]!!.jsonPrimitive.content.toInt())
    assertEquals("photo.png", incoming["fileNames"]!!.jsonArray[0].jsonPrimitive.content)
    assertEquals(12345L, incoming["totalSize"]!!.jsonPrimitive.content.toLong())
    assertEquals("hello there", incoming["text"]!!.jsonPrimitive.content)

    val (dismissCode, _) = post("/incoming/dismiss", """{"receiveId":$receiveId}""")
    assertEquals(200, dismissCode)
    val (_, resp2) = get("/state")
    val incoming2 = Json.parseToJsonElement(resp2).jsonObject["incoming"]!!.jsonArray
    assertFalse(incoming2.any { it.jsonObject["receiveId"]!!.jsonPrimitive.content == receiveId.toString() })
  }

  @Test
  fun pairingDialogDismissClearsErrorDialog() = runBlocking {
    controller.screenStateFlow.update {
      it.copy(
        pairingDialogState = com.carlom.klardrop.PairingDialogState(
          deviceId = "dev-err",
          deviceName = "Errored Device",
          deviceType = "DESKTOP",
          onAccept = {},
          onReject = {},
          isError = true,
          errorMessage = "boom",
        )
      )
    }

    val (code, _) = post("/pairing-dialog/dismiss")
    assertEquals(200, code)

    val (_, resp) = get("/state")
    val pairingDialog = Json.parseToJsonElement(resp).jsonObject["pairingDialog"]
    assertTrue(pairingDialog is kotlinx.serialization.json.JsonNull)
  }

  @Test
  fun reportProblemBlankDescriptionReturns400() = runBlocking {
    val (code, _) = post("/report-problem", """{"description":""}""")
    assertEquals(400, code)
  }

  @Test
  fun reportProblemValidDescriptionReturnsDisabledOutcome() = runBlocking {
    val (code, resp) = post("/report-problem", """{"description":"it broke"}""")
    assertEquals(200, code)
    assertTrue(resp.contains("\"outcome\":\"disabled\""), resp)
  }
}
