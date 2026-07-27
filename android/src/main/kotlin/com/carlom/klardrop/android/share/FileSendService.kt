package com.carlom.klardrop.android.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.carlom.klardrop.android.MainActivity
import com.carlom.klardrop.android.applicationComponent
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.MessengerSendProgress.Completed
import com.carlom.klardrop.common.communication.MessengerSendProgress.Error
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.toSendRequest
import com.carlom.klardrop.common.communication.untilCompleted
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * Foreground service that keeps outbound transfers alive, in two roles.
 *
 * **1. It runs share-sheet batches itself.** A transfer only streams its bytes *after* the receiver
 * accepts, which can be long after the share sheet closed. The `content://` read grant from the
 * share Intent dies with the Activity, so re-opening the URI from the (Activity-less) transfer
 * coroutine throws SecurityException. By forwarding the grant into this service's start Intent
 * ([Intent.FLAG_GRANT_READ_URI_PERMISSION] + [ClipData]), the grant lives for the service's
 * lifetime and the transfer survives the sheet closing. Live progress is mirrored into
 * [ActiveSends] so the (still-open) share sheet can show status off the same source.
 *
 * **2. It anchors transfers running elsewhere in the process.** Sends started from the app itself —
 * the chat screen, the discovery screen — run in `Messenger`'s own scope, which nothing in the app
 * cancels. But the *platform* will happily freeze or kill the process once no Activity is on
 * screen, taking the socket with it. `Messenger.send` therefore registers every file send in
 * [OutgoingTransfers] via [AndroidOutgoingTransferAnchor], which starts this service in
 * [ACTION_ANCHOR] mode: no work of its own, just the foreground component the process needs to
 * survive being backgrounded mid-transfer.
 *
 * Either way the service stays up while [OutgoingTransfers] is non-empty or a batch is in flight,
 * holds a [WifiTransferLock] so the radio doesn't power-save mid-stream, renders the registry into
 * one ongoing notification, and stops itself shortly after the last transfer drains.
 *
 * Every file share routes through here regardless of size: a tiny file still gates on the receiver
 * accepting, so it needs the same foreground anchor as a big one.
 */
class FileSendService : Service() {

  data class SendFile(val uri: Uri, val name: String, val size: Long, val mimeType: String)

  private val coroutines: Coroutines by lazy { commonComponent().coroutines() }
  private val messenger: Messenger by lazy { commonComponent().messenger() }
  private val serviceScope: CoroutineScope by lazy {
    CoroutineScope(SupervisorJob() + coroutines.ioDispatcher)
  }
  private val wifiLock by lazy { WifiTransferLock(this) }
  private val notificationManager by lazy {
    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
  }

  /** Number of in-flight batches this service is running itself; see [isIdle]. */
  private val activeBatches = AtomicInteger(0)

  /** Live batch coroutines, so [ACTION_CANCEL] can stop them without tearing down [serviceScope]. */
  private val batchJobs: MutableList<Job> = Collections.synchronizedList(mutableListOf())

  /**
   * Extra context for the notification while a multi-file batch runs ("2 of 5"). The registry only
   * knows about one file at a time — the batch sends sequentially — so the position comes from here.
   */
  @Volatile
  private var batchContext: String? = null

  private var foregroundStarted = false
  private var wifiLockHeld = false
  private var renderJob: Job? = null

  /** Volatile + [refreshIdleTimer]'s lock: armed from the main thread and from batch coroutines. */
  @Volatile
  private var idleJob: Job? = null

