package com.carlom.klardrop.common.utils

/**
 * Utility functions for determining file types based on file names and extensions.
 * Uses MIME type mappings from the platform file system.
 */
object FileTypeUtils {

  // MIME type mappings extracted from PlatformFileSystem for consistency
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
    "apk" to "application/vnd.android.package-archive",
    // Additional common extensions not in original list
    "heic" to "image/heic",
    "heif" to "image/heif",
    "mkv" to "video/x-matroska",
    // Documents — pdf was previously missing and was the most-reported "unknown
    // mime type" event from real users.
    "pdf" to "application/pdf",
    "doc" to "application/msword",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xls" to "application/vnd.ms-excel",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "ppt" to "application/vnd.ms-powerpoint",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "csv" to "text/csv",
    "rtf" to "application/rtf",
    // Archives
    "zip" to "application/zip",
    "rar" to "application/vnd.rar",
    "7z" to "application/x-7z-compressed",
    "tar" to "application/x-tar",
    "gz" to "application/gzip",
    // Code/text
    "json" to "application/json",
    "js" to "application/javascript",
    "md" to "text/markdown"
  )

  private const val DEFAULT_MIME_TYPE = "application/octet-stream"

  /**
   * Checks if the file is a video based on its extension.
   * @param fileName The name of the file including extension
   * @return true if the file is a video, false otherwise
   */
  fun isVideo(fileName: String): Boolean {
    val extension = getFileExtension(fileName)
    val mimeType = mimeTypes[extension] ?: return false
    return mimeType.startsWith("video/")
  }

  /**
   * Gets the file extension from a file name, normalized to lowercase.
   * @param fileName The name of the file including extension
   * @return the file extension in lowercase, or empty string if no extension found
   */
  private fun getFileExtension(fileName: String): String {
    val lastDotIndex = fileName.lastIndexOf('.')
    return if (lastDotIndex >= 0 && lastDotIndex < fileName.length - 1) {
      fileName.substring(lastDotIndex + 1).lowercase()
    } else {
      ""
    }
  }


  fun getMimeTypeFromExtension(extension: String?): String {
    if (extension == null) return DEFAULT_MIME_TYPE

    return mimeTypes[extension] ?: run {
      // Falling back to octet-stream is harmless — receivers detect by magic
      // bytes when this matters. Worth a local log so we can grow the map over
      // time, but not worth a Bugsnag event.
      nativeLogger("FileTypeUtils", "Unknown mime type for extension $extension; using $DEFAULT_MIME_TYPE")
      DEFAULT_MIME_TYPE
    }
  }

  /**
   * Checks if the MIME type represents an image.
   * @param mimeType The MIME type to check
   * @return true if the MIME type is an image, false otherwise
   */
  fun isImageMimeType(mimeType: String): Boolean {
    return mimeType.startsWith("image/")
  }

  /**
   * Checks if the MIME type represents a video.
   * @param mimeType The MIME type to check
   * @return true if the MIME type is a video, false otherwise
   */
  fun isVideoMimeType(mimeType: String): Boolean {
    return mimeType.startsWith("video/")
  }

  fun isAudioMimeType(mimeType: String): Boolean {
    return mimeType.startsWith("audio/")
  }

  /**
   * Checks if the MIME type represents either an image or video.
   * @param mimeType The MIME type to check
   * @return true if the MIME type is an image or video, false otherwise
   */
  fun isImageOrVideoMimeType(mimeType: String): Boolean {
    return isImageMimeType(mimeType) || isVideoMimeType(mimeType)
  }

}
