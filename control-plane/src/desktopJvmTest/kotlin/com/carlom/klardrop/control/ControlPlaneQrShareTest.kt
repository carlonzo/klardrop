package com.carlom.klardrop.control

import com.carlom.klardrop.DiscoveryController
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
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

// Setup mirrors ControlPlaneNotificationsTest.kt: a real LoopbackHttpServer + Klardrop/
// DiscoveryController wired through ControlPlane.bind(), driven over plain HttpURLConnection.
class ControlPlaneQrShareTest {

  private val token = "test-auth-token"
  private var server: LoopbackHttpServer? = null
  private var port: Int = 0
  private lateinit var app: Klardrop
  private lateinit var controller: DiscoveryController
  private lateinit var dataDir: File
  private lateinit var sharedFile: File

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

    dataDir = File.createTempFile("klardrop-debugcontrol-qrshare-test", "").apply { delete(); mkdirs() }
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

    sharedFile = File(dataDir, "share-me.txt").apply { writeText("hello qr share") }
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
  fun qrShareMissingPathsReturns400() = runBlocking {
    val (code, resp) = post("/qr-share", "{}")
    assertEquals(400, code)
    assertTrue(resp.contains("\"ok\":false"), resp)
  }

  @Test
  fun qrShareNonExistentPathReturns400() = runBlocking {
    val missing = File(dataDir, "does-not-exist.bin").absolutePath
    val (code, resp) = post("/qr-share", """{"paths":[${jsonQuoted(missing)}]}""")
    assertEquals(400, code)
    assertTrue(resp.contains("not found"), resp)
  }

  @Test
  fun qrShareDirectoryPathReturns400() = runBlocking {
    val dir = File(dataDir, "a-directory").apply { mkdirs() }
    val (code, resp) = post("/qr-share", """{"paths":[${jsonQuoted(dir.absolutePath)}]}""")
    assertEquals(400, code)
    assertTrue(resp.contains("not found"), resp)
  }

  @Test
  fun qrShareStartReturnsUrlExpiryAndMatrixAndUpdatesState() = runBlocking {
    // No real Wi-Fi/LAN interface in CI/sandboxed test runs, so LanAddressSelector.selectIpv4()
    // can legitimately return null and QrShareSession.start() resolves to Failed("Connect to
    // Wi-Fi...") instead of QrVisible. Assert on whichever of the two contract-shaped outcomes
    // actually happened rather than requiring a real network to pass this test.
    val (code, resp) = post("/qr-share", """{"paths":[${jsonQuoted(sharedFile.absolutePath)}]}""")
    val json = Json.parseToJsonElement(resp).jsonObject

    if (code == 200) {
      assertTrue(json["url"]!!.jsonPrimitive.content.startsWith("https://"), resp)
      assertTrue(json["expiresAt"]!!.jsonPrimitive.content.toLong() > 0L, resp)
      val qr = json["qr"]!!.jsonArray
      assertTrue(qr.isNotEmpty(), resp)
      assertTrue(qr.all { row -> row.jsonPrimitive.content.all { it == '0' || it == '1' } }, resp)

      val (stateCode, stateResp) = get("/state")
      assertEquals(200, stateCode)
      val qrShare = Json.parseToJsonElement(stateResp).jsonObject["qrShare"]!!.jsonObject
      assertEquals(true, qrShare["active"]!!.jsonPrimitive.boolean)
      assertEquals(json["url"]!!.jsonPrimitive.content, qrShare["url"]!!.jsonPrimitive.content)

      val (stopCode, _) = post("/qr-share/stop")
      assertEquals(200, stopCode)
      val (_, stateResp2) = get("/state")
      val qrShare2 = Json.parseToJsonElement(stateResp2).jsonObject["qrShare"]!!.jsonObject
      assertFalse(qrShare2["active"]!!.jsonPrimitive.boolean)
      assertEquals(JsonNull, qrShare2["url"])
    } else {
      assertEquals(400, code)
      assertTrue(resp.contains("Wi-Fi") || resp.contains("hotspot"), resp)
    }
  }

  @Test
  fun qrShareStopWithNoActiveShareIsANoOp() = runBlocking {
    val (code, resp) = post("/qr-share/stop")
    assertEquals(200, code)
    assertTrue(resp.contains("\"ok\":true"), resp)
  }

  private fun jsonQuoted(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
