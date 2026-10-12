package com.carlom.klardrop.common.features

import android.content.Context
import android.content.Context.CLIPBOARD_SERVICE
import kotlinx.coroutines.flow.Flow

actual class ClipboardReaderWriter(private val context: Context) : ClipboardSource {

  private val clipManager by lazy { context.getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager }

  actual override fun read(): String {
    return clipManager.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
  }

  actual override fun readForSync(): String = read()

  actual override fun write(text: String) {
    clipManager.setPrimaryClip(android.content.ClipData.newPlainText("Text", text))
  }

  actual override fun changeSignals(): Flow<Unit>? = null
}