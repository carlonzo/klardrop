package com.carlom.klardrop.common.features

import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

interface ClipboardSource {
  fun read(): String
  fun readForSync(): String
  fun write(text: String)
  /** Event-driven change notifications when supported by the platform (e.g. wl-paste --watch on Linux). Null falls back to polling. */
  fun changeSignals(): Flow<Unit>?
}

expect class ClipboardReaderWriter : ClipboardSource {
  /** User-initiated (chat Paste). iOS always reads `string`. */
  override fun read(): String
  /** Background poller only. iOS applies the session Allow Paste gate. */
  override fun readForSync(): String
  override fun write(text: String)
  override fun changeSignals(): Flow<Unit>?
}

/**
 * Read/write access to the local clipboard plus a stream of its changes.
 *
 * Narrow interface over [ClipboardManager] so consumers that only need clipboard access —
 * notably [com.carlom.klardrop.common.trust.ClipboardSyncManager], whose trust gating is
 * worth unit-testing — don't have to construct the platform `ClipboardReaderWriter`.
 */
interface ClipboardAccess {
  val flow: Flow<String>
  fun read(): String
  fun write(text: String)
}

class ClipboardManager(
  private val coroutines: Coroutines,
  private val readerWriter: ClipboardSource
) : ClipboardAccess {

  private val clipboardScope = coroutines.newScope(coroutines.ioDispatcher)

  override val flow = buildFlow()
    .distinctUntilChanged()
    .shareIn(clipboardScope, started = SharingStarted.WhileSubscribed())

  private fun buildFlow(): Flow<String> {
    val signals = readerWriter.changeSignals()
    return if (signals != null) {
      flow {
        // Emit initial read at subscription
        runCatching { readerWriter.readForSync() }
          .getOrDefault("")
          .takeIf { it.isNotEmpty() }
          ?.let { emit(it) }

        signals
          .catch {
            // If the signal source fails unexpectedly, fall back to polling for the rest of this collection
            while (currentCoroutineContext().isActive) {
              delay(500)
              emit(Unit)
            }
          }
          .collect {
            runCatching { readerWriter.readForSync() }
              .getOrDefault("")
              .takeIf { it.isNotEmpty() }
              ?.let { emit(it) }
          }
      }
    } else {
      callbackFlow {
        val collectionJob = coroutines.appScope.launch {
          while (isActive) {
            runCatching { readerWriter.readForSync() }
              .getOrDefault("")
              .takeIf { it.isNotEmpty() }
              ?.let { send(it) }

            delay(500)
          }
        }

        awaitClose {
          collectionJob.cancel()
        }
      }
    }
  }

  override fun write(text: String) {
    readerWriter.write(text)
  }

  override fun read(): String {
    return runCatching { readerWriter.read() }
      .getOrDefault("")
  }
}


