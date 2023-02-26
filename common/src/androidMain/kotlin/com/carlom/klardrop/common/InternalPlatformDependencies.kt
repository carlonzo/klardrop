package com.carlom.klardrop.common

import android.content.Context

actual class InternalPlatformDependencies(private val context: Context) {

  actual fun getRootPath(): String {
    return context.filesDir.absolutePath
  }

}