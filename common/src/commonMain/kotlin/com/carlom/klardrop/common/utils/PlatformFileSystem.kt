package com.carlom.klardrop.common.utils

import okio.BufferedSink
import okio.BufferedSource

expect class PlatformFileSystem {
  fun getReadStreamFromUri(uri: String): BufferedSource

  fun getWriteStreamFromUri(uri: String): BufferedSink

  fun getResolvedFileData(uri: String): ResolvedFileData

  fun exists(uri: String): Boolean

  fun move(from: String, to: String)

  fun delete(uri: String)
  suspend fun moveToStorage(filePath: String, mimeType: String?)

}

data class ResolvedFileData(
  val fileName: String,
  val mimeType: String,
  val fileSize: Long
)