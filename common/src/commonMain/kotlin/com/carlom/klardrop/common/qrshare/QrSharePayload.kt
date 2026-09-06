package com.carlom.klardrop.common.qrshare

import com.carlom.klardrop.common.utils.FileTypeUtils
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.extension
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.size

sealed interface QrSharePayload {
  data class Text(val text: String) : QrSharePayload
  data class Files(val files: List<SharedFile>) : QrSharePayload
}

data class SharedFile(
  val file: PlatformFile,
  val fileName: String,
  val mimeType: String,
  val fileSize: Long, // 0 if unknown; never Content-Length: -1
) {
  constructor(file: PlatformFile) : this(
    file = file,
    fileName = file.name,
    mimeType = FileTypeUtils.getMimeTypeFromExtension(file.extension),
    fileSize = runCatching { file.size() }.getOrDefault(0L),
  )
}
