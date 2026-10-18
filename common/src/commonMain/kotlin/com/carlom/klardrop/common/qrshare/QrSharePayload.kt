package com.carlom.klardrop.common.qrshare

import com.carlom.klardrop.common.utils.FileTypeUtils
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.extension
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.size

public sealed interface QrSharePayload {
  public data class Text(public val text: String) : QrSharePayload
  public data class Files(public val files: List<SharedFile>) : QrSharePayload
}

public data class SharedFile(
  public val file: PlatformFile,
  public val fileName: String,
  public val mimeType: String,
  public val fileSize: Long, // 0 if unknown; never Content-Length: -1
) {
  public constructor(file: PlatformFile) : this(
    file = file,
    fileName = file.name,
    mimeType = FileTypeUtils.getMimeTypeFromExtension(file.extension),
    fileSize = runCatching { file.size() }.getOrDefault(0L),
  )
}
