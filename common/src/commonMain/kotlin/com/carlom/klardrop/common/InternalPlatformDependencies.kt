package com.carlom.klardrop.common

import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.FileResolver
import okio.BufferedSource
import okio.Path

expect class InternalPlatformDependencies {

  fun getRootPath(): String
  fun getDeviceName(): String
  fun deviceType(): DeviceType
  fun getStoragePath(): Path

  fun fileResolver(): FileResolver
}