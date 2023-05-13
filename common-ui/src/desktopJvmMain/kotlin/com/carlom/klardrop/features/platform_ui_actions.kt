package com.carlom.klardrop.features

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.AwtWindow
import androidx.compose.ui.window.FrameWindowScope
import java.awt.FileDialog
import java.io.File
import javax.swing.UIManager


fun FrameWindowScope.openFileChooser(listener: (List<String>) -> Unit) {
  UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())


  val dialog = FileDialog(window).apply {
    isMultipleMode = true
    title = "Select files to send"
  }

  dialog.isVisible = true

  listener(
    dialog.files.map { File(dialog.directory).resolve(it).absolutePath }
  )
  println("Selected files: ${dialog.files.map { File(dialog.directory).resolve(it).absolutePath }}")
}

@Composable
fun FrameWindowScope.FileDialog(
  listener: (List<String>) -> Unit
) = AwtWindow(
  create = {
    object : FileDialog(window, "Choose a file", LOAD) {
      override fun setVisible(value: Boolean) {
        super.setVisible(value)
        if (value) {
          listener(
            files.map { File(directory).resolve(it).absolutePath }
          )
        }
      }
    }.apply {
      isMultipleMode = true
      this.title = title
    }
  },
  dispose = FileDialog::dispose
)