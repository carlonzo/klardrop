package com.carlom.klardrop.common.qrshare

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal data class ClaimedSlot(
  val token: String,
  val boundIp: String,
  var lastHttpActivity: Instant,
  var downloadsInFlight: Int = 0,
)

internal sealed interface TokenAccessResult {
  data class Allowed(
    val token: String,
    val isNewClaim: Boolean,
    val fileIndex: Int?,
  ) : TokenAccessResult

  data object Denied : TokenAccessResult // 404
  data object Capped : TokenAccessResult // 503
}

internal data class ParsedSharePath(val token: String, val fileIndex: Int?)

internal fun parseSharePath(path: String): ParsedSharePath? {
  val cleanPath = path.substringBefore('?')
  val segments = cleanPath.split('/').filter { it.isNotEmpty() }
  if (segments.isEmpty() || segments[0] != "s") return null
  return when (segments.size) {
    2 -> {
      val token = segments[1]
      if (token.isEmpty()) null else ParsedSharePath(token, null)
    }
    4 -> {
      if (segments[2] != "file") return null
      val token = segments[1]
      val index = segments[3].toIntOrNull() ?: return null
      if (index < 0 || token.isEmpty()) return null
      ParsedSharePath(token, index)
    }
    else -> null
  }
}

internal class QrTokenTable(
  private val clock: Clock,
  private val postArrivalGrace: Duration = 2.minutes,
  private val maxClaimed: Int = 8,
  private val maxConcurrentDownloads: Int = 8,
  initialWaitingToken: String? = null,
) {
  private val mutex = Mutex()
  var waitingToken: String? = initialWaitingToken
    private set

  private val claimed = mutableMapOf<String, ClaimedSlot>()

  private fun evictExpiredLocked(now: Instant) {
    claimed.entries.removeAll { (_, slot) ->
      slot.downloadsInFlight == 0 && (now - slot.lastHttpActivity) >= postArrivalGrace
    }
  }

  suspend fun setWaiting(token: String?) {
    mutex.withLock {
      waitingToken = token
    }
  }

  suspend fun claimWaiting(peerIpv4: String): Boolean = mutex.withLock {
    if (peerIpv4.isBlank()) return false
    val now = clock.now()
    evictExpiredLocked(now)
    val token = waitingToken ?: return false
    if (claimed.size >= maxClaimed) {
      return false
    }
    claimed[token] = ClaimedSlot(
      token = token,
      boundIp = peerIpv4,
      lastHttpActivity = now,
      downloadsInFlight = 0,
    )
    waitingToken = null
    return true
  }

  suspend fun resolveAccess(path: String, peerIpv4: String): TokenAccessResult = mutex.withLock {
    if (peerIpv4.isBlank()) {
      return TokenAccessResult.Denied
    }

    val now = clock.now()
    evictExpiredLocked(now)

    val parsed = parseSharePath(path) ?: return TokenAccessResult.Denied
    val (token, fileIndex) = parsed

    if (token == waitingToken) {
      if (claimed.size >= maxClaimed) {
        return TokenAccessResult.Capped
      }
      claimed[token] = ClaimedSlot(
        token = token,
        boundIp = peerIpv4,
        lastHttpActivity = now,
        downloadsInFlight = 0,
      )
      waitingToken = null
      return TokenAccessResult.Allowed(token = token, isNewClaim = true, fileIndex = fileIndex)
    }

    val slot = claimed[token]
    if (slot != null) {
      if (slot.boundIp == peerIpv4) {
        slot.lastHttpActivity = now
        return TokenAccessResult.Allowed(token = token, isNewClaim = false, fileIndex = fileIndex)
      } else {
        return TokenAccessResult.Denied
      }
    }

    return TokenAccessResult.Denied
  }

  suspend fun startDownload(token: String): Boolean = mutex.withLock {
    val totalDownloads = claimed.values.sumOf { it.downloadsInFlight }
    if (totalDownloads >= maxConcurrentDownloads) {
      return false
    }
    val slot = claimed[token] ?: return false
    slot.downloadsInFlight++
    slot.lastHttpActivity = clock.now()
    return true
  }

  suspend fun endDownload(token: String): Unit = mutex.withLock {
    val slot = claimed[token]
    if (slot != null) {
      slot.downloadsInFlight = maxOf(0, slot.downloadsInFlight - 1)
      slot.lastHttpActivity = clock.now()
    }
    evictExpiredLocked(clock.now())
  }

  suspend fun bumpActivity(token: String): Unit = mutex.withLock {
    claimed[token]?.lastHttpActivity = clock.now()
  }

  suspend fun evictExpired(): Unit = mutex.withLock {
    evictExpiredLocked(clock.now())
  }

  suspend fun getClaimedCount(): Int = mutex.withLock {
    evictExpiredLocked(clock.now())
    claimed.size
  }

  suspend fun getClaimedSlot(token: String): ClaimedSlot? = mutex.withLock {
    claimed[token]?.copy()
  }

  fun reset() {
    waitingToken = null
    claimed.clear()
  }

  suspend fun clear(): Unit = mutex.withLock {
    reset()
  }
}
