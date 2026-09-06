package com.carlom.klardrop.common.qrshare

import TestCoroutines
import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.FileTransfer
import com.carlom.klardrop.common.communication.TransferAnchor
import com.carlom.klardrop.common.utils.Coroutines
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private class LocalRecordingTransferAnchor : TransferAnchor {
  data class BeginCall(val transferId: String, val label: String, val direction: TransferAnchor.Direction)
  data class ProgressCall(val transferId: String, val percentage: Int)

  val beginCalls = mutableListOf<BeginCall>()
  val progressCalls = mutableListOf<ProgressCall>()
  val endCalls = mutableListOf<String>()
  val activeTransfers = mutableSetOf<String>()

  override fun begin(transferId: String, label: String, direction: TransferAnchor.Direction) {
    beginCalls.add(BeginCall(transferId, label, direction))
    activeTransfers.add(transferId)
  }

  override fun progress(transferId: String, percentage: Int) {
    progressCalls.add(ProgressCall(transferId, percentage))
  }

  override fun end(transferId: String) {
    endCalls.add(transferId)
    activeTransfers.remove(transferId)
  }
}

private class FakeLanAddressSelector(
  private var currentIp: String? = "192.168.1.100",
) : LanAddressSelector {
  val ipFlow = MutableSharedFlow<String?>(replay = 1)

  init {
    ipFlow.tryEmit(currentIp)
  }

  override suspend fun selectIpv4(): String? = currentIp

  override fun observeChanges(): Flow<String?> = ipFlow

  fun emitIp(newIp: String?) {
    currentIp = newIp
    ipFlow.tryEmit(newIp)
  }
}

private class SessionTestClock(
  private var current: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L),
) : Clock {
  override fun now(): Instant = current

  fun advance(duration: Duration) {
    current += duration
  }
}

private class SessionTestFileManager : FileManager {
  override fun prepareSaveFile(fileName: String, mimeType: String): FileTransfer = error("unused")
  override fun getReadStreamFrom(file: PlatformFile): RawSource = Buffer()
  override suspend fun openFile(filePath: String): Boolean = false
  override suspend fun openUrl(url: String): Boolean = false
}

private class FakeLanHttpShareServer(
  coroutines: Coroutines,
  fileManager: FileManager,
  tls: LanTlsListener = LanTlsListener(),
  clock: Clock = Clock.System,
) : LanHttpShareServer(coroutines, fileManager, tls, clock) {
  private val _testEvents = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 64)
  override val events: SharedFlow<DownloadEvent> = _testEvents.asSharedFlow()

  var startCallCount = 0
  var stopCallCount = 0
  var dropWaitingCallCount = 0

  var lastPayload: QrSharePayload? = null
  var lastWaitingToken: String? = null
  var lastIpv4: String? = null
  var lastPort: Int = 0

  var shouldFailStart = false
  var startFailureMessage = "Bind failed"
  var boundPortToReturn = 49152

  override suspend fun start(payload: QrSharePayload, waitingToken: String, ipv4: String, port: Int): Bound {
    startCallCount++
    lastPayload = payload
    lastWaitingToken = waitingToken
    lastIpv4 = ipv4
    lastPort = port

    if (shouldFailStart) {
      throw IllegalStateException(startFailureMessage)
    }

    return Bound(if (port != 0) port else boundPortToReturn)
  }

  override fun stop() {
    stopCallCount++
  }

  override fun dropWaitingToken() {
    dropWaitingCallCount++
  }

  suspend fun emitDownloadEvent(event: DownloadEvent) {
    _testEvents.emit(event)
  }
}

@OptIn(ExperimentalCoroutinesApi::class)
class QrShareSessionTest {

  private val testDispatcher = UnconfinedTestDispatcher()
  private val coroutines = TestCoroutines(dispatcher = testDispatcher, ioDispatcher = testDispatcher)
  private val fileManager = SessionTestFileManager()
  private val textPayload = QrSharePayload.Text("Hello Klardrop")

  private class TestContext(
    val anchor: LocalRecordingTransferAnchor,
    val selector: FakeLanAddressSelector,
    val clock: SessionTestClock,
    val server: FakeLanHttpShareServer,
    val session: QrShareSession,
  )

