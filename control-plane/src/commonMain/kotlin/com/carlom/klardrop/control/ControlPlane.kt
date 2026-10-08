package com.carlom.klardrop.control

import com.carlom.klardrop.DiscoveryController
import com.carlom.klardrop.OutgoingTransfer
import com.carlom.klardrop.TRANSFER_KIND_FILE
import com.carlom.klardrop.TrustStatus
import com.carlom.klardrop.UiNotification
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.Reachability
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.persistence.DeliveryStatus
import com.carlom.klardrop.common.qrshare.QrSharePayload
import com.carlom.klardrop.common.qrshare.QrShareState
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.update.InstallProgress
import com.carlom.klardrop.common.update.UpdateAction
import com.carlom.klardrop.common.update.UpdateChecker
import com.carlom.klardrop.common.update.UpdateStatus
import com.klardrop.common.CrashReporter
import com.klardrop.common.ReportOutcome
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.LogBuffer
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.platformFileFromPath
import com.carlom.klardrop.sharedFileFromPlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.concurrent.Volatile
import kotlin.random.Random

/** UTF-8 byte cap for POST /clipboard's `text`. */
private const val MAX_CLIPBOARD_BYTES = 1_048_576

/** Maximum number of paths one `POST /share` may carry. */
private const val MAX_SHARE_PATHS = 64

/** Answered by routes that need a bound DiscoveryController while the host is still starting. */
private const val NOT_BOUND_MESSAGE = "daemon is not ready yet: no engine is bound"

/** `MessengerSendProgress.Error.reason` the engine emits when the recipient rejects a transfer. */
private const val MESSENGER_REASON_DECLINED = "declined"

/** GET /thumbnail: longest edge of the extracted frame and the ffmpeg timeout. */
private const val THUMBNAIL_MAX_WIDTH_PX = 320
private const val THUMBNAIL_TIMEOUT_MS = 10_000L

private data class TransferItem(
  val id: String,
  val deviceId: String,
  val fileName: String,
  val totalSize: Long,
  val transferredSize: Long,
  val isSender: Boolean,
  /** "pending" | "awaiting" | "progress" for an outgoing send; always "progress" for incoming. */
  val phase: String = "progress",
)

/**
 * Loopback HTTP control plane. Maps each endpoint onto the same DiscoveryController
 * methods the Compose buttons call, so the Omarchy shell or an agent can pair / send / accept
 * without tapping the UI.
 *
 * Started when [com.carlom.klardrop.common.ApplicationInfo.controlPort] is set. Binds 127.0.0.1.
 */
object ControlPlane : ControlPlaneService {
  private val json = Json { ignoreUnknownKeys = true }

  @Volatile
  private var controller: DiscoveryController? = null

  @Volatile
  private var klardrop: Klardrop? = null

  @Volatile
  override var windowVisibilityProvider: (() -> Boolean)? = null

  @Volatile
  override var windowVisibilitySetter: ((Boolean) -> Unit)? = null

  override val boundPort: Int
    get() = server?.boundPort ?: 0

  override var token: String? = null
    private set

  private val stateVersionFlow = MutableStateFlow(1L)
  private var stateCollectorJob: Job? = null
  private var receiverCollectorJob: Job? = null
  private var outgoingCollectorJob: Job? = null
  private var updateCollectorJob: Job? = null
  private var unreadCountsCollectorJob: Job? = null
  private var qrShareCollectorJob: Job? = null
  private val activeTransfersFlow = MutableStateFlow<Map<String, TransferItem>>(emptyMap())
  private val unreadCountsFlow = MutableStateFlow<Map<String, Long>>(emptyMap())
  private var server: LoopbackHttpServer? = null

  // The cert QrTlsCertGenerator hands to LanTlsListener is valid for 24h from generation
  // (QrTlsCertGenerator.generate's default validityHours) — that's the real hard expiry for a
  // share URL, since the client's TLS handshake fails after it. QrShareSession itself has no
  // fixed-duration expiry field (it lives until dismissed / idle-timed-out), so this is what
  // /qr-share and /state report as `expiresAt`.
  private val qrShareCertValidityMillis = 24L * 60 * 60 * 1000
  @Volatile
  private var qrShareStartedAtMillis: Long? = null

  /**
   * Observes one outgoing transfer. A file transfer keeps today's behavior exactly: it appears in
   * `/state`'s `transfers[]` while it runs, which is what the Qt and Omarchy shells read. Any
   * other kind (text/clipboard) has no bytes and no row there, so it only drives the share
   * registry. When the transfer id is bound to a share request, both kinds update the registry.
   */
  internal fun trackOutgoingSend(
    id: String,
    deviceId: String,
    fileName: String,
    totalSize: Long,
    flow: Flow<MessengerSendProgress>,
    scope: CoroutineScope,
    kind: String = TRANSFER_KIND_FILE,
  ) {
    if (kind != TRANSFER_KIND_FILE) {
      scope.launch {
        trackShareRequestProgress(id, flow)
      }
      return
    }
    val key = "tx-$id"
    activeTransfersFlow.update { current ->
      current + (key to TransferItem(
        id = id,
        deviceId = deviceId,
        fileName = fileName,
        totalSize = totalSize,
        transferredSize = 0L,
        isSender = true,
        phase = "pending",
      ))
    }
    stateVersionFlow.update { it + 1 }

    scope.launch {
      var transferred = 0L
      try {
        flow.collect { progress ->
          when (progress) {
            is MessengerSendProgress.Pending -> Unit
            is MessengerSendProgress.AwaitingRecipient -> {
              var phaseChanged = false
              activeTransfersFlow.update { current ->
                val existing = current[key] ?: return@update current
                if (existing.phase == "awaiting") return@update current
                phaseChanged = true
                current + (key to existing.copy(phase = "awaiting"))
              }
              if (phaseChanged) stateVersionFlow.update { it + 1 }
              ShareRegistry.update(id, ShareStatus.AWAITING)
            }
            is MessengerSendProgress.InProgress -> {
              transferred = progress.bytesTransferred
              val total = if (progress.totalBytes > 0) progress.totalBytes else totalSize
              var phaseChanged = false
              activeTransfersFlow.update { current ->
                val existing = current[key]
                phaseChanged = existing?.phase != "progress"
                current + (key to TransferItem(
                  id = id,
                  deviceId = deviceId,
                  fileName = existing?.fileName ?: fileName,
                  totalSize = total,
                  transferredSize = progress.bytesTransferred,
                  isSender = true,
                  phase = "progress",
                ))
              }
              if (phaseChanged) stateVersionFlow.update { it + 1 } else bumpStateVersionThrottled()
              ShareRegistry.update(id, ShareStatus.TRANSFERRING, progress.bytesTransferred)
            }
            is MessengerSendProgress.Completed -> {
              activeTransfersFlow.update { current -> current - key }
              stateVersionFlow.update { it + 1 }
              ShareRegistry.update(id, ShareStatus.COMPLETED, transferred)
            }
            is MessengerSendProgress.Error -> {
              activeTransfersFlow.update { current -> current - key }
              stateVersionFlow.update { it + 1 }
              ShareRegistry.update(
                id,
                shareItemStatus(progress),
                transferred,
                shareErrorText(progress),
              )
            }
          }
        }
      } finally {
        var removed = false
        activeTransfersFlow.update { current ->
          if (current.containsKey(key)) {
            removed = true
            current - key
          } else {
            current
          }
        }
        if (removed) {
          stateVersionFlow.update { it + 1 }
        }
      }
    }
  }