  private fun commonComponent() = applicationComponent().klardrop().commonComponent

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_CANCEL) {
      log("FileSendService", "Cancel requested; stopping batch transfers")
      // Only the batches this service owns. Transfers running in the app process are not ours to
      // kill, and the notification only offers this action while a batch is actually in flight.
      batchJobs.toList().forEach { it.cancel() }
      refreshIdleTimer()
      return START_NOT_STICKY
    }

    ensureChannel()
    // Android requires startForeground() promptly after startForegroundService(); do it first.
    promoteToForeground()
    acquireWifiLock()
    startRendering()

    if (intent?.action == ACTION_ANCHOR) {
      // Nothing to run: the transfer lives in the app process. This start exists purely to give
      // that process a foreground component for the duration.
      refreshIdleTimer()
      return START_NOT_STICKY
    }

    val batch = intent?.let(::parseBatch).orEmpty()
    val deviceId = intent?.getStringExtra(EXTRA_DEVICE_ID)
    val transferId = intent?.getStringExtra(EXTRA_TRANSFER_ID)
    if (deviceId == null || batch.isEmpty()) {
      log("FileSendService", "No work in start command; stopping if nothing else is running")
      refreshIdleTimer()
      return START_NOT_STICKY
    }

    activeBatches.incrementAndGet()
    // LAZY so the job is registered in batchJobs before it can possibly run: a batch that fails
    // instantly would otherwise reach the finally — and remove itself — before `job` even exists.
    val job = serviceScope.launch(start = CoroutineStart.LAZY) {
      try {
        sendBatch(deviceId, batch, transferId)
        transferId?.let { ActiveSends.publish(it, Completed) }
      } catch (e: Throwable) {
        log("FileSendService", "Batch to $deviceId failed", e)
        transferId?.let { ActiveSends.publish(it, Error(e.message ?: "Transfer failed")) }
      } finally {
        activeBatches.decrementAndGet()
        batchContext = null
        refreshIdleTimer()
      }
    }
    batchJobs.add(job)
    job.invokeOnCompletion { batchJobs.remove(job) }
    job.start()
    return START_NOT_STICKY
  }

  private fun parseBatch(intent: Intent): List<SendFile> {
    val uris: List<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableArrayListExtra(EXTRA_URIS, Uri::class.java)
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableArrayListExtra(EXTRA_URIS)
    } ?: emptyList()
    val names = intent.getStringArrayListExtra(EXTRA_NAMES) ?: arrayListOf()
    val sizes = intent.getLongArrayExtra(EXTRA_SIZES) ?: LongArray(0)
    val mimes = intent.getStringArrayListExtra(EXTRA_MIMES) ?: arrayListOf()
    return uris.mapIndexed { i, uri ->
      SendFile(
        uri = uri,
        name = names.getOrElse(i) { "file" },
        size = sizes.getOrElse(i) { 0L },
        mimeType = mimes.getOrElse(i) { "application/octet-stream" },
      )
    }
  }

  private suspend fun sendBatch(deviceId: String, files: List<SendFile>, transferId: String?) {
    files.forEachIndexed { index, file ->
      log("FileSendService", "Sending ${file.name} (${file.size} bytes) to $deviceId [${index + 1}/${files.size}]")
      batchContext = if (files.size > 1) "${index + 1} of ${files.size}" else null
      // Streams via ContentResolver.openInputStream under this service's forwarded read grant.
      // The notification entry for this file comes from OutgoingTransfers — Messenger.send
      // registers one for every file send, wherever it was started from, including here.
      messenger.send(deviceId, FileMessage(file.name, file.size, file.mimeType).toSendRequest(PlatformFile(file.uri)))
        .untilCompleted()
        .collect { progress ->
          // Mirror live progress for the share sheet. Suppress per-file Completed mid-batch — the
          // batch-level terminal state is published once in onStartCommand after the whole loop.
          if (progress !is Completed) transferId?.let { ActiveSends.publish(it, progress) }
          when (progress) {
            is Error -> log("FileSendService", "Send of ${file.name} errored: ${progress.message}")
            Completed -> log("FileSendService", "Sent ${file.name} to $deviceId")
            is MessengerSendProgress.InProgress,
            MessengerSendProgress.Pending -> {}
          }
        }
    }
  }

  /**
   * Render [OutgoingTransfers] into the ongoing notification, and shut the service down once it
   * drains. Idempotent — every start command calls it, only the first one starts the collector.
   */
  private fun startRendering() {
    if (renderJob != null) return
    renderJob = serviceScope.launch(coroutines.mainDispatcher) {
      OutgoingTransfers.state.collect { entries ->
        if (foregroundStarted) {
          notificationManager.notify(NOTIFICATION_ID, buildNotification(entries))
        }
        refreshIdleTimer()
      }
    }
  }

  private fun isIdle(): Boolean = OutgoingTransfers.isEmpty() && activeBatches.get() <= 0

  /**
   * Arm the shutdown timer when nothing is transferring, disarm it when something is. Stopping is
   * deferred by [IDLE_GRACE] because sends arrive back-to-back — a multi-file batch briefly empties
   * the registry between files, and stopping on that gap would tear down the foreground component
   * only to immediately rebuild it (which Android can refuse outright once the app is backgrounded).
   */
  @Synchronized
  private fun refreshIdleTimer() {
    idleJob?.cancel()
    if (!isIdle()) return
    idleJob = serviceScope.launch(coroutines.mainDispatcher) {
      delay(IDLE_GRACE)
      // Re-check: a transfer may have started while we waited.
      if (isIdle()) {
        log("FileSendService", "No transfers left; stopping")
        stopForegroundAndSelf()
      }
    }
  }

  private fun promoteToForeground() {
    if (foregroundStarted) return
    ServiceCompat.startForeground(
      this,
      NOTIFICATION_ID,
      buildNotification(OutgoingTransfers.state.value),
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )
    foregroundStarted = true
  }

  private fun acquireWifiLock() {
    if (wifiLockHeld) return
    // Held for the service's whole lifetime rather than per batch: the service only exists while
    // something is transferring, and in-app sends need the radio kept awake just as much.
    wifiLock.acquire()
    wifiLockHeld = true
  }

  private fun buildNotification(entries: Map<String, OutgoingTransfers.Entry>): Notification {
    val title = when (entries.size) {
      0 -> "Preparing transfer…"
      1 -> "Sending ${entries.values.first().label}"
      else -> "Sending ${entries.size} files"
    }
    val progress: Int? = when (entries.size) {
      0 -> null
      1 -> entries.values.first().percentage
      // No per-file sizes here, so a flat mean across the in-flight files. Files not yet accepted
      // count as 0 rather than dropping out, so the bar can't jump backwards as one completes.
      else -> entries.values.map { it.percentage ?: 0 }.average().roundToInt()
    }

    val openIntent = PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val cancelIntent = PendingIntent.getService(
      this, 1, Intent(this, FileSendService::class.java).setAction(ACTION_CANCEL),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(this, CHANNEL_TRANSFERS)
      .setSmallIcon(android.R.drawable.stat_sys_upload)
      .setContentTitle(title)
      .setContentText(batchContext?.let { "Sending via Klardrop — $it" } ?: "Sending via Klardrop")
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(openIntent)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .apply {
        if (progress == null) setProgress(0, 0, true) else setProgress(100, progress, false)
        // Only offered for batches this service runs. A transfer living in the app process isn't
        // ours to stop, and an action that silently did nothing would be worse than none at all.
        if (activeBatches.get() > 0) addAction(0, "Terminate transfer", cancelIntent)
      }
      .build()
  }

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    if (notificationManager.getNotificationChannel(CHANNEL_TRANSFERS) != null) return
    notificationManager.createNotificationChannel(
      NotificationChannel(CHANNEL_TRANSFERS, "File transfers", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Progress of files being sent to other devices."
      }
    )
  }

  private fun stopForegroundAndSelf() {
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    if (wifiLockHeld) {
      wifiLock.release()
      wifiLockHeld = false
    }
    serviceScope.cancel()
    super.onDestroy()
  }

  companion object {
    private const val CHANNEL_TRANSFERS = "klardrop_transfers"
    private const val NOTIFICATION_ID = 2001
    private const val ACTION_CANCEL = "com.carlom.klardrop.action.CANCEL_FILE_SEND"
    private const val ACTION_ANCHOR = "com.carlom.klardrop.action.ANCHOR_FILE_SEND"

    private const val EXTRA_DEVICE_ID = "klardrop.device_id"
    private const val EXTRA_TRANSFER_ID = "klardrop.transfer_id"
    private const val EXTRA_URIS = "klardrop.uris"
    private const val EXTRA_NAMES = "klardrop.names"
    private const val EXTRA_SIZES = "klardrop.sizes"
    private const val EXTRA_MIMES = "klardrop.mimes"

    /** How long the service lingers after the last transfer drains. See [refreshIdleTimer]. */
    private val IDLE_GRACE = 5.seconds

    /**
     * Bring the service up as a pure foreground anchor for transfers running elsewhere in the
     * process. Callers must register the transfer in [OutgoingTransfers] *first*, otherwise the
     * service can start against an empty registry and immediately stop itself again.
     *
     * Throws if Android refuses a background foreground-service start (API 31+);
     * [AndroidOutgoingTransferAnchor] is the only caller and handles that.
     */
    fun anchor(context: Context) {
      ContextCompat.startForegroundService(
        context, Intent(context, FileSendService::class.java).setAction(ACTION_ANCHOR),
      )
    }

    /**
     * Start a foreground transfer for [files], forwarding this caller's temporary read grant for
     * each URI to the service. Must be called from a foreground context (e.g. the share Activity)
     * that currently holds the grant. [transferId] keys the live-progress entry in [ActiveSends]
     * that the share sheet observes.
     */
    fun start(context: Context, deviceId: String, files: List<SendFile>, transferId: String) {
      if (files.isEmpty()) return
      val intent = Intent(context, FileSendService::class.java).apply {
        putExtra(EXTRA_DEVICE_ID, deviceId)
        putExtra(EXTRA_TRANSFER_ID, transferId)
        putParcelableArrayListExtra(EXTRA_URIS, ArrayList(files.map { it.uri }))
        putStringArrayListExtra(EXTRA_NAMES, ArrayList(files.map { it.name }))
        putExtra(EXTRA_SIZES, files.map { it.size }.toLongArray())
        putStringArrayListExtra(EXTRA_MIMES, ArrayList(files.map { it.mimeType }))
        // Forward the read grant for every URI: the flag covers the data URI and all ClipData items.
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = files.fold(null as ClipData?) { acc, f ->
          if (acc == null) ClipData.newRawUri(null, f.uri) else acc.also { it.addItem(ClipData.Item(f.uri)) }
        }
      }
      ContextCompat.startForegroundService(context, intent)
    }
  }
}