  private fun runSessionTest(block: suspend TestContext.() -> Unit) = runTest(testDispatcher) {
    val anchor = LocalRecordingTransferAnchor()
    val selector = FakeLanAddressSelector()
    val clock = SessionTestClock()
    val server = FakeLanHttpShareServer(coroutines, fileManager, clock = clock)
    val session = QrShareSession(
      coroutines = coroutines,
      fileManager = fileManager,
      transferAnchor = anchor,
      lanAddressSelector = selector,
      clock = clock,
      server = server,
    )
    val context = TestContext(anchor, selector, clock, server, session)
    try {
      context.block()
    } finally {
      session.cancel()
    }
  }

  @Test
  fun noLanIp_failsWithoutStartingServerOrBeginWait() = runSessionTest {
    selector.emitIp(null)

    val result = session.start(textPayload)

    assertIs<QrShareState.Failed>(result)
    assertEquals("Connect to Wi-Fi (or a hotspot)", result.message)
    assertEquals(result, session.state.value)
    assertTrue(anchor.beginCalls.isEmpty())
    assertEquals(0, server.startCallCount)
  }

  @Test
  fun startSuccess_setsQrVisibleAndBeginsWait() = runSessionTest {
    val result = session.start(textPayload)

    assertIs<QrShareState.QrVisible>(result)
    assertEquals("192.168.1.100", result.ipv4)
    assertEquals(49152, result.port)
    assertEquals("Hello Klardrop", result.payloadSummary)
    assertTrue(result.url.startsWith("https://192.168.1.100:49152/s/"))
    assertEquals(result, session.state.value)

    assertEquals(1, server.startCallCount)
    assertEquals(1, anchor.beginCalls.size)
    val waitBegin = anchor.beginCalls.first()
    assertTrue(waitBegin.transferId.startsWith("qr:"))
    assertTrue(waitBegin.transferId.endsWith(":wait"))
    assertEquals("Waiting for someone to scan", waitBegin.label)
    assertEquals(TransferAnchor.Direction.OUTGOING, waitBegin.direction)
    assertEquals(setOf(waitBegin.transferId), anchor.activeTransfers)
  }

  @Test
  fun serverStartThrows_endsWaitAndSetsFailed() = runSessionTest {
    server.shouldFailStart = true
    server.startFailureMessage = "Port already in use"

    val result = session.start(textPayload)

    assertIs<QrShareState.Failed>(result)
    assertEquals("Port already in use", result.message)
    assertEquals(result, session.state.value)

    assertEquals(1, anchor.beginCalls.size)
    assertEquals(1, anchor.endCalls.size)
    assertEquals(anchor.beginCalls.first().transferId, anchor.endCalls.first())
    assertTrue(anchor.activeTransfers.isEmpty())
  }

  @Test
  fun tokenRotated_updatesUrlInState() = runSessionTest {
    session.start(textPayload)

    val rotatedUrl = "https://192.168.1.100:49152/s/newRotatedToken123"
    server.emitDownloadEvent(LanHttpShareServer.DownloadEvent.TokenRotated(rotatedUrl))

    val currentState = session.state.value
    assertIs<QrShareState.QrVisible>(currentState)
    assertEquals(rotatedUrl, currentState.url)
  }

  @Test
  fun getFileStarted_beginsFileAnchor_reportsProgress_endsFileAnchor() = runSessionTest {
    session.start(textPayload)

    server.emitDownloadEvent(
      LanHttpShareServer.DownloadEvent.Started(
        connectionId = "conn1",
        index = 0,
        fileName = "document.pdf",
        totalBytes = 2000L,
      )
    )

    val servingState = session.state.value
    assertIs<QrShareState.Serving>(servingState)
    assertTrue(servingState.qrStillVisible)
    assertEquals(1, servingState.downloads.size)
    assertEquals("document.pdf", servingState.downloads[0].fileName)
    assertEquals(0, servingState.downloads[0].percentage)

    val fileBegin = anchor.beginCalls.first { it.transferId.contains(":file:0:conn:conn1") }
    assertEquals("document.pdf", fileBegin.label)
    assertEquals(TransferAnchor.Direction.OUTGOING, fileBegin.direction)

    // Progress
    server.emitDownloadEvent(
      LanHttpShareServer.DownloadEvent.Progress(
        connectionId = "conn1",
        bytesTransferred = 1000L,
        totalBytes = 2000L,
      )
    )
    val progressCall = anchor.progressCalls.first { it.transferId.contains(":conn:conn1") }
    assertEquals(50, progressCall.percentage)
    val progressState = session.state.value as QrShareState.Serving
    assertEquals(50, progressState.downloads[0].percentage)

    // Ended
    server.emitDownloadEvent(
      LanHttpShareServer.DownloadEvent.Ended(
        connectionId = "conn1",
        success = true,
      )
    )
    assertTrue(anchor.endCalls.contains(fileBegin.transferId))

    // Because qrSheetVisible was true and downloads reached 0, transitions back to QrVisible
    assertIs<QrShareState.QrVisible>(session.state.value)
  }

