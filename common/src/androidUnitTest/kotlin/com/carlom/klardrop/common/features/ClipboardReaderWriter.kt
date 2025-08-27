package com.carlom.klardrop.common.features

// Test-only implementation for ClipboardReaderWriter without requiring Android Context
class ClipboardReaderWriter() {
  private var clipboard: String = ""

  fun read(): String = clipboard

  fun write(text: String) {
    clipboard = text
  }
}
