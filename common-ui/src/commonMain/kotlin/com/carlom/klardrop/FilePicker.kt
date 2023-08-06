package com.carlom.klardrop

import androidx.compose.runtime.Composable

expect class FilePicker {
  fun openFilePicker(deviceUi: DeviceUi)

  @Composable
  fun registerPicker(onFilesPicked: (DeviceUi, List<String>) -> Unit)
}

expect class FilePickerFactory {
  @Composable
  fun createPicker(): FilePicker
}