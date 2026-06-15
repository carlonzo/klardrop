package com.carlom.klardrop.common.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.turbine.test
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.untilCompleted
import com.carlom.klardrop.common.database.AppDatabase
import com.carlom.klardrop.common.database.createTestDriver
import com.carlom.klardrop.common.utils.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Memory+disk outbox. Test-first repro (RED).
 *
 * DESIGN under test (common-side surfaces the redo must provide):
 *  - In-memory [MessageOutbox]: the transient SENDING state lives ONLY here, NEVER on disk.
 *  - [MessageRepository.getMessagesForDevice] MERGES the on-disk SQLDelight flow + the in-memory
 *    outbox flow for that device (sorted by timestamp; dedupe by message id — a disk row wins over
 *    an outbox entry with the same id).
 *  - On send COMPLETED: the message is already persisted as SENT by TextMessageHandler.handleOutgoing;
 *    the outbox entry is dropped. No duplicate row.
 *  - On send FAILED: the message is persisted to disk as FAILED via a MINIMAL final-status marker
 *    (SENT/FAILED only — NEVER 'SENDING') and the outbox entry dropped. FAILED must SURVIVE restart.
 *
 * Each [DeliveryStatus] on the merged read is the discriminator the UI maps to KdDeliveryState.
 *
 * The send-flow boundary (DeviceChatViewModel.sendTextMessage, presentation module) is modeled
 * inline here over a fake Messenger so the assertions land on the real common surfaces
 * (MessageRepository merge + outbox + temp DB). The exact production VM mirrors this orchestration:
 *   add to outbox (SENDING) BEFORE messenger.send;
 *   collect messenger.send(...).untilCompleted();
 *   Completed -> drop from outbox (handleOutgoing already persisted it as SENT);
 *   Error     -> persist as FAILED + drop from outbox.
 *
 * On CURRENT code there is no outbox and nothing is persisted on failure, so this FAILS (RED).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutboxMergeReliabilityTest {

  private lateinit var driver: SqlDriver
  private lateinit var db: AppDatabase
  private lateinit var repository: MessageRepository
  private lateinit var outbox: MessageOutbox
  private lateinit var dispatcher: TestDispatcher
  private lateinit var clock: Clock

  private val deviceId = "offline-peer"

  @BeforeTest
  fun setup() {
    driver = createTestDriver()
    db = AppDatabase(driver)
    dispatcher = UnconfinedTestDispatcher()
    clock = Clock()
    outbox = MessageOutbox()
    // The repository read the UI consumes merges disk + the in-memory outbox.
    repository = MessageRepositoryImpl(db, clock, dispatcher, outbox)
  }

  @AfterTest
  fun tearDown() {
    driver.close()
  }

  /**
   * A fake Messenger send that mirrors TextMessageHandler.handleOutgoing's "persist on a successful
   * send" path: it persists the SENT row to disk only when [succeed] is true, just before emitting
   * Completed. When [succeed] is false it emits Error and writes nothing to disk (matching the real
   * handler, which only inserts after the write succeeds).
   */
  private fun fakeSend(
    text: String,
    messageId: Long,
    succeed: Boolean,
  ): Flow<MessengerSendProgress> = flow {
    emit(MessengerSendProgress.Pending)
    if (succeed) {
      // handleOutgoing's existing insert path persists the outgoing message as SENT.
      repository.insertMessage(
        messageId = messageId,
        remoteDeviceId = deviceId,
        content = text,
        isSender = true,
        messageType = MessageType.TEXT,
        isRead = true,
        sendStatus = SendStatus.SENT,
      )
      emit(MessengerSendProgress.Completed)
    } else {
      emit(MessengerSendProgress.Error("Device is not visible"))
    }
  }

  /**
   * Inline model of the redesigned DeviceChatViewModel.sendTextMessage boundary.
   * add-to-outbox BEFORE send; on Completed drop from outbox; on Error persist FAILED + drop.
   */
  private suspend fun sendTextMessage(
    text: String,
    messageId: Long,
    succeed: Boolean,
  ) {
    // 1) optimistic: appears as SENDING from the in-memory outbox, nothing on disk yet.
    outbox.add(
      OutboxEntry(
        messageId = messageId,
        remoteDeviceId = deviceId,
        content = text,
        timestamp = clock.currentTimeMillis(),
      )
    )

    val finalStatus = fakeSend(text, messageId, succeed)
      .untilCompleted()
      .lastOrNull()

    when (finalStatus) {
      is MessengerSendProgress.Completed -> {
        // handleOutgoing already persisted it as SENT; the VM must NOT insert again.
        outbox.remove(messageId)
      }
      is MessengerSendProgress.Error -> {
        // survives restart + retryable.
        repository.insertMessage(
          messageId = messageId,
          remoteDeviceId = deviceId,
          content = text,
          isSender = true,
          messageType = MessageType.TEXT,
          isRead = true,
          sendStatus = SendStatus.FAILED,
        )
        outbox.remove(messageId)
      }
      else -> {}
    }
  }

  /**
   * (1) Sending to an OFFLINE peer makes the outgoing message appear in getMessagesForDevice as
   *     SENDING (from the in-memory outbox) WHILE in flight, with NOTHING written to disk yet.
   */
  @Test
  fun inFlightSend_appearsAsSending_fromOutbox_withNothingOnDisk() = runTest(dispatcher) {
    val messageId = 1L
    val text = "hi while offline"

    // In flight: add to the outbox but do not complete the send yet.
    outbox.add(
      OutboxEntry(
        messageId = messageId,
        remoteDeviceId = deviceId,
        content = text,
        timestamp = clock.currentTimeMillis(),
      )
    )

    repository.getMessagesForDevice(deviceId, 10).test {
      val merged = awaitItem()
      assertEquals(1, merged.size, "in-flight outbox entry should appear in the merged read")
      val row = merged.first()
      assertEquals(text, row.content)
      assertTrue(row.isSender, "outgoing message")
      assertEquals(DeliveryStatus.SENDING, row.deliveryStatus, "in-flight = SENDING from the outbox")
    }

    // Nothing is on disk while merely 'sending'.
    val onDisk = db.messageQueries.getMessagesForDevice(deviceId, 10).executeAsList()
    assertTrue(onDisk.isEmpty(), "NO row may be written to disk while merely sending")
  }

  /**
   * (2) On failure the message transitions to FAILED and IS persisted to disk; assert it SURVIVES
   *     by querying a FRESH repository over the SAME db and still seeing it as FAILED.
   */
  @Test
  fun failedSend_persistsFailedToDisk_andSurvivesRestart() = runTest(dispatcher) {
    val messageId = 2L
    val text = "this one fails"

    sendTextMessage(text, messageId, succeed = false)

    // After failure: dropped from the outbox, present on disk as FAILED.
    assertNull(outbox.entries.value.filter { it.remoteDeviceId == deviceId }.firstOrNull { it.messageId == messageId }, "dropped from outbox")

    // Survives "restart": a fresh repository over the same db still returns it as FAILED.
    val freshRepo = MessageRepositoryImpl(db, clock, dispatcher, MessageOutbox())
    freshRepo.getMessagesForDevice(deviceId, 10).test {
      val merged = awaitItem()
      assertEquals(1, merged.size, "the FAILED message must survive restart")
      val row = merged.first()
      assertEquals(text, row.content)
      assertEquals(DeliveryStatus.FAILED, row.deliveryStatus, "failed send persisted as FAILED on disk")
    }
  }

  /**
   * (3) On a successful send the message ends as SENT, persisted exactly ONCE (no duplicate from a
   *     double-insert: handleOutgoing persists SENT; the VM must not also insert on Completed).
   */
  @Test
  fun successfulSend_endsAsSent_persistedExactlyOnce() = runTest(dispatcher) {
    val messageId = 3L
    val text = "this one sends"

    sendTextMessage(text, messageId, succeed = true)

    assertNull(outbox.entries.value.filter { it.remoteDeviceId == deviceId }.firstOrNull { it.messageId == messageId }, "dropped from outbox")

    repository.getMessagesForDevice(deviceId, 10).test {
      val merged = awaitItem()
      assertEquals(1, merged.size, "exactly one persisted row — no duplicate insert on Completed")
      val row = merged.first()
      assertEquals(text, row.content)
      assertEquals(DeliveryStatus.SENT, row.deliveryStatus, "successful send = SENT")
    }

    // Disk holds exactly one row (the SENT one) — proves no double-persist.
    val onDisk = db.messageQueries.getMessagesForDevice(deviceId, 10).executeAsList()
    assertEquals(1, onDisk.size, "persisted exactly once")
    assertNotNull(onDisk.first())
  }
}
