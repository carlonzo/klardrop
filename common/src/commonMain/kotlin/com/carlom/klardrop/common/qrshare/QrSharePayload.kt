package com.carlom.klardrop.common.qrshare

import io.github.vinceglb.filekit.PlatformFile

sealed interface QrSharePayload {
  data class Text(val text: String) : QrSharePayload
  data class Files(val files: List<SharedFile>) : QrSharePayload
}

data class SharedFile(
  val file: PlatformFile,
  val fileName: String,
  val mimeType: String,
  val fileSize: Long, // 0 if unknown; never Content-Length: -1
)
