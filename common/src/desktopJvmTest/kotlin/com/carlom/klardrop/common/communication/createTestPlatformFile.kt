package com.carlom.klardrop.common.communication

import io.github.vinceglb.filekit.PlatformFile

actual fun createTestPlatformFile(fileName: String, data: ByteArray): PlatformFile {
  val tempFile = java.io.File.createTempFile("klardrop-test-", "-$fileName")
  tempFile.writeBytes(data)
  tempFile.deleteOnExit()
  return PlatformFile(tempFile)
}
