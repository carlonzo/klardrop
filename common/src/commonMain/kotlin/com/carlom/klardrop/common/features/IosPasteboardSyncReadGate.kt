package com.carlom.klardrop.common.features

/**
 * Session-scoped iOS pasteboard policy for background clipboard sync.
 *
 * `UIPasteboard.string == nil` is both "empty" and "user dismissed Allow Paste".
 * Latch-on-empty would disable sync for the whole process whenever the first poll
 * saw an empty board, so presence is checked with `hasStrings` after `changeCount`
 * moves — never as a 500ms poll, and never after this process already declined.
 *
 * 1. Unchanged [changeCount] → return cache. Do not read `string` / `hasStrings`.
 * 2. Already declined this process → update [lastChangeCount], return cache.
 *    Do not read `string` / `hasStrings`.
 * 3. `!hasStrings` → cache `""`. Not a decline.
 * 4. Else read `string`. Non-empty → allowed; keep syncing on future counts.
 *    Empty + `hasStrings` → declined this session; never read `string` again
 *    until process death (or [clearDecline] / a successful user [recordUserRead]).
 */
class IosPasteboardSyncReadGate {
  private var lastChangeCount = -1L
  private var cached = ""
  private var declinedThisSession = false

  /**
   * Whether the iOS actual may touch `hasStrings` / `string`.
   * False when [changeCount] is unchanged or this process already declined.
   */
  fun shouldProbePasteboard(changeCount: Long): Boolean =
    changeCount != lastChangeCount && !declinedThisSession

  fun readForSync(
    changeCount: Long,
    hasStrings: Boolean,
    readString: () -> String,
  ): String {
    if (changeCount == lastChangeCount) return cached
    lastChangeCount = changeCount
    if (declinedThisSession) return cached
    if (!hasStrings) {
      cached = ""
      return cached
    }
    val value = readString()
    if (value.isEmpty()) {
      declinedThisSession = true
      return cached
    }
    cached = value
    return cached
  }

  /**
   * User-initiated Paste. Always called after a real `string` read.
   * Non-empty content means they Allowed; clear the session decline so sync can resume.
   */
  fun recordUserRead(changeCount: Long, content: String): String {
    lastChangeCount = changeCount
    cached = content
    if (content.isNotEmpty()) {
      declinedThisSession = false
    }
    return content
  }

  fun clearDecline() {
    declinedThisSession = false
  }
}
