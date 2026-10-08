package com.carlom.klardrop.control

import com.carlom.klardrop.common.utils.Clock
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * Bounded, in-memory record of the share operations accepted by `POST /share`, so a client can
 * later correlate a result with the exact request it submitted.
 *
 * This is deliberately NOT a second transfer database: it holds nothing but the submission's
 * identity and the last known outcome of its items, and it forgets. A lookup after eviction or
 * after a daemon restart reports the request as unknown (404) — never a synthesized success.
 *
 * Retention policy:
 *  - at most [MAX_REQUESTS] requests are kept; the least recently inserted one is evicted first
 *  - a request older than [TTL_MILLIS] is pruned
 *  - pruning runs on every insert and on every lookup, so expiry does not depend on a sweeper
 *
 * All state is guarded by [lock] because this is a singleton touched from the HTTP dispatcher
 * and from the transfer-progress collectors on the app scope, which can run concurrently. No
 * critical section suspends, so the lock is never held across a suspension point and can never
 * deadlock. Kotlin has no `synchronized` in common code and Kotlin/Native has none at all, so a
 * coroutine Mutex is the only portable monitor here.
 *
 * Every operation is therefore `suspend`. `ControlPlane.stop()` — which is not `suspend`, by
 * interface — clears the registry through `runBlocking`, which is safe because the critical
 * section it waits for is a few map operations wide.
 */
object ShareRegistry {

  /** Maximum number of retained share requests. Older requests are evicted first. */
  const val MAX_REQUESTS = 256

  /** How long a request stays queryable after it was accepted (2 hours). */
  const val TTL_MILLIS = 2L * 60 * 60 * 1000

  private val lock = Mutex()

  /** Insertion-ordered: iteration order is the eviction order. */
  private val requests = LinkedHashMap<String, ShareRequestRecord>()

  /** transfer id -> request id, for progress reported by an in-flight transfer. */
  private val requestsByTransferId = HashMap<String, String>()

  @Volatile
  private var nowProvider: () -> Long = { Clock().currentTimeMillis() }

  /** Replaces the clock used for TTL pruning. Pass null to restore the system clock. */
  internal fun setNowProviderForTesting(provider: (() -> Long)?) {
    nowProvider = provider ?: { Clock().currentTimeMillis() }
  }

  /**
   * Records a new request and returns it. [items] must already carry their per-item state
   * (a preparation failure is recorded as such here, never as a queued item).
   */
  suspend fun register(
    deviceId: String,
    kind: String,
    items: List<ShareItem>,
  ): ShareRequestRecord = lock.withLock {
    pruneLocked(nowProvider())
    val now = nowProvider()
    val record = ShareRequestRecord(
      requestId = nextRequestId(),
      deviceId = deviceId,
      kind = kind,
      createdAt = now,
      updatedAt = now,
      items = items.map { it.copy() }.toMutableList(),
    )
    requests[record.requestId] = record
    while (requests.size > MAX_REQUESTS) {
      val oldest = requests.keys.firstOrNull() ?: break
      evictLocked(oldest)
    }
    record
  }

  /** Associates an in-flight transfer id with the request that submitted it. */
  suspend fun bind(transferId: String, requestId: String): Boolean = lock.withLock {
    if (requests[requestId] == null) return@withLock false
    requestsByTransferId[transferId] = requestId
    true
  }

  /**
   * Records the latest known outcome of the transfer minted for [transferId]. A no-op when the
   * transfer was not submitted through `POST /share`, or when its item already reached a
   * terminal state (a late event must not resurrect a completed or declined item).
   *
   * Returns true when the registry changed.
   */
  suspend fun update(
    transferId: String,
    status: String,
    transferredSize: Long? = null,
    error: String? = null,
  ): Boolean = lock.withLock {
    val requestId = requestsByTransferId[transferId] ?: return@withLock false
    val record = requests[requestId] ?: return@withLock false
    val item = record.items.firstOrNull { it.transferId == transferId } ?: return@withLock false
    if (item.status.isTerminal()) return@withLock false
    val now = nowProvider()
    item.status = status
    item.error = error
    if (transferredSize != null) item.transferredSize = transferredSize
    record.updatedAt = now
    true
  }

  /** The request with [requestId], or null once it has been evicted or expired. */
  suspend fun get(requestId: String): ShareRequestRecord? = lock.withLock {
    pruneLocked(nowProvider())
    requests[requestId]?.snapshot()
  }

  /** Every retained request, newest first. */
  suspend fun list(): List<ShareRequestRecord> = lock.withLock {
    pruneLocked(nowProvider())
    requests.values.toList().asReversed().map { it.snapshot() }
  }

  /** Drops every record and every transfer binding. Used by `ControlPlane.stop()` and by tests. */
  suspend fun reset() = lock.withLock {
    requests.clear()
    requestsByTransferId.clear()
  }

  /** True for an outcome that will never change again. */
  private fun String.isTerminal(): Boolean =
    this == ShareStatus.COMPLETED || this == ShareStatus.DECLINED || this == ShareStatus.FAILED

  private fun pruneLocked(now: Long) {
    val expired = requests.values.filter { now - it.createdAt > TTL_MILLIS }.map { it.requestId }
    expired.forEach { evictLocked(it) }
  }

  private fun evictLocked(requestId: String) {
    val record = requests.remove(requestId) ?: return
    record.items.forEach { item ->
      item.transferId?.let { requestsByTransferId.remove(it) }
    }
  }

  private fun nextRequestId(): String =
    "req-" + Random.nextBytes(8).joinToString("") {
      (it.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}

/** The per-item statuses a share item can report. */
object ShareStatus {
  const val QUEUED = "queued"
  const val AWAITING = "awaiting"
  const val TRANSFERRING = "transferring"
  const val COMPLETED = "completed"
  const val DECLINED = "declined"
  const val FAILED = "failed"
}

/** The mutually exclusive payload kinds `POST /share` accepts. */
object ShareKind {
  const val FILES = "files"
  const val TEXT = "text"
  const val CLIPBOARD = "clipboard"
}

/** One submitted item of a share request. Mutated in place under [ShareRegistry]'s lock. */
data class ShareItem(
  /** Wire id of the transfer message, or null when the item failed before any transfer existed. */
  val transferId: String?,
  /** Submitted path for a file, null for text/clipboard. */
  val path: String?,
  /** Submitted file name, null for text/clipboard or when the path could not be resolved. */
  val fileName: String?,
  val totalSize: Long = 0L,
  var transferredSize: Long = 0L,
  var status: String = ShareStatus.QUEUED,
  var error: String? = null,
)

/** One accepted share request. Mutated in place under [ShareRegistry]'s lock. */
data class ShareRequestRecord(
  val requestId: String,
  val deviceId: String,
  val kind: String,
  val createdAt: Long,
  var updatedAt: Long,
  val items: MutableList<ShareItem>,
) {
  /**
   * The request-level status, derived from its items: any failure first (a partially failed
   * multi-file request is never reported as a success), then a decline, then all-complete,
   * otherwise still [ShareStatus.QUEUED].
   */
  val status: String
    get() = when {
      items.any { it.status == ShareStatus.FAILED } -> ShareStatus.FAILED
      items.any { it.status == ShareStatus.DECLINED } -> ShareStatus.DECLINED
      items.isNotEmpty() && items.all { it.status == ShareStatus.COMPLETED } -> ShareStatus.COMPLETED
      else -> ShareStatus.QUEUED
    }

  /** Immutable view for a reader that must not see later in-place mutations. */
  fun snapshot(): ShareRequestRecord = copy(items = items.map { it.copy() }.toMutableList())
}
