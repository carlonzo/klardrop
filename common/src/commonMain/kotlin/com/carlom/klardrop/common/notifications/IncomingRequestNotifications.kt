package com.carlom.klardrop.common.notifications

import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Posts a system notification for every incoming transfer parked on the user's accept/reject
 * decision ([ReceiveMessageStatus.PendingAuthorization], i.e. the sender got ACK_AWAITING_USER),
 * and routes the notification's Accept / Reject buttons back into that transfer.
 *
 * Process-scoped and started from [com.carlom.klardrop.common.Klardrop.init], not from the UI:
 * on Android the UI's controller only exists while an Activity is composed, but the receive
 * pipeline keeps running under the background service, and a request that lands then has no
 * other way to reach the user.
 *
 * Posted regardless of foreground state: the in-app prompt only renders on the discovery screen
 * and that device's chat, so a foregrounded user on any other screen would otherwise miss it.
 * The notification is cancelled as soon as the transfer leaves the pending state, whichever
 * surface the decision came from.
 *
 * Takes the [Notifier] members as plain functions so common tests can observe them ([Notifier]
 * is an expect class).
 */
internal class IncomingRequestNotifications(
  private val receives: Flow<Pair<String, StateFlow<ReceiveMessageUpdate>>>,
  private val show: (AppNotification) -> Unit,
  private val cancel: (String) -> Unit,
  private val actions: Flow<NotificationAction>,
) {

  /** Notification id → the pending transfer's decision callback. */
  private val decisions = MutableStateFlow<Map<String, (Boolean) -> Unit>>(emptyMap())

  /**
   * Subscribes eagerly ([CoroutineStart.UNDISPATCHED]) so a request that arrives right after
   * startup isn't emitted into the replay-less receive flow before anyone listens.
   */
  internal fun start(scope: CoroutineScope) {
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      actions.collect { action ->
        if (action !is NotificationAction.TransferDecided) return@collect
        val decide = decisions.value[action.notificationId] ?: run {
          log("IncomingRequestNotifications", "No pending transfer for ${action.notificationId} (already decided?)")
          return@collect
        }
        log("IncomingRequestNotifications", "Notification decision ${action.notificationId} accepted=${action.accepted}")
        decide(action.accepted)
      }
    }
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      receives.collect { (deviceId, flow) ->
        launch { follow(deviceId, flow) }
      }
    }
  }

  private suspend fun follow(deviceId: String, flow: StateFlow<ReceiveMessageUpdate>) {
    var shownId: String? = null
    try {
      // Only the authorization phase matters here; stop following once the transfer moves past
      // it (Progress / Completed / Failed) so a long file download doesn't hold a collector.
      flow.transformWhile { update ->
        emit(update)
        update.status is ReceiveMessageStatus.Started || update.status is ReceiveMessageStatus.PendingAuthorization
      }.collect { update ->
        val status = update.status
        if (status !is ReceiveMessageStatus.PendingAuthorization) {
          shownId?.let(::dismiss)
          shownId = null
          return@collect
        }
        if (shownId != null) return@collect
        // Same rule as the in-app banner: only real Text/File headers are user-facing.
        val header = update.messages.firstOrNull { it is TextMessage || it is FileMessage } ?: return@collect
        val id = "incoming-${header.id}"
        decisions.update { it + (id to status.acceptTransfer) }
        shownId = id
        show(
          AppNotification.IncomingTransfer(
            id = id,
            deviceId = update.device?.deviceId ?: deviceId,
            deviceName = update.device?.name?.takeIf { it != "unknown" } ?: "A nearby device",
            fileName = (header as? FileMessage)?.fileName,
          )
        )
      }
    } finally {
      shownId?.let(::dismiss)
    }
  }

  private fun dismiss(id: String) {
    decisions.update { it - id }
    cancel(id)
  }
}