  /** Feeds one text/clipboard transfer's progress into the share registry, and nothing else. */
  private suspend fun trackShareRequestProgress(id: String, flow: Flow<MessengerSendProgress>) {
    var transferred = 0L
    flow.collect { progress ->
      when (progress) {
        is MessengerSendProgress.InProgress -> {
          transferred = progress.bytesTransferred
          ShareRegistry.update(id, ShareStatus.TRANSFERRING, transferred)
        }
        is MessengerSendProgress.AwaitingRecipient ->
          ShareRegistry.update(id, ShareStatus.AWAITING)
        is MessengerSendProgress.Completed ->
          ShareRegistry.update(id, ShareStatus.COMPLETED, transferred)
        is MessengerSendProgress.Error ->
          ShareRegistry.update(
            id,
            shareItemStatus(progress),
            transferred,
            shareErrorText(progress),
          )
        is MessengerSendProgress.Pending -> Unit
      }
    }
  }

  /** A recipient rejection is its own outcome; every other error is a failure. */
  private fun shareItemStatus(progress: MessengerSendProgress.Error): String =
    if (progress.reason == MESSENGER_REASON_DECLINED) ShareStatus.DECLINED else ShareStatus.FAILED

  private fun shareErrorText(progress: MessengerSendProgress.Error): String =
    if (progress.reason.isNullOrBlank()) {
      progress.message
    } else {
      "${progress.message} (${progress.reason})"
    }

  @Volatile
  private var updateCheckerProvider: (() -> UpdateChecker)? = null

  @Volatile
  private var coroutinesProvider: (() -> Coroutines)? = null

  internal fun setUpdateCheckerForTesting(checker: UpdateChecker?, coroutines: Coroutines?) {
    this.updateCheckerProvider = if (checker != null) { { checker } } else null
    this.coroutinesProvider = if (coroutines != null) { { coroutines } } else null
  }

  internal fun setHasActiveTransfersForTesting(active: Boolean) {
    if (active) {
      activeTransfersFlow.value = mapOf(
        "test-transfer" to TransferItem("test-transfer", "dev-1", "file.txt", 1000L, 500L, false)
      )
    } else {
      activeTransfersFlow.value = emptyMap()
    }
  }

  internal suspend fun dispatchForTesting(request: HttpRequest): HttpResponse = dispatch(request)

  /** Test isolation: share requests live in a process-wide singleton. */
  internal suspend fun resetShareRegistryForTesting() = ShareRegistry.reset()

  @Volatile
  private var lastProgressBumpMs = 0L

  private fun bumpStateVersionThrottled() {
    val now = Clock().currentTimeMillis()
    if (now - lastProgressBumpMs >= 250L) {
      lastProgressBumpMs = now
      stateVersionFlow.update { it + 1 }
    }
  }

  override suspend fun start(app: Klardrop) {
    klardrop = app
    val info = app.commonComponent.applicationInfo()
    val port = info.controlPort ?: return
    if (port < 0) return
    if (server != null) return

    // Fails closed before anything is bound — see controlFilePathToPublish().
    val controlFilePath = controlFilePathToPublish()
    val genToken = if (controlFilePath != null) generateToken() else null
    token = genToken

    val http = LoopbackHttpServer(
      host = "127.0.0.1",
      port = port,
      authToken = genToken,
      dispatcher = app.commonComponent.coroutines().ioDispatcher,
      handle = ::dispatch,
    )
    try {
      withContext(app.commonComponent.coroutines().ioDispatcher) {
        http.start()
      }
    } catch (e: Exception) {
      token = null
      throw e
    }
    server = http
    if (genToken != null) {
      try {
        writeControlFile(http.boundPort, genToken, forBuild(info.isDebug))
      } catch (e: Exception) {
        // Fail closed: a control file we could not create or protect must never leave a
        // bound listener behind that clients cannot authenticate against.
        http.stop()
        server = null
        token = null
        throw e
      }
    }
  }

  override suspend fun bind(discoveryController: DiscoveryController, app: Klardrop) {
    klardrop = app
    controller = discoveryController
    stateCollectorJob?.cancel()
    stateCollectorJob = app.commonComponent.coroutines().appScope.launch {
      discoveryController.screenStateFlow.collect {
        stateVersionFlow.update { it + 1 }
      }
    }
    receiverCollectorJob?.cancel()
    receiverCollectorJob = app.commonComponent.coroutines().appScope.launch {
      app.commonComponent.messageReceiver().latestUpdates.collect { updates ->
        var hasChanges = false
        updates.forEach { (deviceId, update) ->
          when (val s = update.status) {
            is ReceiveMessageStatus.Started -> {
              val file = update.messages.filterIsInstance<FileMessage>().firstOrNull()
              if (file != null) {
                val key = "rx-${file.id}"
                activeTransfersFlow.update { current ->
                  current + (key to TransferItem(
                    id = file.id.toString(),
                    deviceId = deviceId,
                    fileName = file.fileName,
                    totalSize = file.fileSize,
                    transferredSize = 0L,
                    isSender = false,
                  ))
                }
                hasChanges = true
              }
            }
            is ReceiveMessageStatus.Progress -> {
              val pair = s.messages.lastOrNull()
              val file = pair?.first as? FileMessage
              val key = if (file != null) "rx-${file.id}" else "rx-$deviceId"
              val name = file?.fileName ?: "file"
              val total = if (s.totalBytes > 0) s.totalBytes else (file?.fileSize ?: 0L)
              activeTransfersFlow.update { current ->
                val existing = current[key]
                current + (key to TransferItem(
                  id = file?.id?.toString() ?: key,
                  deviceId = deviceId,
                  fileName = existing?.fileName ?: name,
                  totalSize = total,
                  transferredSize = s.bytesTransferred,
                  isSender = false,
                ))
              }
              bumpStateVersionThrottled()
            }
            is ReceiveMessageStatus.Completed, is ReceiveMessageStatus.Failed -> {
              activeTransfersFlow.update { current ->
                val remaining = current.filterNot { (_, v) -> !v.isSender && v.deviceId == deviceId }
                if (remaining.size != current.size) {
                  hasChanges = true
                }
                remaining
              }
            }
            else -> Unit
          }
        }
        if (hasChanges) {
          stateVersionFlow.update { it + 1 }
        }
      }
    }
    updateCollectorJob?.cancel()
    updateCollectorJob = app.commonComponent.coroutines().appScope.launch {
      val checker = app.commonComponent.updateChecker()
      launch {
        checker.status.collect {
          stateVersionFlow.update { it + 1 }
        }
      }
      launch {
        checker.install.collect {
          stateVersionFlow.update { it + 1 }
        }
      }
    }
    unreadCountsCollectorJob?.cancel()
    unreadCountsCollectorJob = app.commonComponent.coroutines().appScope.launch {
      app.commonComponent.messageRepository().getAllDevicesWithUnreadCounts().collect { counts ->
        unreadCountsFlow.value = counts
        stateVersionFlow.update { it + 1 }
      }
    }
    qrShareCollectorJob?.cancel()
    qrShareCollectorJob = app.commonComponent.coroutines().appScope.launch {
      app.commonComponent.qrShareSession().state.collect {
        stateVersionFlow.update { it + 1 }
      }
    }
    bindOutgoingTransfers(discoveryController.outgoingTransfers)
    start(app)
  }

