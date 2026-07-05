package com.carlom.klardrop.common.features

import platform.AppKit.NSPasteboard
import platform.AppKit.NSPasteboardTypeString

actual class ClipboardReaderWriter {

  private val pasteboard by lazy { NSPasteboard.generalPasteboard }

  // ClipboardManager polls read() every 500ms on the main thread. stringForType does a
  // UTType/LaunchServices lookup on every call, which is expensive and has crashed inside
  // LaunchServices during churny startup. changeCount is a cheap monotonic counter that only
  // moves when the clipboard actually changes — gate the expensive read on it so stringForType
  // runs on real changes, not twice a second.
  private var lastChangeCount = -1L
  private var cached = ""

  actual fun read(): String {
    val count = pasteboard.changeCount
    if (count == lastChangeCount) return cached
    lastChangeCount = count
    cached = pasteboard.stringForType(NSPasteboardTypeString) ?: ""
    return cached
  }

  actual fun write(text: String) {
    pasteboard.clearContents()
    pasteboard.setString(text, forType = NSPasteboardTypeString)
  }
}
