package com.carlom.klardrop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.awt.ComposeWindow
import java.awt.FileDialog
import java.io.File
import javax.swing.UIManager

actual class FilePicker(
  private val window: ComposeWindow
) {

  private lateinit var onFilesPicked: (DeviceUi, List<String>) -> Unit
  actual fun openFilePicker(deviceUi: DeviceUi) {
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())

    val dialog = FileDialog(window).apply {
      isMultipleMode = true
      title = "Select files to send"
    }

    dialog.isVisible = true

    onFilesPicked(
      deviceUi,
      dialog.files.map { File(dialog.directory).resolve(it).absolutePath }
    )

    println("Selected files: ${dialog.files.map { File(dialog.directory).resolve(it).absolutePath }}")
  }

  @Composable
  actual fun registerPicker(onFilesPicked: (DeviceUi, List<String>) -> Unit) {
    this@FilePicker.onFilesPicked = onFilesPicked
  }

}

actual class FilePickerFactory(private val window: ComposeWindow) {
  @Composable
  actual fun createPicker(): FilePicker {
    return remember(window) {
      FilePicker(window)
    }
  }
}