  internal fun bindOutgoingTransfers(flow: Flow<OutgoingTransfer>) {
    val app = klardrop ?: error("not bound")
    outgoingCollectorJob?.cancel()
    outgoingCollectorJob = app.commonComponent.coroutines().appScope.launch {
      flow.collect { transfer ->
        trackOutgoingSend(
          id = transfer.id,
          deviceId = transfer.deviceId,
          fileName = transfer.fileName,
          totalSize = transfer.totalSize,
          flow = transfer.flow,
          scope = this,
          kind = transfer.kind,
        )
      }
    }
  }

  override fun stop() {
    // A null token means this instance never wrote a control file (failed bind, stop() without
    // a prior start(), or a repeat stop()) — deleteControlFile(null) deletes unconditionally,
    // so calling it here would remove an unrelated running daemon's control.json.
    token?.let { deleteControlFile(it) }
    stateCollectorJob?.cancel()
    stateCollectorJob = null
    receiverCollectorJob?.cancel()
    receiverCollectorJob = null
    outgoingCollectorJob?.cancel()
    outgoingCollectorJob = null
    updateCollectorJob?.cancel()
    updateCollectorJob = null
    unreadCountsCollectorJob?.cancel()
    unreadCountsCollectorJob = null
    qrShareCollectorJob?.cancel()
    qrShareCollectorJob = null
    klardrop?.commonComponent?.qrShareSession()?.cancel()
    qrShareStartedAtMillis = null
    activeTransfersFlow.value = emptyMap()
    unreadCountsFlow.value = emptyMap()
    // An in-process stop/start is a fresh daemon as far as a client is concerned: leaving the
    // registry populated would hand out pre-stop records frozen mid-flight, contradicting the
    // "unknown after a restart" contract this registry documents.
    // `runBlocking` here is safe and deliberate: the registry's critical section is a handful of
    // map operations that never suspend, so this cannot block for long — and a client that asks
    // straight after a stop must already see an unknown request, never a frozen mid-flight one.
    runBlocking { ShareRegistry.reset() }
    updateCheckerProvider = null
    coroutinesProvider = null
    // Close the listener BEFORE dropping the bindings: while it is still accepting, a request
    // that already passed the 503 gate would re-read a null controller and turn into a 500 —
    // the very failure the gate exists to prevent.
    server?.stop()
    server = null
    // A stopped control plane has no engine behind it, so the routes that need one must say
    // "not ready" rather than answering from state left over from the previous run.
    controller = null
    klardrop = null
    token = null
  }

  private fun isDebugBuild(): Boolean =
    klardrop?.commonComponent?.applicationInfo()?.isDebug ?: false

