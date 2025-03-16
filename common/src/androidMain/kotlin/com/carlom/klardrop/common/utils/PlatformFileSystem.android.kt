package com.carlom.klardrop.common.utils

import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile

internal actual fun PlatformFile.mimeType(): String {
  return when (val file = androidFile) {
    is AndroidFile.FileWrapper -> mimeTypeFromExtension()
    is AndroidFile.UriWrapper -> FileKit.context.contentResolver.getType(file.uri) ?: mimeTypeFromExtension()
  }
}