package com.carlom.klardrop.common.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.File

actual class FileResolver(private val context: Context) {
  @SuppressLint("Recycle")
  actual fun getReadStreamFromUri(uri: String): BufferedSource {
    return when (uri.substringBefore(":")) {

      "content" -> {
        val contentResolver = context.contentResolver
        val inputStream = contentResolver.openInputStream(Uri.parse(uri))
        inputStream!!.source().buffer()
      }

      else -> {
        val path = uri.substringAfter("file://")
        File(path).source().buffer()
      }
    }
  }

  actual fun getResolvedFileData(uri: String): ResolvedFileData {
    val androidUri = Uri.parse(uri)

    return when (uri.substringBefore(":")) {

      "content" -> {
        val contentResolver = context.contentResolver

        contentResolver.query(androidUri, null, null, null, null).use { cursor ->
          cursor ?: throw IllegalStateException("Cursor is null with uri $uri")

          val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
          cursor.moveToFirst()

          val mimetype = contentResolver.getType(androidUri)

          val fileName = cursor.getString(nameIndex)
          val filesize = cursor.getLong(sizeIndex)

          ResolvedFileData(fileName, mimetype, filesize)
        }
      }

      else -> {
        val path = uri.substringAfter("file://")
        File(path).let { file ->
          return ResolvedFileData(
            fileName = file.name,
            mimeType = null,
            fileSize = file.length()
          )
        }
      }
    }
  }

}