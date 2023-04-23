package com.carlom.klardrop.common.utils

import com.carlom.klardrop.common.persistence.CurrentFileSystem
import kotlinx.cinterop.usePinned
import okio.BufferedSink
import okio.BufferedSource
import okio.Path.Companion.toPath
import okio.buffer
import platform.Foundation.NSInputStream
import platform.Foundation.inputStreamWithFileAtPath

actual class PlatformFileSystem {

  actual fun getReadStreamFromUri(uri: String): BufferedSource {
    return CurrentFileSystem.source(uri.toPath()).buffer()
  }

  actual fun getResolvedFileData(uri: String): ResolvedFileData {
    TODO("Not yet implemented")
  }

  actual fun getWriteStreamFromUri(uri: String): BufferedSink {
    TODO("Not yet implemented")
  }

  actual fun delete(uri: String) {
    TODO("Not yet implemented")
  }

  actual suspend fun moveToStorage(filePath: String, mimeType: String?) {
    TODO()
  }

}