package com.carlom.klardrop.common.trust

import com.carlom.klardrop.common.communication.message.TrustRevocationMessage
import com.carlom.klardrop.common.utils.log

/**
 * When a peer's handshake says they still trust us but we no longer trust them (we unpaired
 * while they were offline, or they reinstalled), notify them immediately with a signed
 * revocation instead of waiting for the next TrustedMessage.
 *
 * Never-paired peers advertise [peerClaimsTrust]=false, so this is silent for first contact.
 *
 * @return the revocation to send, or null if nothing should be sent
 */
internal suspend fun revocationIfPeerStale(
  trustManager: TrustManager,
  peerId: String,
  peerClaimsTrust: Boolean,
): TrustRevocationMessage? {
  if (!peerClaimsTrust) return null
  if (trustManager.isTrusted(peerId)) return null
  if (trustManager.hasOpenPairingSession(peerId)) {
    log("TrustHeal", "Peer $peerId claims trust during in-flight pairing; not revoking")
    return null
  }
  val revocation = trustManager.createRevocationMessage(peerId, reason = "device_unknown")
  if (revocation == null) {
    log("TrustHeal", "Peer $peerId claims trust we don't hold, but could not build a revocation")
    return null
  }
  log("TrustHeal", "Peer $peerId claims trust we no longer hold; sending revocation")
  return revocation
}
