package com.carlom.klardrop.android.share

import android.content.Context
import com.carlom.klardrop.common.communication.OutgoingTransferAnchor
import com.carlom.klardrop.common.utils.log

/**
 * Android's [OutgoingTransferAnchor]: registers the transfer in [OutgoingTransfers] and makes sure
 * [FileSendService] is up, so the process holds a `dataSync` foreground component for as long as
 * anything is sending.
 *
 * This covers every outbound file send in the app — the chat screen, the discovery screen, and the
 * batches [FileSendService] itself runs for the share sheet (those already have the service up, so
 * their anchor calls just add a notification entry). Before this existed only the share-sheet path
 * was protected: hitting send in-app and switching away left the transfer to be frozen or killed
 * mid-stream, because nothing in the app was foreground any more.
 *
 * Registry-first ordering matters: [OutgoingTransfers.begin] runs *before* the service is asked to
 * start, so the service can never observe an empty registry on startup and immediately stop itself.
 */
class AndroidOutgoingTransferAnchor(context: Context) : OutgoingTransferAnchor {

  private val appContext = context.applicationContext

  override fun begin(transferId: String, label: String) {
    OutgoingTransfers.begin(transferId, label)
    // Android 12+ forbids starting a foreground service from the background. In practice a send
    // is started by the user with the app on screen, so this succeeds; when it doesn't (an
    // automatic retry while backgrounded, say) the transfer still runs — just unanchored, exactly
    // as it did before. Never let that failure propagate into the send.
    runCatching { FileSendService.anchor(appContext) }
      .onFailure { log("AndroidOutgoingTransferAnchor", "Could not start the transfer service", it) }
  }

  override fun progress(transferId: String, percentage: Int) {
    OutgoingTransfers.progress(transferId, percentage)
  }

  override fun end(transferId: String) {
    // The service notices the registry draining and shuts itself down after a short grace period.
    OutgoingTransfers.end(transferId)
  }
}
