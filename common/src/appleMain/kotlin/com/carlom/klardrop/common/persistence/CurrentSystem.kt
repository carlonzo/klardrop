package com.carlom.klardrop.common.persistence

import kotlinx.io.files.FileSystem
import kotlinx.io.files.SystemFileSystem


val CurrentFileSystem: FileSystem
  get() = SystemFileSystem