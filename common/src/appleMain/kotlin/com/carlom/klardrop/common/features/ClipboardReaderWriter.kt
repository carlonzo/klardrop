package com.carlom.klardrop.common.features

actual class ClipboardReaderWriter {

//  private val pasteboard by lazy { UIPasteboard.generalPasteboard }

  actual fun read(): String {
//   return pasteboard.string ?: return ""
    return ""
  }

  actual fun write(text: String) {
//    pasteboard.string = text
  }

}