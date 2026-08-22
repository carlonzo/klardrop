package com.carlom.klardrop.chat

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.communication.Client
import com.carlom.klardrop.common.communication.ConnectionsPool
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.Reachability
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSendRequest
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.communication.untilCompleted
import com.carlom.klardrop.common.persistence.ChatMessage
import com.carlom.klardrop.common.features.ClipboardManager
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.PlatformFileSystem
import com.carlom.klardrop.common.utils.log
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

class DeviceChatViewModel(
  private val deviceId: String,
  val messageRepository: MessageRepository,
  private val messenger: Messenger,
  private val messageReceiver: MessageReceiver,
  private val client: Client,
  private val connectionsPool: ConnectionsPool,
  private val coroutines: Coroutines,
  private val fileManager: FileManager,
  private val platformFileSystem: PlatformFileSystem,
  private val clipboardManager: ClipboardManager,
  reachabilitySource: StateFlow<Map<String, Reachability>>,
  private val clock: Clock = Clock(),
) {

  // TODO we need to dispose this viewmodel
  // newScope (rather than a raw CoroutineScope) so the scope carries the platform's last-resort
  // CoroutineExceptionHandler: an uncaught throw in a UI job aborts the process on Kotlin/Native.
  private val viewModelScope = coroutines.newScope(coroutines.mainDispatcher + SupervisorJob())

  // Rate window for [onTransferProgress]; reset by [transferIdle] so each transfer measures itself.
  private var windowStartMs = 0L
  private var windowStartBytes = 0L

  private val _uiState = MutableStateFlow(ChatUiState())
  val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

  val reachability: StateFlow<Reachability> =
    reachabilitySource
      .map { it[deviceId] ?: Reachability.Unknown }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Reachability.Unknown)


  val messages: StateFlow<List<ChatMessage>> =
    messageRepository.getMessagesForDevice(deviceId, limit = 100)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  /**
   * Most recent receive update from this device that's awaiting the user's accept/reject
   * decision. Null when nothing is pending.
   *
   * Reads [MessageReceiver.latestUpdates] — the aggregated, retained per-device receive
   * state that mirrors the LIVE producer flow. Previously this called
   * [MessageReceiver.onReceiveMessage], which mints a brand-new flow that the receive
   * pipeline never writes to, so the banner only ever appeared on the discovery/home
   * screen. [latestUpdates] holds the current value, so the prompt shows here regardless
   * of whether the chat screen was already open when the transfer arrived.
   */
  val pendingAuth: StateFlow<ReceiveMessageUpdate?> =
    messageReceiver.latestUpdates
      .map { updates -> updates[deviceId]?.takeIf { it.status is ReceiveMessageStatus.PendingAuthorization } }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  init {
    // Mark messages as read when chat screen is opened
    viewModelScope.launch {
      messageRepository.markMessagesAsRead(deviceId)
    }

    // Dial-on-open (docs/connection-review.md F1/F11): opening the chat screen is a strong
    // "I want to talk to this device now" signal, but by itself it never dialed anything — only
    // EagerReachabilityConnector (on discovery) and Messenger.send (on user send) do. If ERC's
    // last probe failed, the 5s failure cooldown can leave the user staring at "Connecting" for
    // no reason even though nothing else is stopping a fresh dial from succeeding. One
    // fire-and-forget nudge per screen open — no retry loop here, Client.connectTo's per-device
    // dial coalescing (F8) makes racing the eager connector's own probe safe.
    viewModelScope.launch {
      if (!connectionsPool.isAvailable(deviceId)) {
        runCatching { client.connectTo(deviceId) }
          .onFailure { log("DeviceChatViewModel", "Dial-on-open failed for $deviceId", it) }
      }
    }

    // Mirror this device's incoming file-transfer progress into ui state so the chat bubble can
    // show live progress instead of the DB-derived value, which is only ever 0 or done (see
    // sendFileMessage below, and docs/connection-review.md F14). [latestUpdates] is keyed by
    // device id, same source [pendingAuth] above reads.
    //
    // ponytail: one concurrent transfer per device assumed. [ReceiveMessageStatus.Progress] is
    // itself per-device (not per-transfer), so a single fraction is the right shape here, not a
    // map — there is nothing finer-grained upstream to key by.
    viewModelScope.launch {
      messageReceiver.latestUpdates.collect { updates ->
        when (val status = updates[deviceId]?.status) {
          // Deliberately no [ReceiveMessageStatus.Started] branch: that status is minted for
          // every inbound message including plain text, and it has no terminal counterpart if
          // opening the sink fails — treating it as "transfer in flight" would flash the status
          // strip on text receives and could pin it on permanently. [beginReceive] follows it
          // with Progress(0) immediately anyway.
          is ReceiveMessageStatus.Progress -> {
            val percentage = status.messages.lastOrNull()?.second
            if (percentage != null) {
              onTransferProgress(percentage / 100f, status.bytesTransferred, status.totalBytes)
            }
          }
          is ReceiveMessageStatus.Completed, is ReceiveMessageStatus.Failed -> {
            transferIdle()
          }
          else -> Unit
        }
      }
    }
  }

  fun sendTextMessage(text: String) {
    if (text.isBlank()) return

    viewModelScope.launch {
      try {
        _uiState.value = _uiState.value.copy(error = null)

        val textMessage = TextMessage(text = text)

        // Persistence (SENDING -> SENT/FAILED) is owned by Messenger.send: it inserts a
        // single row up front and flips it to its terminal state exactly once, regardless of
        // how many times the retry loop re-attempts the transport. The VM must not persist
        // anything itself here — doing so would render a second, duplicate bubble alongside
        // the row Messenger.send already owns (see docs/connection-review.md F12/F13).
        val finalStatus = messenger.send(deviceId, textMessage.toSimpleSendRequest())
          .untilCompleted()
          .lastOrNull()

        if (finalStatus is MessengerSendProgress.Error) {
          _uiState.update {
            it.copy(error = "Failed to send message: ${finalStatus.message}")
          }
        }
      } catch (e: Exception) {
        _uiState.value = _uiState.value.copy(
          error = "Failed to send message: ${e.message}"
        )
      }
    }
  }

  fun onDispose() {
    // Cancel any coroutines started by this ViewModel
    viewModelScope.cancel()
  }

  fun openFileClicked(filePath: String) {
    viewModelScope.launch {
      try {
        val success = fileManager.openFile(filePath)
        if (!success) {
          _uiState.update {
            it.copy(error = "Unable to open file. No suitable app found.")
          }
        }
      } catch (e: Exception) {
        log("DeviceChatViewModel", "Failed to open file at path $filePath", e)

        _uiState.update {
          it.copy(error = "Failed to open file: ${e.message}")
        }
      }
    }
  }

  fun copyText(text: String) {
    if (text.isEmpty()) return
    clipboardManager.write(text)
    _uiState.update { it.copy(notice = "Copied to clipboard") }
  }

  /** Send whatever text is currently on the clipboard (used by the attachment chooser's Paste action). */
  fun pasteFromClipboard() {
    val text = clipboardManager.read().trim()
    if (text.isEmpty()) {
      _uiState.update { it.copy(notice = "Clipboard is empty") }
      return
    }
    sendTextMessage(text)
  }

  fun openUrlClicked(url: String) {
    viewModelScope.launch {
      try {
        val success = fileManager.openUrl(url)
        if (!success) {
          _uiState.update {
            it.copy(error = "Unable to open link. No handler found.")
          }
        }
      } catch (e: Exception) {
        log("DeviceChatViewModel", "Failed to open url $url", e)
        _uiState.update {
          it.copy(error = "Failed to open link: ${e.message}")
        }
      }
    }
  }

  fun sendFiles(files: List<PlatformFile>) {
    if (files.isEmpty()) return

    viewModelScope.launch {
      try {
        // Feedback from the instant the user hits send. Nothing is on screen yet — the chat
        // bubble only appears once FileMessageHandler inserts its rows, which happens after
        // the connection has been dialed and established, and that gap is seconds of dead UI
        // on a cold link. Mark the transfer active immediately so the screen says so.
        _uiState.update { it.copy(error = null).transferring(fraction = null, statusText = "Preparing to send…") }

        // Send each file - persistence is handled by FileMessageHandler
        files.forEach { file ->
          val fileData = runCatching { platformFileSystem.getResolvedFileData(file) }
            .onFailure {
              log("DeviceChatViewModel", "Unable to resolve file at path $file. File cannot be sent!", it)
            }
            .getOrNull()

          if (fileData != null) {
            val fileMessage = FileMessage(
              fileData.fileName,
              fileData.fileSize,
              fileData.mimeType
            )

            // Send the file - handler will create DB records and manage transfer
            sendFileMessage(fileMessage.toSendRequest(file))
          }
        }

      } catch (e: Exception) {
        _uiState.value = _uiState.value.copy(
          error = "Failed to send files: ${e.message}"
        )
      } finally {
        // sendFileMessage clears the transfer state per file, but the optimistic "Preparing"
        // state set above outlives every path that never reaches it — an unresolvable file,
        // an empty resolve, or a throw before the first send. Without this the banner would
        // stay up forever.
        transferIdle()
      }
    }
  }

  /**
   * Fold one byte-counter sample into ui state and, once the transfer has been running long
   * enough to have a trustworthy rate, into [TransferStats].
   *
   * The rate window starts at the first sample that actually carries bytes, not at the first
   * sample overall: a send emits InProgress(0) before it blocks on the recipient's accept, and
   * anchoring the window there would divide the bytes by however many seconds a human took to
   * tap Accept. `bytes <= windowStartBytes` keeps re-anchoring until bytes start moving.
   *
   * ponytail: plain average over the window, no EWMA — LAN throughput is steady enough that a
   * smoothed rate would look identical. Revisit if the ETA visibly jitters on flaky links.
   */
  private fun onTransferProgress(fraction: Float, bytes: Long, total: Long) {
    val now = clock.currentTimeMillis()
    if (windowStartMs == 0L || bytes <= windowStartBytes) {
      windowStartMs = now
      windowStartBytes = bytes
    }
    val stats = transferStatsOrNull(
      bytes = bytes,
      total = total,
      windowStartBytes = windowStartBytes,
      elapsedMs = now - windowStartMs,
    )
    _uiState.update { it.transferring(fraction = fraction, stats = stats) }
  }

  private fun transferIdle() {
    windowStartMs = 0L
    windowStartBytes = 0L
    _uiState.update { it.transferIdle() }
  }

  fun clearError() {
    _uiState.value = _uiState.value.copy(error = null)
  }

  fun clearNotice() {
    _uiState.update { it.copy(notice = null) }
  }

  fun retryFileTransfer(failedFileTransferId: Long) {
    viewModelScope.launch {
      val row = messageRepository.getFileTransferById(failedFileTransferId).first() ?: run {
        _uiState.update { it.copy(error = "Could not find the original file to retry.") }
        return@launch
      }
      val sourcePath = row.file_path
      if (sourcePath.isBlank()) {
        _uiState.update { it.copy(error = "The original file path is no longer available.") }
        return@launch
      }
      val file = runCatching { PlatformFile(Path(sourcePath)) }.getOrNull()
      if (file == null) {
        _uiState.update { it.copy(error = "Could not reopen the original file.") }
        return@launch
      }
      sendFiles(listOf(file))
    }
  }

  /**
   * Send a file message — persistence is handled by FileMessageHandler.
   *
   * Collects every [MessengerSendProgress] (not just the terminal one via `.lastOrNull()`) so the
   * live [MessengerSendProgress.InProgress] percentages reach ui state as they're emitted — the
   * chat bubble reads a stale, DB-derived transferred_size otherwise (docs/connection-review.md
   * F14: the bar sat at 0% for the whole transfer then jumped straight to done).
   *
   * ponytail: same one-transfer-per-device ceiling as the incoming path above — [fileTransferProgress]
   * is a single value, not a map keyed by file transfer id, because the sender-side row id created
   * deep inside FileMessageHandler is never surfaced back to this call site to key by.
   */
  private suspend fun sendFileMessage(sendRequest: SendMessageRequest) {
    var finalStatus: MessengerSendProgress? = null

    messenger.send(deviceId, sendRequest)
      .untilCompleted()
      .collect { progress ->
        finalStatus = progress
        when (progress) {
          is MessengerSendProgress.InProgress ->
            onTransferProgress(progress.percentage / 100f, progress.bytesTransferred, progress.totalBytes)
          is MessengerSendProgress.AwaitingRecipient ->
            _uiState.update { it.transferring(fraction = null, statusText = "Waiting for the recipient to accept…") }
          MessengerSendProgress.Pending ->
            _uiState.update { it.transferring(fraction = null, statusText = "Connecting…") }
          is MessengerSendProgress.Completed, is MessengerSendProgress.Error ->
            transferIdle()
        }
      }

    val error = finalStatus as? MessengerSendProgress.Error
    if (error != null) {
      _uiState.update {
        it.copy(error = "Failed to send message: ${error.message}")
      }
    }
  }
}

