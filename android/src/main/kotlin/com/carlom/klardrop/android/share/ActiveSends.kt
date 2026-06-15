package com.carlom.klardrop.android.share

import com.carlom.klardrop.common.communication.MessengerSendProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide registry of in-flight outgoing sends so the share sheet (an Activity) and
 * [FileSendService] (a foreground service) can observe the *same* transfer without either owning
 * it. The transfer itself runs inside the service; this is just the shared live-progress view.
 *
 * ponytail: in-memory only and never pruned — a transfer that outlives the process is already a
 * FileTransfer row in MessageRepository (the durable source). This overlay only carries the
 * ephemeral bits (waiting-to-accept / live %) that aren't worth a DB write per chunk. One tiny
 * StateFlow entry per file share; the share process is short-lived, so the leak is bounded.
 */
object ActiveSends {
  private val seq = AtomicLong(0)
  private val flows = ConcurrentHashMap<String, MutableStateFlow<MessengerSendProgress>>()

  /** Allocate a transfer id, seeded with [MessengerSendProgress.Pending]. */
  fun create(): String {
    val id = seq.incrementAndGet().toString()
    flows[id] = MutableStateFlow(MessengerSendProgress.Pending)
    return id
  }

  fun publish(id: String, progress: MessengerSendProgress) {
    flows[id]?.value = progress
  }

  /** Live state for [id], or null if it was never created. */
  fun flow(id: String): StateFlow<MessengerSendProgress>? = flows[id]
}
