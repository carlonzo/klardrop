package com.carlom.klardrop.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.carlom.klardrop.android.MainActivity
import com.carlom.klardrop.android.applicationComponent
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.launch

/**
 * Opt-in foreground service that keeps Klardrop discoverable and connectable while the app is
 * backgrounded.
 *
 * Discovery, the server, and the eager connector already run in-process (started from
 * `Klardrop.init()`); on modern Android they get frozen when the app has no foreground component.
 * This service supplies that foreground component (a persistent notification) so the process stays
 * alive, and holds a [WifiManager.MulticastLock] so mDNS multicast keeps flowing in the background.
 *
 * Lifecycle is driven entirely by the persisted `backgroundDiscoveryEnabled` preference: an
 * observer in [com.carlom.klardrop.android.KlarDropApplication] starts/stops this service when the
 * pref flips, and [MainActivity] ensures it's running on launch when the pref is on. The Stop
 * action on the notification turns the pref off (which stops the service via that same observer).
 */
class DiscoveryForegroundService : Service() {

  private val multicastLock: WifiManager.MulticastLock by lazy {
    (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
      .createMulticastLock("klardrop:discovery")
      .apply { setReferenceCounted(false) }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      log("DiscoveryForegroundService", "Stop requested; turning background discovery off")
      // Reflect the choice in the persisted pref so the Settings toggle shows OFF and the
      // app-side observer doesn't immediately restart us.
      val component = applicationComponent().klardrop().commonComponent
      component.coroutines().appScope.launch {
        runCatching { component.localPropertiesRepository().saveBackgroundDiscoveryEnabled(false) }
      }
      stopForegroundAndSelf()
      return START_NOT_STICKY
    }

    ensureChannel()
    promoteToForeground()
    runCatching { if (!multicastLock.isHeld) multicastLock.acquire() }
      .onFailure { log("DiscoveryForegroundService", "multicast lock acquire failed", it) }
    // Restart if the OS kills us — the whole point is to stay present.
    return START_STICKY
  }

  private fun promoteToForeground() {
    ServiceCompat.startForeground(
      this,
      NOTIFICATION_ID,
      buildNotification(),
      if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
    )
  }

  private fun buildNotification(): Notification {
    val open = PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val stop = PendingIntent.getService(
      this, 1, Intent(this, DiscoveryForegroundService::class.java).setAction(ACTION_STOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(this, CHANNEL_DISCOVERY)
      .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
      .setContentTitle("Klardrop is discoverable")
      .setContentText("Nearby devices can find and send to this device.")
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setContentIntent(open)
      .addAction(0, "Stop", stop)
      .build()
  }

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (nm.getNotificationChannel(CHANNEL_DISCOVERY) != null) return
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL_DISCOVERY, "Discoverable", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Shown while this device stays discoverable to nearby devices in the background."
      }
    )
  }

  private fun stopForegroundAndSelf() {
    runCatching { if (multicastLock.isHeld) multicastLock.release() }
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    runCatching { if (multicastLock.isHeld) multicastLock.release() }
    super.onDestroy()
  }

  companion object {
    private const val CHANNEL_DISCOVERY = "klardrop_discovery"
    private const val NOTIFICATION_ID = 3001
    private const val ACTION_STOP = "com.carlom.klardrop.action.STOP_DISCOVERY"

    /** Start the service. Safe to call repeatedly (idempotent). Caller should be in the
     *  foreground (Android 12+ blocks starting a foreground service from the background). */
    fun start(context: Context) {
      ContextCompat.startForegroundService(
        context, Intent(context, DiscoveryForegroundService::class.java),
      )
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, DiscoveryForegroundService::class.java))
    }
  }
}
