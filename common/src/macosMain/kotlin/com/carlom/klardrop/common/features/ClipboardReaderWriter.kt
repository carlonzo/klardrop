package com.carlom.klardrop.common.features

import platform.AppKit.NSPasteboard
import platform.AppKit.NSPasteboardTypeString

actual class ClipboardReaderWriter {

  private val pasteboard by lazy { NSPasteboard.generalPasteboard }

  actual fun read(): String {
    return pasteboard.stringForType(NSPasteboardTypeString) ?: ""
  }

  actual fun write(text: String) {
    pasteboard.clearContents()
    pasteboard.setString(text, forType = NSPasteboardTypeString)
  }
}
