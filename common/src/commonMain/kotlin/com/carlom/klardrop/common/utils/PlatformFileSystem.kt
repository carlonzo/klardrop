package com.carlom.klardrop.common.utils

import okio.BufferedSink
import okio.BufferedSource

expect class PlatformFileSystem {
  fun getReadStreamFromUri(uri: String): BufferedSource

  fun getWriteStreamFromUri(uri: String): BufferedSink

  fun getResolvedFileData(uri: String): ResolvedFileData

  fun delete(uri: String)
  suspend fun moveToStorage(filePath: String, mimeType: String)

}

data class ResolvedFileData(
  val fileName: String,
  val mimeType: String,
  val fileSize: Long
)

internal fun getMimeTypeFromExtension(extension: String?): String {
  if (extension == null) return "application/octet-stream"

  return mimeTypes[extension] ?: "application/octet-stream"
}

private val mimeTypes = mapOf(

  "html" to "text/html",
  "htm" to "text/html",
  "shtml" to "text/html",
  "css" to "text/css",
  "xml" to "text/xml",
  "gif" to "image/gif",
  "jpeg" to "image/jpeg",
  "jpg" to "image/jpeg",
  "mml" to "text/mathml",
  "txt" to "text/plain",
  "wml" to "text/vnd.wap.wml",
  "htc" to "text/x-component",
  "png" to "image/png",
  "tif" to "image/tiff",
  "tiff" to "image/tiff",
  "ico" to "image/x-icon",
  "jng" to "image/x-jng",
  "bmp" to "image/x-ms-bmp",
  "svg" to "image/svg+xml",
  "svgz" to "image/svg+xml",
  "webp" to "image/webp",
  "mid" to "audio/midi",
  "midi" to "audio/midi",
  "kar" to "audio/midi",
  "mp3" to "audio/mpeg",
  "ogg" to "audio/ogg",
  "m4a" to "audio/x-m4a",
  "3gpp" to "video/3gpp",
  "3gp" to "video/3gpp",
  "ts" to "video/mp2t",
  "mp4" to "video/mp4",
  "mpeg" to "video/mpeg",
  "mpg" to "video/mpeg",
  "mov" to "video/quicktime",
  "webm" to "video/webm",
  "flv" to "video/x-flv",
  "m4v" to "video/x-m4v",
  "mng" to "video/x-mng",
  "asx" to "video/x-ms-asf",
  "asf" to "video/x-ms-asf",
  "wmv" to "video/x-ms-wmv",
  "avi" to "video/x-msvideo",
  "apk" to "application/vnd.android.package-archive"

)