  private suspend fun dispatch(request: HttpRequest): HttpResponse {
    val path = request.path.trimEnd('/').ifEmpty { "/" }
    val body = parseBody(request.body)
    return when {
      request.method == "GET" && path == "/health" ->
        HttpResponse(200, jsonOk(""" "port":${server?.boundPort ?: 0} """))

      request.method == "GET" && path == "/capabilities" -> {
        val payload = buildJsonObject {
          put("ok", true)
          put("apiVersion", API_VERSION)
          put("version", klardrop?.commonComponent?.applicationInfo()?.appVersion.orEmpty())
          putJsonArray("capabilities") {
            forBuild(isDebugBuild()).forEach { add(JsonPrimitive(it)) }
          }
        }.toString()
        HttpResponse(200, payload)
      }

      request.method == "GET" && path == "/window" -> {
        if (!isDebugBuild()) return HttpResponse(403, jsonError("forbidden: debug builds only"))
        val visible = windowVisibilityProvider?.invoke() ?: false
        val payload = buildJsonObject {
          put("ok", true)
          put("visible", visible)
        }.toString()
        HttpResponse(200, payload)
      }

      request.method == "POST" && path == "/window" -> {
        if (!isDebugBuild()) return HttpResponse(403, jsonError("forbidden: debug builds only"))
        val visible = body.boolean("visible") ?: error("missing visible")
        log("ControlPlane", "action window visible=$visible")
        windowVisibilitySetter?.invoke(visible)
        val payload = buildJsonObject {
          put("ok", true)
          put("visible", visible)
        }.toString()
        HttpResponse(200, payload)
      }

      request.method == "GET" && path == "/state" -> {
        val since = queryParam(request.query, "since")?.toLongOrNull()
        if (since != null) {
          withTimeoutOrNull(30_000L) {
            stateVersionFlow.first { it > since }
          }
        }
        HttpResponse(200, snapshotState())
      }

      request.method == "GET" && path == "/logs" -> {
        if (!isDebugBuild()) return HttpResponse(403, jsonError("forbidden: debug builds only"))
        val limit = queryParam(request.query, "limit")?.toIntOrNull() ?: 400
        val lines = LogBuffer.snapshot(limit)
        val payload = buildJsonObject {
          put("ok", true)
          putJsonArray("lines") { lines.forEach { add(JsonPrimitive(it)) } }
        }.toString()
        HttpResponse(200, payload)
      }

      request.method == "GET" && path == "/history" -> {
        val deviceId = queryParam(request.query, "device")
          ?: return HttpResponse(400, jsonError("missing device parameter"))
        val limitParam = queryParam(request.query, "limit")
        val limit = if (limitParam == null) {
          50L
        } else {
          (limitParam.toLongOrNull() ?: return HttpResponse(400, jsonError("invalid limit")))
            .coerceIn(1L, 200L)
        }
        val beforeParam = queryParam(request.query, "before")
        val beforeId = if (beforeParam == null) {
          null
        } else {
          beforeParam.toLongOrNull() ?: return HttpResponse(400, jsonError("invalid before"))
        }
        val app = klardrop ?: error("not bound")
        val repo = app.commonComponent.messageRepository()
        val io = app.commonComponent.coroutines().ioDispatcher
        val messages = repo.getMessagesForDevicePage(deviceId, limit, beforeId)
          ?: return HttpResponse(400, jsonError("unknown before id"))
        val fileTransfers = withContext(io) {
          messages.mapNotNull { it.fileTransferId }.distinct().associateWith {
            repo.getFileTransferById(it).first()
          }
        }
        val nextBefore = if (messages.size.toLong() == limit) messages.lastOrNull()?.id else null
        val payload = buildJsonObject {
          put("ok", true)
          put("deviceId", deviceId)
          putNullable("nextBefore", nextBefore)
          putJsonArray("messages") {
            for (msg in messages) {
              val transfer = msg.fileTransferId?.let { fileTransfers[it] }
              add(
                buildJsonObject {
                  put("id", msg.id)
                  put("content", msg.content)
                  put("timestamp", msg.timestamp)
                  put("isSender", msg.isSender)
                  put("messageType", msg.messageType)
                  put("deliveryStatus", msg.deliveryStatus.name)
                  put("isRead", msg.isRead != 0L)
                  put("mimeType", msg.mimeType)
                  putNullable("fileTransferId", msg.fileTransferId)
                  if (transfer != null) {
                    putJsonObject("file") {
                      put("fileName", transfer.file_name)
                      put("filePath", transfer.file_path)
                      put("fileSize", transfer.total_size)
                      put("transferredSize", transfer.transferred_size)
                      put("status", transfer.status)
                    }
                  } else {
                    put("file", JsonNull)
                  }
                }
              )
            }
          }
        }.toString()
        HttpResponse(200, payload)
      }

      request.method == "GET" && path == "/thumbnail" -> {
        val idParam = queryParam(request.query, "id")
          ?: return HttpResponse(400, jsonError("missing id parameter"))
        val id = idParam.toLongOrNull() ?: return HttpResponse(400, jsonError("invalid id"))
        val app = klardrop ?: error("not bound")
        val repo = app.commonComponent.messageRepository()
        val io = app.commonComponent.coroutines().ioDispatcher
        val message = withContext(io) { repo.getMessageById(id) }
          ?: return HttpResponse(404, jsonError("unknown message id"))
        val fileTransferId = message.fileTransferId
        if (fileTransferId == null || !message.mimeType.startsWith("video/")) {
          return HttpResponse(400, jsonError("message has no video file"))
        }
        val transfer = withContext(io) { repo.getFileTransferById(fileTransferId).first() }
        val videoPath = transfer?.file_path
        if (videoPath.isNullOrBlank() || !withContext(io) { SystemFileSystem.exists(Path(videoPath)) }) {
          return HttpResponse(404, jsonError("video file not found"))
        }
        val cacheDir = Path(app.commonComponent.platformFileSystem().getTempStoragePath(), "thumbnails")
        val outputPath = Path(cacheDir, "$id.png")
        val hasCached = withContext(io) { SystemFileSystem.exists(outputPath) }
        if (!hasCached) {
          val extracted = withContext(io) {
            SystemFileSystem.createDirectories(cacheDir)
            extractVideoThumbnail(videoPath, outputPath.toString(), THUMBNAIL_MAX_WIDTH_PX, THUMBNAIL_TIMEOUT_MS)
              .also { ok -> if (!ok) SystemFileSystem.delete(outputPath, mustExist = false) }
          }
          if (!extracted) {
            return HttpResponse(200, jsonOk(""" "thumbnail":null """))
          }
        }
        HttpResponse(200, jsonOk(""" "thumbnail":${jsonString(outputPath.toString())} """))
      }

      request.method == "POST" && path == "/history/read" -> {
        val ctrl = controller ?: error("DiscoveryController not bound")
        val deviceId = requireDeviceId(body)
        ctrl.markDeviceReadAndWait(deviceId)
        stateVersionFlow.update { it + 1 }
        HttpResponse(200, jsonOk(""" "action":"history-read" """))
      }

      request.method == "POST" && path == "/active-chat" -> {
        val ctrl = controller ?: error("DiscoveryController not bound")
        ctrl.setActiveChatDeviceId(body.string("deviceId"))
        HttpResponse(200, jsonOk(""" "action":"active-chat" """))
      }

      request.method == "POST" && path == "/retry" -> {
        val app = klardrop ?: error("not bound")
        val ctrl = controller ?: error("DiscoveryController not bound")
        val fileTransferId = body.long("fileTransferId")
          ?: return HttpResponse(400, jsonError("missing fileTransferId"))
        val repo = app.commonComponent.messageRepository()
        val io = app.commonComponent.coroutines().ioDispatcher
        val transfer = withContext(io) { repo.getFileTransferById(fileTransferId).first() }
          ?: return HttpResponse(404, jsonError("file transfer not found"))
        val message = withContext(io) { repo.getMessageByFileTransferId(fileTransferId) }
        if (message == null || !message.isSender) {
          return HttpResponse(409, jsonError("only a failed outgoing transfer can be retried"))
        }
        val failed = transfer.status == "FAILED" || message.deliveryStatus == DeliveryStatus.FAILED
        if (!failed) {
          return HttpResponse(409, jsonError("transfer is not failed"))
        }
        val filePath = transfer.file_path
        val fileAvailable = filePath.isNotBlank() &&
          withContext(io) { SystemFileSystem.exists(Path(filePath)) }
        if (!fileAvailable) {
          return HttpResponse(409, jsonError("the original file is no longer available"))
        }
        log("ControlPlane", "action retry fileTransferId=$fileTransferId")
        ctrl.sendFiles(message.remoteDeviceId, listOf(platformFileFromPath(filePath)))
        HttpResponse(
          200,
          jsonOk(""" "action":"retry","deviceId":${jsonString(message.remoteDeviceId)} """)
        )
      }

      request.method == "POST" && path == "/clipboard" -> {
        val app = klardrop ?: error("not bound")
        val text = body.string("text")
        when {
          text.isNullOrEmpty() -> HttpResponse(400, jsonError("missing or empty text"))
          text.encodeToByteArray().size > MAX_CLIPBOARD_BYTES ->
            HttpResponse(413, jsonError("text too large"))
          else -> {
            withContext(app.commonComponent.coroutines().ioDispatcher) {
              app.commonComponent.clipboardManager().write(text)
            }
            HttpResponse(200, jsonOk(""" "action":"clipboard" """))
          }
        }
      }

      request.method == "POST" && path == "/rename-device" -> {
        val ctrl = controller ?: error("DiscoveryController not bound")
        val name = body.string("name") ?: body.string("deviceName") ?: body.string("customName")
        ctrl.saveCustomDeviceName(name?.ifBlank { null })
        stateVersionFlow.update { it + 1 }
        HttpResponse(200, jsonOk(""" "name":${jsonString(name ?: "")} """))
      }

      request.method == "POST" && path == "/settings" -> {
        val ctrl = controller ?: error("DiscoveryController not bound")
        val bgDiscovery = body.boolean("backgroundDiscovery") ?: body.boolean("backgroundDiscoveryEnabled")
        if (bgDiscovery != null) {
          ctrl.setBackgroundDiscoveryEnabled(bgDiscovery)
        }
        val name = body.string("name") ?: body.string("deviceName") ?: body.string("customName")
        if (name != null) {
          ctrl.saveCustomDeviceName(name.ifBlank { null })
        }
        stateVersionFlow.update { it + 1 }
        HttpResponse(200, jsonOk(""" "action":"settings" """))
      }

      request.method == "POST" && path == "/send-clipboard" -> {
        val app = klardrop ?: error("not bound")
        val ctrl = controller ?: error("DiscoveryController not bound")
        val deviceId = requireDeviceId(body)
        val text = body.string("text") ?: app.commonComponent.clipboardManager().read().trim()
        if (text.isEmpty()) {
          HttpResponse(400, jsonError("clipboard is empty"))
        } else {
          log("ControlPlane", "action send-clipboard to $deviceId")
          val result = ctrl.debugSendTextAndWait(deviceId, text)
          stateVersionFlow.update { it + 1 }
          HttpResponse(200, jsonOk(""" "action":"send-clipboard","result":${jsonString(result)} """))
        }
      }

      request.method == "POST" && path == "/pair" ->
        action("pair") { it.debugPair(requireDeviceId(body)) }

      request.method == "POST" && path == "/unpair" -> {
        val ctrl = controller ?: error("DiscoveryController not bound")
        val deviceId = requireDeviceId(body)
        log("ControlPlane", "action unpair")
        ctrl.debugUnpairAndWait(deviceId)
        stateVersionFlow.update { it + 1 }
        HttpResponse(200, jsonOk(""" "action":"unpair" """))
      }

      request.method == "POST" && path == "/accept-pair" ->
        action("accept-pair") { it.debugAcceptPairing(requireDeviceId(body)) }

      request.method == "POST" && path == "/reject-pair" ->
        action("reject-pair") { it.debugRejectPairing(requireDeviceId(body)) }

      request.method == "POST" && path == "/send-text" -> {
        val text = body.string("text") ?: error("missing text")
        val ctrl = controller ?: error("DiscoveryController not bound")
        val deviceId = requireDeviceId(body)
        log("ControlPlane", "action send-text")
        val result = ctrl.debugSendTextAndWait(deviceId, text)
        stateVersionFlow.update { it + 1 }
        HttpResponse(200, jsonOk(""" "action":"send-text","result":${jsonString(result)} """))
      }

      request.method == "POST" && path == "/send-file" -> {
        val deviceId = requireDeviceId(body)
        val filePath = body.string("path")
        val paths = body["paths"]?.let { el ->
          (el as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
        }
        when {
          paths != null && paths.isNotEmpty() -> {
            action("send-file") { ctrl ->
              paths.forEach { p -> ctrl.debugSendFile(deviceId, p) }
            }
          }
          filePath != null -> action("send-file") { it.debugSendFile(deviceId, filePath) }
          else -> error("missing path or paths")
        }
      }

      request.method == "POST" && path == "/share" ->
        if (controller == null) HttpResponse(503, jsonError(NOT_BOUND_MESSAGE))
        else handleShareRequest(body)

      request.method == "GET" && path == "/transfers" -> {
        // A host that has not bound a DiscoveryController has no engine behind these routes:
        // answering 200 with an empty list, or 500 from an internal error, would both read to a
        // client as "the daemon knows there is nothing in flight".
        if (controller == null) {
          HttpResponse(503, jsonError(NOT_BOUND_MESSAGE))
        } else {
          val requestId = queryParam(request.query, "id")
          if (requestId == null) {
            val records = ShareRegistry.list()
            val payload = buildJsonObject {
              put("ok", true)
              putJsonArray("requests") { records.forEach { add(shareRequestJson(it)) } }
            }.toString()
            HttpResponse(200, payload)
          } else {
            val record = ShareRegistry.get(requestId)
              ?: return HttpResponse(404, jsonError("unknown request id"))
            val payload = buildJsonObject {
              put("ok", true)
              put("request", shareRequestJson(record))
            }.toString()
            HttpResponse(200, payload)
          }
        }
      }

      request.method == "POST" && path == "/qr-share" -> {
        val app = klardrop ?: error("not bound")
        val paths = (body["paths"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
        if (paths.isNullOrEmpty()) return HttpResponse(400, jsonError("missing paths"))
        val io = app.commonComponent.coroutines().ioDispatcher
        // Regular files only, not directories: SystemFileSystem.exists() alone would let a
        // directory path through and QrSharePayload.Files/PlatformFile expect a readable file.
        val missing = withContext(io) {
          paths.filterNot { SystemFileSystem.metadataOrNull(Path(it))?.isRegularFile == true }
        }
        if (missing.isNotEmpty()) {
          return HttpResponse(400, jsonError("path(s) not found: ${missing.joinToString(", ")}"))
        }

        val payload = QrSharePayload.Files(paths.map { sharedFileFromPlatformFile(platformFileFromPath(it)) })
        when (val state = app.commonComponent.qrShareSession().start(payload)) {
          is QrShareState.QrVisible -> {
            val startedAt = Clock().currentTimeMillis()
            qrShareStartedAtMillis = startedAt
            stateVersionFlow.update { it + 1 }
            val qrJson = renderQrMatrix(state.url).joinToString(",") { jsonString(it) }
            HttpResponse(
              200,
              jsonOk(
                """ "url":${jsonString(state.url)},"expiresAt":${startedAt + qrShareCertValidityMillis},"qr":[$qrJson] """,
              ),
            )
          }
          is QrShareState.Failed -> HttpResponse(400, jsonError(state.message))
          else -> HttpResponse(500, jsonError("unexpected qr share state after start()"))
        }
      }

      request.method == "POST" && path == "/qr-share/stop" -> {
        val app = klardrop ?: error("not bound")
        app.commonComponent.qrShareSession().cancel()
        qrShareStartedAtMillis = null
        stateVersionFlow.update { it + 1 }
        HttpResponse(200, jsonOk(""" "action":"qr-share-stop" """))
      }

      request.method == "POST" && path == "/accept-incoming" -> {
        val receiveId = body.int("receiveId")
        val deviceId = body.string("deviceId")
        action("accept-incoming") { ctrl ->
          when {
            receiveId != null -> ctrl.debugAcceptIncoming(receiveId)
            deviceId != null -> ctrl.debugAcceptIncomingFrom(deviceId)
            else -> error("missing receiveId or deviceId")
          }
        }
      }

      request.method == "POST" && path == "/reject-incoming" -> {
        val receiveId = body.int("receiveId") ?: error("missing receiveId")
        action("reject-incoming") { it.debugRejectIncoming(receiveId) }
      }

      request.method == "POST" && path == "/reset-identity" -> {
        if (!isDebugBuild()) return HttpResponse(403, jsonError("forbidden: debug builds only"))
        val app = klardrop ?: error("not bound")
        val shortId = app.commonComponent.currentDeviceProvider().rotateDeviceId()
        app.commonComponent.trustManager().resetIdentity()
        app.commonComponent.incomingAuthorizer().clearFirstContact()
        stateVersionFlow.update { it + 1 }
        log("ControlPlane", "reset-identity -> $shortId")
        HttpResponse(200, jsonOk(""" "deviceId":${jsonString(shortId)} """))
      }

      request.method == "POST" && path == "/refresh-permissions" ->
        action("refresh-permissions") { it.refreshPermissions() }

      request.method == "POST" && path == "/notification/dismiss" -> {
        val ctrl = controller ?: error("DiscoveryController not bound")
        val id = body.int("id") ?: return HttpResponse(400, jsonError("missing id"))
        if (ctrl.screenStateFlow.value.notifications.none { it.id == id }) {
          return HttpResponse(404, jsonError("unknown notification id"))
        }
        ctrl.onNotificationDismissed(id)
        stateVersionFlow.update { it + 1 }
        HttpResponse(200, jsonOk(""" "action":"notification-dismiss" """))
      }

      request.method == "POST" && path == "/notification/pair" -> {
        val ctrl = controller ?: error("DiscoveryController not bound")
        val id = body.int("id") ?: return HttpResponse(400, jsonError("missing id"))
        if (ctrl.screenStateFlow.value.notifications.none { it.id == id }) {
          return HttpResponse(404, jsonError("unknown notification id"))
        }
        ctrl.onNotificationPair(id)
        stateVersionFlow.update { it + 1 }
        HttpResponse(200, jsonOk(""" "action":"notification-pair" """))
      }

      request.method == "POST" && path == "/incoming/dismiss" -> {
        val ctrl = controller ?: error("DiscoveryController not bound")
        val receiveId = body.int("receiveId") ?: return HttpResponse(400, jsonError("missing receiveId"))
        if (receiveId !in ctrl.screenStateFlow.value.receivingMessages) {
          return HttpResponse(404, jsonError("unknown receiveId"))
        }
        ctrl.onCardDismissed(receiveId)
        stateVersionFlow.update { it + 1 }
        HttpResponse(200, jsonOk(""" "action":"incoming-dismiss" """))
      }

      request.method == "POST" && path == "/incoming/open" -> {
        val ctrl = controller ?: error("DiscoveryController not bound")
        val receiveId = body.int("receiveId") ?: return HttpResponse(400, jsonError("missing receiveId"))
        val update = ctrl.screenStateFlow.value.receivingMessages[receiveId]
          ?: return HttpResponse(404, jsonError("unknown receiveId"))
        ctrl.onReceivedCardClicked(update)
        stateVersionFlow.update { it + 1 }
        HttpResponse(200, jsonOk(""" "action":"incoming-open" """))
      }

      request.method == "POST" && path == "/pairing-dialog/dismiss" -> {
        val ctrl = controller ?: error("DiscoveryController not bound")
        ctrl.dismissPairingDialog()
        stateVersionFlow.update { it + 1 }
        HttpResponse(200, jsonOk(""" "action":"pairing-dialog-dismiss" """))
      }

      request.method == "POST" && path == "/report-problem" -> {
        val app = klardrop ?: error("not bound")
        val description = body.string("description")
        when {
          description.isNullOrBlank() -> HttpResponse(400, jsonError("missing or empty description"))
          description.length > 10_000 -> HttpResponse(413, jsonError("description too large"))
          else -> {
            val name = body.string("name")
            val email = body.string("email")
            val outcome = withContext(app.commonComponent.coroutines().ioDispatcher) {
              CrashReporter.reportUserFeedback(description, name, email)
            }
            val outcomeStr = when (outcome) {
              ReportOutcome.Sent -> "sent"
              ReportOutcome.Disabled -> "disabled"
              ReportOutcome.Failed -> "failed"
            }
            HttpResponse(200, jsonOk(""" "outcome":${jsonString(outcomeStr)} """))
          }
        }
      }

      request.method == "POST" && path == "/update/check" -> {
        val app = klardrop
        val checker = updateCheckerProvider?.invoke() ?: app?.commonComponent?.updateChecker() ?: error("not bound")
        checker.checkNow()
        stateVersionFlow.update { it + 1 }
        HttpResponse(200, jsonOk(""" "action":"check" """))
      }

      request.method == "POST" && path == "/update/apply" -> {
        val app = klardrop
        val checker = updateCheckerProvider?.invoke() ?: app?.commonComponent?.updateChecker() ?: error("not bound")
        val coroutines = coroutinesProvider?.invoke() ?: app?.commonComponent?.coroutines() ?: error("not bound")
        val force = (body["force"] as? JsonPrimitive)?.booleanOrNull == true
        if (activeTransfersFlow.value.isNotEmpty() && !force) {
          HttpResponse(409, jsonError("active transfers in progress"))
        } else if (checker.install.value !is InstallProgress.Ready) {
          HttpResponse(400, jsonError("update is not staged"))
        } else {
          log("ControlPlane", "action apply update")
          coroutines.appScope.launch(coroutines.ioDispatcher) {
            checker.applyUpdate()
            stateVersionFlow.update { it + 1 }
          }
          HttpResponse(200, jsonOk(""" "action":"apply" """))
        }
      }

      else -> HttpResponse(404, jsonError("unknown ${request.method} $path"))
    }
  }

  private fun action(name: String, block: (DiscoveryController) -> Unit): HttpResponse {
    val ctrl = controller ?: error("DiscoveryController not bound")
    log("ControlPlane", "action $name")
    block(ctrl)
    stateVersionFlow.update { it + 1 }
    return HttpResponse(200, jsonOk(""" "action":${jsonString(name)} """))
  }

  /**
   * `POST /share`: accepts exactly one payload kind (files, text or clipboard), records the
   * request with per-item outcomes, and answers as soon as the transfer is queued. The response
   * is never a delivery claim — clients read the outcome from `GET /transfers?id=`.
   */
  private suspend fun handleShareRequest(body: JsonObject): HttpResponse {
    val ctrl = controller ?: error("DiscoveryController not bound")
    val app = klardrop ?: error("not bound")
    val deviceId = body.string("deviceId")?.trim()?.takeIf { it.isNotEmpty() }
      ?: return HttpResponse(400, jsonError("missing deviceId"))
    val paths = (body["paths"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    // A blank payload is not a payload: sending it would put an empty bubble on the peer's
    // screen and still answer "queued". Matches the clipboard branch, which refuses an empty
    // read. The raw value still counts as a *kind*, so the refusal names what was wrong.
    val rawText = body.string("text")
    val text = rawText?.takeIf { it.isNotBlank() }
    val clipboard = body.boolean("clipboard") == true
    val kinds = buildList {
      if (paths != null) add(ShareKind.FILES)
      if (rawText != null) add(ShareKind.TEXT)
      if (clipboard) add(ShareKind.CLIPBOARD)
    }
    log("ControlPlane", "action share kind=${kinds.joinToString(",")}")
    return when {
      kinds.isEmpty() ->
        HttpResponse(400, jsonError("missing payload: exactly one of paths, text or clipboard"))
      kinds.size > 1 ->
        HttpResponse(400, jsonError("mixed payload: exactly one of paths, text or clipboard"))
      kinds[0] == ShareKind.FILES -> submitFileShare(ctrl, deviceId, paths.orEmpty())
      kinds[0] == ShareKind.TEXT ->
        if (text.isNullOrBlank()) HttpResponse(400, jsonError("text is empty"))
        else submitTrackedShare(ctrl, deviceId, ShareKind.TEXT, text)
      else -> {
        val clipboardText = app.commonComponent.clipboardManager().read().trim()
        if (clipboardText.isEmpty()) {
          HttpResponse(400, jsonError("clipboard is empty"))
        } else {
          submitTrackedShare(ctrl, deviceId, ShareKind.CLIPBOARD, clipboardText)
        }
      }
    }
  }

  /**
   * Prepares every submitted path up front so one that cannot be sent is reported as a failed
   * item instead of vanishing or being queued for a peer that would never receive it. The send
   * runs on the app scope, after the response body is built, so the client can only ever read a
   * submission outcome out of this response.
   */
  private suspend fun submitFileShare(
    ctrl: DiscoveryController,
    deviceId: String,
    paths: List<String>,
  ): HttpResponse {
    val submitted = paths.filter { it.isNotBlank() }
    if (submitted.isEmpty()) return HttpResponse(400, jsonError("empty paths"))
    // Bounded before any per-path work: the registry caps the number of REQUESTS, not the items
    // inside one, so an uncapped array would let a single call defeat the whole retention policy
    // and queue an unbounded sequential send nobody can cancel.
    if (submitted.size > MAX_SHARE_PATHS) {
      return HttpResponse(
        400,
        jsonError("too many paths: at most $MAX_SHARE_PATHS per request, got ${submitted.size}"),
      )
    }
    val io = klardrop?.commonComponent?.coroutines()?.ioDispatcher ?: error("not bound")
    val prepared = ctrl.prepareOutgoingFiles(submitted.map { platformFileFromPath(it) })
    // A path that is not a readable regular file can never be streamed, so it is refused here
    // rather than queued: the peer would wait for bytes that do not exist.
    val sendable = prepared.map { entry ->
      val readable = entry.error == null && withContext(io) {
        SystemFileSystem.metadataOrNull(Path(entry.path))?.isRegularFile == true
      }
      entry to readable
    }
    val requestId = registerShareRequest(
      deviceId,
      ShareKind.FILES,
      sendable.map { (entry, readable) ->
        ShareItem(
          // An id implies a trackable transfer: an item that will never be sent has none, so a
          // client polling `transfers --id` is never handed a transfer that can never progress.
          transferId = if (readable) entry.message?.id?.toString() else null,
          path = entry.path,
          fileName = entry.fileName ?: entry.path.substringAfterLast('/').substringAfterLast('\\'),
          totalSize = entry.message?.fileSize ?: 0L,
          status = if (readable) ShareStatus.QUEUED else ShareStatus.FAILED,
          error = entry.error ?: if (readable) null else "no readable file at ${entry.path}",
        )
      },
    )
    val payload = shareAcceptedJson(requestId)
    val scope = klardrop?.commonComponent?.coroutines()?.appScope ?: error("not bound")
    scope.launch {
      ctrl.startPreparedFiles(
        deviceId,
        sendable.mapNotNull { (entry, readable) -> if (readable) entry else null },
      )
    }
    stateVersionFlow.update { it + 1 }
    return HttpResponse(200, payload)
  }

  private suspend fun submitTrackedShare(
    ctrl: DiscoveryController,
    deviceId: String,
    kind: String,
    text: String,
  ): HttpResponse {
    var payload: String? = null
    ctrl.startTrackedTextSend(deviceId, text) { transfer ->
      val requestId = registerShareRequest(
        deviceId,
        kind,
        listOf(ShareItem(transferId = transfer.id, path = null, fileName = null, totalSize = 0L)),
      )
      // Built before the transfer becomes observable, so this response can only ever report the
      // submission and never an outcome that raced in behind it.
      payload = shareAcceptedJson(requestId)
    }
    stateVersionFlow.update { it + 1 }
    return HttpResponse(200, payload ?: error("share request was not registered"))
  }

  private suspend fun registerShareRequest(
    deviceId: String,
    kind: String,
    items: List<ShareItem>,
  ): String {
    val record = ShareRegistry.register(deviceId, kind, items)
    items.forEach { item ->
      item.transferId?.let { ShareRegistry.bind(it, record.requestId) }
    }
    return record.requestId
  }

  private suspend fun shareAcceptedJson(requestId: String): String {
    val record = ShareRegistry.get(requestId) ?: error("share request $requestId vanished")
    return buildJsonObject {
      put("ok", true)
      put("action", "share")
      put("requestId", record.requestId)
      put("deviceId", record.deviceId)
      put("kind", record.kind)
      put("status", record.status)
      putJsonArray("items") { record.items.forEach { add(shareItemJson(it)) } }
    }.toString()
  }

  private fun shareRequestJson(record: ShareRequestRecord): JsonObject = buildJsonObject {
    put("requestId", record.requestId)
    put("deviceId", record.deviceId)
    put("kind", record.kind)
    put("status", record.status)
    put("createdAt", record.createdAt)
    put("updatedAt", record.updatedAt)
    putJsonArray("items") { record.items.forEach { add(shareItemJson(it)) } }
  }

  private fun shareItemJson(item: ShareItem): JsonObject = buildJsonObject {
    putNullable("transferId", item.transferId)
    putNullable("path", item.path)
    putNullable("fileName", item.fileName)
    put("totalSize", item.totalSize)
    put("transferredSize", item.transferredSize)
    put("status", item.status)
    putNullable("error", item.error)
  }

  private fun generateToken(): String {
    val bytes = Random.Default.nextBytes(16)
    return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
  }

  private fun parseBody(raw: String): JsonObject {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return JsonObject(emptyMap())
    return json.parseToJsonElement(trimmed) as? JsonObject
      ?: error("body must be a JSON object")
  }

  private fun requireDeviceId(body: JsonObject): String =
    body.string("deviceId") ?: error("missing deviceId")

  private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

  private fun JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull

  private fun JsonObject.long(key: String): Long? =
    this[key]?.jsonPrimitive?.longOrNull

  private fun JsonObject.boolean(key: String): Boolean? =
    this[key]?.jsonPrimitive?.booleanOrNull

  private suspend fun snapshotState(): String {
    val app = klardrop
    val ctrl = controller
    val info = app?.commonComponent?.applicationInfo()
    val self = app?.commonComponent?.currentDeviceProvider()?.get()
    val state = ctrl?.screenStateFlow?.value
    val trusted = app?.commonComponent?.trustManager()?.getTrustedDevices().orEmpty()
    return buildJsonObject {
      put("ok", true)
      put("version", stateVersionFlow.value)
      putJsonObject("self") {
        put("deviceId", self?.shortDeviceId ?: "")
        put("deviceName", self?.deviceName ?: "")
        put("osType", self?.osType?.name ?: "")
        put("deviceType", self?.deviceType?.name ?: "")
      }
      putJsonObject("protocols") {
        put("klardrop", info?.enableKlardropServer ?: false)
        put("nearby", info?.enableNearbyServer ?: false)
        put("ble", info?.enableBle ?: false)
      }
      putJsonObject("settings") {
        put("backgroundDiscoveryEnabled", ctrl?.backgroundDiscoveryEnabled?.value ?: false)
        put("supportsBackgroundDiscovery", ctrl?.supportsBackgroundDiscovery ?: false)
      }
      putJsonArray("devices") {
        state?.devices?.forEach { device ->
          add(
            buildJsonObject {
              put("deviceId", device.deviceId)
              put("deviceName", device.deviceName)
              put("deviceType", device.deviceType.name)
              put("trustStatus", device.trustStatus.label())
              put("reachability", device.reachability.label())
              put("hasUnread", device.hasUnreadMessages)
              put("unreadCount", unreadCountsFlow.value[device.deviceId] ?: 0L)
              putNullable("pairingError", device.pairingError)
              putJsonArray("connectionTypes") {
                device.connectionTypes.forEach { add(JsonPrimitive(it.name)) }
              }
            },
          )
        }
      }
      putJsonArray("trustedIds") {
        trusted.forEach { add(JsonPrimitive(it.deviceId)) }
      }
      val dialog = state?.pairingDialogState
      if (dialog == null) {
        put("pairingDialog", JsonNull)
      } else {
        putJsonObject("pairingDialog") {
          put("deviceId", dialog.deviceId)
          put("deviceName", dialog.deviceName)
          put("isError", dialog.isError)
          putNullable("errorMessage", dialog.errorMessage)
        }
      }
      putJsonArray("incoming") {
        state?.receivingMessages?.forEach { (id, update) ->
          val files = update.messages.filterIsInstance<FileMessage>()
          val text = update.messages.filterIsInstance<TextMessage>().firstOrNull()?.text
          add(
            buildJsonObject {
              put("receiveId", id)
              putNullable("deviceId", update.device?.deviceId)
              putNullable("deviceName", update.device?.name)
              putNullable("status", update.status::class.simpleName)
              put("pendingAuth", update.status is ReceiveMessageStatus.PendingAuthorization)
              put("fileCount", files.size)
              putJsonArray("fileNames") { files.take(10).forEach { add(JsonPrimitive(it.fileName)) } }
              put("totalSize", files.sumOf { it.fileSize })
              putNullable("text", text?.take(500))
            },
          )
        }
      }
      putJsonArray("notifications") {
        state?.notifications?.forEach { n ->
          add(
            buildJsonObject {
              put("id", n.id)
              putNullable("type", n::class.simpleName)
              when (n) {
                is UiNotification.PeerRevokedTrust -> {
                  put("deviceId", n.deviceId)
                  put("deviceName", n.deviceName)
                }
              }
            },
          )
        }
      }
      putJsonArray("transfers") {
        activeTransfersFlow.value.values.forEach { t ->
          add(
            buildJsonObject {
              put("id", t.id)
              put("deviceId", t.deviceId)
              put("fileName", t.fileName)
              put("totalSize", t.totalSize)
              put("transferredSize", t.transferredSize)
              put("isSender", t.isSender)
              put("phase", t.phase)
            },
          )
        }
      }
      val qrShareState = app?.commonComponent?.qrShareSession()?.state?.value
      putJsonObject("qrShare") {
        when (qrShareState) {
          is QrShareState.QrVisible -> {
            put("active", true)
            put("url", qrShareState.url)
            putNullable("expiresAt", qrShareStartedAtMillis?.plus(qrShareCertValidityMillis))
            put("downloadCount", 0)
            putJsonArray("downloads") {}
          }
          is QrShareState.Serving -> {
            put("active", true)
            put("url", qrShareState.url)
            putNullable("expiresAt", qrShareStartedAtMillis?.plus(qrShareCertValidityMillis))
            put("downloadCount", qrShareState.downloads.size)
            putJsonArray("downloads") {
              qrShareState.downloads.forEach { d ->
                add(
                  buildJsonObject {
                    put("fileName", d.fileName)
                    put("percentage", d.percentage)
                    put("bytesTransferred", d.bytesTransferred)
                    put("totalBytes", d.totalBytes)
                  },
                )
              }
            }
          }
          else -> {
            put("active", false)
            put("url", JsonNull)
            put("expiresAt", JsonNull)
            put("downloadCount", 0)
            putJsonArray("downloads") {}
          }
        }
      }

      val checker = updateCheckerProvider?.invoke() ?: app?.commonComponent?.updateChecker()
      val updateStatus = checker?.status?.value
      val installProgress = checker?.install?.value
      val isReady = installProgress is InstallProgress.Ready
      val availableVer = (updateStatus as? UpdateStatus.Available)?.version
      val err = when {
        installProgress is InstallProgress.Failed -> installProgress.message
        updateStatus is UpdateStatus.Failed -> updateStatus.message
        else -> null
      }
      val statusStr = when {
        installProgress is InstallProgress.Downloading -> "downloading"
        installProgress is InstallProgress.Ready -> "ready"
        installProgress is InstallProgress.Applying -> "applying"
        installProgress is InstallProgress.Failed -> "failed"
        updateStatus is UpdateStatus.Checking -> "checking"
        updateStatus is UpdateStatus.UpToDate -> "up_to_date"
        updateStatus is UpdateStatus.Available -> "available"
        updateStatus is UpdateStatus.Failed -> "failed"
        else -> "unknown"
      }
      putJsonObject("update") {
        put("status", statusStr)
        putNullable("version", availableVer)
        put("staged", isReady)
        putNullable("error", err)
        if (installProgress is InstallProgress.Downloading) {
          val frac = installProgress.fraction
          if (frac != null) put("fraction", frac.toDouble()) else put("fraction", JsonNull)
        }
        putNullable("currentVersion", checker?.version)
        putNullable("channel", checker?.channel)
        put("supported", checker?.supported ?: false)
        if (updateStatus is UpdateStatus.Available) {
          putNullable("notesUrl", updateStatus.notesUrl)
          put("installChannel", updateStatus.channel.displayName)
          putJsonObject("action") {
            when (val a = updateStatus.action) {
              is UpdateAction.RunCommand -> {
                put("type", "command")
                put("value", a.command)
              }
              is UpdateAction.OpenUrl -> {
                put("type", "url")
                put("value", a.url)
              }
            }
          }
        }
      }
    }.toString()
  }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) {
  if (value == null) put(key, JsonNull) else put(key, value)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Long?) {
  if (value == null) put(key, JsonNull) else put(key, value)
}

private fun TrustStatus.label(): String = when (this) {
  TrustStatus.Trusted -> "trusted"
  TrustStatus.Untrusted -> "untrusted"
  TrustStatus.Pairing -> "pairing"
  TrustStatus.Unknown -> "unknown"
}

private fun Reachability.label(): String = when (this) {
  Reachability.Reachable -> "reachable"
  Reachability.Unreachable -> "unreachable"
  Reachability.Probing -> "probing"
  Reachability.Unknown -> "unknown"
}

private fun queryParam(query: String, key: String): String? {
  if (query.isEmpty()) return null
  return query.split('&').firstNotNullOfOrNull { part ->
    val eq = part.indexOf('=')
    if (eq < 0) null
    else if (part.substring(0, eq) == key) part.substring(eq + 1) else null
  }
}

/**
 * The control-file path this host may publish a token to, or null on a platform that has no
 * local clients to hand one to.
 *
 * Fails closed: a null path anywhere but Android means there is nowhere private to put the
 * bearer token, and a loopback listener started with a null token accepts *unauthenticated*
 * requests against this device's identity and its files — strictly worse than not running at
 * all. Android is the single exception, because its harness reaches the port through
 * `adb forward`, which is already an authenticated channel.
 */
internal fun controlFilePathToPublish(): String? {
  val path = resolveControlFilePath()
  if (path == null && !controlFileOptional) {
    throw IllegalStateException(
      "no control-file location: refusing to start an unauthenticated loopback control plane",
    )
  }
  return path
}
