package com.carlom.klardrop.common.features

import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

expect class ClipboardReaderWriter {
  fun read(): String
  fun write(text: String)
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
  private val readerWriter: ClipboardReaderWriter
) : ClipboardAccess {

  private val clipboardScope = coroutines.newScope(coroutines.ioDispatcher)

  override val flow = callbackFlow {

    val collectionJob = coroutines.appScope.launch {

      while (isActive) {
        read().takeIf { it.isNotEmpty() }?.let {
          send(it)
        }

        delay(500)
      }
    }

    awaitClose {
      collectionJob.cancel()
    }
  }.distinctUntilChanged()
    .shareIn(clipboardScope, started = SharingStarted.WhileSubscribed())

  override fun write(text: String) {
    readerWriter.write(text)
  }

  override fun read(): String {
    return runCatching { readerWriter.read() }
      .getOrDefault("")
  }
}


