package com.carlom.klardrop.common.features

import kotlinx.coroutines.flow.Flow

// Test-only implementation for ClipboardReaderWriter without requiring Android Context
class ClipboardReaderWriter() : ClipboardSource {
  private var clipboard: String = ""

  override fun read(): String = clipboard

  override fun readForSync(): String = read()

  override fun write(text: String) {
    clipboard = text
  }

  override fun changeSignals(): Flow<Unit>? = null
}