  @Test
  fun dismissBeforeClaim_stopsServer_endsWait_noLeftoverAnchors() = runSessionTest {
    session.start(textPayload)

    session.dismissQrSheet()

    assertEquals(QrShareState.Idle, session.state.value)
    assertEquals(1, server.dropWaitingCallCount)
    assertEquals(1, server.stopCallCount)
    assertTrue(anchor.activeTransfers.isEmpty())
  }

  @Test
  fun dismissAfterClaimWithNoDownload_endsWait_beginsGrace() = runSessionTest {
    session.start(textPayload)

    server.emitDownloadEvent(LanHttpShareServer.DownloadEvent.LandingHit("/s/token"))

    session.dismissQrSheet()

    val servingState = session.state.value
    assertIs<QrShareState.Serving>(servingState)
    assertFalse(servingState.qrStillVisible)
    assertTrue(servingState.downloads.isEmpty())

    // Wait anchor must have ended immediately
    assertTrue(anchor.endCalls.any { it.endsWith(":wait") })
    assertFalse(anchor.activeTransfers.any { it.endsWith(":wait") })

    // Grace anchor must have begun
    val graceBegin = anchor.beginCalls.first { it.transferId.endsWith(":grace") }
    assertEquals("Waiting for download", graceBegin.label)
    assertEquals(TransferAnchor.Direction.OUTGOING, graceBegin.direction)
    assertEquals(setOf(graceBegin.transferId), anchor.activeTransfers)
  }

  @Test
  fun hideEndsWait_neverLeavesWaitDuringDownload() = runSessionTest {
    session.start(textPayload)

    server.emitDownloadEvent(
      LanHttpShareServer.DownloadEvent.Started(
        connectionId = "conn1",
        index = 0,
        fileName = "photo.jpg",
        totalBytes = 5000L,
      )
    )

    val waitId = anchor.beginCalls.first { it.transferId.endsWith(":wait") }.transferId
    val fileId = anchor.beginCalls.first { it.transferId.contains(":conn:conn1") }.transferId

    assertEquals(setOf(waitId, fileId), anchor.activeTransfers)

    // User hides the QR sheet during the download
    session.dismissQrSheet()

    // Wait anchor MUST be ended immediately
    assertTrue(anchor.endCalls.contains(waitId))
    assertFalse(anchor.activeTransfers.contains(waitId))
    // Only file anchor remains
    assertEquals(setOf(fileId), anchor.activeTransfers)

    val servingState = session.state.value
    assertIs<QrShareState.Serving>(servingState)
    assertFalse(servingState.qrStillVisible)

    // When the file download finishes, grace begins
    server.emitDownloadEvent(LanHttpShareServer.DownloadEvent.Ended("conn1", true))

    assertTrue(anchor.endCalls.contains(fileId))
    val graceId = anchor.beginCalls.first { it.transferId.endsWith(":grace") }.transferId
    assertEquals(setOf(graceId), anchor.activeTransfers)
  }

  @Test
  fun cancel_abortsDownloads_stopsServer_endsAllAnchors_idle() = runSessionTest {
    session.start(textPayload)

    server.emitDownloadEvent(
      LanHttpShareServer.DownloadEvent.Started(
        connectionId = "conn1",
        index = 0,
        fileName = "large.iso",
        totalBytes = 1_000_000L,
      )
    )

    assertEquals(2, anchor.activeTransfers.size)

    session.cancel()

    assertEquals(QrShareState.Idle, session.state.value)
    assertEquals(1, server.stopCallCount)
    assertTrue(anchor.activeTransfers.isEmpty())
  }

