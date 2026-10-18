package com.carlom.klardrop.control

import com.carlom.klardrop.DiscoveryController
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ControlPlaneSettingsTest {

  private lateinit var dataDir: File
  private lateinit var app: Klardrop
  private lateinit var discovery: DiscoveryController

  @BeforeTest
  fun setUp() = runBlocking {
    dataDir = File.createTempFile("klardrop-control-plane-settings", "").apply { delete(); mkdirs() }
    System.setProperty("klardrop.data.dir", dataDir.absolutePath)
    io.github.vinceglb.filekit.FileKit.init("klardrop")

    val appInfo = ApplicationInfo(
      isDebug = false,
      enableKlardropServer = false,
      enableNearbyServer = false,
      disablePersistence = true,
      disableSystemClipboard = true,
      // Every rename republishes discovery; with BLE on, each republish probes and registers
      // over the shared BlueZ D-Bus connection with blocking calls on the IO pool, which on CI
      // starved the next test's controller. Neither test needs BLE.
      enableBle = false,
      // 0 = ephemeral port, the release desktop default.
      controlPort = 0,
    )
    app = Klardrop(
      applicationInfo = appInfo,
      internalPlatformDependency = InternalPlatformDependencies(appInfo),
    )
    app.init()
    ControlPlane.start(app)
    discovery = DiscoveryController(app.commonComponent)
    ControlPlane.bind(discovery, app)
  }

  @AfterTest
  fun tearDown() {
    ControlPlane.stop()
    if (::discovery.isInitialized) discovery.dispose()
    System.clearProperty("klardrop.data.dir")
    dataDir.deleteRecursively()
  }

  private fun request(
    method: String,
    path: String,
    token: String? = null,
    body: String = "",
  ): Pair<Int, String> {
    val conn = URI("http://127.0.0.1:${ControlPlane.boundPort}$path").toURL().openConnection() as HttpURLConnection
    conn.requestMethod = method
    if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
    if (body.isNotEmpty()) {
      conn.doOutput = true
      conn.setRequestProperty("Content-Type", "application/json")
      conn.outputStream.use { it.write(body.encodeToByteArray()) }
    }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    return code to (stream?.bufferedReader()?.readText().orEmpty())
  }

  @Test
  fun postSettingsMustBeVisibleInNextGetState() {
    val token = ControlPlane.token
    var expected = true
    // 2000 round trips (~2s): without the fix this goes red within a few hundred on desktop JVM.
    for (iteration in 1..2000) {
      val (postCode, postBody) = request(
        "POST",
        "/settings",
        token,
        """{"backgroundDiscoveryEnabled":$expected}""",
      )
      assertEquals(200, postCode, "Iteration $iteration: POST /settings failed: $postBody")

      val (getCode, getBody) = request("GET", "/state", token)
      assertEquals(200, getCode, "Iteration $iteration: GET /state failed: $getBody")

      val json = Json.parseToJsonElement(getBody).jsonObject
      val settings = json["settings"]?.jsonObject
      val actual = settings?.get("backgroundDiscoveryEnabled")?.jsonPrimitive?.boolean
      assertEquals(
        expected,
        actual,
        "Iteration $iteration: expected backgroundDiscoveryEnabled=$expected, but got $actual in GET /state: $getBody",
      )

      expected = !expected
    }
  }

  @Test
  fun postRenameDeviceAndSettingsMustBeVisibleInNextGetState() {
    val token = ControlPlane.token
    val systemName = com.carlom.klardrop.common.CommonPlatformDependencies.getDeviceName()

    for (iteration in 1..2000) {
      val name = "name-$iteration"
      val isRenameDevice = iteration % 2 != 0
      val endpoint = if (isRenameDevice) "/rename-device" else "/settings"
      val (postCode, postBody) = request(
        "POST",
        endpoint,
        token,
        """{"name":"$name"}""",
      )
      assertEquals(200, postCode, "Iteration $iteration: POST $endpoint failed: $postBody")

      val (getCode, getBody) = request("GET", "/state", token)
      assertEquals(200, getCode, "Iteration $iteration: GET /state failed: $getBody")

      val json = Json.parseToJsonElement(getBody).jsonObject
      val self = json["self"]?.jsonObject
      val actualName = self?.get("deviceName")?.jsonPrimitive?.content
      assertEquals(
        name,
        actualName,
        "Iteration $iteration: expected deviceName=$name, but got $actualName in GET /state: $getBody",
      )

      if (iteration % 100 == 0) {
        val blankEndpoint = if ((iteration / 100) % 2 != 0) "/rename-device" else "/settings"
        val (blankPostCode, blankPostBody) = request(
          "POST",
          blankEndpoint,
          token,
          """{"name":""}""",
        )
        assertEquals(200, blankPostCode, "Iteration $iteration: POST $blankEndpoint with blank name failed: $blankPostBody")

        val (blankGetCode, blankGetBody) = request("GET", "/state", token)
        assertEquals(200, blankGetCode, "Iteration $iteration: GET /state after blank name failed: $blankGetBody")

        val blankJson = Json.parseToJsonElement(blankGetBody).jsonObject
        val blankActualName = blankJson["self"]?.jsonObject?.get("deviceName")?.jsonPrimitive?.content
        assertEquals(
          systemName,
          blankActualName,
          "Iteration $iteration: expected systemName=$systemName, but got $blankActualName in GET /state: $blankGetBody",
        )
      }
    }
  }
}
