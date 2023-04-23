package com.carlom.klardrop.common.features

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

actual class ClipboardReaderWriter {

  private val clip by lazy { Toolkit.getDefaultToolkit().systemClipboard }

  actual fun read(): String {
    return clip.getData(DataFlavor.stringFlavor).toString()
  }

  actual fun write(text: String) {
    clip.setContents(StringSelection(text), null)
  }


}
