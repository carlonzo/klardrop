package com.carlom.klardrop.common.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.callback.SingleFileConflictCallback
import com.anggrayudi.storage.file.CreateMode
import com.anggrayudi.storage.file.moveFileToDownloadMedia
import com.anggrayudi.storage.media.FileDescription
import com.anggrayudi.storage.media.MediaFile
import com.anggrayudi.storage.result.SingleFileResult
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import okio.BufferedSink
import okio.BufferedSource
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.sink
import okio.source
import java.io.File

actual class PlatformFileSystem(private val context: Context) {
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

          val mimetype = contentResolver.getType(androidUri) ?: DEFAULT_MIME_TYPE

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
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: DEFAULT_MIME_TYPE,
            fileSize = file.length()
          )
        }
      }
    }
  }

  @SuppressLint("Recycle")
  actual fun getWriteStreamFromUri(uri: String): BufferedSink {

    return when (uri.substringBefore(":")) {

      "content" -> {
        val contentResolver = context.contentResolver

        val outputStream = contentResolver.openOutputStream(Uri.parse(uri)) ?: throw IllegalArgumentException("Cannot write to uri $uri ")
        outputStream.sink().buffer()
      }

      else -> {
        val path = uri.substringAfter("file://")
        File(path).sink(append = false).buffer()
      }
    }
  }

  actual fun delete(uri: String) {
    when (uri.substringBefore(":")) {

      "content" -> {
        val contentResolver = context.contentResolver
        contentResolver.delete(Uri.parse(uri), null, null)
      }

      else -> {
        val path = uri.substringAfter("file://")
        File(path).delete()
      }
    }
  }


  actual suspend fun moveToStorage(filePath: String, mimeType: String) {
    val file = File(filePath)
    val fd = FileDescription(file.name, "Klardrop", mimeType)

    val source = DocumentFile.fromFile(file)

    val fileMoveResult = source.moveFileToDownloadMedia(context = context,
      fileDescription = fd,
      mode = CreateMode.CREATE_NEW,
      onConflict = object : SingleFileConflictCallback<DocumentFile>() {}
    ).filterIsInstance<SingleFileResult.Completed>()
      .first()

    when (val result = fileMoveResult.result) {
      is MediaFile -> log("PlatformFileSystem", "Created Media in Mediastore uri ${result.uri}")
      is DocumentFile -> log("PlatformFileSystem", "Created Document uri ${result.uri}")
      else -> log("PlatformFileSystem", "Created unknown file $result")
    }

  }

  actual fun getTempStoragePath(): Path {
    return context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.toOkioPath() ?: context.filesDir.toOkioPath()
  }

}