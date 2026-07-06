package com.carlom.klardrop.common.persistence

/**
 * Delivery state stored on disk.
 *
 * SENDING IS persisted (deliberately, unlike the old design): a single row is written as
 * SENDING before any socket write or ACK, and flipped to its terminal state (SENT/FAILED)
 * exactly once — so a crash mid-send shows SENDING/FAILED on restart, never a false SENT.
 * NULL in the database means SENT (legacy rows predate this column; kept for read-compat).
 */
enum class SendStatus {
  SENDING,
  SENT,
  FAILED,
}

/**
 * UI-facing delivery state for a merged chat message. Mirrors [SendStatus] read back from disk
 * (SENDING/FAILED are explicit column values; NULL/anything else reads as SENT).
 */
enum class DeliveryStatus {
  SENDING,
  SENT,
  FAILED,
}

/**
 * Merged chat message model — the UI-facing type returned by
 * [MessageRepository.getMessagesForDevice].
 *
 * Combines the persisted disk rows (SENDING/SENT/FAILED) with any legacy in-memory outbox
 * entries. The repository merges and deduplicates them: a disk row always wins over an
 * outbox entry with the same [id].
 */
data class ChatMessage(
  /** Stable unique id — matches the DB row id for persisted messages, or the
   *  [OutboxEntry.messageId] for in-flight sends. */
  val id: Long,
  val remoteDeviceId: String,
  val content: String,
  val timestamp: Long,
  val isSender: Boolean,
  val messageType: String,
  val fileTransferId: Long?,
  val isRead: Long,
  val mimeType: String,
  /** Delivery state derived from outbox (SENDING) or disk send_status (SENT/FAILED). */
  val deliveryStatus: DeliveryStatus,
)
