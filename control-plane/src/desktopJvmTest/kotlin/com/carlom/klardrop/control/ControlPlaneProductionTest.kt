package com.carlom.klardrop.control

import com.carlom.klardrop.DiscoveryController
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The control plane ships in release builds, so this suite exercises it exactly as a packaged
 * app does: `isDebug = false`, no `--debug`, an ephemeral port. The debug-only routes must stay
 * unreachable there while the production ones answer with the published bearer token.
 *
 * The build points XDG_RUNTIME_DIR at an isolated temp dir (see build.gradle.kts), so the
 * control file written here is this test's own.
 */
class ControlPlaneProductionTest {

  private lateinit var dataDir: File
  private lateinit var app: Klardrop

  @BeforeTest
  fun setUp() = runBlocking {
    dataDir = File.createTempFile("klardrop-control-plane-prod", "").apply { delete(); mkdirs() }
    System.setProperty("klardrop.data.dir", dataDir.absolutePath)
    io.github.vinceglb.filekit.FileKit.init("klardrop")

    val appInfo = ApplicationInfo(
      isDebug = false,
      enableKlardropServer = false,
      enableNearbyServer = false,
      disablePersistence = true,
      disableSystemClipboard = true,
      // 0 = ephemeral port, the release desktop default.
      controlPort = 0,
    )
    app = Klardrop(
      applicationInfo = appInfo,
      internalPlatformDependency = InternalPlatformDependencies(appInfo),
    )
    app.init()
    ControlPlane.start(app)
  }

  @AfterTest
  fun tearDown() {
    ControlPlane.stop()
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
  fun controlFileIsPublishedOwnerOnlyWithTheRuntimeToken() {
    val token = ControlPlane.token
    assertTrue(token != null && token.isNotEmpty(), "a control file path means a token is generated")

    val file = File(resolveControlFilePath()!!)
    assertTrue(file.isFile, "control.json must exist in a release build")
    assertEquals(
      "rw-------",
      PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())),
      "the token must be owner-only",
    )
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.parentFile.toPath())))

    val json = Json.parseToJsonElement(file.readText()).jsonObject
    assertEquals(ControlPlane.boundPort, json["port"]!!.jsonPrimitive.int)
    assertEquals(token, json["token"]!!.jsonPrimitive.content)
    assertEquals(API_VERSION, json["apiVersion"]!!.jsonPrimitive.int)
    assertFalse(json["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("logs"))
  }

  @Test
  fun unauthenticatedRequestsAreRejected() {
    val (code, _) = request("GET", "/state")
    assertEquals(401, code)
  }

  @Test
  fun capabilitiesAdvertisesTheProductionRoutesWithoutDebugOnes() {
    val (code, body) = request("GET", "/capabilities", ControlPlane.token)
    assertEquals(200, code)
    val json = Json.parseToJsonElement(body).jsonObject
    assertEquals(true, json["ok"]!!.jsonPrimitive.boolean)
    assertEquals(API_VERSION, json["apiVersion"]!!.jsonPrimitive.int)
    assertEquals(app.commonComponent.applicationInfo().appVersion, json["version"]!!.jsonPrimitive.content)
    val advertised = json["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content }
    // A literal list, not the constant `forBuild()` builds this response from: comparing the
    // answer with its own input cannot detect a capability that was added or dropped by mistake.
    assertEquals(
      listOf(
        "health", "state", "capabilities", "history", "send-text", "send-file", "send-clipboard",
        "share", "transfers", "pair", "unpair", "accept-pair", "reject-pair", "accept-incoming",
        "reject-incoming", "retry", "rename-device", "settings", "qr-share", "update",
      ),
      advertised,
    )
    for (debugOnly in DEBUG_ONLY_CAPABILITIES) {
      assertFalse(advertised.contains(debugOnly), "$debugOnly must not be advertised in a release build")
    }
  }

  @Test
  fun stateAnswersWithThePublishedToken() {
    val (code, body) = request("GET", "/state", ControlPlane.token)
    assertEquals(200, code)
    assertTrue(body.startsWith("{"), body)
  }

  @Test
  fun theSubmissionRoutesAreGatedOnABoundEngineInAReleaseBuild() {
    val token = ControlPlane.token
    // This host starts the control plane WITHOUT binding a DiscoveryController, which is exactly
    // the window a desktop app is in between publishing its control file and wiring its engine.
    // The two routes the native CLI depends on must say "not ready" there — not an empty 200
    // (which reads as "nothing in flight") and not a 500.
    val (shareCode, shareBody) = request("POST", "/share", token, """{"deviceId":"nobody","text":"hi"}""")
    assertEquals(503, shareCode, shareBody)
    assertTrue(shareBody.contains("not ready"), shareBody)

    val (listCode, listBody) = request("GET", "/transfers", token)
    assertEquals(503, listCode, listBody)
    assertTrue(listBody.contains("not ready"), listBody)
  }

  @Test
  fun debugOnlyRoutesAreForbiddenInReleaseBuilds() {
    val token = ControlPlane.token
    assertEquals(403, request("GET", "/logs", token).first)
    assertEquals(403, request("GET", "/window", token).first)
    assertEquals(403, request("POST", "/window", token, """{"visible":false}""").first)
    assertEquals(403, request("POST", "/reset-identity", token).first)
  }

  @Test
  fun theSubmissionRoutesServeAnEngineInAReleaseBuild() {
    val token = ControlPlane.token
    // The 503 gate only proves the routes refuse an unbound host. This proves they WORK once an
    // engine is attached, which is the path the native CLI actually takes on a packaged desktop.
    val controller = runBlocking {
      val discovery = DiscoveryController(app.commonComponent)
      ControlPlane.bind(discovery, app)
      discovery
    }
    assertNotNull(controller)

    val (code, body) = request("POST", "/share", token, """{"deviceId":"dev-release","text":"hello"}""")
    assertEquals(200, code, body)
    val requestId = Json.parseToJsonElement(body).jsonObject["requestId"]!!.jsonPrimitive.content
    assertTrue(requestId.startsWith("req-"), body)

    val (lookupCode, lookupBody) = request("GET", "/transfers?id=$requestId", token)
    assertEquals(200, lookupCode, lookupBody)
    assertEquals(
      requestId,
      Json.parseToJsonElement(lookupBody).jsonObject["request"]!!.jsonObject["requestId"]!!.jsonPrimitive.content,
      lookupBody,
    )
  }
}