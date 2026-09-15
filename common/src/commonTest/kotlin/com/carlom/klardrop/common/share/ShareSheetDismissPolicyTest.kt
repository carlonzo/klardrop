package com.carlom.klardrop.common.share

import com.carlom.klardrop.common.communication.MessengerSendProgress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareSheetDismissPolicyTest {

  @Test
  fun selectDevice_doesNotDismiss() {
    assertFalse(
      ShareSheetDismissPolicy.shouldDismiss(ShareSheetDismissTrigger.SelectDevice),
    )
  }

  @Test
  fun sendTap_doesNotDismiss_evenOnceProgressIsPending() {
    assertFalse(
      ShareSheetDismissPolicy.shouldDismiss(
        ShareSheetDismissTrigger.SendTap,
        progress = MessengerSendProgress.Pending,
      ),
    )
  }

  @Test
  fun swipe_duringPending_doesNotDismiss() {
    assertFalse(swipe(MessengerSendProgress.Pending))
  }

  @Test
  fun swipe_duringAwaitingRecipient_doesNotDismiss() {
    assertFalse(swipe(MessengerSendProgress.AwaitingRecipient))
  }

  @Test
  fun swipe_duringInProgress_doesNotDismiss() {
    assertFalse(swipe(MessengerSendProgress.InProgress(percentage = 40)))
  }

  @Test
  fun swipe_withNoSendStarted_dismisses() {
    assertTrue(swipe(progress = null))
  }

  @Test
  fun completed_allowsDismiss() {
    assertTrue(
      ShareSheetDismissPolicy.shouldDismiss(
        ShareSheetDismissTrigger.Completed,
        progress = MessengerSendProgress.Completed,
      ),
    )
  }

  @Test
  fun completed_trigger_doesNotDismiss_whenStillInFlight() {
    assertFalse(
      ShareSheetDismissPolicy.shouldDismiss(
        ShareSheetDismissTrigger.Completed,
        progress = MessengerSendProgress.InProgress(percentage = 10),
      ),
    )
  }

  @Test
  fun error_staysUntilUserClose() {
    val error = MessengerSendProgress.Error("no route")
    assertFalse(swipe(error))
    assertTrue(
      ShareSheetDismissPolicy.shouldDismiss(
        ShareSheetDismissTrigger.UserHide,
        progress = error,
        handoffComplete = true,
      ),
    )
  }

  @Test
  fun hideAfterHandoff_allowsDismiss() {
    assertTrue(
      ShareSheetDismissPolicy.shouldDismiss(
        ShareSheetDismissTrigger.UserHide,
        progress = MessengerSendProgress.Pending,
        handoffComplete = true,
      ),
    )
  }

  @Test
  fun hideBeforeHandoff_doesNotDismiss() {
    assertFalse(
      ShareSheetDismissPolicy.shouldDismiss(
        ShareSheetDismissTrigger.UserHide,
        progress = MessengerSendProgress.Pending,
        handoffComplete = false,
      ),
    )
  }

  @Test
  fun emptyPayload_allowsDismiss() {
    assertTrue(
      ShareSheetDismissPolicy.shouldDismiss(ShareSheetDismissTrigger.EmptyPayload),
    )
  }

  @Test
  fun connecting_isPending() {
    assertTrue(ShareSheetDismissPolicy.connecting() === MessengerSendProgress.Pending)
  }

  private fun swipe(progress: MessengerSendProgress?) = ShareSheetDismissPolicy.shouldDismiss(
    ShareSheetDismissTrigger.SwipeAway,
    progress = progress,
  )
}
