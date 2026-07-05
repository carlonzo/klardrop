package com.carlom.klardrop.common.persistence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * An in-flight (sending) message entry held in the in-memory outbox.
 *
 * NOTE: outgoing TEXT sends no longer populate this — [Messenger.send][com.carlom.klardrop.common.communication.Messenger]
 * now persists a single SENDING row up front and flips it to SENT/FAILED itself (see
 * docs/connection-review.md F12/F13), so the disk row alone drives the merged read's
 * [DeliveryStatus] for text. This class is kept for the merge machinery in
 * [MessageRepository.getMessagesForDevice] and any other caller that wants a purely
 * in-memory, never-persisted optimistic entry.
 */
data class OutboxEntry(
  val messageId: Long,
  val remoteDeviceId: String,
  val content: String,
  val timestamp: Long,
)

/**
 * In-memory outbox for in-flight outgoing messages.
 *
 * Thread-safe via [MutableStateFlow]. Exposed as a [StateFlow] so the repository can
 * combine it with the SQLDelight disk flow for the merged chat read.
 */
class MessageOutbox {
  private val _entries = MutableStateFlow<List<OutboxEntry>>(emptyList())
  val entries: StateFlow<List<OutboxEntry>> = _entries.asStateFlow()

  /** Add an entry to the outbox (optimistic / SENDING state). */
  fun add(entry: OutboxEntry) {
    _entries.update { current -> current + entry }
  }

  /** Remove an entry by its [messageId] (called on Completed or Error). */
  fun remove(messageId: Long) {
    _entries.update { current -> current.filter { it.messageId != messageId } }
  }
}
