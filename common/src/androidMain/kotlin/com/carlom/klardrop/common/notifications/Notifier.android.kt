package com.carlom.klardrop.common.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Android system-notification surface. Posts via [NotificationManager], with
 * action buttons backed by [PendingIntent]s that fire into a dynamically
 * registered [BroadcastReceiver]. The receiver translates the inbound intent
 * into a [NotificationAction] and emits it on [actions], which the running
 * app collects and routes back into the relevant flow.
 *
 * If the OS killed the app process while a notification was up, the action
 * lands at the receiver but [actions] has no live collectors — that decision
 * is dropped. Acceptable for v1; the user can re-issue from the app.
 */
public actual class Notifier(context: Context) {

  private val appContext = context.applicationContext
  private val notificationManager =
    appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  private val flow = MutableSharedFlow<NotificationAction>(
    extraBufferCapacity = 16,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  public actual val actions: Flow<NotificationAction> = flow.asSharedFlow()

  init {
    ensurePairingChannel()
    ensureIncomingChannel()
    registerActionReceiver()
  }

  public actual fun show(notification: AppNotification): Unit {
    when (notification) {
      is AppNotification.IncomingPairing -> showIncomingPairing(notification)
      is AppNotification.IncomingTransfer -> showIncomingTransfer(notification)
    }
  }

  public actual fun cancel(id: String): Unit {
    notificationManager.cancel(id, NOTIFICATION_TAG_ID)
  }

  private fun showIncomingTransfer(notification: AppNotification.IncomingTransfer) {
    val acceptIntent = actionPendingIntent(notification.id, ACTION_TRANSFER_ACCEPT, notification.deviceId)
    val rejectIntent = actionPendingIntent(notification.id, ACTION_TRANSFER_REJECT, notification.deviceId)
    val what = notification.fileName?.let { "the file \"$it\"" } ?: "a message"

    val builder = NotificationCompat.Builder(appContext, CHANNEL_INCOMING)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle("Incoming from ${notification.deviceName}")
      .setContentText("This unpaired device wants to send you $what.")
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_MESSAGE)
      // No auto-cancel: tapping the body only opens the app, and a freshly created UI may not
      // render this request (e.g. a sender missing from the device list), so the notification
      // stays as the fallback prompt. IncomingRequestNotifications cancels it once decided.
      .setContentIntent(openAppPendingIntent(notification.id, notification.deviceId))
      .addAction(0, "Accept", acceptIntent)
      .addAction(0, "Reject", rejectIntent)

    notificationManager.notify(notification.id, NOTIFICATION_TAG_ID, builder.build())
    log("Notifier", "posted incoming-transfer notification id=${notification.id} device=${notification.deviceId}")
  }

  /**
   * Tapping the body has to bring the app forward, which a broadcast can't do (and a receiver
   * can't start an Activity from the background). Falls back to the [ACTION_OPENED] broadcast if
   * the package somehow has no launcher entry.
   */
  private fun openAppPendingIntent(notificationId: String, deviceId: String): PendingIntent {
    val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
      ?: return actionPendingIntent(notificationId, ACTION_OPENED, deviceId)
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getActivity(appContext, notificationId.hashCode(), launch, flags)
  }

  private fun showIncomingPairing(notification: AppNotification.IncomingPairing) {
    val tapIntent = openAppPendingIntent(notification.id, notification.deviceId)
    val acceptIntent = actionPendingIntent(notification.id, ACTION_PAIRING_ACCEPT, notification.deviceId)
    val rejectIntent = actionPendingIntent(notification.id, ACTION_PAIRING_REJECT, notification.deviceId)

    val builder = NotificationCompat.Builder(appContext, CHANNEL_PAIRING)
      .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
      .setContentTitle("Pairing request")
      .setContentText("${notification.deviceName} wants to pair with this device.")
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_MESSAGE)
      .setAutoCancel(true)
      .setContentIntent(tapIntent)
      .addAction(0, "Accept", acceptIntent)
      .addAction(0, "Reject", rejectIntent)

    notificationManager.notify(notification.id, NOTIFICATION_TAG_ID, builder.build())
    log("Notifier", "posted pairing notification id=${notification.id} device=${notification.deviceId}")
  }

  private fun actionPendingIntent(notificationId: String, action: String, deviceId: String): PendingIntent {
    val intent = Intent(BROADCAST_ACTION).apply {
      `package` = appContext.packageName
      putExtra(EXTRA_ACTION, action)
      putExtra(EXTRA_NOTIFICATION_ID, notificationId)
      putExtra(EXTRA_DEVICE_ID, deviceId)
    }
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    // Encode (notificationId,action) into the request code so PendingIntents
    // for different buttons on the same notification don't overwrite each
    // other via Android's [Intent] equality matching.
    val requestCode = (notificationId.hashCode() * 31) + action.hashCode()
    return PendingIntent.getBroadcast(appContext, requestCode, intent, flags)
  }

  private fun ensurePairingChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    if (notificationManager.getNotificationChannel(CHANNEL_PAIRING) != null) return
    val channel = NotificationChannel(
      CHANNEL_PAIRING,
      "Pairing requests",
      NotificationManager.IMPORTANCE_HIGH,
    ).apply {
      description = "Heads-up when another device asks to pair."
    }
    notificationManager.createNotificationChannel(channel)
  }

  // Separate from the pairing channel so the user can tune them independently, and because an
  // existing channel's importance can't be changed from code.
  private fun ensureIncomingChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    if (notificationManager.getNotificationChannel(CHANNEL_INCOMING) != null) return
    val channel = NotificationChannel(
      CHANNEL_INCOMING,
      "Incoming requests",
      NotificationManager.IMPORTANCE_HIGH,
    ).apply {
      description = "Heads-up when an unpaired device wants to send you a message or file."
    }
    notificationManager.createNotificationChannel(channel)
  }

  private fun registerActionReceiver() {
    val filter = IntentFilter(BROADCAST_ACTION)
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(ctx: Context, intent: Intent) {
        val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID) ?: return
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        val mapped: NotificationAction = when (action) {
          ACTION_OPENED -> NotificationAction.Opened(notificationId)
          ACTION_PAIRING_ACCEPT -> NotificationAction.PairingAccepted(notificationId, deviceId)
          ACTION_PAIRING_REJECT -> NotificationAction.PairingRejected(notificationId, deviceId)
          ACTION_TRANSFER_ACCEPT -> NotificationAction.TransferDecided(notificationId, accepted = true)
          ACTION_TRANSFER_REJECT -> NotificationAction.TransferDecided(notificationId, accepted = false)
          else -> return
        }
        log("Notifier", "received action $action notificationId=$notificationId deviceId=$deviceId")
        flow.tryEmit(mapped)
        // The notification is single-use; dismiss so it doesn't leave a stale
        // accept/reject row hanging in the shade after the user has chosen.
        notificationManager.cancel(notificationId, NOTIFICATION_TAG_ID)
      }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      @Suppress("UnspecifiedRegisterReceiverFlag")
      appContext.registerReceiver(receiver, filter)
    }
  }

  private companion object {
    const val CHANNEL_PAIRING = "klardrop_pairing"
    const val CHANNEL_INCOMING = "klardrop_incoming_requests"
    const val NOTIFICATION_TAG_ID = 1001
    const val BROADCAST_ACTION = "com.carlom.klardrop.NOTIFICATION_ACTION"
    const val EXTRA_ACTION = "klardrop.action"
    const val EXTRA_NOTIFICATION_ID = "klardrop.notification_id"
    const val EXTRA_DEVICE_ID = "klardrop.device_id"
    const val ACTION_OPENED = "OPENED"
    const val ACTION_PAIRING_ACCEPT = "PAIRING_ACCEPT"
    const val ACTION_PAIRING_REJECT = "PAIRING_REJECT"
    const val ACTION_TRANSFER_ACCEPT = "TRANSFER_ACCEPT"
    const val ACTION_TRANSFER_REJECT = "TRANSFER_REJECT"
  }
}
