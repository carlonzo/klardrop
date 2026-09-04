package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.trust.revocationIfPeerStale
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

internal fun CoroutineScope.launchStaleTrustRevocation(
  messenger: ConnectionMessenger,
  trustManager: TrustManager,
  peerId: String,
  peerClaimsTrust: Boolean,
) {
  launch {
    val revocation = runCatching {
      revocationIfPeerStale(trustManager, peerId, peerClaimsTrust)
    }.onFailure {
      log("TrustHeal", "Failed building revocation for $peerId", it)
    }.getOrNull() ?: return@launch
    runCatching {
      val flow = MutableSharedFlow<MessengerSendProgress>(extraBufferCapacity = 8)
      messenger.send(revocation.toSimpleSendRequest(), flow)
    }.onFailure {
      log("TrustHeal", "Failed sending connect-time revocation to $peerId", it)
    }
  }
}