  @Test
  fun ipChange_rebuildsUrlAndCert_whenSheetVisible() = runSessionTest {
    session.start(textPayload)

    selector.emitIp("192.168.1.222")

    assertEquals(2, server.startCallCount)
    assertEquals("192.168.1.222", server.lastIpv4)

    val currentState = session.state.value
    assertIs<QrShareState.QrVisible>(currentState)
    assertEquals("192.168.1.222", currentState.ipv4)
    assertTrue(currentState.url.contains("192.168.1.222"))
  }

  @Test
  fun ipChangeToNull_doesNotTearDownLiveListener() = runSessionTest {
    session.start(textPayload)

    selector.emitIp(null)

    assertIs<QrShareState.QrVisible>(session.state.value)
    assertEquals(0, server.stopCallCount)
  }

  @Test
  fun qrWaitTimeout_3minWithoutClaim_stopsServerLikeDismissBeforeClaim() = runSessionTest {
    session.start(textPayload)

    clock.advance(3.minutes)
    session.checkTimeouts()

    assertEquals(QrShareState.Idle, session.state.value)
    assertTrue(anchor.activeTransfers.isEmpty())
    assertEquals(1, server.stopCallCount)
  }

  @Test
  fun gracePeriod_diesAfter2MinInactivity() = runSessionTest {
    session.start(textPayload)
    server.emitDownloadEvent(LanHttpShareServer.DownloadEvent.LandingHit("/s/token"))
    session.dismissQrSheet()

    val graceId = anchor.activeTransfers.first { it.endsWith(":grace") }

    clock.advance(1.minutes)
    session.checkTimeouts()
    assertEquals(setOf(graceId), anchor.activeTransfers)

    clock.advance(1.minutes + 1.milliseconds)
    session.checkTimeouts()

    assertEquals(QrShareState.Idle, session.state.value)
    assertTrue(anchor.activeTransfers.isEmpty())
    assertEquals(1, server.stopCallCount)
  }

  @Test
  fun gracePeriod_sequentialDownloads_resetGrace() = runSessionTest {
    session.start(textPayload)
    server.emitDownloadEvent(LanHttpShareServer.DownloadEvent.LandingHit("/s/token"))
    session.dismissQrSheet()

    assertTrue(anchor.activeTransfers.any { it.endsWith(":grace") })

    // Friend starts file 0 download after 30s
    clock.advance(30.seconds)
    server.emitDownloadEvent(
      LanHttpShareServer.DownloadEvent.Started("conn1", 0, "file0.txt", 1000L)
    )
    // Grace ended when file GET started
    assertFalse(anchor.activeTransfers.any { it.endsWith(":grace") })

    // File 0 streams for 180 seconds
    clock.advance(180.seconds)
    server.emitDownloadEvent(LanHttpShareServer.DownloadEvent.Ended("conn1", true))

    // File 0 ended: grace begins again!
    assertTrue(anchor.activeTransfers.any { it.endsWith(":grace") })

    // 1 second later, client requests file 1
    clock.advance(1.seconds)
    server.emitDownloadEvent(
      LanHttpShareServer.DownloadEvent.Started("conn2", 1, "file1.txt", 1000L)
    )
    assertFalse(anchor.activeTransfers.any { it.endsWith(":grace") })

    server.emitDownloadEvent(LanHttpShareServer.DownloadEvent.Ended("conn2", true))
    assertTrue(anchor.activeTransfers.any { it.endsWith(":grace") })

    // Advance 2 minutes after file 1 ended
    clock.advance(2.minutes + 1.milliseconds)
    session.checkTimeouts()

    assertEquals(QrShareState.Idle, session.state.value)
    assertTrue(anchor.activeTransfers.isEmpty())
    assertEquals(1, server.stopCallCount)
  }

  @Test
  fun directFileGetWithoutLandingHit_claimsWaitingToken() = runSessionTest {
    session.start(textPayload)

    // Direct GET file/0 without prior LandingHit
    server.emitDownloadEvent(
      LanHttpShareServer.DownloadEvent.Started("conn1", 0, "file0.txt", 1000L)
    )
    server.emitDownloadEvent(LanHttpShareServer.DownloadEvent.Ended("conn1", true))

    // Now dismiss QR sheet: since Started claimed, grace begins instead of going directly to Idle
    session.dismissQrSheet()

    val state = session.state.value
    assertIs<QrShareState.Serving>(state)
    assertFalse(state.qrStillVisible)
    assertTrue(anchor.activeTransfers.any { it.endsWith(":grace") })
  }
}
