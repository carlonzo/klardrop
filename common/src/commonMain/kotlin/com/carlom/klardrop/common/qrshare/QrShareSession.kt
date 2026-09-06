package com.carlom.klardrop.common.qrshare

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.communication.TransferAnchor
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class QrShareSession(
  private val coroutines: Coroutines,
  private val fileManager: FileManager,
  private val transferAnchor: TransferAnchor,
  private val lanAddressSelector: LanAddressSelector,
  private val clock: Clock,
  private val server: LanHttpShareServer,
) {
  companion object {
    val POST_ARRIVAL_GRACE: Duration = 2.minutes
    val QR_WAIT_TIMEOUT: Duration = 3.minutes
    internal val TICKER_INTERVAL: Duration = 500.milliseconds
  }

  private val _state = MutableStateFlow<QrShareState>(QrShareState.Idle)
  val state: StateFlow<QrShareState> = _state.asStateFlow()

  private var sessionScope: CoroutineScope? = null
  private var sessionId: String = ""
  private var currentPayload: QrSharePayload? = null
  private var currentIpv4: String = ""
  private var currentPort: Int = 0
  private var currentUrl: String = ""

  private var qrSheetVisible: Boolean = false
  var hasClaimed: Boolean = false
    private set

  private var sessionStartTime: Instant? = null
  private var lastHttpActivity: Instant? = null

  private var waitAnchorActive: Boolean = false
  private var graceAnchorActive: Boolean = false
  private var waitingTokenDropped: Boolean = false
  private val inFlightDownloads = mutableMapOf<String, InFlightDownload>()

  private data class InFlightDownload(
    val connectionId: String,
    val index: Int,
    val fileName: String,
    val totalBytes: Long,
    var bytesTransferred: Long = 0L,
    var percentage: Int = 0,
  ) {
    fun toProgress() = QrDownloadProgress(
      fileName = fileName,
      percentage = percentage,
      bytesTransferred = bytesTransferred,
      totalBytes = totalBytes,
    )
  }

  suspend fun start(payload: QrSharePayload): QrShareState {
    cancel()

    _state.value = QrShareState.Starting

    val ipv4 = lanAddressSelector.selectIpv4()
    if (ipv4 == null) {
      val failed = QrShareState.Failed("Connect to Wi-Fi (or a hotspot)")
      _state.value = failed
      return failed
    }

    val waitingToken = generateShareToken()
    sessionId = generateConnectionId()
    currentPayload = payload
    currentIpv4 = ipv4
    qrSheetVisible = true
    hasClaimed = false
    lastHttpActivity = null
    waitingTokenDropped = false
    sessionStartTime = clock.now()
    inFlightDownloads.clear()

    transferAnchor.begin("qr:$sessionId:wait", "Waiting for someone to scan", TransferAnchor.Direction.OUTGOING)
    waitAnchorActive = true

    val bound = try {
      server.start(payload, waitingToken, ipv4)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log("QrShareSession", "Failed to start LAN HTTP server: ${e.message}")
      if (waitAnchorActive) {
        transferAnchor.end("qr:$sessionId:wait")
        waitAnchorActive = false
      }
      val failed = QrShareState.Failed(e.message ?: "Failed to start share server")
      _state.value = failed
      return failed
    }

    currentPort = bound.port
    val url = "https://$ipv4:${bound.port}/s/$waitingToken"
    currentUrl = url
    log("QrShareSession", "QR URL https://$ipv4:${bound.port}/s/<redacted>")
    val visibleState = QrShareState.QrVisible(
      url = url,
      ipv4 = ipv4,
      port = bound.port,
      payloadSummary = payload.summary(),
    )
    _state.value = visibleState

    val scope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)
    sessionScope = scope

    scope.launch {
      server.events.collect { event ->
        handleServerEvent(event)
      }
    }

    scope.launch {
      lanAddressSelector.observeChanges().collect { newIpv4 ->
        handleAddressChange(newIpv4)
      }
    }

    scope.launch {
      while (isActive) {
        delay(TICKER_INTERVAL)
        checkTimeouts()
      }
    }

    return visibleState
  }

  private fun dropWaitingTokenIfNeeded() {
    if (!waitingTokenDropped) {
      waitingTokenDropped = true
      server.dropWaitingToken()
    }
  }

  fun dismissQrSheet() {
    val currentState = _state.value
    if (currentState is QrShareState.Idle || currentState is QrShareState.Failed) {
      return
    }

    qrSheetVisible = false
    dropWaitingTokenIfNeeded()

    if (waitAnchorActive) {
      transferAnchor.end("qr:$sessionId:wait")
      waitAnchorActive = false
    }

    if (inFlightDownloads.isEmpty()) {
      if (!hasClaimed) {
        stopInternal()
      } else {
        val now = clock.now()
        val activity = lastHttpActivity
        if (activity != null && (now - activity) >= POST_ARRIVAL_GRACE) {
          stopInternal()
        } else {
          if (!graceAnchorActive) {
            transferAnchor.begin("qr:$sessionId:grace", "Waiting for download", TransferAnchor.Direction.OUTGOING)
            graceAnchorActive = true
          }
          _state.value = QrShareState.Serving(
            url = currentUrl,
            ipv4 = currentIpv4,
            port = currentPort,
            downloads = emptyList(),
            qrStillVisible = false,
          )
        }
      }
    } else {
      _state.value = QrShareState.Serving(
        url = currentUrl,
        ipv4 = currentIpv4,
        port = currentPort,
        downloads = inFlightDownloads.values.map { it.toProgress() },
        qrStillVisible = false,
      )
    }
  }

  fun cancel() {
    if (_state.value is QrShareState.Idle) return
    log("QrShareSession", "Session cancelled")
    stopInternal()
  }

  private fun stopInternal() {
    qrSheetVisible = false
    dropWaitingTokenIfNeeded()
    if (waitAnchorActive) {
      transferAnchor.end("qr:$sessionId:wait")
      waitAnchorActive = false
    }
    if (graceAnchorActive) {
      transferAnchor.end("qr:$sessionId:grace")
      graceAnchorActive = false
    }
    for (dl in inFlightDownloads.values) {
      transferAnchor.end("qr:$sessionId:file:${dl.index}:conn:${dl.connectionId}")
    }
    inFlightDownloads.clear()
    server.stop()
    sessionScope?.cancel()
    sessionScope = null
    _state.value = QrShareState.Idle
  }

  internal fun checkTimeouts() {
    val currentState = _state.value
    if (currentState is QrShareState.Idle || currentState is QrShareState.Failed) {
      return
    }

    val now = clock.now()

    // Keep serving for as long as the QR is on screen. A 3-minute cap while the
    // user is still holding the code up (lining up a camera, cert warning) is
    // what made the QR vanish mid-scan. Timeout only after they hide it.

    val downloadsInFlight = inFlightDownloads.size
    val withinGrace = hasClaimed && lastHttpActivity?.let { (now - it) < POST_ARRIVAL_GRACE } == true
    val keepServer = qrSheetVisible || downloadsInFlight > 0 || withinGrace

    if (!keepServer) {
      log("QrShareSession", "Server keep condition expired, stopping session")
      stopInternal()
    }
  }

  internal fun handleServerEvent(event: LanHttpShareServer.DownloadEvent) {
    if (_state.value is QrShareState.Idle || _state.value is QrShareState.Failed) {
      return
    }

    when (event) {
      is LanHttpShareServer.DownloadEvent.TokenRotated -> {
        currentUrl = event.url
        when (val s = _state.value) {
          is QrShareState.QrVisible -> {
            _state.value = s.copy(url = event.url)
          }
          is QrShareState.Serving -> {
            _state.value = s.copy(url = event.url)
          }
          else -> Unit
        }
      }
      is LanHttpShareServer.DownloadEvent.LandingHit -> {
        hasClaimed = true
        lastHttpActivity = clock.now()
      }
      is LanHttpShareServer.DownloadEvent.Started -> {
        hasClaimed = true
        lastHttpActivity = clock.now()
        if (graceAnchorActive) {
          transferAnchor.end("qr:$sessionId:grace")
          graceAnchorActive = false
        }
        val fileId = "qr:$sessionId:file:${event.index}:conn:${event.connectionId}"
        transferAnchor.begin(fileId, event.fileName, TransferAnchor.Direction.OUTGOING)

        inFlightDownloads[event.connectionId] = InFlightDownload(
          connectionId = event.connectionId,
          index = event.index,
          fileName = event.fileName,
          totalBytes = event.totalBytes,
          bytesTransferred = 0L,
          percentage = 0,
        )

        _state.value = QrShareState.Serving(
          url = currentUrl,
          ipv4 = currentIpv4,
          port = currentPort,
          downloads = inFlightDownloads.values.map { it.toProgress() },
          qrStillVisible = qrSheetVisible,
        )
      }
      is LanHttpShareServer.DownloadEvent.Progress -> {
        lastHttpActivity = clock.now()
        val dl = inFlightDownloads[event.connectionId]
        if (dl != null) {
          val pct = if (event.totalBytes > 0) {
            ((event.bytesTransferred * 100L) / event.totalBytes).toInt().coerceIn(0, 100)
          } else {
            0
          }
          dl.bytesTransferred = event.bytesTransferred
          dl.percentage = pct

          val fileId = "qr:$sessionId:file:${dl.index}:conn:${dl.connectionId}"
          transferAnchor.progress(fileId, pct)

          if (_state.value is QrShareState.Serving) {
            _state.value = QrShareState.Serving(
              url = currentUrl,
              ipv4 = currentIpv4,
              port = currentPort,
              downloads = inFlightDownloads.values.map { it.toProgress() },
              qrStillVisible = qrSheetVisible,
            )
          }
        }
      }
      is LanHttpShareServer.DownloadEvent.Ended -> {
        lastHttpActivity = clock.now()
        val dl = inFlightDownloads.remove(event.connectionId)
        if (dl != null) {
          val fileId = "qr:$sessionId:file:${dl.index}:conn:${dl.connectionId}"
          transferAnchor.end(fileId)
        }

        if (inFlightDownloads.isNotEmpty()) {
          _state.value = QrShareState.Serving(
            url = currentUrl,
            ipv4 = currentIpv4,
            port = currentPort,
            downloads = inFlightDownloads.values.map { it.toProgress() },
            qrStillVisible = qrSheetVisible,
          )
        } else {
          if (qrSheetVisible) {
            val payload = currentPayload
            _state.value = QrShareState.QrVisible(
              url = currentUrl,
              ipv4 = currentIpv4,
              port = currentPort,
              payloadSummary = payload?.summary().orEmpty(),
            )
          } else {
            if (!graceAnchorActive) {
              transferAnchor.begin("qr:$sessionId:grace", "Waiting for download", TransferAnchor.Direction.OUTGOING)
              graceAnchorActive = true
            }
            _state.value = QrShareState.Serving(
              url = currentUrl,
              ipv4 = currentIpv4,
              port = currentPort,
              downloads = emptyList(),
              qrStillVisible = false,
            )
          }
        }
      }
    }
  }

  internal suspend fun handleAddressChange(newIpv4: String?) {
    val currentState = _state.value
    if (currentState is QrShareState.Idle || currentState is QrShareState.Failed) {
      return
    }
    if (newIpv4 == currentIpv4) {
      return
    }

    if (newIpv4 == null) {
      // One empty NetworkInterface snapshot is common on Android (and our
      // observer emits null when enumeration throws). Tearing down the listen
      // socket here is what made scanned QR URLs immediately unreachable.
      log("QrShareSession", "Ignoring transient empty LAN address snapshot")
      return
    }

    if (qrSheetVisible) {
      log("QrShareSession", "IP changed to $newIpv4 while QR sheet visible, restarting server")
      for (dl in inFlightDownloads.values) {
        transferAnchor.end("qr:$sessionId:file:${dl.index}:conn:${dl.connectionId}")
      }
      inFlightDownloads.clear()
      if (graceAnchorActive) {
        transferAnchor.end("qr:$sessionId:grace")
        graceAnchorActive = false
      }

      val payload = currentPayload ?: return
      val newWaitingToken = generateShareToken()
      currentIpv4 = newIpv4

      try {
        val bound = server.start(payload, newWaitingToken, newIpv4, port = currentPort)
        currentPort = bound.port
        val newUrl = "https://$newIpv4:${bound.port}/s/$newWaitingToken"
        currentUrl = newUrl
        _state.value = QrShareState.QrVisible(
          url = newUrl,
          ipv4 = newIpv4,
          port = bound.port,
          payloadSummary = payload.summary(),
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log("QrShareSession", "Failed to restart server on new IP: ${e.message}")
        if (waitAnchorActive) {
          transferAnchor.end("qr:$sessionId:wait")
          waitAnchorActive = false
        }
        server.stop()
        sessionScope?.cancel()
        sessionScope = null
        _state.value = QrShareState.Failed(e.message ?: "Failed to restart server on new IP")
      }
    } else {
      log("QrShareSession", "IP changed while sheet dismissed, stopping server")
      stopInternal()
    }
  }
}
