package com.carlom.klardrop.common.persistence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * An in-flight (sending) message entry held in the in-memory outbox.
 *
 * These entries are NEVER persisted to disk. A send in progress lives only here.
 * On send Completed: the outbox entry is removed (handleOutgoing already persisted the SENT row).
 * On send Error: the message is persisted as FAILED and the outbox entry is removed.
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
