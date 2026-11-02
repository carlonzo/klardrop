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
public expect class Notifier {
  public fun show(notification: AppNotification): Unit
  public fun cancel(id: String): Unit
  public val actions: Flow<NotificationAction>
}

/**
 * Notifications Klardrop knows how to render. Each subtype is an explicit
 * shape so platform code can pick the right category / channel / sound and
 * the right set of action buttons without parsing free-form text.
 */
public sealed interface AppNotification {
  public val id: String

  /**
   * Another device is asking to pair (trust handshake). Only fired while the
   * app is backgrounded — the in-app [com.carlom.klardrop.PairingApprovalDialog]
   * handles foreground requests.
   */
  public data class IncomingPairing(
    override val id: String,
    public val deviceId: String,
    public val deviceName: String,
  ) : AppNotification

  /**
   * An untrusted device is waiting for the user to accept or reject a text or
   * file (the receiver's AWAITING_USER state). Posted by
   * [IncomingRequestNotifications] regardless of foreground, so the request
   * is visible even when the in-app prompt isn't on screen. [id] is per
   * transfer, never the device id, so it can't collide with [IncomingPairing].
   * The message body is deliberately not included: it's untrusted content
   * and the notification can render on the lock screen.
   */
  public data class IncomingTransfer(
    override val id: String,
    public val deviceId: String,
    public val deviceName: String,
    /** Null for a text message. */
    public val fileName: String?,
  ) : AppNotification
}

/**
 * Decisions the user makes from a notification. Platform impls translate
 * tap / accept-button / reject-button events into one of these and emit
 * through [Notifier.actions]; the consumer matches by [notificationId] to
 * the [AppNotification] that produced it.
 */
public sealed interface NotificationAction {
  public val notificationId: String

  /** User tapped the notification body. Open the app to the relevant screen. */
  public data class Opened(override val notificationId: String) : NotificationAction

  /** User accepted a pairing request from the notification. */
  public data class PairingAccepted(
    override val notificationId: String,
    public val deviceId: String,
  ) : NotificationAction

  /** User rejected a pairing request from the notification. */
  public data class PairingRejected(
    override val notificationId: String,
    public val deviceId: String,
  ) : NotificationAction

  /** User accepted (or rejected) an [AppNotification.IncomingTransfer] from the notification. */
  public data class TransferDecided(
    override val notificationId: String,
    public val accepted: Boolean,
  ) : NotificationAction
}
