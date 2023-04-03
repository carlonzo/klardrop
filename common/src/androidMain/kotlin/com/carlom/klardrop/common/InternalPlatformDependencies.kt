package com.carlom.klardrop.common

import android.content.Context
import android.os.Build
import com.carlom.klardrop.common.utils.DeviceType
import okio.Path
import okio.Path.Companion.toPath
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

}