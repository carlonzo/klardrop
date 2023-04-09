package com.carlom.klardrop.common.utils

import okio.BufferedSource

expect class FileResolver {
  fun getReadStreamFromUri(uri: String): BufferedSource
  fun getResolvedFileData(uri: String): ResolvedFileData

}

data class ResolvedFileData(
  val fileName: String,
  val mimeType: String?,
  val fileSize: Long
)