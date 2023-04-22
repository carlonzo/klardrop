package com.carlom.klardrop.common.utils

import okio.BufferedSink
import okio.BufferedSource

actual class PlatformFileSystem {
  actual fun getReadStreamFromUri(uri: String): BufferedSource {
    TODO("Not yet implemented")
  }

  actual fun getResolvedFileData(uri: String): ResolvedFileData {
    TODO("Not yet implemented")
  }

  actual fun getWriteStreamFromUri(uri: String): BufferedSink {
    TODO("Not yet implemented")
  }

  actual fun exists(uri: String): Boolean {
    TODO("Not yet implemented")
  }

  actual fun move(from: String, to: String) {
    TODO("Not yet implemented")
  }

  actual fun delete(uri: String) {
    TODO("Not yet implemented")
  }

  actual fun moveToStorage(filePath: String, mimeType: String?) {
    TODO("Not yet implemented")
  }

}