data class ChatUiState(
  val error: String? = null,
  val notice: String? = null,
  /**
   * Live fraction (0f..1f) of the in-flight file transfer for this device, from whichever
   * direction is currently active (send or receive). Null means "no fraction to show" — which
   * is NOT the same as "nothing is happening": check [fileTransferActive] for that. A transfer
   * is active-but-fractionless while connecting, while the sender waits for the recipient to
   * accept, and while the receiver opens its sink. See [DeviceChatViewModel.sendFileMessage]
   * and the receive-side collector in [DeviceChatViewModel.init].
   */
  val fileTransferProgress: Float? = null,
  /**
   * True while a file transfer for this device is in flight in either direction, including the
   * phases that have no percentage yet. UIs render an indeterminate bar when this is true and
   * [fileTransferProgress] is null — the old behaviour (fraction-only) painted those phases as
   * a motionless 0% bar, which read as "nothing is happening".
   */
  val fileTransferActive: Boolean = false,
  /**
   * Short human label for the current non-streaming phase ("Connecting…", "Waiting for the
   * recipient to accept…"), or null once bytes are actually flowing. Surfaced as a banner so
   * there is visible feedback even before the message bubble exists — the bubble is only
   * created once the transfer's DB rows are inserted, which is after the connection is up.
   */
  val fileTransferStatusText: String? = null,
  /**
   * Throughput/ETA for the in-flight transfer, or null while there isn't one — or while it has
   * been running for less than [TRANSFER_STATS_MIN_ELAPSED_MS]. Small files finish inside that
   * window and never show stats, which is the point: a rate and an ETA on a transfer that is
   * already over is noise.
   */
  val transferStats: TransferStats? = null,
)

