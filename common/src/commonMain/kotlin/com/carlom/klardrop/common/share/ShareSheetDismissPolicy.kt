package com.carlom.klardrop.common.share

import com.carlom.klardrop.common.communication.MessengerSendProgress

/**
 * When a share-from-outside sheet may close.
 *
 * Product rule: the sheet stays open until the transfer completes. Closing the
 * host (Android Activity.finish, iOS onComplete) mid-send drops the connection
 * — files die; short text often wins the race. Desktop ShareDialog and iOS
 * ShareInboxSheet use the same rules.
 *
 * Auto-dismiss on select, on send, or on the device-list → Connecting content
 * swap is a bug. Explicit Hide may close only after the send has been handed
 * off (file grant on the service / text send launched).
 *
 * Swift: `ShareSheetDismissPolicy.shared.shouldDismiss(trigger:progress:handoffComplete:)`.
 */
enum class ShareSheetDismissTrigger {
  /** User tapped a device. Select ≠ send; never close. */
  SelectDevice,

  /** User tapped Send. Swap to Connecting; never auto-close. */
  SendTap,

  /** Swipe-away / ModalBottomSheet onDismissRequest / iOS interactive dismiss. */
  SwipeAway,

  /** Transfer reached Completed; UI may delay, then close. */
  Completed,

  /** SendStatus Hide / Close button. */
  UserHide,

  /** Nothing to send. */
  EmptyPayload,
}

object ShareSheetDismissPolicy {

  /** Connecting state to show immediately on Send tap, before the first progress emission. */
  fun connecting(): MessengerSendProgress = MessengerSendProgress.Pending

  fun shouldDismiss(
    trigger: ShareSheetDismissTrigger,
    progress: MessengerSendProgress? = null,
    handoffComplete: Boolean = false,
  ): Boolean = when (trigger) {
    ShareSheetDismissTrigger.SelectDevice,
    ShareSheetDismissTrigger.SendTap -> false
    ShareSheetDismissTrigger.EmptyPayload -> true
    ShareSheetDismissTrigger.Completed -> progress is MessengerSendProgress.Completed
    ShareSheetDismissTrigger.UserHide ->
      // Hide after the send is handed off, or Close on Failed / Sent.
      handoffComplete ||
        progress is MessengerSendProgress.Completed ||
        progress is MessengerSendProgress.Error
    ShareSheetDismissTrigger.SwipeAway -> when (progress) {
      null -> true
      is MessengerSendProgress.Completed -> true
      is MessengerSendProgress.Error -> false
      MessengerSendProgress.Pending,
      MessengerSendProgress.AwaitingRecipient,
      is MessengerSendProgress.InProgress -> false
    }
  }
}
