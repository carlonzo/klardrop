package com.carlom.klardrop.common.utils

import com.carlom.klardrop.common.CommonPlatformDependencies
import com.carlom.klardrop.common.readFromBash
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path

internal actual fun PlatformFile.mimeType(): String {
  // file --mime-type -b <filepath

    val mimeType = when(CommonPlatformDependencies.osType()){
      OsType.APPLE, OsType.LINUX ->  readFromBash("file", "--mime-type", "-b", path)
      else -> null
    }

  return mimeType ?: mimeTypeFromExtension()
}