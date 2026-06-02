package com.carlom.klardrop.common.features

import platform.UIKit.UIPasteboard

actual class ClipboardReaderWriter {

  private val pasteboard by lazy { UIPasteboard.generalPasteboard }

  actual fun read(): String {
    return pasteboard.string ?: ""
  }

  actual fun write(text: String) {
    pasteboard.string = text
  }
}
