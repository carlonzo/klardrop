package com.carlom.klardrop.common.persistence

import okio.FileSystem

actual val CurrentFileSystem: FileSystem
  get() = FileSystem.SYSTEM