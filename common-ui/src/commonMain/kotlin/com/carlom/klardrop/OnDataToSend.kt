package com.carlom.klardrop

import io.github.vinceglb.filekit.PlatformFile

sealed interface OnDataToSend {
  data class Text(val text: String) : OnDataToSend
  data class FilesList(val files: List<PlatformFile>) : OnDataToSend
}