package com.carlom.klardrop.common.persistence

/**
 * Final delivery state stored on disk.
 * NEVER persisted as SENDING — that state is memory-only (in-flight outbox).
 * NULL in the database means SENT (successful delivery, already persisted by handleOutgoing).
 */
enum class SendStatus {
  SENT,
  FAILED,
}

/**
 * UI-facing delivery state for a merged chat message.
 * SENDING comes from the in-memory outbox; SENT/FAILED come from disk.
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
 * Combines both in-memory outbox entries (SENDING state) and persisted disk rows
 * (SENT or FAILED state). The repository merges and deduplicates them: a disk
 * row always wins over an outbox entry with the same [id].
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
