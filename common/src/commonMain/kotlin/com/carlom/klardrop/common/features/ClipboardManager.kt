package com.carlom.klardrop.common.features

import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
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

class ClipboardManager(
  private val coroutines: Coroutines,
  private val readerWriter: ClipboardReaderWriter
) {

  private val clipboardScope = coroutines.newScope(coroutines.ioDispatcher)

  val flow = callbackFlow {

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
  }.distinctUntilChanged().shareIn(clipboardScope, started = SharingStarted.WhileSubscribed())

  fun write(text: String) {
    readerWriter.write(text)
  }

  fun read(): String {
    return runCatching { readerWriter.read() }
      .onFailure { log("ClipboardManager", "Cant read from clipboard. ${it.message}") }
      .getOrDefault("")
  }
}


