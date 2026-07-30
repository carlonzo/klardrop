package com.carlom.klardrop.android.share

import android.content.Context
import com.carlom.klardrop.common.communication.TransferAnchor
import com.carlom.klardrop.common.utils.log

/**
 * Android's [TransferAnchor]: registers the transfer in [ActiveTransfers] and makes sure
 * [FileTransferService] is up, so the process holds a `dataSync` foreground component — plus a
 * partial wake lock and a WifiLock — for as long as anything is transferring.
 *
 * This covers every file transfer in the app, in both directions:
 *  - **outgoing** — the chat screen, the discovery screen, and the batches [FileTransferService]
 *    itself runs for the share sheet (those already have the service up, so their anchor calls
 *    just add a notification entry).
 *  - **incoming** — every accepted receive, Klardrop and Nearby Share alike. This is the direction
 *    that needs it most: the user taps Accept and puts the phone down, so the entire transfer
 *    happens with the app backgrounded and the screen off, which without an anchor means the
 *    process is frozen (and the socket with it) within a minute or two.
 *
 * Registry-first ordering matters: [ActiveTransfers.begin] runs *before* the service is asked to
 * start, so the service can never observe an empty registry on startup and immediately stop itself.
 */
class AndroidTransferAnchor(context: Context) : TransferAnchor {

  private val appContext = context.applicationContext

  override fun begin(transferId: String, label: String, direction: TransferAnchor.Direction) {
    ActiveTransfers.begin(transferId, label, direction)
    // Android 12+ forbids starting a foreground service from the background. An outgoing transfer
    // is started by the user with the app on screen, so it succeeds; an incoming one relies on the
    // app being either on screen or already running a foreground service (the opt-in "stay
    // discoverable" one) — which is the only way the process was alive to receive the connection
    // in the first place. When the start is refused the transfer still runs, just unanchored, so
    // never let that failure propagate into the transfer itself.
    runCatching { FileTransferService.anchor(appContext) }
      .onFailure { log("AndroidTransferAnchor", "Could not start the transfer service", it) }
  }

  override fun progress(transferId: String, percentage: Int) {
    ActiveTransfers.progress(transferId, percentage)
  }

  override fun end(transferId: String) {
    // The service notices the registry draining and shuts itself down after a short grace period.
    ActiveTransfers.end(transferId)
  }
}
