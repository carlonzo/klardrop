package com.carlom.klardrop.android.share

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide registry of outbound transfers that currently need the app kept alive.
 *
 * [AndroidOutgoingTransferAnchor] writes to it (driven by `Messenger.send`, wherever the send was
 * started from) and [FileSendService] reads it: the service renders the map into its ongoing
 * notification and stops itself once the map drains. That indirection is deliberate — the transfer
 * runs in the app process's messenger scope, not inside the service, so the service can't observe
 * it directly and must not own it.
 *
 * Distinct from [ActiveSends], which mirrors a *single* share-sheet transfer's progress back to the
 * sheet UI. This one is the aggregate "is anything sending right now" view.
 */
object OutgoingTransfers {

  /** @param percentage null until the receiver accepts and bytes actually start moving. */
  data class Entry(val label: String, val percentage: Int?)

  private val _state = MutableStateFlow<Map<String, Entry>>(emptyMap())

  /** Insertion-ordered; the first entry is the oldest in-flight transfer. */
  val state: StateFlow<Map<String, Entry>> = _state.asStateFlow()

  fun begin(id: String, label: String) {
    _state.update { it + (id to Entry(label, percentage = null)) }
  }

  fun progress(id: String, percentage: Int) {
    _state.update { current ->
      // Ignore progress for an id we never began (or already ended) — a late tick must not
      // resurrect a finished transfer and keep the service alive forever.
      val existing = current[id] ?: return@update current
      current + (id to existing.copy(percentage = percentage.coerceIn(0, 100)))
    }
  }

  fun end(id: String) {
    _state.update { it - id }
  }

  fun isEmpty(): Boolean = _state.value.isEmpty()
}
