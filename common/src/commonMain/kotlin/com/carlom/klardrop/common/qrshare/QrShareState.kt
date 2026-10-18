package com.carlom.klardrop.common.qrshare

public sealed interface QrShareState {
  public data object Idle : QrShareState
  public data object Starting : QrShareState
  public data class QrVisible(
    public val url: String,
    public val ipv4: String,
    public val port: Int,
    public val payloadSummary: String,
  ) : QrShareState

  public data class Serving(
    public val url: String,
    public val ipv4: String,
    public val port: Int,
    public val downloads: List<QrDownloadProgress>,
    public val qrStillVisible: Boolean,
  ) : QrShareState

  public data class Failed(public val message: String) : QrShareState
}

public data class QrDownloadProgress(
  public val fileName: String,
  public val percentage: Int,
  public val bytesTransferred: Long,
  public val totalBytes: Long,
)

internal fun QrSharePayload.summary(): String = when (this) {
  is QrSharePayload.Text -> if (text.length <= 40) text else "${text.take(37)}..."
  is QrSharePayload.Files -> when (files.size) {
    0 -> "0 files"
    1 -> files.first().fileName
    else -> "${files.size} files"
  }
}
