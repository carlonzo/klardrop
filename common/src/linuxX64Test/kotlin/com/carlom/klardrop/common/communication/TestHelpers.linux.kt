package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.features.ClipboardReaderWriter
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.io.files.Path

actual fun testClipboardReaderWriter(): ClipboardReaderWriter = ClipboardReaderWriter()

actual fun createTestPlatformFile(fileName: String, data: ByteArray): PlatformFile {
  return PlatformFile(Path("/tmp", fileName))
}
