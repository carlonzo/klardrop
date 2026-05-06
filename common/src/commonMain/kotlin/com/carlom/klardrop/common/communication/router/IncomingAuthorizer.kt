package com.carlom.klardrop.common.communication.router

import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Gates incoming transfers behind a user accept/reject decision when the sender is not
 * a trusted/paired device.
 *
 * Policy:
 *  - Trusted senders (per [TrustManager.isTrusted]) bypass the prompt entirely.
 *  - For untrusted senders, the first interaction (file or text) prompts the user.
 *  - Once a user has accepted *any* message from an untrusted device in the current
 *    process, subsequent **text** from that device flows without a prompt — but
 *    **files always prompt** because they're heavier and a different threat model
 *    (overwrite local files, fill disk).
 *  - The "first contact" set is process-scoped (in-memory). Restarting the app clears
 *    it; the user is re-prompted on the next text from an untrusted sender.
 *
 * The prompt itself is plumbed through the existing [ReceiveMessageStatus.PendingAuthorization]
 * status which the UI banner / chat already render — we just emit it on the device's
 * receive flow and suspend until the UI invokes the embedded `acceptTransfer` callback.
 */
open class IncomingAuthorizer(
  private val trustManager: TrustManager,
) {

  enum class TransferKind { FILE, TEXT }

  /**
   * Remote device IDs that have accepted at least one transfer from us this process
   * lifetime. Used to skip the prompt on subsequent text messages (files always prompt).
   *
   * Guarded by [setMutex]. Process-scoped in-memory state — intentionally not persisted
   * so a restart re-prompts; longer-lived trust should go through the pairing flow.
   */
  private val firstContactAccepted = mutableSetOf<String>()
  private val setMutex = Mutex()

  /**
   * Suspends until the user has decided whether to accept the transfer. Returns true
   * if accepted (or auto-accepted), false if explicitly rejected.
   *
   * [receiveFlow] is the same flow the message router uses to emit Started/Progress/
   * Completed updates for this device — we reuse it so the UI's existing subscriptions
   * see the [ReceiveMessageStatus.PendingAuthorization] state without any extra wiring.
   * On reject, sets the flow to [ReceiveMessageStatus.Failed] so the banner card
   * naturally completes and clears.
   */
  open suspend fun authorize(
    fromDeviceId: String,
    kind: TransferKind,
    headers: List<Message>,
    receiveFlow: MutableStateFlow<ReceiveMessageUpdate>,
  ): Boolean {
    if (trustManager.isTrusted(fromDeviceId)) {
      log("IncomingAuthorizer", "Auto-accepting transfer from trusted device $fromDeviceId")
      return true
    }

    if (kind == TransferKind.TEXT) {
      val alreadyAccepted = setMutex.withLock { fromDeviceId in firstContactAccepted }
      if (alreadyAccepted) {
        log("IncomingAuthorizer", "Auto-accepting text from $fromDeviceId (already accepted first contact)")
        return true
      }
    }

    log("IncomingAuthorizer", "Prompting user for $kind transfer from untrusted $fromDeviceId")
    val deferred = CompletableDeferred<Boolean>()
    receiveFlow.update {
      it.copy(
        messages = headers,
        status = ReceiveMessageStatus.PendingAuthorization { accept ->
          deferred.complete(accept)
        },
      )
    }
    val accepted = deferred.await()

    if (accepted) {
      setMutex.withLock { firstContactAccepted.add(fromDeviceId) }
      log("IncomingAuthorizer", "User accepted $kind from $fromDeviceId")
    } else {
      receiveFlow.update {
        it.copy(status = ReceiveMessageStatus.Failed("Declined"))
      }
      log("IncomingAuthorizer", "User declined $kind from $fromDeviceId")
    }
    return accepted
  }
}
