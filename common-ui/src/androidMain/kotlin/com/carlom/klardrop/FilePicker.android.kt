package com.carlom.klardrop

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

actual class FilePicker {

  private lateinit var getContent: ActivityResultLauncher<PickVisualMediaRequest>
  private lateinit var deviceUi: DeviceUi

  actual fun openFilePicker(deviceUi: DeviceUi) {
    this.deviceUi = deviceUi
    getContent.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
  }

  @Composable
  actual fun registerPicker(onFilesPicked: (DeviceUi, List<String>) -> Unit) {

    getContent = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
      if (it == null) return@rememberLauncherForActivityResult

      onFilesPicked(deviceUi, listOf(it.toString()))
    }

  }

}

actual class FilePickerFactory {
  @Composable
  actual fun createPicker(): FilePicker {
    return FilePicker()
  }
}