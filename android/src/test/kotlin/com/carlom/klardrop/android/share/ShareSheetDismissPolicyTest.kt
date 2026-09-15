package com.carlom.klardrop.android.share

import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.share.ShareSheetDismissPolicy
import com.carlom.klardrop.common.share.ShareSheetDismissTrigger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android share-sheet lifetime. Canonical cases live in commonTest; this file
 * pins the Pixel share-target contract so `:android:testDebugUnitTest` covers it.
 */
class ShareSheetDismissPolicyTest {

  @Test
  fun selectDeviceDoesNotDismiss() {
    assertFalse(ShareSheetDismissPolicy.shouldDismiss(ShareSheetDismissTrigger.SelectDevice))
  }

  @Test
  fun sendTapDoesNotDismiss() {
    assertFalse(
      ShareSheetDismissPolicy.shouldDismiss(
        ShareSheetDismissTrigger.SendTap,
        progress = MessengerSendProgress.Pending,
      ),
    )
  }

  @Test
  fun swipeDuringFileSendDoesNotDismiss() {
    assertFalse(swipe(MessengerSendProgress.Pending))
    assertFalse(swipe(MessengerSendProgress.AwaitingRecipient))
    assertFalse(swipe(MessengerSendProgress.InProgress(percentage = 40)))
  }

  @Test
  fun swipeWithNoSendStartedDismisses() {
    assertTrue(swipe(null))
  }

  @Test
  fun completedAllowsDismiss() {
    assertTrue(
      ShareSheetDismissPolicy.shouldDismiss(
        ShareSheetDismissTrigger.Completed,
        progress = MessengerSendProgress.Completed,
      ),
    )
  }

  @Test
  fun errorStaysUntilUserClose() {
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
  fun hideAfterHandoffAllowsDismiss() {
    assertTrue(
      ShareSheetDismissPolicy.shouldDismiss(
        ShareSheetDismissTrigger.UserHide,
        progress = MessengerSendProgress.Pending,
        handoffComplete = true,
      ),
    )
  }

  @Test
  fun hideBeforeHandoffDoesNotDismiss() {
    assertFalse(
      ShareSheetDismissPolicy.shouldDismiss(
        ShareSheetDismissTrigger.UserHide,
        progress = MessengerSendProgress.Pending,
        handoffComplete = false,
      ),
    )
  }

  @Test
  fun emptyPayloadAllowsDismiss() {
    assertTrue(ShareSheetDismissPolicy.shouldDismiss(ShareSheetDismissTrigger.EmptyPayload))
  }

  private fun swipe(progress: MessengerSendProgress?) = ShareSheetDismissPolicy.shouldDismiss(
    ShareSheetDismissTrigger.SwipeAway,
    progress = progress,
  )
}
