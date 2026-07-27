package com.carlom.klardrop.common.communication

/**
 * Platform hook that keeps the host process — and with it the open socket carrying the bytes —
 * alive for the duration of an outbound payload transfer.
 *
 * A file send is not a quick round trip: it first waits for the receiver to accept (which can take
 * as long as the user takes to look at their phone), then streams for as long as the file is big.
 * On Android that whole window is exactly when the OS is most likely to freeze or kill a process
 * whose Activity just went away — the user hits send, switches app, and the transfer dies. The
 * transfer itself already runs in [MessengerImpl]'s own process-lifetime scope, so nothing in the
 * *app* cancels it; only the platform does. This hook is where a platform gets to say "not yet".
 *
 * [MessengerImpl] calls [begin] before a payload-bearing send starts, feeds [progress] as bytes go
 * out, and always calls [end] — success, failure, or cancellation. Implementations must therefore
 * be cheap, thread-safe (calls arrive on the messenger's IO scope), and must never throw: a
 * platform that can't anchor right now should degrade to doing nothing rather than fail the send.
 *
 * Android backs this with a `dataSync` foreground service (see `AndroidOutgoingTransferAnchor`).
 * Desktop, iOS and macOS use [None] — desktop processes aren't killed for being idle, and iOS
 * gives a backgrounded app no equivalent lever.
 */
interface OutgoingTransferAnchor {

  /**
   * A transfer identified by [transferId] is starting. [label] is a human-readable name for the
   * payload (usually the file name) that platforms may surface in a progress notification.
   */
  fun begin(transferId: String, label: String)

  /** Best-effort progress for an in-flight [transferId]; [percentage] is 0..100. */
  fun progress(transferId: String, percentage: Int)

  /** The transfer reached a terminal state. Always called exactly once per [begin]. */
  fun end(transferId: String)

  companion object {
    /** No-op anchor for platforms that don't need (or can't provide) one. */
    val None: OutgoingTransferAnchor = object : OutgoingTransferAnchor {
      override fun begin(transferId: String, label: String) = Unit
      override fun progress(transferId: String, percentage: Int) = Unit
      override fun end(transferId: String) = Unit
    }
  }
}
