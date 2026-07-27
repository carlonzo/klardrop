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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that streams shared files to a peer.
 *
 * Why a service instead of just sending from the share Activity: a transfer only streams its
 * bytes *after* the receiver accepts, which can be long after the share sheet closed. The
 * `content://` read grant from the share Intent dies with the Activity, so re-opening the URI
 * from the (Activity-less) transfer coroutine throws SecurityException. By forwarding the grant
 * into this service's start Intent ([FLAG_GRANT_READ_URI_PERMISSION] + [ClipData]), the grant
 * lives for the service's lifetime, the process stays at foreground priority (won't be killed
 * mid-transfer — the share sheet can close and the transfer survives), and the user gets a
 * progress + cancel notification.
 *
 * Every file share routes through here regardless of size: a tiny file still gates on the receiver
 * accepting, so it needs the same foreground anchor as a big one. Live progress is mirrored into
 * [ActiveSends] so the (still-open) share sheet can show status off the same source.
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

  /** Number of in-flight batches; the service stops itself once this hits zero. */
  private val activeBatches = AtomicInteger(0)
  private var foregroundStarted = false

  private fun commonComponent() = applicationComponent().klardrop().commonComponent

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_CANCEL) {
      log("FileSendService", "Cancel requested; stopping transfers")
      serviceScope.cancel()
      stopForegroundAndSelf()
      return START_NOT_STICKY
    }

    ensureChannel()
    // Android requires startForeground() promptly after startForegroundService(); do it first.
    promoteToForeground(buildNotification(title = "Preparing transfer…", progress = null))

    val batch = intent?.let(::parseBatch).orEmpty()
    val deviceId = intent?.getStringExtra(EXTRA_DEVICE_ID)
    val transferId = intent?.getStringExtra(EXTRA_TRANSFER_ID)
    if (deviceId == null || batch.isEmpty()) {
      log("FileSendService", "No work in start command; stopping")
      stopIfIdle()
      return START_NOT_STICKY
    }

    activeBatches.incrementAndGet()
    serviceScope.launch {
      try {
        sendBatch(deviceId, batch, transferId)
        transferId?.let { ActiveSends.publish(it, Completed) }
      } catch (e: Throwable) {
        log("FileSendService", "Batch to $deviceId failed", e)
        transferId?.let { ActiveSends.publish(it, Error(e.message ?: "Transfer failed")) }
      } finally {
        activeBatches.decrementAndGet()
        stopIfIdle()
      }
    }
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
    // Keep WiFi at full power for the whole batch; released in finally on success OR failure.
    wifiLock.acquire()
    try {
      files.forEachIndexed { index, file ->
        log("FileSendService", "Sending ${file.name} (${file.size} bytes) to $deviceId [${index + 1}/${files.size}]")
        updateProgress(file.name, index, files.size, 0)
        // Streams via ContentResolver.openInputStream under this service's forwarded read grant.
        messenger.send(deviceId, FileMessage(file.name, file.size, file.mimeType).toSendRequest(PlatformFile(file.uri)))
          .untilCompleted()
          .collect { progress ->
            // Mirror live progress for the share sheet. Suppress per-file Completed mid-batch — the
            // batch-level terminal state is published once in onStartCommand after the whole loop.
            if (progress !is Completed) transferId?.let { ActiveSends.publish(it, progress) }
            when (progress) {
              is MessengerSendProgress.InProgress -> updateProgress(file.name, index, files.size, progress.percentage)
              is Error -> log("FileSendService", "Send of ${file.name} errored: ${progress.message}")
              Completed -> log("FileSendService", "Sent ${file.name} to $deviceId")
              // Both phases are already mirrored to the share sheet by the publish above,
              // which renders them as "Waiting for receiver to accept…". The foreground
              // notification only tracks byte progress, so there's nothing to update here.
              MessengerSendProgress.Pending,
              MessengerSendProgress.AwaitingRecipient -> {}
            }
          }
      }
    } finally {
      wifiLock.release()
    }
  }

  private fun promoteToForeground(notification: Notification) {
    if (foregroundStarted) {
      notificationManager.notify(NOTIFICATION_ID, notification)
      return
    }
    ServiceCompat.startForeground(
      this,
      NOTIFICATION_ID,
      notification,
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )
    foregroundStarted = true
  }

  private fun updateProgress(fileName: String, index: Int, total: Int, percent: Int) {
    val title = if (total > 1) "Sending $fileName (${index + 1}/$total)" else "Sending $fileName"
    notificationManager.notify(NOTIFICATION_ID, buildNotification(title, percent))
  }

  private fun buildNotification(title: String, progress: Int?): Notification {
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
      .setContentText("Sending via Klardrop")
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(openIntent)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .apply {
        if (progress == null) setProgress(0, 0, true) else setProgress(100, progress, false)
      }
      .addAction(0, "Terminate transfer", cancelIntent)
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

  private fun stopIfIdle() {
    if (activeBatches.get() <= 0) stopForegroundAndSelf()
  }

  private fun stopForegroundAndSelf() {
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    serviceScope.cancel()
    super.onDestroy()
  }

  companion object {
    private const val CHANNEL_TRANSFERS = "klardrop_transfers"
    private const val NOTIFICATION_ID = 2001
    private const val ACTION_CANCEL = "com.carlom.klardrop.action.CANCEL_FILE_SEND"

    private const val EXTRA_DEVICE_ID = "klardrop.device_id"
    private const val EXTRA_TRANSFER_ID = "klardrop.transfer_id"
    private const val EXTRA_URIS = "klardrop.uris"
    private const val EXTRA_NAMES = "klardrop.names"
    private const val EXTRA_SIZES = "klardrop.sizes"
    private const val EXTRA_MIMES = "klardrop.mimes"

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
