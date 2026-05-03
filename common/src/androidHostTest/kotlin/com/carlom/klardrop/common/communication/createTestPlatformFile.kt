package com.carlom.klardrop.common.communication

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.io.files.Path

actual fun createTestPlatformFile(fileName: String, data: ByteArray): PlatformFile {
  return PlatformFile(Path("/tmp", fileName))
}
