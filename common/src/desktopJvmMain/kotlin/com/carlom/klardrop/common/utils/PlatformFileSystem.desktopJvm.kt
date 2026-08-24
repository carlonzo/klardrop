package com.carlom.klardrop.common.utils

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import java.nio.file.Files
import java.nio.file.Paths

internal actual fun PlatformFile.mimeType(): String {
  // Files.probeContentType replaces a `file --mime-type -b` subprocess: same answer on
  // Linux/macOS (the JDK's provider reads the same magic/xdg databases), and it cannot fail
  // with "Cannot run program" when the app is launched without a usable PATH.
  val probed = runCatching { Files.probeContentType(Paths.get(path)) }.getOrNull()
  return probed?.takeIf { it.isNotBlank() } ?: mimeTypeFromExtension()
}
