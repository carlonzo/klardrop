package com.carlom.klardrop.common

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import com.carlom.klardrop.common.utils.DeviceType
import okio.BufferedSource
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.source
import java.io.File

actual class InternalPlatformDependencies(private val context: Context) {

  actual fun getRootPath(): String {
    return context.filesDir.absolutePath
  }

  actual fun getDeviceName(): String {
    return Build.MODEL
  }

  actual fun deviceType(): DeviceType {
    return DeviceType.MOBILE
  }

  actual fun getStoragePath(): Path {
    val file = File(context.filesDir, "received")
    if (!file.exists()) {
      file.mkdirs()
    }

    return file.absolutePath.toPath()
  }

  @SuppressLint("Recycle")
  actual fun getReadStreamFromUri(uri: String): BufferedSource {
    val schema = uri.substringBefore(":")

    when (schema) {
      "file" -> {
        val path = uri.substringAfter("file://")
        return File(path).source().buffer()
      }

      "content" -> {
        val contentResolver = context.contentResolver
        val inputStream = contentResolver.openInputStream(Uri.parse(uri))
        return inputStream!!.source().buffer()
      }

      else -> {
        throw IllegalArgumentException("Schema $schema not supported for uri $uri")
      }
    }
  }

}