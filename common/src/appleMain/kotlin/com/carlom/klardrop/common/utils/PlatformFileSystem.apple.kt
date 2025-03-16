package com.carlom.klardrop.common.utils

import io.github.vinceglb.filekit.PlatformFile

internal actual fun PlatformFile.mimeType(): String {
  return mimeTypeFromExtension()
}