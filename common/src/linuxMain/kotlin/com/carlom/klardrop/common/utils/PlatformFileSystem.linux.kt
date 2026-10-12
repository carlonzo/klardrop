package com.carlom.klardrop.common.utils

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path

internal actual fun PlatformFile.mimeType(): String {
  val probed = try {
    val result = execProcess(listOf("xdg-mime", "query", "filetype", path))
    if (result.exitCode == 0) result.stdoutString.trim().ifBlank { null } else null
  } catch (_: Exception) {
    null
  }
  return probed ?: mimeTypeFromExtension()
}
