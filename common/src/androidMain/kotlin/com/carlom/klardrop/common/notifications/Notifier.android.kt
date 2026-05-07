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
actual class Notifier(context: Context) {

  private val appContext = context.applicationContext
  private val notificationManager =
    appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  private val flow = MutableSharedFlow<NotificationAction>(
    extraBufferCapacity = 16,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  actual val actions: Flow<NotificationAction> = flow.asSharedFlow()

  init {
    ensurePairingChannel()
    registerActionReceiver()
  }

  actual fun show(notification: AppNotification) {
    when (notification) {
      is AppNotification.IncomingPairing -> showIncomingPairing(notification)
    }
  }

  actual fun cancel(id: String) {
    notificationManager.cancel(id, NOTIFICATION_TAG_ID)
  }

  private fun showIncomingPairing(notification: AppNotification.IncomingPairing) {
    val tapIntent = actionPendingIntent(notification.id, ACTION_OPENED, notification.deviceId)
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
    const val NOTIFICATION_TAG_ID = 1001
    const val BROADCAST_ACTION = "com.carlom.klardrop.NOTIFICATION_ACTION"
    const val EXTRA_ACTION = "klardrop.action"
    const val EXTRA_NOTIFICATION_ID = "klardrop.notification_id"
    const val EXTRA_DEVICE_ID = "klardrop.device_id"
    const val ACTION_OPENED = "OPENED"
    const val ACTION_PAIRING_ACCEPT = "PAIRING_ACCEPT"
    const val ACTION_PAIRING_REJECT = "PAIRING_REJECT"
  }
}
