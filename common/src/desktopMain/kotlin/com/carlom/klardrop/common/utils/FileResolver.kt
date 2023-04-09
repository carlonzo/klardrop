package com.carlom.klardrop.common.utils

import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.File
import java.nio.file.Files

actual class FileResolver() {
  actual fun getReadStreamFromUri(uri: String): BufferedSource {
    return uri.toFile.inputStream().source().buffer()
  }

  @Suppress("NewApi")
  actual fun getResolvedFileData(uri: String): ResolvedFileData {
    uri.toFile.let { file ->
      return ResolvedFileData(
        fileName = file.name,
        mimeType = Files.probeContentType(file.toPath()),
        fileSize = file.length()
      )
    }
  }

  private val String.toFile: File
    get() {
      val pathNoSchema = this.substringAfter("file://")
      return File(pathNoSchema)
    }

}