package com.carlom.klardrop.common.notifications

import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.DeviceType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingRequestNotificationsTest {

  private val device = DeviceInfo("device-1", "Laptop", DeviceType.DESKTOP)

  @Test
  fun pendingTextIsNotifiedAndAcceptFromTheNotificationResolvesIt() = runTest(UnconfinedTestDispatcher()) {
    val receives = MutableSharedFlow<Pair<String, StateFlow<ReceiveMessageUpdate>>>()
    val actions = MutableSharedFlow<NotificationAction>()
    val shown = mutableListOf<AppNotification>()
    val cancelled = mutableListOf<String>()
    val decisions = mutableListOf<Boolean>()
    val watcher = IncomingRequestNotifications(receives, { shown += it }, { cancelled += it }, actions)
    watcher.start(backgroundScope)

    val text = TextMessage(text = "hi")
    val flow = MutableStateFlow(ReceiveMessageUpdate(device = device, status = ReceiveMessageStatus.Started))
    receives.emit(device.deviceId to flow)
    flow.value = flow.value.copy(
      messages = listOf(text),
      status = ReceiveMessageStatus.PendingAuthorization { decisions += it },
    )

    val notification = shown.single() as AppNotification.IncomingTransfer
    assertEquals("incoming-${text.id}", notification.id)
    assertEquals("Laptop", notification.deviceName)
    assertEquals(null, notification.fileName)

    actions.emit(NotificationAction.TransferDecided(notification.id, accepted = true))
    assertEquals(listOf(true), decisions)

    // The router moves the flow on once the decision lands; that dismisses the notification.
    flow.value = flow.value.copy(status = ReceiveMessageStatus.Completed)
    assertEquals(listOf(notification.id), cancelled)
  }

  @Test
  fun decisionMadeInAppCancelsTheNotification() = runTest(UnconfinedTestDispatcher()) {
    val receives = MutableSharedFlow<Pair<String, StateFlow<ReceiveMessageUpdate>>>()
    val shown = mutableListOf<AppNotification>()
    val cancelled = mutableListOf<String>()
    IncomingRequestNotifications(receives, { shown += it }, { cancelled += it }, MutableSharedFlow())
      .start(backgroundScope)

    val file = FileMessage("photo.jpg", 10, "image/jpeg")
    val flow = MutableStateFlow(
      ReceiveMessageUpdate(device = device, messages = listOf(file), status = ReceiveMessageStatus.PendingAuthorization {})
    )
    receives.emit(device.deviceId to flow)
    assertEquals("photo.jpg", (shown.single() as AppNotification.IncomingTransfer).fileName)

    // Accepted from the banner/chat: the file starts streaming.
    flow.value = flow.value.copy(status = ReceiveMessageStatus.Progress(emptyList()))
    assertEquals(listOf(shown.single().id), cancelled)
  }

  @Test
  fun controlFramesAndTrustedTransfersNeverNotify() = runTest(UnconfinedTestDispatcher()) {
    val receives = MutableSharedFlow<Pair<String, StateFlow<ReceiveMessageUpdate>>>()
    val shown = mutableListOf<AppNotification>()
    IncomingRequestNotifications(receives, { shown += it }, {}, MutableSharedFlow()).start(backgroundScope)

    // Pending with no Text/File header (same rule as the in-app banner).
    receives.emit(
      device.deviceId to MutableStateFlow(
        ReceiveMessageUpdate(device = device, status = ReceiveMessageStatus.PendingAuthorization {})
      )
    )
    // Trusted sender: Started → Completed, never pending.
    val trusted = MutableStateFlow(ReceiveMessageUpdate(device = device, status = ReceiveMessageStatus.Started))
    receives.emit(device.deviceId to trusted)
    trusted.value = trusted.value.copy(messages = listOf(TextMessage(text = "x")), status = ReceiveMessageStatus.Completed)

    assertTrue(shown.isEmpty())
  }
}
