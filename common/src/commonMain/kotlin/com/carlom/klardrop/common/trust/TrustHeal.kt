package com.carlom.klardrop.common.trust

import com.carlom.klardrop.common.communication.message.TrustRevocationMessage
import com.carlom.klardrop.common.discovery.DiscoveryDevice
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

/**
 * After a reinstall the same physical peer shows up under a new short id at the same
 * IP, while we still hold trust for the old id (offline row). Those old ids are the
 * ones sharing [address] with [keepDeviceId] and still marked trusted.
 */
internal suspend fun dropSupersededTrust(
  keepDeviceId: String,
  address: String,
  visibleDevices: com.carlom.klardrop.common.discovery.VisibleDevices,
  trustManager: TrustManager,
) {
  val trusted = trustManager.getTrustedDevices().map { it.deviceId }.toSet()
  val ids = supersededTrustedIds(
    keepDeviceId = keepDeviceId,
    address = address,
    devices = visibleDevices.visibleDevices.value,
    isTrusted = { it in trusted },
  )
  for (id in ids) {
    log("TrustHeal", "Dropping superseded trust $id (same address $address as $keepDeviceId)")
    trustManager.removeTrust(id)
  }
}

internal fun supersededTrustedIds(
  keepDeviceId: String,
  address: String,
  devices: Map<String, DiscoveryDevice>,
  isTrusted: (String) -> Boolean,
): List<String> {
  if (address.isBlank()) return emptyList()
  return devices.values.mapNotNull { device ->
    val id = device.deviceInfo.deviceId
    if (id == keepDeviceId) return@mapNotNull null
    if (!device.deviceConnections.any { it.address == address }) return@mapNotNull null
    if (!isTrusted(id)) return@mapNotNull null
    id
  }
}
