package com.carlom.klardrop.common.discovery

/**
 * Dedupe + log-throttle decisions for the mDNS ingestion seam in [DiscoveryNetwork].
 *
 * jmDNS re-delivers the same service many times per minute (announcement refreshes,
 * TXT-record arrivals), and every delivery used to become a full
 * `onNewDeviceVisible` merge plus a log line. This filter separates *signal* (new
 * endpoint, changed identity) from *noise* (identical re-resolution):
 *
 * - [classify]: an event is a [IngestOutcome.Duplicate] only when the exact
 *   endpoint is already stored **and** the identity is byte-identical. Anything
 *   else (new device, port/IP move, rename, richer TXT) is [IngestOutcome.Process],
 *   so endpoint changes and enrichment always flow through to [VisibleDevices].
 * - [touchDueForDuplicate]: duplicates still prove liveness for the 5-minute TTL
 *   sweep, but at most once per [duplicateTouchIntervalMs] per device instead of
 *   once per resolution.
 * - [shouldLogInvalid]: transient resolutions (empty TXT, not-yet-valid) are
 *   expected mid-resolution, not faults — log the verdict once per service, not
 *   once per delivery. Reset on mDNS rebuild ([onMdnsRebuilt]).
 *
 * Pure apart from the [nowMs] time source, so it stays unit-testable without the
 * platform mDNS backend.
 */
internal class DiscoveryIngestFilter(
  private val nowMs: () -> Long,
  private val duplicateTouchIntervalMs: Long = DUPLICATE_TOUCH_INTERVAL_MS,
) {

  private val lastDuplicateTouchMs = mutableMapOf<String, Long>()
  private val invalidLoggedKeys = mutableSetOf<String>()

  fun classify(
    deviceInfo: DeviceInfo,
    deviceConnection: DeviceConnection,
    existing: DiscoveryDevice?,
  ): IngestOutcome {
    if (existing == null) return IngestOutcome.Process
    if (!existing.deviceConnections.contains(deviceConnection)) return IngestOutcome.Process
    if (existing.deviceInfo != deviceInfo) return IngestOutcome.Process
    return IngestOutcome.Duplicate
  }

  /**
   * Returns true when a liveness touch is due for a duplicate sighting, and records
   * it. First duplicate sighting always touches; afterwards at most once per
   * [duplicateTouchIntervalMs]. The interval is far below the 5-minute visibility
   * TTL, so a steadily-announcing peer can never go stale, while a resolution
   * storm collapses to ~2 touches/minute/peer.
   */
  fun touchDueForDuplicate(deviceId: String): Boolean {
    val now = nowMs()
    val last = lastDuplicateTouchMs[deviceId]
    return if (last == null || now - last >= duplicateTouchIntervalMs) {
      lastDuplicateTouchMs[deviceId] = now
      true
    } else {
      false
    }
  }

  /** One-shot per service key; callers reset via [onMdnsRebuilt]. */
  fun shouldLogInvalid(serviceKey: String): Boolean {
    if (invalidLoggedKeys.size > MAX_INVALID_KEYS) invalidLoggedKeys.clear()
    return invalidLoggedKeys.add(serviceKey)
  }

  /** mDNS state was rebuilt (network change) — prior verdicts may be stale. */
  fun onMdnsRebuilt() {
    invalidLoggedKeys.clear()
    lastDuplicateTouchMs.clear()
  }

  companion object {
    /**
     * Minimum gap between liveness touches from duplicate resolutions.
     * 30s keeps two orders of magnitude of headroom under the 5-minute device
     * TTL while collapsing a ~9-resolutions/10s storm to ~2 touches/minute.
     */
    const val DUPLICATE_TOUCH_INTERVAL_MS = 30_000L

    private const val MAX_INVALID_KEYS = 512
  }
}

internal sealed interface IngestOutcome {
  data object Process : IngestOutcome
  data object Duplicate : IngestOutcome
}
