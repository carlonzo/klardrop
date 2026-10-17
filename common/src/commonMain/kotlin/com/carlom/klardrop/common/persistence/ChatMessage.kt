package com.carlom.klardrop.common.persistence

/**
 * Delivery state stored on disk.
 *
 * SENDING IS persisted (deliberately, unlike the old design): a single row is written as
 * SENDING before any socket write or ACK, and flipped to its terminal state (SENT/FAILED)
 * exactly once — so a crash mid-send shows SENDING/FAILED on restart, never a false SENT.
 * NULL in the database means SENT (legacy rows predate this column; kept for read-compat).
 */
public enum class SendStatus {
  SENDING,
  SENT,
  FAILED,
}

/**
 * UI-facing delivery state for a merged chat message. Mirrors [SendStatus] read back from disk
 * (SENDING/FAILED are explicit column values; NULL/anything else reads as SENT).
 */
public enum class DeliveryStatus {
  SENDING,
  SENT,
  FAILED,
}

/**
 * Merged chat message model — the UI-facing type returned by
 * [MessageRepository.getMessagesForDevice].
 *
 * Persisted chat row (SENDING/SENT/FAILED).
 */
public data class ChatMessage(
  /** Stable unique id — the DB row id. */
  public val id: Long,
  public val remoteDeviceId: String,
  public val content: String,
  public val timestamp: Long,
  public val isSender: Boolean,
  public val messageType: String,
  public val fileTransferId: Long?,
  public val isRead: Long,
  public val mimeType: String,
  /** Delivery state derived from outbox (SENDING) or disk send_status (SENT/FAILED). */
  public val deliveryStatus: DeliveryStatus,
)
