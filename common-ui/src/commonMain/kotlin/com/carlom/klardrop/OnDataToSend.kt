package com.carlom.klardrop

sealed interface OnDataToSend {
  data class Text(val text: String) : OnDataToSend
  data class FilesList(val filesPath: List<String>) : OnDataToSend
}