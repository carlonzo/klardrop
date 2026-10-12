package com.carlom.klardrop.common.features

import kotlinx.coroutines.flow.Flow
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * [useInMemory] backs reads/writes with a plain in-process string instead of the real AWT
 * clipboard — used by tests (via [com.carlom.klardrop.common.ApplicationInfo.disableSystemClipboard])
 * so they never touch the developer's actual desktop clipboard.
 */
actual class ClipboardReaderWriter(private val useInMemory: Boolean = false) : ClipboardSource {

  private var inMemoryValue: String = ""
  private val clip by lazy { Toolkit.getDefaultToolkit().systemClipboard }

  actual override fun read(): String {
    if (useInMemory) return inMemoryValue
    return clip.getData(DataFlavor.stringFlavor).toString()
  }

  actual override fun readForSync(): String = read()

  actual override fun write(text: String) {
    if (useInMemory) {
      inMemoryValue = text
      return
    }
    clip.setContents(StringSelection(text), null)
  }

  actual override fun changeSignals(): Flow<Unit>? = null
}
