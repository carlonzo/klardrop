package com.carlom.klardrop.control

import com.carlom.klardrop.DiscoveryController
import com.carlom.klardrop.OutgoingTransfer
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.update.InstallChannel
import com.carlom.klardrop.common.update.InstallProgress
import com.carlom.klardrop.common.update.LatestManifest
import com.carlom.klardrop.common.update.ReleaseAsset
import com.carlom.klardrop.common.update.UpdateChecker
import com.carlom.klardrop.common.update.UpdateInstaller
import com.carlom.klardrop.common.update.UpdateManifestFetcher
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.OsType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class TestCoroutines(private val dispatcher: CoroutineDispatcher) : Coroutines {
  override fun newScope() = CoroutineScope(dispatcher)
  override fun newScope(context: CoroutineContext) = CoroutineScope(context)
  override val appScope = CoroutineScope(dispatcher)
  override val ioDispatcher = dispatcher
  override val mainDispatcher = dispatcher
  override val cpuDispatcher = dispatcher
}

class ControlPlaneUpdateTest {

  private val token = "test-auth-token"
  private var server: LoopbackHttpServer? = null
  private var port: Int = 0
  private val coroutines = TestCoroutines(Dispatchers.IO)

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
  }

  @AfterTest
  fun tearDown() {
    server?.stop()
    server = null
    ControlPlane.stop()
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
  fun updateCheckReturns200() {
    val checker = UpdateChecker(
      currentVersion = "1.0.0",
      osType = OsType.LINUX,
      fetcher = UpdateManifestFetcher { null },
      detectChannel = { InstallChannel.TARBALL },
      coroutines = coroutines,
    )
    ControlPlane.setUpdateCheckerForTesting(checker, coroutines)

    val (code, response) = post("/update/check")
    assertEquals(200, code)
    assertTrue(response.contains("\"action\":\"check\""), response)
  }

  @Test
  fun updateApplyReturns400WhenNothingStaged() {
    val checker = UpdateChecker(
      currentVersion = "1.0.0",
      osType = OsType.LINUX,
      fetcher = UpdateManifestFetcher { null },
      detectChannel = { InstallChannel.TARBALL },
      coroutines = coroutines,
    )
    ControlPlane.setUpdateCheckerForTesting(checker, coroutines)
    ControlPlane.setHasActiveTransfersForTesting(false)

    val (code, response) = post("/update/apply")
    assertEquals(400, code)
    assertTrue(response.contains("update is not staged"), response)
  }

  @Test
  fun updateApplyReturns409DuringActiveTransferUnlessForced() = runBlocking {
    var applied = false
    val fakeInstaller = object : UpdateInstaller {
      override suspend fun downloadAndStage(asset: ReleaseAsset, onProgress: (Float?) -> Unit) {}
      override fun applyAndRestart() {
        applied = true
      }
    }
    val checker = UpdateChecker(
      currentVersion = "1.0.0",
      osType = OsType.LINUX,
      fetcher = UpdateManifestFetcher {
        LatestManifest(
          version = "2.0.0",
          platforms = mapOf(UpdateChecker.ASSET_LINUX_TARBALL to ReleaseAsset("https://example/pkg.tar.gz")),
        )
      },
      detectChannel = { InstallChannel.TARBALL },
      coroutines = coroutines,
      installerFactory = { fakeInstaller },
      assetKey = UpdateChecker.ASSET_LINUX_TARBALL,
    )
    // Stage the update so checker.install is Ready
    checker.checkNow()
    while (checker.install.value !is InstallProgress.Ready) {
      delay(20)
    }

    ControlPlane.setUpdateCheckerForTesting(checker, coroutines)
    ControlPlane.setHasActiveTransfersForTesting(true)

    // 1. Without force -> 409
    val (code409, response409) = post("/update/apply")
    assertEquals(409, code409)
    assertTrue(response409.contains("active transfers in progress"), response409)

    // 2. With force: true -> 200 (bypasses active transfer guard)
    val (code200, response200) = post("/update/apply", """{"force":true}""")
    assertEquals(200, code200)
    assertTrue(response200.contains("\"action\":\"apply\""), response200)
  }

  @Test
  fun inFlightSendAppearsInStateTransfersAndBlocksUpdateApply() = runBlocking {
    val fakeInstaller = object : UpdateInstaller {
      override suspend fun downloadAndStage(asset: ReleaseAsset, onProgress: (Float?) -> Unit) {}
      override fun applyAndRestart() {}
    }
    val checker = UpdateChecker(
      currentVersion = "1.0.0",
      osType = OsType.LINUX,
      fetcher = UpdateManifestFetcher {
        LatestManifest(
          version = "2.0.0",
          platforms = mapOf(UpdateChecker.ASSET_LINUX_TARBALL to ReleaseAsset("https://example/pkg.tar.gz")),
        )
      },
      detectChannel = { InstallChannel.TARBALL },
      coroutines = coroutines,
      installerFactory = { fakeInstaller },
      assetKey = UpdateChecker.ASSET_LINUX_TARBALL,
    )
    checker.checkNow()
    while (checker.install.value !is InstallProgress.Ready) {
      delay(20)
    }
    ControlPlane.setUpdateCheckerForTesting(checker, coroutines)

    val sendFlow = MutableSharedFlow<MessengerSendProgress>(replay = 1, extraBufferCapacity = 8)
    ControlPlane.trackOutgoingSend(
      id = "tx-42",
      deviceId = "dev-recipient",
      fileName = "document.pdf",
      totalSize = 2048L,
      flow = sendFlow,
      scope = coroutines.appScope,
    )

    // Emit in-flight progress
    sendFlow.emit(
      MessengerSendProgress.InProgress(
        percentage = 50,
        bytesTransferred = 1024L,
        totalBytes = 2048L,
      )
    )
    delay(50)

    // 1. In-flight send must appear in /state transfers with isSender=true
    val (stateCode, stateResponse) = get("/state")
    assertEquals(200, stateCode)
    assertTrue(stateResponse.contains("\"isSender\":true"), stateResponse)
    assertTrue(stateResponse.contains("\"fileName\":\"document.pdf\""), stateResponse)
    assertTrue(stateResponse.contains("\"transferredSize\":1024"), stateResponse)
    assertTrue(stateResponse.contains("\"totalSize\":2048"), stateResponse)

    // 2. In-flight send must make /update/apply return 409
    val (applyCode, applyResponse) = post("/update/apply")
    assertEquals(409, applyCode)
    assertTrue(applyResponse.contains("active transfers in progress"), applyResponse)

    // 3. Completing the send clears transfers and unblocks apply
    sendFlow.emit(MessengerSendProgress.Completed)
    delay(50)

    val (clearedCode, clearedResponse) = get("/state")
    assertEquals(200, clearedCode)
    assertTrue(clearedResponse.contains("\"transfers\":[]"), clearedResponse)

    val (successCode, successResponse) = post("/update/apply")
    assertEquals(200, successCode)
    assertTrue(successResponse.contains("\"action\":\"apply\""), successResponse)
  }

  @Test
  fun outgoingTransferFlowWiringThroughBindAppearsInStateTransfersAndBlocksUpdateApply() = runBlocking {
    val fakeInstaller = object : UpdateInstaller {
      override suspend fun downloadAndStage(asset: ReleaseAsset, onProgress: (Float?) -> Unit) {}
      override fun applyAndRestart() {}
    }
    val checker = UpdateChecker(
      currentVersion = "1.0.0",
      osType = OsType.LINUX,
      fetcher = UpdateManifestFetcher {
        LatestManifest(
          version = "2.0.0",
          platforms = mapOf(UpdateChecker.ASSET_LINUX_TARBALL to ReleaseAsset("https://example/pkg.tar.gz")),
        )
      },
      detectChannel = { InstallChannel.TARBALL },
      coroutines = coroutines,
      installerFactory = { fakeInstaller },
      assetKey = UpdateChecker.ASSET_LINUX_TARBALL,
    )
    checker.checkNow()
    while (checker.install.value !is InstallProgress.Ready) {
      delay(20)
    }
    ControlPlane.setUpdateCheckerForTesting(checker, coroutines)

    val appInfo = ApplicationInfo(
      enableKlardropServer = false,
      enableNearbyServer = false,
      disablePersistence = true,
    )
    val app = Klardrop(
      applicationInfo = appInfo,
      internalPlatformDependency = InternalPlatformDependencies(appInfo),
    )
    io.github.vinceglb.filekit.FileKit.init("klardrop")
    app.init()
    val controller = DiscoveryController(app.commonComponent)
    ControlPlane.bind(controller, app)

    val outgoingTransfers = MutableSharedFlow<OutgoingTransfer>(extraBufferCapacity = 64)
    ControlPlane.bindOutgoingTransfers(outgoingTransfers)
    while (outgoingTransfers.subscriptionCount.value == 0) {
      delay(20)
    }

    val sendFlow = MutableSharedFlow<MessengerSendProgress>(replay = 1, extraBufferCapacity = 8)
    outgoingTransfers.emit(
      OutgoingTransfer(
        id = "tx-bind-42",
        deviceId = "dev-recipient-bind",
        fileName = "document-bind.pdf",
        totalSize = 4096L,
        flow = sendFlow,
      )
    )

    // Emit in-flight progress
    sendFlow.emit(
      MessengerSendProgress.InProgress(
        percentage = 50,
        bytesTransferred = 2048L,
        totalBytes = 4096L,
      )
    )

    // 1. In-flight send must appear in /state transfers with isSender=true
    var stateCode = 0
    var stateResponse = ""
    for (i in 0 until 50) {
      val (c, r) = get("/state")
      stateCode = c
      stateResponse = r
      if (r.contains("\"isSender\":true")) break
      delay(20)
    }
    assertEquals(200, stateCode)
    assertTrue(stateResponse.contains("\"isSender\":true"), stateResponse)
    assertTrue(stateResponse.contains("\"fileName\":\"document-bind.pdf\""), stateResponse)
    assertTrue(stateResponse.contains("\"transferredSize\":2048"), stateResponse)
    assertTrue(stateResponse.contains("\"totalSize\":4096"), stateResponse)

    // 2. In-flight send must make /update/apply return 409
    val (applyCode, applyResponse) = post("/update/apply")
    assertEquals(409, applyCode)
    assertTrue(applyResponse.contains("active transfers in progress"), applyResponse)

    // 3. Completing the send clears transfers and unblocks apply
    sendFlow.emit(MessengerSendProgress.Completed)

    var clearedCode = 0
    var clearedResponse = ""
    for (i in 0 until 50) {
      val (c, r) = get("/state")
      clearedCode = c
      clearedResponse = r
      if (r.contains("\"transfers\":[]")) break
      delay(20)
    }
    assertEquals(200, clearedCode)
    assertTrue(clearedResponse.contains("\"transfers\":[]"), clearedResponse)

    val (successCode, successResponse) = post("/update/apply")
    assertEquals(200, successCode)
    assertTrue(successResponse.contains("\"action\":\"apply\""), successResponse)
  }
}
