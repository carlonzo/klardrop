package com.carlom.klardrop.common.notifications

import kotlinx.coroutines.flow.Flow

/**
 * Per-platform system-notification surface.
 *
 * Designed around two flows:
 *  - Outbound: [show] / [cancel] post or dismiss notifications described by
 *    [AppNotification].
 *  - Inbound: [actions] emits a [NotificationAction] each time the user taps
 *    the notification body or one of its action buttons.
 *
 * The inbound channel is decoupled from the outbound call site so action
 * delivery survives process restarts on Android (a tap routes through a
 * BroadcastReceiver that re-enters the running app) and so multiple
 * subscribers can react to the same decision.
 *
 * Platforms with no notification surface wire up no-op implementations and
 * an empty [actions] flow.
 */
expect class Notifier {
  fun show(notification: AppNotification)
  fun cancel(id: String)
  val actions: Flow<NotificationAction>
}

/**
 * Notifications Klardrop knows how to render. Each subtype is an explicit
 * shape so platform code can pick the right category / channel / sound and
 * the right set of action buttons without parsing free-form text.
 */
sealed interface AppNotification {
  val id: String

  /**
   * Another device is asking to pair (trust handshake). Only fired while the
   * app is backgrounded — the in-app [com.carlom.klardrop.PairingApprovalDialog]
   * handles foreground requests.
   */
  data class IncomingPairing(
    override val id: String,
    val deviceId: String,
    val deviceName: String,
  ) : AppNotification
}

/**
 * Decisions the user makes from a notification. Platform impls translate
 * tap / accept-button / reject-button events into one of these and emit
 * through [Notifier.actions]; the consumer matches by [notificationId] to
 * the [AppNotification] that produced it.
 */
sealed interface NotificationAction {
  val notificationId: String

  /** User tapped the notification body. Open the app to the relevant screen. */
  data class Opened(override val notificationId: String) : NotificationAction

  /** User accepted a pairing request from the notification. */
  data class PairingAccepted(
    override val notificationId: String,
    val deviceId: String,
  ) : NotificationAction

  /** User rejected a pairing request from the notification. */
  data class PairingRejected(
    override val notificationId: String,
    val deviceId: String,
  ) : NotificationAction
}
