package com.carlom.klardrop.android.share

import com.carlom.klardrop.common.communication.TransferAnchor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide registry of file transfers — in either direction — that currently need the app kept
 * alive and the device kept awake.
 *
 * [AndroidTransferAnchor] writes to it (driven by `Messenger.send` for outgoing transfers and by
 * the receive pipeline for incoming ones, wherever either was started from) and
 * [FileTransferService] reads it: the service renders the map into its ongoing notification and
 * stops itself once the map drains. That indirection is deliberate — a transfer runs in the app
 * process's messenger/router scope, not inside the service, so the service can't observe it
 * directly and must not own it.
 *
 * Distinct from [ActiveSends], which mirrors a *single* share-sheet transfer's progress back to the
 * sheet UI. This one is the aggregate "is anything transferring right now" view.
 */
object ActiveTransfers {

  /**
   * @param percentage null until bytes actually start moving — for a send that's the receiver
   *   accepting, for a receive it's the first chunk landing. The notification renders null as an
   *   indeterminate bar rather than a 0% one that looks stuck.
   */
  data class Entry(
    val label: String,
    val percentage: Int?,
    val direction: TransferAnchor.Direction,
  )

  private val _state = MutableStateFlow<Map<String, Entry>>(emptyMap())

  /** Insertion-ordered; the first entry is the oldest in-flight transfer. */
  val state: StateFlow<Map<String, Entry>> = _state.asStateFlow()

  fun begin(id: String, label: String, direction: TransferAnchor.Direction) {
    _state.update { it + (id to Entry(label, percentage = null, direction = direction)) }
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
