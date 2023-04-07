package com.carlom.klardrop.common.features

import android.content.Context
import android.content.Context.CLIPBOARD_SERVICE

actual class ClipboardReaderWriter(private val context: Context) {

  private val clipManager by lazy { context.getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager }

  actual fun read(): String {
    return clipManager.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
  }

  actual fun write(text: String) {
    clipManager.setPrimaryClip(android.content.ClipData.newPlainText("Text", text))
  }


}