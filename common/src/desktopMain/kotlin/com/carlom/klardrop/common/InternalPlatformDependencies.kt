package com.carlom.klardrop.common

actual class InternalPlatformDependencies {
  actual fun getRootPath(): String {
    return System.getenv("HOME")+"/" ?: "~"
  }

}