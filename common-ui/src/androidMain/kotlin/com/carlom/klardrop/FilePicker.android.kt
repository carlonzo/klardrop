package com.carlom.klardrop

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

actual class FilePicker(
  private val activity: ComponentActivity
) {

  private lateinit var getContent: ActivityResultLauncher<PickVisualMediaRequest>
  actual fun openFilePicker() {
    getContent.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
  }

  @Composable
  actual fun registerPicker(onFilesPicked: (List<String>) -> Unit) {

    getContent = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
      onFilesPicked(listOf(it.toString()))
    }


  }

}