/** Live throughput figures for a file transfer. All byte counts are raw bytes. */
data class TransferStats(
  val bytesTransferred: Long,
  val totalBytes: Long,
  val bytesPerSecond: Long,
  /** Null when the rate is 0 (nothing has moved in the window) so no ETA can be projected. */
  val etaSeconds: Long?,
)

/**
 * Pure half of [DeviceChatViewModel.onTransferProgress]: given the current byte count and the
 * rate window, either the figures to show or null (window too young, or no known total).
 */
internal fun transferStatsOrNull(
  bytes: Long,
  total: Long,
  windowStartBytes: Long,
  elapsedMs: Long,
): TransferStats? {
  if (elapsedMs < TRANSFER_STATS_MIN_ELAPSED_MS || total <= 0) return null
  val bytesPerSecond = (bytes - windowStartBytes) * 1000 / elapsedMs
  return TransferStats(
    bytesTransferred = bytes,
    totalBytes = total,
    bytesPerSecond = bytesPerSecond,
    etaSeconds = if (bytesPerSecond > 0) (total - bytes) / bytesPerSecond else null,
  )
}

/**
 * How long a transfer must have been streaming before its stats line appears. Keeps the card
 * uncluttered for the small files that make up most sends.
 */
internal const val TRANSFER_STATS_MIN_ELAPSED_MS = 2_000L

/** Mark a transfer as in flight. [fraction] null = active but no percentage to show yet. */
private fun ChatUiState.transferring(
  fraction: Float?,
  statusText: String? = null,
  stats: TransferStats? = null,
) = copy(
  fileTransferProgress = fraction,
  fileTransferActive = true,
  fileTransferStatusText = statusText,
  transferStats = stats,
)

/** Clear every trace of an in-flight transfer. */
private fun ChatUiState.transferIdle() = copy(
  fileTransferProgress = null,
  fileTransferActive = false,
  fileTransferStatusText = null,
  transferStats = null,
)
