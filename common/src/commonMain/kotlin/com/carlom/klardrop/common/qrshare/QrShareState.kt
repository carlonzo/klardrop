package com.carlom.klardrop.common.qrshare

sealed interface QrShareState {
  data object Idle : QrShareState
  data object Starting : QrShareState
  data class QrVisible(
    val url: String,
    val ipv4: String,
    val port: Int,
    val payloadSummary: String,
  ) : QrShareState

  data class Serving(
    val url: String,
    val ipv4: String,
    val port: Int,
    val downloads: List<QrDownloadProgress>,
    val qrStillVisible: Boolean,
  ) : QrShareState

  data class Failed(val message: String) : QrShareState
}

data class QrDownloadProgress(
  val fileName: String,
  val percentage: Int,
  val bytesTransferred: Long,
  val totalBytes: Long,
)

fun QrSharePayload.summary(): String = when (this) {
  is QrSharePayload.Text -> if (text.length <= 40) text else "${text.take(37)}..."
  is QrSharePayload.Files -> when (files.size) {
    0 -> "0 files"
    1 -> files.first().fileName
    else -> "${files.size} files"
  }
}
