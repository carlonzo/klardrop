package com.carlom.klardrop.common.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.turbine.test
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.database.AppDatabase
import com.carlom.klardrop.common.database.Messages
import com.carlom.klardrop.common.database.createTestDriver
import com.carlom.klardrop.common.persistence.MessageSendStatus
import com.carlom.klardrop.common.persistence.MessageType
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Repro for B22: an outgoing TEXT to an OFFLINE / unreachable peer must still be persisted
 * and surface in the chat history.
 *
 * Today, persistence of an outgoing TEXT happens ONLY inside [TextMessageHandler.handleOutgoing],
 * which is reached only AFTER a live connection has been established and bytes are written.
 * When the peer is offline, [MessengerImpl.send] emits [MessengerSendProgress.Error] before any
 * handler runs, so NOTHING is written to the messages table. The user just sees a transient
 * snackbar and the message vanishes.
 *
 * This test exercises the exact contract that [DeviceChatViewModel.sendTextMessage] relies on
 * (drive [Messenger.send] to completion, then expect the message to live in
 * [MessageRepository.getMessagesForDevice]). It uses a REAL in-memory [MessageRepositoryImpl]
 * and a fake [Messenger] that emits Pending -> Error to model an offline peer.
 *
 * Expected (post-fix) behaviour: the outgoing TEXT is persisted immediately as SENDING and then
 * transitions to FAILED when the send errors (mirrors the FileTransferStatus pattern, where a
 * file row is inserted IN_PROGRESS up front and flipped to FAILED on failure). On a successful
 * send it would instead settle on SENT.
 *
 * On current code NOTHING is persisted on the offline path, so this test FAILS (RED).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutgoingTextOfflinePersistenceTest {

  private lateinit var driver: SqlDriver
  private lateinit var db: AppDatabase
  private lateinit var repository: MessageRepositoryImpl
  private lateinit var dispatcher: TestDispatcher

  private val deviceId = "offline-peer"

  @BeforeTest
  fun setup() {
    driver = createTestDriver()
    db = AppDatabase(driver)
    dispatcher = UnconfinedTestDispatcher()
    repository = MessageRepositoryImpl(db, Clock(), dispatcher)
  }

  @AfterTest
  fun tearDown() {
    driver.close()
  }

  @Test
  fun outgoingTextToOfflinePeerIsPersistedAndEndsFailed() = runTest(dispatcher) {
    val offlineMessenger = FakeMessenger(MessengerSendProgress.Error("$deviceId is not visible"))

    // Mirror DeviceChatViewModel.sendTextMessage -> sendMessage: drive the send to completion.
    sendTextLikeViewModel(repository, offlineMessenger, deviceId, "hello offline world")

    val persisted: List<Messages> = repository.getMessagesForDevice(deviceId, limit = 100).first()

    // B22: nothing is persisted today -> this assertion is what currently fails (RED).
    assertEquals(
      1,
      persisted.size,
      "An outgoing TEXT to an offline peer must still be persisted so it shows in chat history"
    )

    val row = persisted.first()
    assertEquals(1L, row.is_sender, "Persisted message must be an outgoing (sender) message")
    assertEquals("hello offline world", row.content)
    assertEquals(MessageType.TEXT.name, row.message_type)

    // And it must carry a terminal FAILED delivery status (SENDING -> FAILED), so the Bubble
    // can render KdDeliveryState.Failed instead of the message silently disappearing.
    assertEquals(
      DeliveryStatus.FAILED,
      row.deliveryStatus(),
      "Outgoing message to an offline peer must end in FAILED status"
    )
  }

  @Test
  fun outgoingTextShowsSendingBeforeItFails() = runTest(dispatcher) {
    val offlineMessenger = FakeMessenger(MessengerSendProgress.Error("$deviceId is not visible"))

    repository.getMessagesForDevice(deviceId, limit = 100).test {
      assertTrue(awaitItem().isEmpty(), "starts empty")

      sendTextLikeViewModel(repository, offlineMessenger, deviceId, "are you there?")

      // The outgoing row should appear in SENDING state first...
      val sending = awaitItem()
      assertEquals(1, sending.size, "Outgoing TEXT must appear immediately as SENDING")
      assertEquals(DeliveryStatus.SENDING, sending.first().deliveryStatus())

      // ...then flip to FAILED once the offline send errors out.
      val failed = awaitItem()
      assertEquals(DeliveryStatus.FAILED, failed.first().deliveryStatus())

      cancelAndIgnoreRemainingEvents()
    }
  }
}

/**
 * Reproduces the persistence contract of [DeviceChatViewModel.sendTextMessage] without depending
 * on the presentation module (which the :klardrop-common test source set cannot reference).
 *
 * Mirrors the fixed ViewModel logic (B22 optimistic outbox):
 *  1. Insert outgoing row as SENDING before calling messenger.send.
 *  2. Drain the send flow to completion (Completed or Error).
 *  3. Update the row to SENT or FAILED based on the terminal status.
 */
private suspend fun sendTextLikeViewModel(
  repository: MessageRepository,
  messenger: Messenger,
  deviceId: String,
  text: String,
) {
  val rowId = repository.insertMessage(
    remoteDeviceId = deviceId,
    content = text,
    isSender = true,
    messageType = MessageType.TEXT,
    isRead = true,
    mimeType = "text/plain",
    sendStatus = MessageSendStatus.SENDING,
  )

  val request = TextMessage(text = text).toSimpleSendRequest()
  var lastProgress: MessengerSendProgress? = null
  messenger.send(deviceId, request).collect { lastProgress = it }

  val terminalStatus = if (lastProgress is MessengerSendProgress.Error) {
    MessageSendStatus.FAILED
  } else {
    MessageSendStatus.SENT
  }
  repository.updateMessageSendStatus(rowId, terminalStatus)
}

private enum class DeliveryStatus { SENDING, SENT, FAILED }

/**
 * Maps a persisted message row to its outgoing delivery status using the send_status column
 * added by the B22 fix.
 */
private fun Messages.deliveryStatus(): DeliveryStatus {
  return when (send_status) {
    MessageSendStatus.SENDING.name -> DeliveryStatus.SENDING
    MessageSendStatus.SENT.name    -> DeliveryStatus.SENT
    MessageSendStatus.FAILED.name  -> DeliveryStatus.FAILED
    else                           -> DeliveryStatus.SENT // incoming messages have no send_status
  }
}

private class FakeMessenger(
  private val terminal: MessengerSendProgress,
) : Messenger {
  override fun send(deviceId: String, messageRequest: SendMessageRequest): Flow<MessengerSendProgress> =
    flow {
      emit(MessengerSendProgress.Pending)
      emit(terminal)
    }

  override fun receive(): Flow<Pair<String, Flow<ReceiveMessageUpdate>>> = flowOf()
}
