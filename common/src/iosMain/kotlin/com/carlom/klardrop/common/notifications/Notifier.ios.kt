package com.carlom.klardrop.common.notifications

import com.carlom.klardrop.common.utils.log
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.Foundation.NSError
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionDestructive
import platform.UserNotifications.UNNotificationActionOptionForeground
import platform.UserNotifications.UNNotificationActionOptionNone
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationCategoryOptionNone
import platform.UserNotifications.UNNotificationDefaultActionIdentifier
import platform.UserNotifications.UNNotificationPresentationOptionNone
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

/**
 * iOS notification surface.
 *
 * Posts via [UNUserNotificationCenter] with a category that carries the
 * Accept / Reject actions, and registers a [UNUserNotificationCenterDelegateProtocol]
 * delegate to receive the user's choice. Action identifiers carry the
 * decision back into the [actions] flow; userInfo carries the deviceId.
 *
 * The delegate retains itself via the [UNUserNotificationCenter.delegate]
 * property; we keep a hard reference in the field so it doesn't get
 * collected by the Kotlin/Native runtime.
 */
@OptIn(ExperimentalForeignApi::class)
actual class Notifier {

  private val flow = MutableSharedFlow<NotificationAction>(
    extraBufferCapacity = 16,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  actual val actions: Flow<NotificationAction> = flow.asSharedFlow()

  private val center = UNUserNotificationCenter.currentNotificationCenter()

  private val delegate = object : NSObject(), UNUserNotificationCenterDelegateProtocol {
    override fun userNotificationCenter(
      center: UNUserNotificationCenter,
      didReceiveNotificationResponse: UNNotificationResponse,
      withCompletionHandler: () -> Unit,
    ) {
      val response = didReceiveNotificationResponse
      val notificationId = response.notification.request.identifier
      val deviceId = response.notification.request.content.userInfo[KEY_DEVICE_ID] as? String ?: ""
      val mapped: NotificationAction? = when (response.actionIdentifier) {
        UNNotificationDefaultActionIdentifier -> NotificationAction.Opened(notificationId)
        ACTION_PAIRING_ACCEPT -> NotificationAction.PairingAccepted(notificationId, deviceId)
        ACTION_PAIRING_REJECT -> NotificationAction.PairingRejected(notificationId, deviceId)
        else -> null
      }
      if (mapped != null) {
        log("Notifier", "iOS action ${response.actionIdentifier} -> $mapped")
        flow.tryEmit(mapped)
      }
      withCompletionHandler()
    }

    override fun userNotificationCenter(
      center: UNUserNotificationCenter,
      willPresentNotification: UNNotification,
      withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
    ) {
      // We only enqueue notifications when the app is backgrounded, so
      // there's nothing to present in-app. Suppress in case one races.
      withCompletionHandler(UNNotificationPresentationOptionNone)
    }
  }

  init {
    registerCategories()
    center.delegate = delegate
    requestAuthorizationIfNeeded()
  }

  actual fun show(notification: AppNotification) {
    when (notification) {
      is AppNotification.IncomingPairing -> showIncomingPairing(notification)
    }
  }

  actual fun cancel(id: String) {
    center.removeDeliveredNotificationsWithIdentifiers(listOf(id))
    center.removePendingNotificationRequestsWithIdentifiers(listOf(id))
  }

  private fun showIncomingPairing(notification: AppNotification.IncomingPairing) {
    val content = UNMutableNotificationContent().apply {
      setTitle("Pairing request")
      setBody("${notification.deviceName} wants to pair with this device.")
      setCategoryIdentifier(CATEGORY_PAIRING)
      setUserInfo(mapOf(KEY_DEVICE_ID to notification.deviceId))
    }
    val request = UNNotificationRequest.requestWithIdentifier(
      identifier = notification.id,
      content = content,
      trigger = null,
    )
    center.addNotificationRequest(request) { error: NSError? ->
      if (error != null) {
        log("Notifier", "iOS notification submit failed: ${error.localizedDescription}")
      }
    }
  }

  private fun registerCategories() {
    val accept = UNNotificationAction.actionWithIdentifier(
      identifier = ACTION_PAIRING_ACCEPT,
      title = "Accept",
      options = UNNotificationActionOptionForeground,
    )
    val reject = UNNotificationAction.actionWithIdentifier(
      identifier = ACTION_PAIRING_REJECT,
      title = "Reject",
      options = UNNotificationActionOptionDestructive,
    )
    val pairingCategory = UNNotificationCategory.categoryWithIdentifier(
      identifier = CATEGORY_PAIRING,
      actions = listOf(accept, reject),
      intentIdentifiers = emptyList<String>(),
      options = UNNotificationCategoryOptionNone,
    )
    center.setNotificationCategories(setOf(pairingCategory))
  }

  private fun requestAuthorizationIfNeeded() {
    val options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
    center.requestAuthorizationWithOptions(options) { granted, error ->
      if (error != null) {
        log("Notifier", "iOS auth request error: ${error.localizedDescription}")
      } else {
        log("Notifier", "iOS notification auth granted=$granted")
      }
    }
  }

  private companion object {
    const val CATEGORY_PAIRING = "klardrop.pairing"
    const val ACTION_PAIRING_ACCEPT = "klardrop.pairing.accept"
    const val ACTION_PAIRING_REJECT = "klardrop.pairing.reject"
    const val KEY_DEVICE_ID = "deviceId"
  }
}
