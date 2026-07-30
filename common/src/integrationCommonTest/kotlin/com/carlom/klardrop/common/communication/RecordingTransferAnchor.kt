package com.carlom.klardrop.common.communication

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * [TransferAnchor] that just records what it was told, in order.
 *
 * Backed by a [MutableStateFlow] rather than a plain list because the messenger (sending side) and
 * the router (receiving side) drive the anchor from their own IO scopes — in this harness that's
 * real threads, so appends have to be atomic.
 */
internal class RecordingTransferAnchor : TransferAnchor {

  sealed interface Event {
    val transferId: String

    data class Begin(
      override val transferId: String,
      val label: String,
      val direction: TransferAnchor.Direction,
    ) : Event

    data class Progress(override val transferId: String, val percentage: Int) : Event
    data class End(override val transferId: String) : Event
  }

  private val _events = MutableStateFlow<List<Event>>(emptyList())

  val events: List<Event> get() = _events.value

  override fun begin(transferId: String, label: String, direction: TransferAnchor.Direction) {
    _events.update { it + Event.Begin(transferId, label, direction) }
  }

  override fun progress(transferId: String, percentage: Int) {
    _events.update { it + Event.Progress(transferId, percentage) }
  }

  override fun end(transferId: String) {
    _events.update { it + Event.End(transferId) }
  }
}
