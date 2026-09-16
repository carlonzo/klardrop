package com.carlom.klardrop.common.features

import platform.UIKit.UIPasteboard

actual class ClipboardReaderWriter {

  private val pasteboard by lazy { UIPasteboard.generalPasteboard }
  private val gate = IosPasteboardSyncReadGate()

  // Chat Paste is a tap — always hit `string`. If they Allow, clear the session
  // decline so background sync can resume.
  actual fun read(): String {
    val count = pasteboard.changeCount
    val value = pasteboard.string ?: ""
    return gate.recordUserRead(count, value)
  }

  // ClipboardManager polls readForSync() every 500ms. UIPasteboard.string (and
  // hasStrings) shows Allow Paste on another app's clipboard; dismissing it does
  // not persist. After the first decline this process, stop probing until death.
  actual fun readForSync(): String {
    val count = pasteboard.changeCount
    if (!gate.shouldProbePasteboard(count)) {
      return gate.readForSync(count, hasStrings = false, readString = { "" })
    }
    return gate.readForSync(
      changeCount = count,
      hasStrings = pasteboard.hasStrings,
      readString = { pasteboard.string ?: "" },
    )
  }

  actual fun write(text: String) {
    pasteboard.string = text
  }
}
