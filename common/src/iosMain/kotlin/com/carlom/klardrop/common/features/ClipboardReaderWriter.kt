package com.carlom.klardrop.common.features

import platform.UIKit.UIPasteboard

actual class ClipboardReaderWriter {

  private val pasteboard by lazy { UIPasteboard.generalPasteboard }

  // ClipboardManager polls read() every 500ms. UIPasteboard.string (and hasStrings)
  // shows the iOS 16+ Allow Paste banner on every read of another app's clipboard;
  // dismissing it does not persist. changeCount is a cheap monotonic counter that
  // does not prompt — gate the string read on it so we ask at most once per real
  // clipboard change, matching the macOS ClipboardReaderWriter pattern.
  private var lastChangeCount = -1L
  private var cached = ""

  actual fun read(): String {
    val count = pasteboard.changeCount
    if (count == lastChangeCount) return cached
    lastChangeCount = count
    cached = pasteboard.string ?: ""
    return cached
  }

  actual fun write(text: String) {
    pasteboard.string = text
  }
}
