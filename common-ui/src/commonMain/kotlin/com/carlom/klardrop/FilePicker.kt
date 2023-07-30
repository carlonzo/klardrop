package com.carlom.klardrop

import androidx.compose.runtime.Composable

expect class FilePicker {
  fun openFilePicker()

  @Composable
  fun registerPicker(onFilesPicked: (List<String>) -> Unit)
}